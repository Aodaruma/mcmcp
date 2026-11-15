function v2nav:machine
setblock 306 101 320 minecraft:iron_door[facing=west,half=lower,hinge=left,open=false,powered=false]
setblock 306 102 320 minecraft:iron_door[facing=west,half=upper,hinge=left,open=false,powered=false]
schedule function v2nav:power_on 40t replace
