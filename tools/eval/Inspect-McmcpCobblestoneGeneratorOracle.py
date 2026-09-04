#!/usr/bin/env python3
"""Inspect a closed world for the bounded real-fluid cobblestone generator gate."""

from __future__ import annotations

import argparse
import importlib.util
import json
import math
import os
from pathlib import Path
import sys


def load_nbtlib():
    dependency_path = os.environ.get("MCMCP_PYDEPS")
    if dependency_path:
        sys.path.insert(0, dependency_path)
    sibling_dependencies = Path(__file__).with_name("pydeps")
    if sibling_dependencies.is_dir():
        sys.path.insert(0, str(sibling_dependencies))
    try:
        import nbtlib  # type: ignore
    except ImportError as failure:
        raise SystemExit(
            "nbtlib is required; install it into tools/eval/pydeps or set "
            "MCMCP_PYDEPS"
        ) from failure
    return nbtlib


def load_region_module():
    path = Path(__file__).with_name("Inspect-McmcpRegion.py")
    spec = importlib.util.spec_from_file_location("mcmcp_region_inspector", path)
    if spec is None or spec.loader is None:
        raise SystemExit(f"cannot load region inspector: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


NBTLIB = load_nbtlib()
REGION = load_region_module()

MIN_X, MAX_X = 196, 201
MIN_Y, MAX_Y = 199, 202
MIN_Z, MAX_Z = 197, 201
WATER_SOURCE = (197, 201, 200)
WATER_DROP = (198, 200, 200)
WATER_CHANNEL = (198, 201, 200)
GENERATION_CELL = (199, 201, 200)
LAVA_SOURCE = (200, 201, 200)
PLAYER_POSITION = (199.5, 201.0, 199.5)
DYNAMIC_WATER_CELLS = {WATER_DROP, WATER_CHANNEL}


def state_index(world: Path) -> dict[tuple[int, int, int], dict]:
    records = REGION.inspect_region(
        world, (MIN_X, MAX_X, MIN_Y, MAX_Y, MIN_Z, MAX_Z)
    )
    return {(record["x"], record["y"], record["z"]): record for record in records}


def expected_static_states() -> dict[tuple[int, int, int], tuple[str, dict[str, str]]]:
    expected: dict[tuple[int, int, int], tuple[str, dict[str, str]]] = {}
    for x in range(MIN_X, MAX_X + 1):
        for y in range(MIN_Y, MAX_Y + 1):
            for z in range(MIN_Z, MAX_Z + 1):
                expected[(x, y, z)] = ("minecraft:air", {})
    for x in range(196, 202):
        for z in range(197, 202):
            expected[(x, 199, z)] = ("minecraft:bedrock", {})
            expected[(x, 200, z)] = ("minecraft:smooth_stone", {})
    for x in range(197, 201):
        if x != GENERATION_CELL[0]:
            expected[(x, 201, 199)] = ("minecraft:glass", {})
        expected[(x, 201, 201)] = ("minecraft:glass", {})
    expected[(196, 201, 200)] = ("minecraft:glass", {})
    expected[(201, 201, 200)] = ("minecraft:glass", {})
    expected[GENERATION_CELL] = ("minecraft:cobblestone", {})
    # Fluid source levels are checked separately because their legacy block states carry level.
    expected.pop(WATER_SOURCE)
    expected.pop(LAVA_SOURCE)
    for dynamic in DYNAMIC_WATER_CELLS:
        expected.pop(dynamic)
    return expected


def normalize_properties(value) -> dict[str, str]:
    return {str(key): str(item) for key, item in (value or {}).items()}


def check_static_cells(
    actual: dict[tuple[int, int, int], dict]
) -> list[dict[str, object]]:
    mismatches: list[dict[str, object]] = []
    for position, (expected_block, expected_properties) in expected_static_states().items():
        record = actual.get(position)
        actual_block = None if record is None else record["block"]
        actual_properties = {} if record is None else normalize_properties(record["properties"])
        if actual_block != expected_block or actual_properties != expected_properties:
            mismatches.append(
                {
                    "position": list(position),
                    "expected": {
                        "block": expected_block,
                        "properties": expected_properties,
                    },
                    "actual": {
                        "block": actual_block,
                        "properties": actual_properties,
                    },
                }
            )
    return mismatches


def fluid_source(actual: dict[tuple[int, int, int], dict], position, block: str) -> bool:
    record = actual.get(position)
    if record is None or record["block"] != block:
        return False
    properties = normalize_properties(record["properties"])
    return properties.get("level") == "0"


def player_record(world: Path):
    player_files = sorted(
        [
            *list((world / "playerdata").glob("*.dat")),
            *list((world / "players" / "data").glob("*.dat")),
        ],
        key=lambda candidate: candidate.stat().st_mtime_ns,
        reverse=True,
    )
    if player_files:
        source = player_files[0]
        return source, NBTLIB.load(source)
    source = world / "level.dat"
    root = NBTLIB.load(source)
    return source, root["Data"]["Player"]


def item_id(entry) -> str:
    return str(entry.get("id", entry.get("Id", "")))


def item_count(entry) -> int:
    return int(entry.get("count", entry.get("Count", 0)))


def item_damage(entry) -> int:
    components = entry.get("components", entry.get("Components", {})) or {}
    if "minecraft:damage" in components:
        return int(components["minecraft:damage"])
    tag = entry.get("tag", entry.get("Tag", {})) or {}
    return int(tag.get("Damage", entry.get("Damage", 0)))


def item_enchanted(entry) -> bool:
    components = entry.get("components", entry.get("Components", {})) or {}
    enchantments = components.get("minecraft:enchantments")
    if enchantments is not None:
        levels = enchantments.get("levels", enchantments.get("Levels", enchantments))
        if len(levels or {}) > 0:
            return True
    tag = entry.get("tag", entry.get("Tag", {})) or {}
    return bool(tag.get("Enchantments", tag.get("ench", [])))


def inspect_player(world: Path) -> dict[str, object]:
    source, player = player_record(world)
    inventory = player.get("Inventory", player.get("inventory", [])) or []
    cobblestone = sum(
        item_count(entry) for entry in inventory if item_id(entry) == "minecraft:cobblestone"
    )
    pickaxes = [entry for entry in inventory if item_id(entry) == "minecraft:iron_pickaxe"]
    position = [float(value) for value in player.get("Pos", player.get("pos", []))]
    health = float(player.get("Health", player.get("health", float("nan"))))
    return {
        "source": str(source),
        "position": position,
        "health": health,
        "cobblestone_count": cobblestone,
        "iron_pickaxe_count": sum(item_count(entry) for entry in pickaxes),
        "iron_pickaxe_damage": item_damage(pickaxes[0]) if len(pickaxes) == 1 else None,
        "iron_pickaxe_enchanted": (
            item_enchanted(pickaxes[0]) if len(pickaxes) == 1 else None
        ),
    }


def position_inside_workspace(position) -> bool:
    if position is None or len(position) != 3:
        return False
    x, y, z = (float(value) for value in position)
    return (
        MIN_X <= x < MAX_X + 1
        and MIN_Y <= y < MAX_Y + 1
        and MIN_Z <= z < MAX_Z + 1
    )


def loose_items(world: Path) -> list[dict[str, object]]:
    directory = world / "entities"
    if not directory.is_dir():
        return []
    found: list[dict[str, object]] = []
    for chunk_x in range(math.floor(MIN_X / 16), math.floor(MAX_X / 16) + 1):
        for chunk_z in range(math.floor(MIN_Z / 16), math.floor(MAX_Z / 16) + 1):
            region_x = math.floor(chunk_x / 32)
            region_z = math.floor(chunk_z / 32)
            path = directory / f"r.{region_x}.{region_z}.mca"
            if not path.exists():
                continue
            chunk = REGION.load_chunk(path, chunk_x, chunk_z)
            if chunk is None:
                continue
            for entity in chunk.get("Entities", chunk.get("entities", [])) or []:
                if str(entity.get("id", entity.get("Id", ""))) != "minecraft:item":
                    continue
                position = entity.get("Pos", entity.get("pos", []))
                if position_inside_workspace(position):
                    item = entity.get("Item", entity.get("item", {})) or {}
                    found.append(
                        {
                            "position": [float(value) for value in position],
                            "item": item_id(item),
                            "count": item_count(item),
                        }
                    )
    return found


def close_enough(actual: list[float], expected: tuple[float, float, float]) -> bool:
    return len(actual) == 3 and all(
        abs(actual[index] - expected[index]) <= 0.0001 for index in range(3)
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("world", type=Path)
    parser.add_argument("--output", type=Path)
    arguments = parser.parse_args()

    blocks = state_index(arguments.world)
    static_mismatches = check_static_cells(blocks)
    player = inspect_player(arguments.world)
    items = loose_items(arguments.world)
    water_source_unchanged = fluid_source(blocks, WATER_SOURCE, "minecraft:water")
    lava_source_unchanged = fluid_source(blocks, LAVA_SOURCE, "minecraft:lava")
    player_passed = (
        close_enough(player["position"], PLAYER_POSITION)
        and math.isfinite(player["health"])
        and 0.0 < player["health"] <= 20.0
        and player["cobblestone_count"] == 8
        and player["iron_pickaxe_count"] == 1
        and player["iron_pickaxe_damage"] == 8
        and player["iron_pickaxe_enchanted"] is False
    )
    passed = (
        not static_mismatches
        and water_source_unchanged
        and lava_source_unchanged
        and player_passed
        and not items
    )
    result = {
        "schema_version": 1,
        "oracle": "offline-cobblestone-generator-world",
        "world_closed_required": True,
        "workspace": {
            "min": [MIN_X, MIN_Y, MIN_Z],
            "max": [MAX_X, MAX_Y, MAX_Z],
        },
        "generation_cell": {
            "position": list(GENERATION_CELL),
            "block": blocks.get(GENERATION_CELL, {}).get("block"),
            "expected": "minecraft:cobblestone",
        },
        "water_source_unchanged": water_source_unchanged,
        "lava_source_unchanged": lava_source_unchanged,
        "static_cell_mismatches": static_mismatches,
        "allowed_dynamic_water_cells": [list(value) for value in sorted(DYNAMIC_WATER_CELLS)],
        "player": player,
        "player_passed": player_passed,
        "loose_items": items,
        "loose_item_count": len(items),
        "passed": passed,
    }
    serialized = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if arguments.output:
        arguments.output.parent.mkdir(parents=True, exist_ok=True)
        arguments.output.write_text(serialized, encoding="utf-8", newline="\n")
    else:
        sys.stdout.write(serialized)
    if not passed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
