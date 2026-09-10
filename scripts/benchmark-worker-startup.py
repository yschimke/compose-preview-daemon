#!/usr/bin/env python3
"""Measure a real spare worker, then render fixtures over its production socket protocol.

Build :daemon:android:writeDaemonClasspath first. Run each variant in a fresh output
folder; compare unprofiled trials separately from instrumented diagnostics.
"""
import argparse
import hashlib
import json
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classpath", type=Path, required=True)
    parser.add_argument("--java", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--jvm-arg", action="append", default=[])
    parser.add_argument("--profiler", type=Path, help="Path to libasyncProfiler.so (4.5)")
    parser.add_argument("--renders", type=int, default=10, help="Fixture renders after one red render")
    parser.add_argument("--fixture", action="append", help="Repeat to cycle fixture functions from RedFixturePreviewsKt (default: MaterialButtonInteractionState)")
    args = parser.parse_args()
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
    summary = {"java": args.java, "jvmArgs": args.jvm_arg, "profiled": bool(args.profiler),
        "fixtures": fixtures, "startedUnixSeconds": time.time(),
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

    def sample_thread_names():
        # HotSpot can retire dynamic compiler threads before the readiness snapshot.
        while not stop_sampling.is_set():
            for task in Path(f"/proc/{process.pid}/task").glob("*"):
                try:
                    thread_names[task.name] = (task / "comm").read_text().strip()
                except FileNotFoundError:
                    pass
            stop_sampling.wait(0.05)

    sampler = threading.Thread(target=sample_thread_names, daemon=True)
    if args.profiler:
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

            for index in range(args.renders + 1):
                function = "RedSquare" if index == 0 else fixtures[(index - 1) % len(fixtures)]
                prefix = "material" if function == "MaterialButtonInteractionState" else function
                tag = "red" if index == 0 else f"{prefix}-{index - 1}"
                before = time.monotonic()
                before_cpu = cpu_ms(process.pid)
                reply = request({"type": "render", "id": index, "timeoutMs": 120000,
                    "target": {"type": "spec", "spec": {
                        "className": "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
                        "functionName": function, "widthPx": 320, "heightPx": 320,
                        "outputBaseName": tag,
                    }}})
                wall_ms = round((time.monotonic() - before) * 1000)
                used_cpu = cpu_ms(process.pid) - before_cpu
                if reply.get("type") != "result":
                    raise RuntimeError(f"render failed: {reply}")
                png = output / "renders" / f"{tag}.png"
                data = png.read_bytes()
                if not data.startswith(b"\x89PNG\r\n\x1a\n"):
                    raise RuntimeError(f"invalid PNG: {png}")
                summary["renders"].append({"tag": tag, "fixture": function, "wallMs": wall_ms,
                    "cpuMs": used_cpu, "pngSha256": hashlib.sha256(data).hexdigest(),
                    "uiaSha256": hashlib.sha256((output / "data" / tag / "uia-hierarchy.json").read_bytes()).hexdigest()})
            summary["totalCpuMs"] = cpu_ms(process.pid)
            summary["totalWallMs"] = round((time.monotonic() - started) * 1000)
            reply = request({"type": "shutdown"})
            if reply.get("type") != "ok":
                raise RuntimeError(f"shutdown failed: {reply}")
        process.wait(timeout=30)
        if process.returncode:
            raise RuntimeError(f"worker exited {process.returncode}")
        reader.join(timeout=5)
        stop_sampling.set()
        if args.profiler:
            sampler.join(timeout=5)
        (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
        print(json.dumps(summary), flush=True)
    finally:
        stop_sampling.set()
        if args.profiler:
            sampler.join(timeout=5)
        if process.poll() is None:
            process.kill()
            process.wait(timeout=10)
        reader.join(timeout=5)
        process.stdout.close()


if __name__ == "__main__":
    main()
