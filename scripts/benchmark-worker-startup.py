#!/usr/bin/env python3
"""Measure a real spare worker, then render fixtures over its production socket protocol.

Build :daemon:android:writeDaemonClasspath first. Run each variant in a fresh output
folder; compare unprofiled trials separately from instrumented diagnostics.
"""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import queue
import re
import socket
import subprocess
import threading
import time


def cpu_ms(pid):
    # /proc stat comm can contain spaces and parentheses; fields after its last ')' start at 3.
    fields = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
    return (int(fields[11]) + int(fields[12])) * 1000 / os.sysconf("SC_CLK_TCK")


def memory_kib(pid, proportional=False):
    """Linux resident memory; PSS/private pages are optional, more expensive snapshots."""
    status = Path(f"/proc/{pid}/status").read_text()
    fields = {key: int(value) for key, value in re.findall(
        r"^(VmRSS|VmHWM|RssAnon|RssFile|RssShmem):\s+(\d+) kB$", status, re.MULTILINE)}
    if proportional:
        rollup = Path(f"/proc/{pid}/smaps_rollup").read_text()
        values = {key: int(value) for key, value in re.findall(
            r"^(Pss|Private_Clean|Private_Dirty|Private_Hugetlb):\s+(\d+) kB$", rollup, re.MULTILINE)}
        fields["Pss"] = values["Pss"]
        fields["Private"] = sum(values.get(key, 0) for key in
            ("Private_Clean", "Private_Dirty", "Private_Hugetlb"))
    return fields


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classpath", type=Path, required=True)
    parser.add_argument("--java", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--jvm-arg", action="append", default=[])
    parser.add_argument("--memory", action="store_true", help="Sample RSS at 100 ms and snapshot PSS/private memory at readiness and workload end (Linux)")
    parser.add_argument("--profiler", type=Path, help="Path to libasyncProfiler.so (4.5)")
    parser.add_argument("--renders", type=int, default=10, help="Fixture renders after one red render")
    parser.add_argument("--fixture", action="append", help="Repeat to cycle functions from --class-name (default: MaterialButtonInteractionState)")
    parser.add_argument("--exercise-recovery", action="store_true",
        help="After timings, check configure/swap and recovery from a throwing composable")
    parser.add_argument("--width", type=int, default=320)
    parser.add_argument("--height", type=int, default=320)
    parser.add_argument("--density", type=float, default=2.0,
        help="Pixel density (default: 2, matching historical RenderSpec requests)")
    parser.add_argument("--gc-checkpoint-every", type=int, default=0,
        help="Diagnostic only: force GC and record heap/PSS every N renders; timings include checkpoint overhead")
    parser.add_argument("--heap-histograms", action="store_true",
        help="Write a live-object histogram at each GC checkpoint (adds another diagnostic GC)")
    parser.add_argument("--heap-dump", action="store_true",
        help="Write a live heap dump after workload measurements (diagnostic overhead excluded from totals)")
    parser.add_argument("--reuse-output", action="store_true",
        help="Overwrite one output name per fixture to distinguish repeated previews from growing output cardinality")
    parser.add_argument("--class-name", default="ee.schimke.composeai.daemon.RedFixturePreviewsKt")
    parser.add_argument("--user-class-dir", type=Path, action="append", default=[],
        help="Directory or jar for the application's child-first classloader")
    parser.add_argument("--swap-every", type=int, default=0,
        help="Swap application classloaders every N fixture renders (diagnostic workload)")
    args = parser.parse_args()
    if args.swap_every < 0 or (args.swap_every and not args.user_class_dir):
        parser.error("--swap-every must be nonnegative and requires --user-class-dir")
    if any(not path.exists() for path in args.user_class_dir):
        parser.error("user class paths must exist")
    if args.heap_histograms and not args.gc_checkpoint_every:
        parser.error("--heap-histograms requires --gc-checkpoint-every")
    if not math.isfinite(args.density) or args.density <= 0:
        parser.error("density must be finite and positive")
    if args.width < 1 or args.height < 1 or args.gc_checkpoint_every < 0:
        parser.error("dimensions must be positive and checkpoint interval nonnegative")
    fixtures = args.fixture or ["MaterialButtonInteractionState"]
    if any(not name.isidentifier() for name in fixtures):
        parser.error("--fixture must be a function identifier")
    if args.renders < 1:
        parser.error("--renders must be positive")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    cp = os.pathsep.join(args.classpath.read_text().splitlines())
    command = [args.java, "-Xmx1g", *args.jvm_arg]
    if args.profiler:
        command.append(
            f"-agentpath:{args.profiler.resolve()}=start,event=ctimer,interval=1ms,"
            f"wall=1ms,cstack=dwarf,file={output / 'profile.jfr'}"
        )
    command += [
        "-Djava.awt.headless=true", "-Drobolectric.graphicsMode=NATIVE",
        "-Drobolectric.enabledSdks=35", "-Droborazzi.test.record=true",
        "-Dcomposeai.daemon.sandboxWorker.spare=true", "-Dcomposeai.daemon.sandboxCount=1",
        f"-Dcomposeai.render.outputDir={output / 'renders'}",
        "-cp", cp, "ee.schimke.composeai.daemon.pool.SandboxWorkerMain",
    ]
    lines = queue.Queue()
    started = time.monotonic()
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    (output / "worker.pid").write_text(str(process.pid) + "\n")
    summary = {"memoryMeasured": args.memory, "java": args.java, "jvmArgs": args.jvm_arg, "profiled": bool(args.profiler),
        "dimensions": [args.width, args.height], "density": args.density, "gcCheckpointEvery": args.gc_checkpoint_every, "heapHistograms": args.heap_histograms, "heapCheckpoints": [], "reuseOutput": args.reuse_output, "fixtureClass": args.class_name, "swapEvery": args.swap_every, "userClassDirs": [str(p.resolve()) for p in args.user_class_dir], "fixtures": fixtures, "startedUnixSeconds": time.time(),
        "hostLoadAverage": os.getloadavg(), "logicalCpus": os.cpu_count(), "renders": []}

    def pump():
        with (output / "worker.log").open("w") as log:
            for line in process.stdout:
                log.write(line)
                log.flush()
                lines.put(line)
        lines.put(None)

    thread_names = {}
    stop_sampling = threading.Event()
    memory_samples = []

    def sample_process():
        # HotSpot can retire compiler threads before readiness. RSS sampling does not read smaps.
        next_memory = 0.0
        while not stop_sampling.is_set():
            if args.profiler:
                for task in Path(f"/proc/{process.pid}/task").glob("*"):
                    try:
                        thread_names[task.name] = (task / "comm").read_text().strip()
                    except FileNotFoundError:
                        pass
            now = time.monotonic()
            if args.memory and now >= next_memory:
                try:
                    memory_samples.append({"elapsedMs": round((now - started) * 1000),
                        **memory_kib(process.pid)})
                except (FileNotFoundError, ProcessLookupError):
                    pass
                next_memory = now + 0.1
            stop_sampling.wait(0.05 if args.profiler else 0.1)

    sampler = threading.Thread(target=sample_process, daemon=True)
    if args.profiler or args.memory:
        sampler.start()
    reader = threading.Thread(target=pump, daemon=True)
    reader.start()
    try:
        deadline = started + 180
        while True:
            line = lines.get(timeout=max(0.001, deadline - time.monotonic()))
            if line is None:
                raise RuntimeError(f"worker exited before readiness; see {output / 'worker.log'}")
            match = re.search(r"composeai-spare-worker: listening pid=(\d+) port=(\d+)", line)
            if match:
                break
            if time.monotonic() > deadline:
                raise TimeoutError("worker readiness timed out")
        assert int(match[1]) == process.pid
        summary["readyWallMs"] = round((time.monotonic() - started) * 1000)
        summary["readyCpuMs"] = cpu_ms(process.pid)
        if args.memory:
            summary["readyMemoryKiB"] = memory_kib(process.pid, proportional=True)
        summary["threadNames"] = thread_names
        for task in Path(f"/proc/{process.pid}/task").iterdir():
            try:
                summary["threadNames"][task.name] = (task / "comm").read_text().strip()
            except FileNotFoundError:
                pass  # A compiler/GC thread may exit while enumerating the task directory.
        with socket.create_connection(("127.0.0.1", int(match[2])), timeout=120) as sock:
            stream = sock.makefile("rwb")

            def request(value):
                stream.write(json.dumps(value).encode() + b"\n")
                stream.flush()
                reply = stream.readline()
                if not reply:
                    raise RuntimeError("worker closed connection")
                return json.loads(reply)

            if args.user_class_dir:
                configured = request({"type": "configure", "systemProperties": {
                    "composeai.daemon.userClassDirs": os.pathsep.join(str(p.resolve()) for p in args.user_class_dir)}})
                if configured.get("type") != "configured" or configured.get("pid") != process.pid:
                    raise RuntimeError(f"configure failed: {configured}")
            previous_loader = None
            expect_new_loader = False
            for index in range(args.renders + 1):
                function = "RedSquare" if index == 0 else fixtures[(index - 1) % len(fixtures)]
                prefix = "material" if function == "MaterialButtonInteractionState" else function
                tag = "red" if index == 0 else (prefix if args.reuse_output else f"{prefix}-{index - 1}")
                before = time.monotonic()
                before_cpu = cpu_ms(process.pid)
                reply = request({"type": "render", "id": index, "timeoutMs": 120000,
                    "target": {"type": "spec", "spec": {
                        "className": "ee.schimke.composeai.daemon.RedFixturePreviewsKt" if index == 0 else args.class_name,
                        "functionName": function, "widthPx": args.width, "heightPx": args.height, "density": args.density,
                        "outputBaseName": tag,
                    }}})
                wall_ms = round((time.monotonic() - before) * 1000)
                used_cpu = cpu_ms(process.pid) - before_cpu
                if reply.get("type") != "result":
                    raise RuntimeError(f"render failed: {reply}")
                loader = reply["result"]["classLoaderHashCode"]
                if expect_new_loader and loader == previous_loader:
                    raise RuntimeError("swap acknowledged without changing the render classloader")
                previous_loader = loader
                expect_new_loader = False
                png = output / "renders" / f"{tag}.png"
                data = png.read_bytes()
                if not data.startswith(b"\x89PNG\r\n\x1a\n"):
                    raise RuntimeError(f"invalid PNG: {png}")
                summary["renders"].append({"tag": tag, "fixture": function, "wallMs": wall_ms,
                    "cpuMs": used_cpu, "classLoaderHashCode": loader, "workerMetrics": reply["result"].get("metrics"), "pngSha256": hashlib.sha256(data).hexdigest(),
                    "uiaSha256": hashlib.sha256((output / "data" / tag / "uia-hierarchy.json").read_bytes()).hexdigest()})
                if args.swap_every and index > 0 and index % args.swap_every == 0 and index < args.renders:
                    swapped = request({"type": "swap"})
                    if swapped.get("type") != "ok":
                        raise RuntimeError(f"swap failed: {swapped}")
                    expect_new_loader = True
                if args.gc_checkpoint_every and (index % args.gc_checkpoint_every == 0 or index == args.renders):
                    jcmd = str(Path(args.java).with_name("jcmd"))
                    gc = subprocess.run([jcmd, str(process.pid), "GC.run"], check=True, capture_output=True, text=True, timeout=30)
                    heap = subprocess.run([jcmd, str(process.pid), "GC.heap_info"], check=True, capture_output=True, text=True, timeout=30)
                    checkpoint = {"renderIndex": index, "elapsedMs": round((time.monotonic() - started) * 1000),
                        "memoryKiB": memory_kib(process.pid, proportional=True), "gcOutput": gc.stdout, "heapInfo": heap.stdout}
                    if args.heap_histograms:
                        histogram = subprocess.run([jcmd, str(process.pid), "GC.class_histogram"],
                            check=True, capture_output=True, text=True, timeout=60)
                        histogram_path = output / f"heap-histogram-{index}.txt"
                        histogram_path.write_text(histogram.stdout)
                        checkpoint["histogramFile"] = histogram_path.name
                        loaders = subprocess.run([jcmd, str(process.pid), "VM.classloader_stats"],
                            check=True, capture_output=True, text=True, timeout=30)
                        loaders_path = output / f"classloader-stats-{index}.txt"
                        loaders_path.write_text(loaders.stdout)
                        checkpoint["classloaderStatsFile"] = loaders_path.name
                    summary["heapCheckpoints"].append(checkpoint)
                    (output / "heap-checkpoints.json").write_text(json.dumps(summary["heapCheckpoints"], indent=2) + "\n")
            summary["totalCpuMs"] = cpu_ms(process.pid)
            summary["totalWallMs"] = round((time.monotonic() - started) * 1000)
            if args.memory:
                summary["workloadEndMemoryKiB"] = memory_kib(process.pid, proportional=True)
            if args.heap_dump:
                dump_path = output / "heap.hprof"
                subprocess.run([str(Path(args.java).with_name("jcmd")), str(process.pid),
                    "GC.heap_dump", str(dump_path)], check=True, capture_output=True, text=True, timeout=120)
                summary["heapDumpFile"] = dump_path.name
            if args.exercise_recovery:
                configured = request({"type": "configure", "systemProperties": {}})
                if configured.get("type") != "configured" or configured.get("pid") != process.pid:
                    raise RuntimeError(f"configure failed: {configured}")
                swapped = request({"type": "swap"})
                if swapped.get("type") != "ok":
                    raise RuntimeError(f"swap failed: {swapped}")
                recovery = []
                for offset, function in enumerate(["RedSquare", "BoomComposable", "RedSquare"]):
                    tag = f"recovery-{offset}"
                    reply = request({"type": "render", "id": args.renders + 1 + offset,
                        "timeoutMs": 120000, "target": {"type": "spec", "spec": {
                            "className": "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
                            "functionName": function, "widthPx": args.width, "heightPx": args.height, "density": args.density,
                            "outputBaseName": tag,
                        }}})
                    if function == "BoomComposable":
                        if reply.get("type") != "failed" or "boom" not in reply.get("diagnostic", ""):
                            raise RuntimeError(f"expected composable failure: {reply}")
                        recovery.append({"fixture": function, "expectedFailure": True,
                            "diagnostic": reply["diagnostic"]})
                    else:
                        if reply.get("type") != "result":
                            raise RuntimeError(f"recovery render failed: {reply}")
                        png_hash = hashlib.sha256((output / "renders" / f"{tag}.png").read_bytes()).hexdigest()
                        uia_hash = hashlib.sha256((output / "data" / tag / "uia-hierarchy.json").read_bytes()).hexdigest()
                        if (png_hash, uia_hash) != (summary["renders"][0]["pngSha256"], summary["renders"][0]["uiaSha256"]):
                            raise RuntimeError(f"recovery parity failed: {tag}")
                        recovery.append({"fixture": function, "parity": True})
                summary["recovery"] = recovery
            reply = request({"type": "shutdown"})
            if reply.get("type") != "ok":
                raise RuntimeError(f"shutdown failed: {reply}")
        process.wait(timeout=30)
        if process.returncode:
            raise RuntimeError(f"worker exited {process.returncode}")
        reader.join(timeout=5)
        stop_sampling.set()
        if args.profiler or args.memory:
            sampler.join(timeout=5)
        if args.memory:
            summary["memorySamplesKiB"] = memory_samples
        (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
        print(json.dumps(summary), flush=True)
    finally:
        stop_sampling.set()
        if args.profiler or args.memory:
            sampler.join(timeout=5)
        if process.poll() is None:
            process.kill()
            process.wait(timeout=10)
        reader.join(timeout=5)
        process.stdout.close()


if __name__ == "__main__":
    main()
