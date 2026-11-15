# Isolated world only, before MCP ON. Select hotbar slot 5 (keyboard 6) manually.
schedule clear v2_long:tick
scoreboard objectives add mcv2long dummy
scoreboard players set #age mcv2long 0
scoreboard players set #seen mcv2long 0
scoreboard players set #elapsed mcv2long 0
scoreboard players set #placed mcv2long 0
scoreboard players set #refilled mcv2long 0
scoreboard players set #mode mcv2long 0
tag @a[tag=mcmcp_v2_long] remove mcmcp_v2_long
tag @s add mcmcp_v2_long
fill 198 200 198 204 200 206 minecraft:smooth_stone
fill 198 201 198 204 206 206 minecraft:air
clear @s
tp @s 200.5 201 200.5 0 60
gamemode survival @s
time set noon
weather clear
