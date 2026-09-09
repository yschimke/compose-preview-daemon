package ee.schimke.composeai.daemon.pool

import ee.schimke.composeai.daemon.RenderRequest
import ee.schimke.composeai.daemon.RobolectricHost
import ee.schimke.composeai.daemon.StartupTimings
import ee.schimke.composeai.daemon.UserClassLoaderHolder
import ee.schimke.composeai.daemon.config.DaemonProperties
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/**
 * Out-of-process sandbox pool (issue #3072) — the child half. One worker JVM hosts exactly **one**
 * Robolectric sandbox, which is the constraint the whole pool exists to respect: Robolectric's
 * native runtime binds to a single classloader per process.
 *
 * The worker is deliberately *not* a daemon. It has no preview index, no extension registry, no
 * watch state and no JSON-RPC surface: the parent daemon resolves a `previewId` into a full spec
 * payload before it dispatches, and re-runs the host-side data products on the result it gets back.
 * All the worker owns is a `RobolectricHost(sandboxCount = 1)` — the exact same single-sandbox host
 * the daemon has always run — plus a socket loop.
 *
 * Two ways to come up:
 *
 * - **Pooled** (the original shape): [SandboxProcessPool] spawned this JVM with
 *   `composeai.daemon.sandboxWorker.port`. Dial the parent's loopback port, boot the sandbox, send
 *   [WorkerResponse.Ready] (or [WorkerResponse.BootFailed]), then serve requests until
 *   [WorkerRequest.Shutdown] or EOF. EOF is a shutdown too — if the parent daemon dies, its workers
 *   must not survive it and leak a JVM each.
 * - **Spare** (`composeai.daemon.sandboxWorker.spare=true`; SANDBOX-POOL.md § "Spare workers"): a
 *   spare pool spawned this JVM *ahead of demand*, against a daemon classpath but no catalog. Boot
 *   the sandbox, warm-render, then **listen** on a loopback port and announce it on stdout
 *   ([SPARE_HANDSHAKE_PREFIX]). The first daemon to connect adopts the worker: its
 *   [WorkerRequest.Configure] carries the catalog's system properties and user-class dirs, after
 *   which the worker serves renders exactly as a pooled one does. Adoption costs a classloader
 *   swap, not a boot — that is the whole point. A spare that is never adopted exits with the
 *   process that spawned it.
 */
object SandboxWorkerMain {

  /**
   * Prefix of the one stdout line a spare worker prints once it is booted, warm and listening:
   * `composeai-spare-worker: listening pid=<pid> port=<port>`. The spare pool reads it to learn
   * where to hand the worker to a daemon; everything else the JVM prints is diagnostics.
   */
  const val SPARE_HANDSHAKE_PREFIX: String = "composeai-spare-worker: listening"

  @JvmStatic
  fun main(args: Array<String>) {
    val spare = DaemonProperties.sandboxWorkerSpare.read()
    val port = System.getProperty(SandboxProcessPool.WORKER_PORT_PROP)?.toIntOrNull()
    if (!spare && port == null) {
      error(
        "${SandboxProcessPool.WORKER_PORT_PROP} is unset — SandboxWorkerMain is spawned by " +
          "SandboxProcessPool (or as a spare, with ${DaemonProperties.Names.SANDBOX_WORKER_SPARE}=true), " +
          "not run directly"
      )
    }
    val slot = System.getProperty(SandboxProcessPool.WORKER_SLOT_PROP)?.toIntOrNull() ?: 0
    Thread.currentThread().name =
      if (spare) "compose-ai-sandbox-spare" else "compose-ai-sandbox-worker-$slot"

    // A worker must never outlive the process that spawned it. Socket EOF covers the normal case,
    // but only once the serve loop is reading — a parent that dies while this worker is still
    // inside its (minutes-long) Robolectric bootstrap would leave a whole JVM stranded, holding a
    // sandbox's worth of heap until something reaps it. Watch the parent process directly so the
    // boot window is covered too. The daemon's own test JVM aborting mid-suite (SIGABRT out of
    // `libandroid_runtime.so`) is exactly that case. For a spare the parent is the spare pool's
    // JVM (serve); the daemon that adopts it is watched too, from `configure`.
    ProcessHandle.current().parent().ifPresent { parent -> haltWhenExits(parent, "parent") }

    // The user-class dirs are read once here for a pooled worker (the pool forwarded the daemon's
    // `composeai.daemon.userClassDirs`) and re-read on `configure` for a spare, which is launched
    // without any. The factory closes over the reference so a re-read takes effect on the next
    // holder the host allocates — see [RobolectricHost.resetUserClassLoaderHolders].
    val userClassUrls = AtomicReference<List<URL>>(UserClassLoaderHolder.urlsFromSysprop())
    val host =
      RobolectricHost(
        sandboxCount = 1,
        // Same derivation DaemonMain uses. Unset (in-process tests, no hot-reload wiring) → null →
        // the worker resolves preview classes off its own sandbox classpath, like a single-sandbox
        // daemon. A spare always gets the factory: it has no class dirs yet, and an empty child
        // loader delegates everything to the sandbox anyway, so the two shapes render alike.
        userClassloaderHolderFactory =
          if (spare || userClassUrls.get().isNotEmpty()) {
            { sandboxClassLoader: ClassLoader ->
              UserClassLoaderHolder(
                urls = userClassUrls.get(),
                parentSupplier = { sandboxClassLoader },
              )
            }
          } else null,
      )

    if (spare) runSpare(host, userClassUrls) else runPooled(host, port!!, slot, userClassUrls)

    // Robolectric leaves non-daemon threads (the sandbox's `SDK Main Thread`) behind, so a plain
    // return from main would not end the JVM. The parent has its result; leave deliberately.
    Runtime.getRuntime().halt(0)
  }

  private fun runPooled(
    host: RobolectricHost,
    port: Int,
    slot: Int,
    userClassUrls: AtomicReference<List<URL>>,
  ) {
    Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
      socket.tcpNoDelay = true
      val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
      val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
      try {
        host.start()
      } catch (t: Throwable) {
        send(writer, WorkerResponse.BootFailed(slot = slot, diagnostic = flattenDiagnostic(t)))
        return
      }
      send(writer, WorkerResponse.Ready(slot = slot, pid = ProcessHandle.current().pid()))

      serve(host, reader, writer, userClassUrls)
      runCatching { host.shutdown() }
    }
  }

  /**
   * Spare mode. The boot and the warm render happen with nobody waiting on a socket, so a spare
   * that fails to boot simply exits non-zero (its stderr is the spare pool's to log) — there is no
   * parent to send [WorkerResponse.BootFailed] to.
   */
  private fun runSpare(host: RobolectricHost, userClassUrls: AtomicReference<List<URL>>) {
    host.start()
    StartupTimings.mark("spare worker: sandbox booted")
    // The point of a spare: pay the sandbox's cold first render (Compose runtime, HardwareRenderer,
    // the font stack, the PNG encoder) now, off everyone's request path, so the adopting catalog
    // pays only its own first render.
    if (DaemonProperties.warmRenderOnBoot.read()) host.warmRenderInProcess()
    StartupTimings.mark("spare worker: warm, listening")
    ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
      // Announced on the REAL stdout, unlike a daemon: a worker's stdout is diagnostics, and this
      // is the one line of it the spare pool parses.
      System.out.println(
        "$SPARE_HANDSHAKE_PREFIX pid=${ProcessHandle.current().pid()} port=${server.localPort}"
      )
      System.out.flush()
      // No accept timeout: a spare waits as long as the pool keeps it. The pool kills spares it
      // evicts, and the parent watch above ends this JVM if the pool's does.
      val socket = server.accept()
      // One adopter for the lifetime of this sandbox; nobody else gets to connect.
      runCatching { server.close() }
      socket.use {
        it.tcpNoDelay = true
        val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8))
        val writer = BufferedWriter(OutputStreamWriter(it.getOutputStream(), Charsets.UTF_8))
        serve(host, reader, writer, userClassUrls)
      }
    }
    runCatching { host.shutdown() }
  }

  private fun serve(
    host: RobolectricHost,
    reader: BufferedReader,
    writer: BufferedWriter,
    userClassUrls: AtomicReference<List<URL>>,
  ) {
    while (true) {
      val line = reader.readLine() ?: return // parent closed the socket / died — exit with it
      val request =
        try {
          workerJson.decodeFromString(WorkerRequest.serializer(), line)
        } catch (t: Throwable) {
          System.err.println("sandbox worker: undecodable request '$line': $t")
          continue
        }
      when (request) {
        is WorkerRequest.Render -> {
          val response =
            try {
              val result =
                host.submit(
                  RenderRequest.Render(id = request.id, target = request.target),
                  timeoutMs = request.timeoutMs,
                )
              WorkerResponse.Result(RenderResultDto.of(result))
            } catch (t: Throwable) {
              WorkerResponse.Failed(id = request.id, diagnostic = flattenDiagnostic(t))
            }
          send(writer, response)
        }
        WorkerRequest.Swap -> {
          runCatching { host.swapUserClassLoaders() }
            .onFailure { System.err.println("sandbox worker: classloader swap failed: $it") }
          send(writer, WorkerResponse.Ok)
        }
        is WorkerRequest.Configure -> {
          configure(host, request, userClassUrls)
          send(writer, WorkerResponse.Configured(pid = ProcessHandle.current().pid()))
        }
        WorkerRequest.Shutdown -> {
          send(writer, WorkerResponse.Ok)
          return
        }
      }
    }
  }

  /**
   * Take on the adopting daemon's catalog. Every forwarded property is applied as-is — the engine
   * and the host read the render-shaping ones (`composeai.render.outputDir`, the IR dir, the
   * placeholder flag, …) per render, not at boot — then the child user classloader is rebuilt from
   * the new `composeai.daemon.userClassDirs`, and the adopter is watched so this JVM halts with it.
   */
  private fun configure(
    host: RobolectricHost,
    request: WorkerRequest.Configure,
    userClassUrls: AtomicReference<List<URL>>,
  ) {
    for ((key, value) in request.systemProperties) System.setProperty(key, value)
    userClassUrls.set(UserClassLoaderHolder.urlsFromSysprop())
    host.resetUserClassLoaderHolders()
    request.parentPid?.let { pid ->
      ProcessHandle.of(pid).ifPresent { handle -> haltWhenExits(handle, "adopting daemon") }
    }
    System.err.println(
      "sandbox worker: configured by pid=${request.parentPid} " +
        "(${request.systemProperties.size} properties, ${userClassUrls.get().size} user class dirs)"
    )
  }

  private fun haltWhenExits(handle: ProcessHandle, what: String) {
    handle.onExit().thenRun {
      System.err.println("sandbox worker: $what ${handle.pid()} exited; halting")
      Runtime.getRuntime().halt(0)
    }
  }

  private fun send(writer: BufferedWriter, response: WorkerResponse) {
    writer.write(workerJson.encodeToString(WorkerResponse.serializer(), response))
    writer.write("\n")
    writer.flush()
  }
}
