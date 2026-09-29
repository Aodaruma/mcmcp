# This scheduled function has no player executor; select the prepared player explicitly.
# Stop within 1200 server ticks even when the agent never starts. No load/tick tag restarts it.
scoreboard players add #age mcv2long 1
execute if block 200 201 201 minecraft:black_concrete_powder run function v2_long:consume
execute if score #seen mcv2long matches 1 run scoreboard players add #elapsed mcv2long 1
execute if score #mode mcv2long matches 1 if score #elapsed mcv2long matches 80 as @a[tag=mcmcp_v2_long,limit=1] run item replace entity @s hotbar.5 with minecraft:black_concrete_powder 32
execute if score #mode mcv2long matches 1 if score #elapsed mcv2long matches 80 run scoreboard players set #refilled mcv2long 1
execute if score #mode mcv2long matches 2 if score #elapsed mcv2long matches 160 as @a[tag=mcmcp_v2_long,limit=1] run item replace entity @s hotbar.5 with minecraft:black_concrete_powder 32
execute if score #mode mcv2long matches 2 if score #elapsed mcv2long matches 160 run scoreboard players set #refilled mcv2long 1
execute if score #mode mcv2long matches 3 if score #elapsed mcv2long matches 80 as @a[tag=mcmcp_v2_long,limit=1] run item replace entity @s hotbar.5 with minecraft:dirt 32
execute if score #mode mcv2long matches 3 if score #elapsed mcv2long matches 80 run scoreboard players set #refilled mcv2long 1
execute if score #age mcv2long matches ..1199 run schedule function v2_long:tick 1t replace
execute if score #age mcv2long matches 1200.. run function v2_long:stop
