# Apply only in the backed-up, isolated validation world.
fill 198 201 198 210 206 206 minecraft:air
fill 198 200 198 210 200 206 minecraft:smooth_stone
fill 202 201 200 206 202 200 minecraft:dirt
setblock 199 201 200 minecraft:chest
item replace block 199 201 200 container.0 with minecraft:snow_block 32
setblock 200 201 198 minecraft:oak_fence_gate[facing=south,open=false]
clear @s
tp @s 200.5 201 200.5 0 0
gamemode survival @s
