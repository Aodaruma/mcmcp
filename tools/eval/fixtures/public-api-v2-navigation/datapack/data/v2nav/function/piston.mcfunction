function v2nav:machine
setblock 306 101 321 minecraft:stone
setblock 306 101 322 minecraft:sticky_piston[facing=north,extended=false]
setblock 306 102 321 minecraft:stone
setblock 306 102 322 minecraft:sticky_piston[facing=north,extended=false]
schedule function v2nav:power_on 40t replace
