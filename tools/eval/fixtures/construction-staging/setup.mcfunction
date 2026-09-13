fill -705 76 60 -696 83 66 minecraft:air
fill -702 79 61 -697 79 63 minecraft:snow_block
fill -704 79 64 -699 79 64 minecraft:snow_block
setblock -703 80 64 minecraft:torch
setblock -700 80 62 minecraft:chest
item replace block -700 80 62 container.0 with minecraft:snow_block 64
item replace block -700 80 62 container.1 with minecraft:snow_block 64
item replace block -700 80 62 container.2 with minecraft:snow_block 64
item replace block -700 80 62 container.3 with minecraft:snow_block 64
item replace block -700 80 62 container.4 with minecraft:snow_block 64
item replace block -700 80 62 container.5 with minecraft:snow_block 64
item replace block -700 80 62 container.6 with minecraft:snow_block 64
item replace block -700 80 62 container.7 with minecraft:snow_block 64
item replace block -700 80 62 container.8 with minecraft:snow_block 64
item replace block -700 80 62 container.9 with minecraft:torch 16
clear @s
kill @e[type=minecraft:item,x=-705,y=76,z=60,dx=9,dy=7,dz=6]
tp @s -698.52857 80 64.422935 90 45
gamemode survival @s
