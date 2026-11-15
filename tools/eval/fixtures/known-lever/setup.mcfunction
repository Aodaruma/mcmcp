fill -9 61 3 -1 64 10 minecraft:air
fill -9 60 3 -1 60 10 minecraft:smooth_stone
setblock -6 61 6 minecraft:lever[face=floor,facing=north,powered=false]
setblock -4 62 5 minecraft:smooth_stone
setblock -4 62 6 minecraft:lever[face=wall,facing=south,powered=false]
setblock -8 63 6 minecraft:smooth_stone
setblock -8 62 6 minecraft:lever[face=ceiling,facing=north,powered=false]
clear @s
tp @s -5.5 61 8.5 180 30
gamemode survival @s
