#!/usr/bin/env python3
"""Compare closed-world region snapshots against an optional gate manifest."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys


def load_json(path: Path):
    with path.open("r", encoding="utf-8-sig") as source:
        return json.load(source)


def position_key(value: dict) -> tuple[int, int, int]:
    return int(value["x"]), int(value["y"]), int(value["z"])


def state(value: dict | None) -> dict:
    if value is None:
        return {"block": "minecraft:air", "properties": {}}
    return {
        "block": str(value.get("block", "minecraft:air")),
        "properties": {
            str(key): str(item)
            for key, item in sorted(value.get("properties", {}).items())
        },
    }


def snapshot_map(rows: list[dict]) -> dict[tuple[int, int, int], dict]:
    result: dict[tuple[int, int, int], dict] = {}
    for row in rows:
        key = position_key(row)
        if key in result:
            raise SystemExit(f"duplicate snapshot position: {key}")
        result[key] = state(row)
    return result


def expected_state(entry: dict, name: str) -> dict:
    value = entry.get(name)
    if not isinstance(value, dict):
        raise SystemExit(f"manifest entry is missing {name}")
    return state(value)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("before", type=Path)
    parser.add_argument("after", type=Path)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--expected-changed-cells", type=int)
    parser.add_argument("--output", type=Path)
    arguments = parser.parse_args()

    before = snapshot_map(load_json(arguments.before))
    after = snapshot_map(load_json(arguments.after))
    keys = sorted(before.keys() | after.keys())
    changes = [
        {
            "position": {"x": key[0], "y": key[1], "z": key[2]},
            "before": before.get(key, state(None)),
            "after": after.get(key, state(None)),
        }
        for key in keys
        if before.get(key, state(None)) != after.get(key, state(None))
    ]

    manifest = load_json(arguments.manifest) if arguments.manifest else None
    expected_count = arguments.expected_changed_cells
    mismatches: list[dict] = []
    expected_change_keys: set[tuple[int, int, int]] = set()
    if manifest is not None:
        manifest_count = int(manifest.get("expected_changed_cell_count", -1))
        if expected_count is not None and expected_count != manifest_count:
            raise SystemExit("CLI and manifest expected change counts disagree")
        expected_count = manifest_count
        for entry in manifest.get("expected_changed_cells", []):
            key = position_key(entry["position"])
            expected_change_keys.add(key)
            wanted_before = expected_state(entry, "before_state")
            wanted_after = expected_state(entry, "after_state")
            if before.get(key, state(None)) != wanted_before or after.get(
                key, state(None)
            ) != wanted_after:
                mismatches.append(
                    {
                        "kind": "expected_change_mismatch",
                        "position": entry["position"],
                        "expected_before": wanted_before,
                        "actual_before": before.get(key, state(None)),
                        "expected_after": wanted_after,
                        "actual_after": after.get(key, state(None)),
                    }
                )
        for entry in manifest.get("temporary_scaffolds", []):
            key = position_key(entry["position"])
            wanted_before = expected_state(entry, "before_state")
            wanted_after = expected_state(entry, "after_state")
            if before.get(key, state(None)) != wanted_before or after.get(
                key, state(None)
            ) != wanted_after:
                mismatches.append(
                    {
                        "kind": "temporary_scaffold_not_restored",
                        "position": entry["position"],
                        "expected_before": wanted_before,
                        "actual_before": before.get(key, state(None)),
                        "expected_after": wanted_after,
                        "actual_after": after.get(key, state(None)),
                    }
                )
        source = manifest.get("expected_source")
        if isinstance(source, dict):
            key = position_key(source["position"])
            wanted = expected_state(source, "state")
            if before.get(key, state(None)) != wanted or after.get(
                key, state(None)
            ) != wanted:
                mismatches.append(
                    {
                        "kind": "source_changed",
                        "position": source["position"],
                        "expected": wanted,
                        "actual_before": before.get(key, state(None)),
                        "actual_after": after.get(key, state(None)),
                    }
                )
        if bool(manifest.get("reject_unlisted_changes", False)):
            for change in changes:
                key = position_key(change["position"])
                if key not in expected_change_keys:
                    mismatches.append({"kind": "unlisted_change", **change})

    if expected_count is None:
        expected_count = len(changes)
    if len(changes) != expected_count:
        mismatches.append(
            {
                "kind": "changed_cell_count",
                "expected": expected_count,
                "actual": len(changes),
            }
        )

    result = {
        "schema_version": 1,
        "oracle": "offline-region-before-after",
        "before": str(arguments.before),
        "after": str(arguments.after),
        "manifest": str(arguments.manifest) if arguments.manifest else None,
        "expected_changed_cells": expected_count,
        "actual_changed_cells": len(changes),
        "changes": changes,
        "mismatches": mismatches,
        "passed": not mismatches,
    }
    serialized = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if arguments.output:
        arguments.output.parent.mkdir(parents=True, exist_ok=True)
        arguments.output.write_text(serialized, encoding="utf-8", newline="\n")
    else:
        sys.stdout.write(serialized)
    if mismatches:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
