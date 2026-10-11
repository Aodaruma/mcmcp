# Separate preparation through the isolated game's command UI, before MCP ON.
# This file is NOT accepted by fixture-admin's restricted command loader.
item replace entity @s hotbar.0 with minecraft:bucket 1
item replace entity @s hotbar.1 with minecraft:netherite_pickaxe 1
item replace entity @s inventory.0 with minecraft:black_wool 16
item replace entity @s inventory.1 with minecraft:snow_block 16
summon minecraft:cow 200.5 201 202.0 {NoAI:1b,PersistenceRequired:1b,Tags:["mcmcp_v2_smoke"]}
