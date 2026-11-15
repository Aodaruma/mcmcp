schedule clear v2nav:power_on
schedule clear v2nav:power_off
schedule clear v2nav:watch_route
schedule clear v2nav:flight_survival
fill 298 101 298 326 108 324 minecraft:air
fill 298 100 298 326 100 324 minecraft:smooth_stone
clear @s
gamemode survival @s
effect give @s minecraft:instant_health 1 5 true
time set noon
weather clear
