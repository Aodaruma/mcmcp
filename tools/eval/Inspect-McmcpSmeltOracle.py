#!/usr/bin/env python3
"""Inspect a closed world for the bounded Phase 5 smelting result."""

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
SCOPED_ITEMS = ("minecraft:raw_iron", "minecraft:coal", "minecraft:iron_ingot")


def item_counts(items) -> dict[str, int]:
    counts: dict[str, int] = {}
    for entry in items or []:
        item_id = str(entry.get("id", entry.get("Id", "")))
        count = int(entry.get("count", entry.get("Count", 0)))
        if item_id and count > 0:
            counts[item_id] = counts.get(item_id, 0) + count
    return counts


def player_inventory(world: Path) -> tuple[str, dict[str, int]]:
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
    return str(source), item_counts(inventory)


def resolve_overworld_dimension(root_or_dimension: Path) -> Path:
    candidates = []
    if (root_or_dimension / "region").is_dir():
        candidates.append(root_or_dimension)
    nested = root_or_dimension / "dimensions" / "minecraft" / "overworld"
    if (nested / "region").is_dir():
        candidates.append(nested)
    if len(candidates) != 1:
        raise SystemExit(
            "expected exactly one supported overworld region directory under "
            f"{root_or_dimension}; found {len(candidates)}"
        )
    return candidates[0]


def furnace_entity(dimension: Path, x: int, y: int, z: int):
    chunk_x = math.floor(x / 16)
    chunk_z = math.floor(z / 16)
    region_x = math.floor(chunk_x / 32)
    region_z = math.floor(chunk_z / 32)
    region_path = dimension / "region" / f"r.{region_x}.{region_z}.mca"
    chunk = REGION.load_chunk(region_path, chunk_x, chunk_z)
    if chunk is None:
        raise SystemExit(f"chunk is absent for furnace: {chunk_x},{chunk_z}")
    entities = chunk.get("block_entities", chunk.get("TileEntities", []))
    for entity in entities:
        if (int(entity.get("x", 0)), int(entity.get("y", 0)), int(entity.get("z", 0))) == (
            x,
            y,
            z,
        ):
            return entity
    raise SystemExit(f"furnace block entity is absent at {x},{y},{z}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("world", type=Path)
    parser.add_argument("--x", type=int, default=196)
    parser.add_argument("--y", type=int, default=200)
    parser.add_argument("--z", type=int, default=194)
    parser.add_argument("--output", type=Path)
    arguments = parser.parse_args()

    player_source, inventory = player_inventory(arguments.world)
    dimension = resolve_overworld_dimension(arguments.world)
    furnace = furnace_entity(dimension, arguments.x, arguments.y, arguments.z)
    station_counts = item_counts(furnace.get("Items", furnace.get("items", [])))
    scoped_inventory = {item: inventory.get(item, 0) for item in SCOPED_ITEMS}
    result = {
        "schema_version": 1,
        "oracle": "offline-smelt-world",
        "world_closed_required": True,
        "player_source": player_source,
        "player_inventory": scoped_inventory,
        "furnace": {
            "position": {"x": arguments.x, "y": arguments.y, "z": arguments.z},
            "id": str(furnace.get("id", "")),
            "items": station_counts,
            "burn_time": int(furnace.get("BurnTime", furnace.get("lit_time_remaining", 0))),
            "cook_time": int(furnace.get("CookTime", furnace.get("cooking_time_spent", 0))),
        },
        "passed": scoped_inventory
        == {"minecraft:raw_iron": 0, "minecraft:coal": 0, "minecraft:iron_ingot": 1}
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
