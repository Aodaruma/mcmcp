function v2_long:prepare
scoreboard players set #mode mcv2long 3
item replace entity @s hotbar.5 with minecraft:black_concrete_powder 1
schedule function v2_long:tick 1t replace
