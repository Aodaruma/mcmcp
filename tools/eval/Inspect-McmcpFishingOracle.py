#!/usr/bin/env python3
"""Inspect a closed world for one bounded Vanilla fishing-loot cycle."""

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

MIN_X, MAX_X = 193, 205
MIN_Y, MAX_Y = 199, 207
MIN_Z, MAX_Z = 194, 206
WATER_MIN = (194, 200, 195)
WATER_MAX = (204, 202, 205)
PLAYER_POSITION = (199.5, 203.0, 194.5)
PLAYER_HEALTH = 16.0
FISHING_BOBBER_ID = "minecraft:fishing_bobber"
FISHING_LOOT_IDS = frozenset(
    {
        "minecraft:bamboo",
        "minecraft:bone",
        "minecraft:book",
        "minecraft:bow",
        "minecraft:bowl",
        "minecraft:cod",
        "minecraft:enchanted_book",
        "minecraft:fishing_rod",
        "minecraft:ink_sac",
        "minecraft:leather",
        "minecraft:leather_boots",
        "minecraft:lily_pad",
        "minecraft:name_tag",
        "minecraft:nautilus_shell",
        "minecraft:potion",
        "minecraft:pufferfish",
        "minecraft:rotten_flesh",
        "minecraft:saddle",
        "minecraft:salmon",
        "minecraft:stick",
        "minecraft:string",
        "minecraft:tripwire_hook",
        "minecraft:tropical_fish",
    }
)


def normalize_properties(value) -> dict[str, str]:
    return {str(key): str(item) for key, item in (value or {}).items()}


def expected_states() -> dict[tuple[int, int, int], tuple[str, dict[str, str]]]:
    expected = {
        (x, y, z): ("minecraft:air", {})
        for x in range(MIN_X, MAX_X + 1)
        for y in range(MIN_Y, MAX_Y + 1)
        for z in range(MIN_Z, MAX_Z + 1)
    }
    for x in range(MIN_X, MAX_X + 1):
        for z in range(MIN_Z, MAX_Z + 1):
            expected[(x, 199, z)] = ("minecraft:sea_lantern", {})
            boundary = x in (MIN_X, MAX_X) or z in (MIN_Z, MAX_Z)
            if boundary:
                for y in range(200, 203):
                    expected[(x, y, z)] = ("minecraft:smooth_stone", {})
    for x in range(WATER_MIN[0], WATER_MAX[0] + 1):
        for y in range(WATER_MIN[1], WATER_MAX[1] + 1):
            for z in range(WATER_MIN[2], WATER_MAX[2] + 1):
                expected[(x, y, z)] = ("minecraft:water", {"level": "0"})
    return expected


def state_index(world: Path) -> dict[tuple[int, int, int], dict]:
    records = REGION.inspect_region(
        world, (MIN_X, MAX_X, MIN_Y, MAX_Y, MIN_Z, MAX_Z)
    )
    return {(record["x"], record["y"], record["z"]): record for record in records}


def check_static_cells(actual: dict[tuple[int, int, int], dict]) -> list[dict]:
    mismatches = []
    for position, (expected_block, expected_properties) in expected_states().items():
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


def item_slot(entry) -> int:
    return int(entry.get("Slot", entry.get("slot", -999)))


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


def inventory_summary(inventory) -> dict[str, object]:
    entries = list(inventory or [])
    initial_rods = [
        entry
        for entry in entries
        if item_slot(entry) == 0 and item_id(entry) == "minecraft:fishing_rod"
    ]
    primary = initial_rods[0] if len(initial_rods) == 1 else None
    loot_entries = [
        entry
        for entry in entries
        if not (item_slot(entry) == 0 and item_id(entry) == "minecraft:fishing_rod")
        and item_id(entry) in FISHING_LOOT_IDS
    ]
    unexpected = [
        {"slot": item_slot(entry), "item": item_id(entry), "count": item_count(entry)}
        for entry in entries
        if not (item_slot(entry) == 0 and item_id(entry) == "minecraft:fishing_rod")
        and item_id(entry) not in FISHING_LOOT_IDS
    ]
    return {
        "primary_rod_count": len(initial_rods),
        "primary_rod_damage": item_damage(primary) if primary is not None else None,
        "primary_rod_enchanted": item_enchanted(primary) if primary is not None else None,
        "loot_item_count": sum(item_count(entry) for entry in loot_entries),
        "loot": [
            {"slot": item_slot(entry), "item": item_id(entry), "count": item_count(entry)}
            for entry in loot_entries
        ],
        "unexpected_inventory": unexpected,
    }


def inspect_player(world: Path) -> dict[str, object]:
    source, player = player_record(world)
    inventory = player.get("Inventory", player.get("inventory", [])) or []
    result = inventory_summary(inventory)
    result.update(
        {
            "source": str(source),
            "position": [float(value) for value in player.get("Pos", player.get("pos", []))],
            "health": float(player.get("Health", player.get("health", float("nan")))),
        }
    )
    return result


def close_enough(actual: list[float], expected: tuple[float, float, float]) -> bool:
    return len(actual) == 3 and all(
        abs(actual[index] - expected[index]) <= 0.0001 for index in range(3)
    )


def entity_records(world: Path) -> list[dict[str, object]]:
    # Include a one-chunk halo so a retrieved item launched toward the shore cannot escape proof.
    min_x, max_x = MIN_X - 16, MAX_X + 16
    min_z, max_z = MIN_Z - 16, MAX_Z + 16
    found = []
    directory = world / "entities"
    if not directory.is_dir():
        return found
    for chunk_x in range(math.floor(min_x / 16), math.floor(max_x / 16) + 1):
        for chunk_z in range(math.floor(min_z / 16), math.floor(max_z / 16) + 1):
            region_x = math.floor(chunk_x / 32)
            region_z = math.floor(chunk_z / 32)
            path = directory / f"r.{region_x}.{region_z}.mca"
            if not path.exists():
                continue
            chunk = REGION.load_chunk(path, chunk_x, chunk_z)
            if chunk is None:
                continue
            for entity in chunk.get("Entities", chunk.get("entities", [])) or []:
                position = entity.get("Pos", entity.get("pos", []))
                if len(position) != 3:
                    continue
                x, _, z = (float(value) for value in position)
                if not (min_x <= x < max_x + 1 and min_z <= z < max_z + 1):
                    continue
                identifier = str(entity.get("id", entity.get("Id", "")))
                if identifier in (FISHING_BOBBER_ID, "minecraft:item"):
                    found.append({"entity": identifier, "position": [float(v) for v in position]})
    return found


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("world", type=Path)
    parser.add_argument("--output", type=Path)
    arguments = parser.parse_args()

    blocks = state_index(arguments.world)
    static_mismatches = check_static_cells(blocks)
    player = inspect_player(arguments.world)
    entities = entity_records(arguments.world)
    bobbers = [entry for entry in entities if entry["entity"] == FISHING_BOBBER_ID]
    loose_items = [entry for entry in entities if entry["entity"] == "minecraft:item"]
    player_passed = (
        close_enough(player["position"], PLAYER_POSITION)
        and abs(player["health"] - PLAYER_HEALTH) <= 0.0001
        and player["primary_rod_count"] == 1
        and player["primary_rod_damage"] == 1
        and player["primary_rod_enchanted"] is False
        and player["loot_item_count"] >= 1
        and not player["unexpected_inventory"]
    )
    passed = not static_mismatches and player_passed and not bobbers and not loose_items
    result = {
        "schema_version": 1,
        "oracle": "offline-vanilla-fishing-world",
        "world_closed_required": True,
        "workspace": {"min": [MIN_X, MIN_Y, MIN_Z], "max": [MAX_X, MAX_Y, MAX_Z]},
        "static_cell_mismatches": static_mismatches,
        "pool_unchanged": not static_mismatches,
        "player": player,
        "player_passed": player_passed,
        "owned_bobbers": bobbers,
        "owned_bobber_count": len(bobbers),
        "loose_items": loose_items,
        "loose_item_count": len(loose_items),
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
