fill -9 56 3 0 64 9 minecraft:air
fill -9 55 3 0 55 9 minecraft:smooth_stone
fill -7 60 5 -5 60 7 minecraft:smooth_stone
setblock -7 61 5 minecraft:snow_block
setblock -7 61 6 minecraft:black_wool
setblock -7 61 7 minecraft:chest
item replace block -7 61 7 container.0 with minecraft:snow_block 1
item replace block -7 61 7 container.1 with minecraft:black_wool 1
setblock -7 62 5 minecraft:torch
item replace block -7 61 7 container.2 with minecraft:torch 16
clear @s
item replace block -7 61 7 container.3 with minecraft:snow_block 64
item replace block -7 61 7 container.4 with minecraft:black_wool 64
kill @e[type=minecraft:item,x=-9,y=55,z=3,dx=9,dy=9,dz=6]
tp @s -4.5 61 6.5 90 60
gamemode survival @s
