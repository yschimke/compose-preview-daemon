package ee.schimke.composeai.daemon.client

import java.io.File

/**
 * A stand-in daemon for [ChildEnvironmentTest]: writes its own environment, one `NAME=value` per
 * line, to the file named by the `envDump.out` system property, then exits.
 */
object EnvDumpMain {
  @JvmStatic
  fun main(args: Array<String>) {
    val out = File(System.getProperty("envDump.out"))
    val tmp = File(out.path + ".tmp")
    tmp.writeText(System.getenv().entries.joinToString("\n") { "${it.key}=${it.value}" })
    tmp.renameTo(out)
  }
}
