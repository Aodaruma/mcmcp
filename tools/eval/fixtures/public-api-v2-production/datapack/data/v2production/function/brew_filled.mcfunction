function v2production:brew
clear @s
item replace entity @s hotbar.0 with minecraft:potion[minecraft:potion_contents={potion:"minecraft:awkward"}] 1
item replace entity @s hotbar.1 with minecraft:potion[minecraft:potion_contents={potion:"minecraft:awkward"}] 1
item replace entity @s hotbar.2 with minecraft:potion[minecraft:potion_contents={potion:"minecraft:awkward"}] 1
item replace entity @s hotbar.3 with minecraft:blaze_powder 1
data merge block 309 101 303 {Fuel:20b}
