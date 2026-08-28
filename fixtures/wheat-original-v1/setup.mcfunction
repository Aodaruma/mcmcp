# Explicit cold-start baseline for the user's existing oak-fenced wheat field.
# This does not construct another arena and does not claim to restore a pristine save.
gamemode survival @s
clear @s

# Reset the six tillable rows. Do not mutate the central water/trapdoor support row.
fill -23 55 -20 -12 55 -20 minecraft:dirt replace
fill -23 55 -19 -12 55 -19 minecraft:dirt replace
fill -23 55 -18 -12 55 -18 minecraft:dirt replace
fill -23 55 -16 -12 55 -16 minecraft:dirt replace
fill -23 55 -15 -12 55 -15 minecraft:dirt replace
fill -23 55 -14 -12 55 -14 minecraft:dirt replace
fill -23 56 -20 -12 56 -20 minecraft:air replace
fill -23 56 -19 -12 56 -19 minecraft:air replace
fill -23 56 -18 -12 56 -18 minecraft:air replace
fill -23 56 -17 -12 56 -17 minecraft:air replace
fill -23 56 -16 -12 56 -16 minecraft:air replace
fill -23 56 -15 -12 56 -15 minecraft:air replace
fill -23 56 -14 -12 56 -14 minecraft:air replace
setblock -11 56 -15 minecraft:oak_fence_gate[facing=west,in_wall=false,open=false,powered=false] replace

# Recreate a deterministic container baseline without replacing the existing chest.
item replace block -10 56 -14 container.0 with minecraft:air
item replace block -10 56 -14 container.1 with minecraft:air
item replace block -10 56 -14 container.2 with minecraft:air
item replace block -10 56 -14 container.3 with minecraft:air
item replace block -10 56 -14 container.4 with minecraft:air
item replace block -10 56 -14 container.5 with minecraft:air
item replace block -10 56 -14 container.6 with minecraft:air
item replace block -10 56 -14 container.7 with minecraft:air
item replace block -10 56 -14 container.8 with minecraft:air
item replace block -10 56 -14 container.9 with minecraft:air
item replace block -10 56 -14 container.10 with minecraft:air
item replace block -10 56 -14 container.11 with minecraft:air
item replace block -10 56 -14 container.12 with minecraft:air
item replace block -10 56 -14 container.13 with minecraft:air
item replace block -10 56 -14 container.14 with minecraft:air
item replace block -10 56 -14 container.15 with minecraft:air
item replace block -10 56 -14 container.16 with minecraft:air
item replace block -10 56 -14 container.17 with minecraft:air
item replace block -10 56 -14 container.18 with minecraft:air
item replace block -10 56 -14 container.19 with minecraft:air
item replace block -10 56 -14 container.20 with minecraft:air
item replace block -10 56 -14 container.21 with minecraft:air
item replace block -10 56 -14 container.22 with minecraft:air
item replace block -10 56 -14 container.23 with minecraft:air
item replace block -10 56 -14 container.24 with minecraft:air
item replace block -10 56 -14 container.25 with minecraft:air
item replace block -10 56 -14 container.26 with minecraft:air
item replace block -10 56 -14 container.0 with minecraft:netherite_hoe 1
item replace block -10 56 -14 container.1 with minecraft:wheat_seeds 64

# Purge setup drops only inside the declared field envelope, then place the owner outside.
kill @e[type=minecraft:item,x=-25,y=54,z=-22,dx=17,dy=4,dz=10]
tp @s -9.318 56 -18.126 101.389 30.450
