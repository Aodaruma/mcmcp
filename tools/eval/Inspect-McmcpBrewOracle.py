#!/usr/bin/env python3
"""Inspect a closed world for the bounded Phase 5 brewing result."""

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
    try:
        import nbtlib  # type: ignore
    except ImportError as failure:
        raise SystemExit("nbtlib is required; set MCMCP_PYDEPS") from failure
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


def entry_item_id(entry) -> str:
    return str(entry.get("id", entry.get("Id", "")))


def entry_count(entry) -> int:
    return int(entry.get("count", entry.get("Count", 0)))


def entry_potion_id(entry) -> str:
    components = entry.get("components", entry.get("Components", {}))
    potion_contents = components.get(
        "minecraft:potion_contents", components.get("potion_contents", {})
    )
    return str(potion_contents.get("potion", potion_contents.get("Potion", "")))


def item_counts(items) -> dict[str, int]:
    counts: dict[str, int] = {}
    for entry in items or []:
        item_id = entry_item_id(entry)
        count = entry_count(entry)
        if item_id and count > 0:
            counts[item_id] = counts.get(item_id, 0) + count
    return counts


def potion_counts(items) -> dict[str, int]:
    counts: dict[str, int] = {}
    for entry in items or []:
        item_id = entry_item_id(entry)
        count = entry_count(entry)
        if item_id != "minecraft:potion" or count <= 0:
            continue
        potion_id = entry_potion_id(entry)
        key = f"{item_id}|{potion_id}"
        counts[key] = counts.get(key, 0) + count
    return counts


def player_inventory(world: Path):
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
        player = NBTLIB.load(source)
    else:
        source = world / "level.dat"
        root = NBTLIB.load(source)
        player = root["Data"]["Player"]
    inventory = player.get("Inventory", player.get("inventory", []))
    return str(source), item_counts(inventory), potion_counts(inventory)


def block_entity(world: Path, x: int, y: int, z: int):
    dimension = world
    if not (dimension / "region").is_dir():
        dimension = world / "dimensions" / "minecraft" / "overworld"
    chunk_x = math.floor(x / 16)
    chunk_z = math.floor(z / 16)
    region_x = math.floor(chunk_x / 32)
    region_z = math.floor(chunk_z / 32)
    region_path = dimension / "region" / f"r.{region_x}.{region_z}.mca"
    chunk = REGION.load_chunk(region_path, chunk_x, chunk_z)
    if chunk is None:
        raise SystemExit(f"chunk is absent for brewing stand: {chunk_x},{chunk_z}")
    entities = chunk.get("block_entities", chunk.get("TileEntities", []))
    for entity in entities:
        if (int(entity.get("x", 0)), int(entity.get("y", 0)), int(entity.get("z", 0))) == (
            x,
            y,
            z,
        ):
            return entity
    raise SystemExit(f"brewing stand block entity is absent at {x},{y},{z}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("world", type=Path)
    parser.add_argument("--x", type=int, default=197)
    parser.add_argument("--y", type=int, default=200)
    parser.add_argument("--z", type=int, default=194)
    parser.add_argument("--output", type=Path)
    arguments = parser.parse_args()

    player_source, inventory, potions = player_inventory(arguments.world)
    station = block_entity(arguments.world, arguments.x, arguments.y, arguments.z)
    station_items = station.get("Items", station.get("items", []))
    station_counts = item_counts(station_items)
    station_potions = potion_counts(station_items)
    scoped_inventory = {
        item: inventory.get(item, 0)
        for item in ("minecraft:potion", "minecraft:nether_wart", "minecraft:blaze_powder")
    }
    scoped_potions = {
        key: potions.get(key, 0)
        for key in (
            "minecraft:potion|minecraft:water",
            "minecraft:potion|minecraft:awkward",
        )
    }
    result = {
        "schema_version": 1,
        "oracle": "offline-brew-world",
        "world_closed_required": True,
        "player_source": player_source,
        "player_inventory": scoped_inventory,
        "player_standard_potions": scoped_potions,
        "brewing_stand": {
            "position": {"x": arguments.x, "y": arguments.y, "z": arguments.z},
            "id": str(station.get("id", "")),
            "items": station_counts,
            "standard_potions": station_potions,
            "brew_time": int(
                station.get("BrewTime", station.get("brewing_time", station.get("brew_time", 0)))
            ),
            "fuel": int(station.get("Fuel", station.get("fuel", 0))),
        },
        "passed": scoped_inventory
        == {
            "minecraft:potion": 3,
            "minecraft:nether_wart": 0,
            "minecraft:blaze_powder": 0,
        }
        and scoped_potions
        == {
            "minecraft:potion|minecraft:water": 0,
            "minecraft:potion|minecraft:awkward": 3,
        }
        and not station_counts,
    }
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
