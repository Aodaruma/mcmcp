gamemode creative
fill 300 100 300 320 100 320 minecraft:stone
fill 300 101 300 320 108 320 minecraft:air
setblock 305 101 303 minecraft:crafting_table
setblock 307 101 303 minecraft:furnace[facing=south]
setblock 309 101 303 minecraft:brewing_stand
clear @s
item replace entity @s hotbar.0 with minecraft:oak_log 16
item replace entity @s hotbar.1 with minecraft:cobblestone 24
item replace entity @s hotbar.2 with minecraft:coal 8
item replace entity @s hotbar.3 with minecraft:raw_iron 8
recipe give @s *
tp @s 305.5 101 305.5 180 0
gamemode survival
effect give @s minecraft:saturation 120 0 true
weather clear
time set noon
