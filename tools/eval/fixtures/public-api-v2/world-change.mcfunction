# Installed as v2smoke:world_change, scheduled once by the separate fixture operator.
# Only the test player tagged during preparation is moved.
execute as @a[tag=mcmcp_v2_boundary_test] in minecraft:the_nether run tp @s 200.5 201 200.5
tag @a[tag=mcmcp_v2_boundary_test] remove mcmcp_v2_boundary_test
