#!/usr/bin/env python3
"""Split a benchmark-worker-startup.py async-profiler recording at production startup marks.

Requires async-profiler 4.5's jfr-converter.jar and a JDK with the jfr command.
Counts are CPU samples, not elapsed milliseconds; hotspot percentages are inclusive.
"""
import argparse
from collections import Counter
from datetime import datetime
import json
from pathlib import Path
import re
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--converter", type=Path, required=True)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    recording = args.directory / "profile.jfr"
    info = json.loads(subprocess.check_output([
        str(args.java.with_name("jfr")), "print", "--json", "--events", "jdk.JVMInformation", str(recording)
    ]))["recording"]["events"][0]["values"]
    epoch = round(datetime.fromisoformat(info["jvmStartTime"]).timestamp() * 1000)
    marks = [(int(ms), label) for ms, label in re.findall(
        r"\[\+(\d+)ms\] (.*)", (args.directory / "worker.log").read_text())]
    boot = next(ms for ms, label in marks if label == "spare worker: sandbox booted")
    warm = next(ms for ms, label in marks if label.startswith("sandbox 0 warm render done"))
    ready = next(ms for ms, label in marks if label == "spare worker: warm, listening")
    names = json.loads((args.directory / "summary.json").read_text()).get("threadNames", {})
    report = {"jvmVersion": info["jvmVersion"], "phases": {},
        "note": "CPU sample counts; inclusive hotspots overlap and must not be summed."}
    needles = {
        "native runtime initialization": "SharedNativeRuntimeLoader.ensureLoaded",
        "application creation": "AndroidTestEnvironment.installAndCreateApplication",
        "BouncyCastle setup": "BouncyCastleProvider.setup",
        "Activity launch": "ActivityScenario.launchInternal",
        "invokedynamic linkage": "MethodHandleNatives.linkCallSiteImpl",
        "class definition": "ClassLoader.defineClass1",
        "captureRoboImage": "RoborazziKt.captureRoboImage",
    }
    for phase, start, end in [("boot", 0, boot), ("warm-render", boot, warm), ("post-warm", warm, ready)]:
        for kind in ["cpu", "wall"]:
            for fmt in ["collapsed", "html"]:
                subprocess.run([str(args.java), "-jar", str(args.converter), "--" + kind,
                    "--threads", "--dot", "--from", str(epoch + start), "--to", str(epoch + end),
                    str(recording), str(args.output / f"{phase}-{kind}.{fmt}")], check=True)
        groups = Counter()
        hotspots = Counter()
        native_leaves = 0
        for line in (args.output / f"{phase}-cpu.collapsed").read_text().splitlines():
            stack, count = line.rsplit(" ", 1)
            frames, count = stack.split(";"), int(count)
            tid = re.search(r"tid=(\d+)", frames[0])
            name = names.get(tid[1], frames[0]) if tid else frames[0]
            application = any(s in frames[0] for s in ["SDK ", "compose-ai-daemon-host", "compose-ai-sandbox-spare"])
            if "Compiler" in name:
                group = "compiler"
            elif name.startswith(("GC ", "G1 ")) or "GC Thread" in frames[0]:
                group = "gc"
            elif application:
                group = "application"
            else:
                group = "other"
            groups[group] += count
            if frames[-1].endswith("/libjvm.so"):
                native_leaves += count
            if application:
                for label, needle in needles.items():
                    if any(needle in frame for frame in frames):
                        hotspots[label] += count
        total = sum(groups.values())
        report["phases"][phase] = {"wallMs": end - start, "cpuSamples": total,
            "threadGroups": dict(groups),
            "unresolvedJvmLeafSamples": native_leaves,
            "applicationInclusiveHotspots": dict(hotspots)}
    (args.output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
