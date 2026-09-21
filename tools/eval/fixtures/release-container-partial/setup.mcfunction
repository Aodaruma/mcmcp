fill -9 61 3 -1 64 10 minecraft:air
fill -9 60 3 -1 60 10 minecraft:smooth_stone
setblock -4 61 6 minecraft:chest
setblock -7 61 6 minecraft:waxed_copper_chest[facing=north,type=left]
setblock -6 61 6 minecraft:waxed_copper_chest[facing=north,type=right]
setblock -3 61 7 minecraft:barrel
item replace block -7 61 6 container.0 with minecraft:black_wool 47
item replace block -6 61 6 container.0 with minecraft:black_wool 64
item replace block -4 61 6 container.0 with minecraft:snow_block 64
item replace block -4 61 6 container.1 with minecraft:snow_block 64
item replace block -4 61 6 container.2 with minecraft:snow_block 64
item replace block -4 61 6 container.3 with minecraft:snow_block 64
item replace block -4 61 6 container.4 with minecraft:snow_block 64
item replace block -4 61 6 container.5 with minecraft:snow_block 64
item replace block -4 61 6 container.6 with minecraft:snow_block 64
item replace block -4 61 6 container.7 with minecraft:snow_block 64
item replace block -4 61 6 container.8 with minecraft:snow_block 64
item replace block -4 61 6 container.9 with minecraft:snow_block 64
item replace block -4 61 6 container.10 with minecraft:snow_block 64
item replace block -4 61 6 container.11 with minecraft:snow_block 64
item replace block -4 61 6 container.12 with minecraft:snow_block 64
item replace block -4 61 6 container.13 with minecraft:snow_block 64
clear @s
tp @s -5.5 61 8.5 180 30
gamemode survival @s
