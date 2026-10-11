# Isolated UI preparation only; not an action or an admin-loader fixture.
fill 196 200 196 214 200 212 minecraft:smooth_stone
fill 196 201 196 214 206 212 minecraft:air
kill @e[type=minecraft:cow,tag=mcmcp_v2_smoke]
kill @e[type=minecraft:item,distance=..32]
clear @s
item replace entity @s hotbar.0 with minecraft:bucket
item replace entity @s hotbar.1 with minecraft:netherite_pickaxe
item replace entity @s inventory.0 with minecraft:black_wool 16
item replace entity @s inventory.1 with minecraft:oak_door 3
item replace entity @s inventory.2 with minecraft:red_bed 1
item replace entity @s inventory.3 with minecraft:sunflower 3
item replace entity @s inventory.4 with minecraft:smooth_stone 32
item replace entity @s inventory.5 with minecraft:shield
setblock 199 201 200 minecraft:chest
item replace block 199 201 200 container.0 with minecraft:snow_block 16
setblock 204 200 202 minecraft:grass_block
summon minecraft:cow 200.5 201 207.0 {NoAI:1b,PersistenceRequired:1b,Tags:["mcmcp_v2_smoke"]}
tp @s 200.5 201 200.5 0 10
gamemode survival @s
time set noon
weather clear
