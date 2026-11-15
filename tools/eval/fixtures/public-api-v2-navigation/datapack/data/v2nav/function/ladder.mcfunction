function v2nav:base
fill 304 101 304 304 105 304 minecraft:stone
fill 304 101 305 304 105 305 minecraft:ladder[facing=south]
fill 305 101 303 310 104 307 minecraft:stone
setblock 305 105 305 minecraft:redstone_wire
setblock 306 105 305 minecraft:rail[shape=east_west]
setblock 307 105 305 minecraft:redstone_wire
setblock 308 105 305 minecraft:rail[shape=east_west]
setblock 309 105 305 minecraft:chest[facing=west]
item replace block 309 105 305 container.0 with minecraft:stone 3
tp @s 304.5 101 307.5 180 0
