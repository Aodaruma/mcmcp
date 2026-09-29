execute if entity @a[x=302,y=101,z=300,dx=1,dy=2,dz=0] run function v2nav:block_route
execute unless entity @a[x=302,y=101,z=300,dx=1,dy=2,dz=0] run schedule function v2nav:watch_route 1t
