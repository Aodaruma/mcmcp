#!/usr/bin/env python3
"""Pure contract checks for Inspect-McmcpFishingOracle.py."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import sys
import types


sys.modules.setdefault("nbtlib", types.ModuleType("nbtlib"))
path = Path(__file__).with_name("Inspect-McmcpFishingOracle.py")
spec = importlib.util.spec_from_file_location("mcmcp_fishing_oracle", path)
if spec is None or spec.loader is None:
    raise SystemExit(f"cannot load oracle: {path}")
oracle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(oracle)


def record(position, block, properties=None):
    return {
        "x": position[0],
        "y": position[1],
        "z": position[2],
        "block": block,
        "properties": properties or {},
    }


blocks = {
    position: record(position, block, properties)
    for position, (block, properties) in oracle.expected_states().items()
}
assert oracle.check_static_cells(blocks) == []
mutated = dict(blocks)
mutated[(199, 202, 200)] = record((199, 202, 200), "minecraft:air")
assert len(oracle.check_static_cells(mutated)) == 1
flowing = dict(blocks)
flowing[(199, 202, 200)] = record(
    (199, 202, 200), "minecraft:water", {"level": "1"}
)
assert len(oracle.check_static_cells(flowing)) == 1

primary = {
    "Slot": 0,
    "id": "minecraft:fishing_rod",
    "count": 1,
    "components": {"minecraft:damage": 1},
}
summary = oracle.inventory_summary(
    [primary, {"Slot": 1, "id": "minecraft:cod", "count": 1}]
)
assert summary["primary_rod_count"] == 1
assert summary["primary_rod_damage"] == 1
assert summary["primary_rod_enchanted"] is False
assert summary["loot_item_count"] == 1
assert summary["unexpected_inventory"] == []
enchanted_book = oracle.inventory_summary(
    [primary, {"Slot": 1, "id": "minecraft:enchanted_book", "count": 1}]
)
assert enchanted_book["loot_item_count"] == 1
assert enchanted_book["unexpected_inventory"] == []

rod_loot = oracle.inventory_summary(
    [
        primary,
        {
            "Slot": 1,
            "id": "minecraft:fishing_rod",
            "count": 1,
            "components": {"minecraft:damage": 7},
        },
    ]
)
assert rod_loot["loot_item_count"] == 1
unexpected = oracle.inventory_summary(
    [primary, {"Slot": 1, "id": "minecraft:diamond", "count": 1}]
)
assert unexpected["loot_item_count"] == 0
assert unexpected["unexpected_inventory"] == [
    {"slot": 1, "item": "minecraft:diamond", "count": 1}
]
assert oracle.close_enough([199.5, 203.0, 194.5], oracle.PLAYER_POSITION)
assert not oracle.close_enough([199.5, 203.0, 194.6], oracle.PLAYER_POSITION)

print("MCMCP fishing offline oracle tests passed.")
