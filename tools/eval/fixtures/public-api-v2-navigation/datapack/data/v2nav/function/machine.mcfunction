function v2nav:base
fill 302 101 319 309 103 319 minecraft:stone
fill 302 101 321 309 103 321 minecraft:stone
fill 302 103 320 309 103 320 minecraft:stone
fill 309 101 320 309 102 320 minecraft:stone
setblock 308 101 320 minecraft:chest[facing=west]
item replace block 308 101 320 container.0 with minecraft:stone 3
setblock 308 102 320 minecraft:lever[face=wall,facing=west,powered=false]
tp @s 304.5 101 320.5 -90 0
