#!/usr/bin/env python3
"""Verify the closed-world warehouse-smelt fixture after the evaluation terminal."""

from __future__ import annotations

import argparse
import importlib.util
import json
from pathlib import Path
import sys


WORKSPACE = (193, 206, 199, 204, 193, 206)
SOURCE = (195, 200, 194)
FURNACE = (196, 200, 194)
OUTPUT = (197, 200, 194)


def load_smelt_oracle_module():
    path = Path(__file__).with_name("Inspect-McmcpSmeltOracle.py")
    spec = importlib.util.spec_from_file_location("mcmcp_smelt_oracle", path)
    if spec is None or spec.loader is None:
        raise SystemExit(f"cannot load smelt oracle: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def stack_rows(entity) -> list[dict]:
    rows: list[dict] = []
    for entry in entity.get("Items", entity.get("items", [])) or []:
        components = entry.get("components", entry.get("Components", {}))
        rows.append(
            {
                "slot": int(entry.get("Slot", entry.get("slot", -1))),
                "item": str(entry.get("id", entry.get("Id", ""))),
                "count": int(entry.get("count", entry.get("Count", 0))),
                "default_components_only": not components,
            }
        )
    return sorted(rows, key=lambda row: (row["slot"], row["item"]))


def entity_id(entity) -> str:
    return str(entity.get("id", entity.get("Id", "")))


def expected_state(x: int, y: int, z: int) -> dict:
    if y == WORKSPACE[2]:
        return {"block": "minecraft:smooth_stone", "properties": {}}
    if (x, y, z) == SOURCE:
        return {
            "block": "minecraft:chest",
            "properties": {"facing": "south", "type": "single", "waterlogged": "false"},
        }
    if (x, y, z) == FURNACE:
        return {
            "block": "minecraft:furnace",
            "properties": {"facing": "north", "lit": "dynamic"},
        }
    if (x, y, z) == OUTPUT:
        return {
            "block": "minecraft:barrel",
            "properties": {"facing": "up", "open": "false"},
        }
    return {"block": "minecraft:air", "properties": {}}


def structural_mismatches(region_rows: list[dict]) -> list[dict]:
    actual = {
        (int(row["x"]), int(row["y"]), int(row["z"])): {
            "block": str(row["block"]),
            "properties": {
                str(key): str(value)
                for key, value in sorted(row.get("properties", {}).items())
            },
        }
        for row in region_rows
    }
    expected_count = (
        (WORKSPACE[1] - WORKSPACE[0] + 1)
        * (WORKSPACE[3] - WORKSPACE[2] + 1)
        * (WORKSPACE[5] - WORKSPACE[4] + 1)
    )
    if len(actual) != len(region_rows):
        return [{"kind": "duplicate_workspace_cell"}]
    mismatches: list[dict] = []
    for x in range(WORKSPACE[0], WORKSPACE[1] + 1):
        for y in range(WORKSPACE[2], WORKSPACE[3] + 1):
            for z in range(WORKSPACE[4], WORKSPACE[5] + 1):
                key = (x, y, z)
                wanted = expected_state(x, y, z)
                observed = actual.get(key)
                matches = observed is not None and observed["block"] == wanted["block"]
                if matches and key == FURNACE:
                    matches = observed["properties"].get("facing") == "north" and observed[
                        "properties"
                    ].get("lit") in {"true", "false"} and set(observed["properties"]) == {
                        "facing",
                        "lit",
                    }
                elif matches:
                    matches = observed["properties"] == wanted["properties"]
                if not matches:
                    mismatches.append(
                        {
                            "position": {"x": x, "y": y, "z": z},
                            "expected": wanted,
                            "actual": observed,
                        }
                    )
    if len(actual) != expected_count:
        mismatches.append(
            {"kind": "workspace_cell_count", "expected": expected_count, "actual": len(actual)}
        )
    return mismatches


def analyze(
    player_counts: dict[str, int], source_entity, furnace_entity, output_entity, region_rows
) -> dict:
    source_stacks = stack_rows(source_entity)
    furnace_stacks = stack_rows(furnace_entity)
    output_stacks = stack_rows(output_entity)
    structure = structural_mismatches(region_rows)
    entity_ids = {
        "source": entity_id(source_entity),
        "furnace": entity_id(furnace_entity),
        "output": entity_id(output_entity),
    }
    output_exact = (
        len(output_stacks) == 1
        and 0 <= output_stacks[0]["slot"] < 27
        and output_stacks[0]["item"] == "minecraft:iron_ingot"
        and output_stacks[0]["count"] == 1
        and output_stacks[0]["default_components_only"]
    )
    furnace_cook_time = int(
        furnace_entity.get("CookTime", furnace_entity.get("cooking_time_spent", 0))
    )
    checks = {
        "player_inventory_empty": not player_counts,
        "source_inventory_empty": not source_stacks,
        "furnace_inventory_empty": not furnace_stacks,
        "furnace_cook_idle": furnace_cook_time == 0,
        "output_component_exact": output_exact,
        "block_entity_types_exact": entity_ids
        == {
            "source": "minecraft:chest",
            "furnace": "minecraft:furnace",
            "output": "minecraft:barrel",
        },
        "workspace_structure_unchanged": not structure,
    }
    return {
        "schema_version": 1,
        "oracle": "offline-warehouse-smelt-world",
        "world_closed_required": True,
        "fixture": {
            "workspace": {
                "min": {"x": WORKSPACE[0], "y": WORKSPACE[2], "z": WORKSPACE[4]},
                "max": {"x": WORKSPACE[1], "y": WORKSPACE[3], "z": WORKSPACE[5]},
            },
            "source": dict(zip(("x", "y", "z"), SOURCE)),
            "furnace": dict(zip(("x", "y", "z"), FURNACE)),
            "output": dict(zip(("x", "y", "z"), OUTPUT)),
        },
        "checks": checks,
        "player_inventory": dict(sorted(player_counts.items())),
        "block_entity_ids": entity_ids,
        "source_stacks": source_stacks,
        "furnace_stacks": furnace_stacks,
        "output_stacks": output_stacks,
        "furnace_data": {
            "burn_time": int(
                furnace_entity.get(
                    "BurnTime", furnace_entity.get("lit_time_remaining", 0)
                )
            ),
            "cook_time": furnace_cook_time,
        },
        "structural_mismatches": structure,
        "passed": all(checks.values()),
    }


def synthetic_region() -> list[dict]:
    rows: list[dict] = []
    for x in range(WORKSPACE[0], WORKSPACE[1] + 1):
        for y in range(WORKSPACE[2], WORKSPACE[3] + 1):
            for z in range(WORKSPACE[4], WORKSPACE[5] + 1):
                state = expected_state(x, y, z)
                properties = dict(state["properties"])
                if (x, y, z) == FURNACE:
                    properties["lit"] = "true"
                rows.append(
                    {"x": x, "y": y, "z": z, "block": state["block"], "properties": properties}
                )
    return rows


def self_test() -> None:
    empty = {"id": "minecraft:chest", "Items": []}
    furnace = {"id": "minecraft:furnace", "Items": [], "BurnTime": 1200, "CookTime": 0}
    output = {
        "id": "minecraft:barrel",
        "Items": [{"Slot": 0, "id": "minecraft:iron_ingot", "count": 1}],
    }
    valid = analyze({}, empty, furnace, output, synthetic_region())
    assert valid["passed"]

    customized = dict(output)
    customized["Items"] = [
        {
            "Slot": 0,
            "id": "minecraft:iron_ingot",
            "count": 1,
            "components": {"minecraft:custom_name": "forbidden"},
        }
    ]
    assert not analyze({}, empty, furnace, customized, synthetic_region())["passed"]
    assert not analyze({"minecraft:coal": 1}, empty, furnace, output, synthetic_region())[
        "passed"
    ]
    cooking = {**furnace, "CookTime": 1}
    assert not analyze({}, empty, cooking, output, synthetic_region())["passed"]
    changed = synthetic_region()
    changed[0] = {**changed[0], "block": "minecraft:gold_block"}
    assert not analyze({}, empty, furnace, output, changed)["passed"]
    print("MCMCP warehouse-smelt offline oracle self-test passed.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("world", type=Path, nargs="?")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--self-test", action="store_true")
    arguments = parser.parse_args()
    if arguments.self_test:
        self_test()
        return
    if arguments.world is None:
        parser.error("world is required unless --self-test is used")

    shared = load_smelt_oracle_module()
    player_source, player_counts = shared.player_inventory(arguments.world)
    source = shared.furnace_entity(arguments.world, *SOURCE)
    furnace = shared.furnace_entity(arguments.world, *FURNACE)
    output = shared.furnace_entity(arguments.world, *OUTPUT)
    region_rows = shared.REGION.inspect_region(arguments.world, WORKSPACE)
    result = analyze(player_counts, source, furnace, output, region_rows)
    result["player_source"] = player_source
    serialized = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if arguments.output:
        arguments.output.parent.mkdir(parents=True, exist_ok=True)
        arguments.output.write_text(serialized, encoding="utf-8", newline="\n")
    else:
        sys.stdout.write(serialized)
    if not result["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
