#!/usr/bin/env python3
"""Run sequential, rotated fresh-worker trials and require visual/hierarchy parity.

Matrix JSON is a list of {"name": "default", "jvmArgs": [], "java": "/path/to/java"}.
The java field can be omitted when --java supplies the default. Results keep each
worker's summary and logs; no production launch settings are changed.
"""
import argparse
import hashlib
import struct
import json
from pathlib import Path
import re
import statistics
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--matrix", type=Path, required=True)
    parser.add_argument("--classpath", type=Path, required=True)
    parser.add_argument("--java")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--trials", type=int, default=3)
    parser.add_argument("--renders", type=int, default=90)
    parser.add_argument("--fixture", action="append", default=[])
    parser.add_argument("--compare-pixels", action="store_true",
        help="Compare decoded RGBA pixels instead of PNG encoding bytes (requires Pillow)")
    args = parser.parse_args()
    if args.compare_pixels:
        try:
            from PIL import Image
        except ImportError:
            parser.error("--compare-pixels requires Pillow")
    variants = json.loads(args.matrix.read_text())
    if not isinstance(variants, list) or not variants or args.trials < 1 or args.renders < 1:
        parser.error("matrix must be a nonempty list; trials and renders must be positive")
    names = set()
    for variant in variants:
        if not isinstance(variant, dict):
            parser.error("each matrix variant must be an object")
        name = variant.get("name", "")
        if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9_-]+", name) or name in names:
            parser.error("variant names must be unique alphanumeric/dash/underscore identifiers")
        names.add(name)
        if not (variant.get("java") or args.java):
            parser.error(f"{name} needs java or --java")
        flags = variant.get("jvmArgs", [])
        if not isinstance(flags, list) or any(not isinstance(flag, str) for flag in flags):
            parser.error(f"{name}: jvmArgs must be a list of strings")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    reference = None
    reference_pngs = None
    runs = []
    for trial in range(args.trials):
        offset = trial % len(variants)
        for variant in variants[offset:] + variants[:offset]:
            name = variant["name"]
            directory = output / f"{trial}-{name}"
            command = [sys.executable, str(Path(__file__).with_name("benchmark-worker-startup.py")),
                "--classpath", str(args.classpath.resolve()),
                "--java", variant.get("java") or args.java,
                "--output", str(directory), "--renders", str(args.renders)]
            for fixture in args.fixture:
                command += ["--fixture", fixture]
            command += ["--jvm-arg=" + flag for flag in variant.get("jvmArgs", [])]
            with (output / f"{trial}-{name}.log").open("w") as log:
                # The worker runner enforces readiness, per-request and shutdown timeouts and
                # kills its child in finally. Let it own that cleanup rather than killing the
                # Python wrapper on an arbitrary total-duration deadline.
                subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
            summary = json.loads((directory / "summary.json").read_text())
            pngs = [r["pngSha256"] for r in summary["renders"]]
            fingerprints = []
            for render in summary["renders"]:
                visual_hash = render["pngSha256"]
                if args.compare_pixels:
                    # Decode only after worker shutdown: image comparison must not compete with
                    # the timed worker or change when its JIT gets to run between requests.
                    with Image.open(directory / "renders" / f"{render['tag']}.png") as image:
                        rgba = image.convert("RGBA")
                        visual_hash = hashlib.sha256(
                            struct.pack(">II", *rgba.size) + rgba.tobytes()).hexdigest()
                fingerprints.append((render["fixture"], visual_hash, render["uiaSha256"]))
            if reference is None:
                reference = fingerprints
                reference_pngs = pngs
            if fingerprints != reference:
                mismatches = [i for i, (expected, actual) in enumerate(zip(reference, fingerprints))
                    if expected != actual]
                raise RuntimeError(f"Visual or hierarchy parity failed: trial {trial}, {name}; frames {mismatches}; "
                    f"expected {len(reference)} frames, got {len(fingerprints)}")
            row = {"trial": trial, "variant": name, "readyWallMs": summary["readyWallMs"],
                "readyCpuMs": summary["readyCpuMs"], "totalWallMs": summary["totalWallMs"],
                "totalCpuMs": summary["totalCpuMs"], "parity": True,
                "comparison": "pixels" if args.compare_pixels else "pngBytes",
                "pngByteParity": pngs == reference_pngs,
                "last30MedianMs": statistics.median(r["wallMs"] for r in summary["renders"][1:][-30:])}
            runs.append(row)
            (output / "matrix-summary.json").write_text(json.dumps(runs, indent=2) + "\n")
            print(json.dumps(row), flush=True)


if __name__ == "__main__":
    main()
