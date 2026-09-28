# Before the WorldChange phase, only in the isolated fixture world.
tag @s add mcmcp_v2_boundary_test
execute in minecraft:the_nether run forceload add 192 192 208 208
schedule function v2smoke:prepare_destination 2s replace
