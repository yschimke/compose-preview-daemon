#!/usr/bin/env python3
"""Compare completed, identically sequenced worker runs, including exported data.

Unlike comparisons across successive frames, corresponding baseline/candidate
frames should have the same node IDs. Optional lambda-identity normalization
handles JVM-generated diagnostic object strings; node IDs remain exact.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path


def normalize_lambdas(value, path=(), lambda_ids=True, semantics_order=False):
    if isinstance(value, dict):
        return {key: normalize_lambdas(item, (*path, key), lambda_ids, semantics_order) for key, item in value.items()}
    if isinstance(value, list):
        return [normalize_lambdas(item, path, lambda_ids, semantics_order) for item in value]
    if isinstance(value, str) and "modifiers" in path and "properties" in path:
        match = re.fullmatch(r"([\w.$]+)\$\$Lambda\$\d+/0x[0-9a-fA-F]+@[0-9a-fA-F]+", value)
        if match and lambda_ids:
            return match[1] + "$$Lambda$<jvm-identity>"
        # SemanticsConfiguration's diagnostic map order varies by JVM. Canonicalize only
        # this unambiguous two-key shape, retaining every description character and role.
        if semantics_order and path[-1] == "properties":
            match = re.fullmatch(r"\{Role=([A-Za-z]+), ContentDescription=(\[[^\[\]{}]*\])\}", value)
            if match:
                return "{ContentDescription=" + match[2] + ", Role=" + match[1] + "}"
    return value


def compare(baseline, candidate, artifacts, normalize_jvm_lambdas=False, normalize_semantics_debug_order=False):
    reports = [json.loads((root / "summary.json").read_text()) for root in (baseline, candidate)]
    left, right = reports
    default_class = "ee.schimke.composeai.daemon.RedFixturePreviewsKt"
    if left.get("fixtureClass", default_class) != right.get("fixtureClass", default_class):
        raise ValueError("fixture classes differ")
    if left.get("dimensions", [320, 320]) != right.get("dimensions", [320, 320]):
        raise ValueError("frame dimensions differ")
    if left.get("reuseOutput") or right.get("reuseOutput"):
        raise ValueError("per-frame data comparison requires distinct output names")
    frames = [report["renders"] for report in reports]
    sequences = [[(f["tag"], f["fixture"]) for f in run] for run in frames]
    if sequences[0] != sequences[1] or not sequences[0]:
        raise ValueError("render sequences differ or are empty")
    if len({tag for tag, _ in sequences[0]}) != len(sequences[0]):
        raise ValueError("repeated output names cannot prove per-frame artifact parity")
    checked = 0
    normalized_artifacts = 0
    for first, second in zip(*frames):
        tag = first["tag"]
        for key in ("pngSha256", "uiaSha256"):
            if first[key] != second[key]:
                raise ValueError(f"{tag}: {key} differs")
        for name in artifacts:
            paths = [root / "data" / tag / name for root in (baseline, candidate)]
            hashes = [hashlib.sha256(path.read_bytes()).digest() for path in paths]
            if hashes[0] != hashes[1]:
                equivalent = ((normalize_jvm_lambdas or normalize_semantics_debug_order) and name.endswith(".json") and
                    normalize_lambdas(json.loads(paths[0].read_text()), lambda_ids=normalize_jvm_lambdas, semantics_order=normalize_semantics_debug_order) ==
                    normalize_lambdas(json.loads(paths[1].read_text()), lambda_ids=normalize_jvm_lambdas, semantics_order=normalize_semantics_debug_order))
                if equivalent:
                    normalized_artifacts += 1
                if not equivalent:
                    raise ValueError(f"{tag}: {name} differs")
            checked += 1
    return {"baseline": str(baseline), "candidate": str(candidate),
        "checkedFrames": len(frames[0]), "checkedDataArtifacts": checked,
        "artifacts": artifacts, "normalizedJvmLambdas": normalize_jvm_lambdas, "normalizedSemanticsDebugOrder": normalize_semantics_debug_order, "normalizedArtifactComparisons": normalized_artifacts, "parity": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--artifact", action="append",
        help="Data filename; defaults to layout, SVG and semantics")
    parser.add_argument("--normalize-jvm-lambdas", action="store_true",
        help="Ignore JVM-generated lambda class numbers and object addresses in JSON strings")
    parser.add_argument("--normalize-semantics-debug-order", action="store_true",
        help="Canonicalize only Role/ContentDescription order in diagnostic modifier strings")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    artifacts = args.artifact or ["layout-inspector.json", "compose-figma.svg", "compose-semantics.json"]
    if any(Path(name).name != name or name in (".", "..") for name in artifacts):
        parser.error("--artifact must be a filename")
    text = json.dumps(compare(args.baseline, args.candidate, artifacts, args.normalize_jvm_lambdas, args.normalize_semantics_debug_order), indent=2) + "\n"
    if args.output:
        args.output.write_text(text)
    print(text, end="")


if __name__ == "__main__":
    main()
