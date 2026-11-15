function v2production:craft
clear @s
item replace entity @s hotbar.0 with minecraft:potion[minecraft:potion_contents={potion:"minecraft:water"}]
item replace entity @s hotbar.1 with minecraft:potion[minecraft:potion_contents={potion:"minecraft:water"}]
item replace entity @s hotbar.2 with minecraft:potion[minecraft:potion_contents={potion:"minecraft:water"}]
item replace entity @s hotbar.3 with minecraft:nether_wart 8
item replace entity @s hotbar.4 with minecraft:blaze_powder 8
tp @s 309.5 101 305.5 180 20
