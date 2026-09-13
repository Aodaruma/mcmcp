fill -10 56 0 0 62 10 minecraft:air
fill -10 55 0 0 55 10 minecraft:smooth_stone
setblock -8 56 3 minecraft:snow_block
setblock -8 57 3 minecraft:torch
setblock -8 56 4 minecraft:black_wool
setblock -8 56 5 minecraft:red_concrete
setblock -7 56 7 minecraft:chest
item replace block -7 56 7 container.0 with minecraft:snow_block 64
item replace block -7 56 7 container.1 with minecraft:black_wool 64
item replace block -7 56 7 container.2 with minecraft:torch 16
item replace block -7 56 7 container.3 with minecraft:red_concrete 64
clear @s
tp @s -5.5 56 6.5 180 30
gamemode survival @s
