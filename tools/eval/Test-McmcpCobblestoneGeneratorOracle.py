#!/usr/bin/env python3
"""Pure contract checks for Inspect-McmcpCobblestoneGeneratorOracle.py."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import sys
import types


# The pure contract functions under test do not parse NBT. Supply a minimal import sentinel so
# this test remains dependency-free while the executable oracle still fails closed without nbtlib.
sys.modules.setdefault("nbtlib", types.ModuleType("nbtlib"))
path = Path(__file__).with_name("Inspect-McmcpCobblestoneGeneratorOracle.py")
spec = importlib.util.spec_from_file_location("mcmcp_cobblestone_oracle", path)
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


def valid_block_index():
    result = {
        position: record(position, block, properties)
        for position, (block, properties) in oracle.expected_static_states().items()
    }
    result[oracle.WATER_SOURCE] = record(
        oracle.WATER_SOURCE, "minecraft:water", {"level": "0"}
    )
    result[oracle.LAVA_SOURCE] = record(
        oracle.LAVA_SOURCE, "minecraft:lava", {"level": "0"}
    )
    result[oracle.WATER_DROP] = record(
        oracle.WATER_DROP, "minecraft:water", {"level": "8"}
    )
    result[oracle.WATER_CHANNEL] = record(
        oracle.WATER_CHANNEL, "minecraft:water", {"level": "8"}
    )
    return result


blocks = valid_block_index()
assert oracle.check_static_cells(blocks) == []
assert oracle.fluid_source(blocks, oracle.WATER_SOURCE, "minecraft:water")
assert oracle.fluid_source(blocks, oracle.LAVA_SOURCE, "minecraft:lava")

mutated = dict(blocks)
mutated[(196, 199, 197)] = record((196, 199, 197), "minecraft:air")
assert len(oracle.check_static_cells(mutated)) == 1

flowing_source = dict(blocks)
flowing_source[oracle.WATER_SOURCE] = record(
    oracle.WATER_SOURCE, "minecraft:water", {"level": "1"}
)
assert not oracle.fluid_source(flowing_source, oracle.WATER_SOURCE, "minecraft:water")

pristine_pickaxe = {
    "id": "minecraft:iron_pickaxe",
    "count": 1,
    "components": {"minecraft:damage": 8},
}
assert oracle.item_damage(pristine_pickaxe) == 8
assert not oracle.item_enchanted(pristine_pickaxe)
enchanted_pickaxe = dict(pristine_pickaxe)
enchanted_pickaxe["components"] = {
    "minecraft:damage": 8,
    "minecraft:enchantments": {"levels": {"minecraft:efficiency": 1}},
}
assert oracle.item_enchanted(enchanted_pickaxe)
assert oracle.close_enough([199.5, 201.0, 198.5], oracle.PLAYER_POSITION)
assert not oracle.close_enough([199.6, 201.0, 198.5], oracle.PLAYER_POSITION)

print("MCMCP cobblestone generator offline oracle tests passed.")
