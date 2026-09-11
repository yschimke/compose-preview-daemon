#!/usr/bin/env python3
"""Summarize completed worker soaks without mistaking RSS high-water for live heap.

Pass one or more benchmark-worker-startup.py summary.json files. Diagnostics
with forced-GC checkpoints are reported separately from per-request timings.
"""
import argparse
import json
from pathlib import Path
import re
import statistics


def summarize(path):
    report = json.loads(path.read_text())
    frames = report["renders"]
    expected = {}
    for frame in frames:
        hashes = (frame["pngSha256"], frame["uiaSha256"])
        if expected.setdefault(frame["fixture"], hashes) != hashes:
            raise ValueError(f"{path}: output drift at {frame['tag']}")
    checkpoints = []
    for point in report.get("heapCheckpoints", []):
        # Serial GC heap_info prints young and tenured generation usage in KiB.
        generations = re.findall(r"generation\s+total \d+K, used (\d+)K", point["heapInfo"])
        if len(generations) != 2:
            raise ValueError(f"{path}: expected Serial GC's two generation usage lines")
        metaspace = re.search(r"Metaspace\s+used (\d+)K", point["heapInfo"])
        checkpoints.append({"renderIndex": point["renderIndex"],
            "postGcHeapUsedKiB": sum(map(int, generations)),
            "metaspaceUsedKiB": int(metaspace[1]) if metaspace else None,
            **point["memoryKiB"]})
    result = {"source": str(path), "jvmArgs": report["jvmArgs"],
        "dimensions": report.get("dimensions", [320, 320]), "density": report.get("density", 2.0),
        "fixtureClass": report.get("fixtureClass", "ee.schimke.composeai.daemon.RedFixturePreviewsKt"),
        "checkedFrames": len(frames), "withinRunParity": True,
        "fixtureHashes": {name: {"pngSha256": hashes[0], "uiaSha256": hashes[1]}
            for name, hashes in expected.items()},
        "forcedGcCheckpointEvery": report.get("gcCheckpointEvery", 0),
        "checkpoints": checkpoints}
    # Compare whole first/last windows; this is descriptive, not a leak verdict.
    width = min(100, (len(frames) - 1) // 2)
    if width:
        result["requestWindows"] = {name: {
            "count": len(window),
            "medianWallMs": statistics.median(f["wallMs"] for f in window),
            "meanCpuMs": statistics.mean(f["cpuMs"] for f in window)}
            for name, window in [("first", frames[1:1 + width]), ("last", frames[-width:])]}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("summaries", type=Path, nargs="+")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    results = [summarize(path) for path in args.summaries]
    expected = {}
    for result in results:
        for fixture, hashes in result["fixtureHashes"].items():
            key = (*result["dimensions"], result["density"], result["fixtureClass"], fixture)
            if expected.setdefault(key, hashes) != hashes:
                raise ValueError(f"cross-run output drift for {key}: {result['source']}")
    text = json.dumps({"runs": results, "crossRunParity": True}, indent=2) + "\n"
    if args.output:
        args.output.write_text(text)
    print(text, end="")


if __name__ == "__main__":
    main()
