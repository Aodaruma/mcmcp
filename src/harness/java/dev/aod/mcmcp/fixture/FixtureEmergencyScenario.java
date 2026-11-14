package dev.aod.mcmcp.fixture;

import dev.aod.mcmcp.client.AgentInputState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Fixed, one-shot hazards in the private harness world; never reachable from a product tool. */
final class FixtureEmergencyScenario {
    private final FixtureSecurity.Context context;
    private final String kind;
    private final int deadline;
    private boolean injected;
    private float minHealth;
    private double maxSpeed;
    private int flyingTicks;
    private boolean rescueLanded, postFlightThreat;
    private net.minecraft.world.entity.monster.zombie.Zombie returningMob;
    private int hiddenAt = -1, returnedAt = -1, returnHitAt = -1, returnHurtAt = -1;
    private float healthAtReturn;

    FixtureEmergencyScenario(FixtureSecurity.Context context, String kind) {
        this.context = context; this.kind = kind;
        deadline = context.server().getTickCount() + 2400;
        minHealth = context.player().getHealth();
    }

    void tick(net.minecraft.server.MinecraftServer server) {
        if (server != context.server() || server.getTickCount() > deadline
                || !FixtureSecurity.reauthorize(context).allowed()) return;
        var player = context.player();
        minHealth = Math.min(minHealth, player.getHealth());
        maxSpeed = Math.max(maxSpeed, player.getDeltaMovement().length());
        if (player.isFallFlying()) flyingTicks++;
        if (kind.equals("recovery_build_flight") && injected && flyingTicks > 0 && player.onGround()
                && !player.isFallFlying()) rescueLanded = true;
        if (kind.equals("recovery_build_flight") && rescueLanded && !postFlightThreat
                && player.onGround() && player.getY() >= 120.9 && player.distanceToSqr(318, 121, 309.5) < 64) {
            postFlightThreat = true;
            var mob = new net.minecraft.world.entity.monster.zombie.Zombie(net.minecraft.world.entity.EntityTypes.ZOMBIE, context.level());
            mob.setPos(318, 121, 309.5); mob.addTag("mcmcp_v2_fixture");
            mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_HELMET));
            mob.setTarget(player); context.level().addFreshEntity(mob);
        }
        if (returningMob != null) {
            int now = server.getTickCount();
            if (hiddenAt < 0 && player.getHealth() < player.getMaxHealth()) {
                hiddenAt = now;
                returningMob.setNoAi(true); returningMob.setNoGravity(true); returningMob.setInvulnerable(true);
                returningMob.teleportTo(295, 143, 295);
            } else if (hiddenAt >= 0 && returnedAt < 0 && now - hiddenAt >= 260) {
                returnedAt = now; healthAtReturn = player.getHealth();
                returningMob.teleportTo(player.getX() + 2.5, player.getY(), player.getZ());
                returningMob.setNoAi(false); returningMob.setNoGravity(false); returningMob.setInvulnerable(false);
                returningMob.setTarget(player); returningMob.setHealth(returningMob.getMaxHealth());
            }
            if (returnedAt >= 0) {
                if (returnHitAt < 0 && returningMob.getHealth() < returningMob.getMaxHealth()) returnHitAt = now;
                if (returnHurtAt < 0 && player.getHealth() < healthAtReturn) returnHurtAt = now;
            }
        }
        if (injected || !kind.startsWith("recovery_") || kind.equals("recovery_neutral")
                || kind.equals("recovery_trader_near")) return;
        if (kind.equals("recovery_place_mob")) {
            if (!BuiltInRegistries.BLOCK.getKey(context.level().getBlockState(new BlockPos(315, 120, 310))
                    .getBlock()).toString().equals("minecraft:black_concrete")) return;
            injected = true;
            var mob = new net.minecraft.world.entity.monster.zombie.Zombie(net.minecraft.world.entity.EntityTypes.ZOMBIE, context.level());
            mob.setPos(player.getX() - 1.5, 121, player.getZ()); mob.addTag("mcmcp_v2_fixture");
            mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_HELMET));
            mob.setTarget(player); context.level().addFreshEntity(mob);
            return;
        }
        var input = AgentInputState.global().inputOwnershipSnapshot();
        if (!input.movementOwned() || !player.onGround() || player.distanceToSqr(310.5, 121, 310.5) > 9) return;
        injected = true;
        if (kind.equals("recovery_mob") || kind.equals("recovery_returning_mob")) {
            var mob = new net.minecraft.world.entity.monster.zombie.Zombie(net.minecraft.world.entity.EntityTypes.ZOMBIE, context.level());
            mob.setPos(312, 121, 310.5); mob.addTag("mcmcp_v2_fixture");
            mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_HELMET));
            mob.setTarget(player);
            context.level().addFreshEntity(mob);
            if (kind.equals("recovery_returning_mob")) returningMob = mob;
        } else if (kind.equals("recovery_trader_push")) {
            var trader = new net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader(
                    net.minecraft.world.entity.EntityTypes.WANDERING_TRADER, context.level());
            trader.setPos(player.getX() + 0.35, player.getY(), player.getZ());
            trader.setNoAi(true); trader.addTag("mcmcp_v2_fixture");
            context.level().addFreshEntity(trader);
        } else {
            for (int x = 308; x <= 314; x++) for (int z = 308; z <= 314; z++)
                context.level().setBlockAndUpdate(new BlockPos(x, 120, z), Blocks.AIR.defaultBlockState());
        }
    }

    Map<String, Object> report() {
        var blocks = new ArrayList<Object>();
        for (int x = 307; x <= 315; x++) for (int z = 307; z <= 315; z++) {
            var pos = new BlockPos(x, 101, z);
            var state = context.level().getBlockState(pos);
            if (!state.isAir()) blocks.add(Map.of("x", x, "y", 101, "z", z,
                    "block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()));
        }
        var entities = context.level().getEntitiesOfClass(LivingEntity.class, new AABB(290, 100, 290, 350, 146, 330),
                entity -> entity.entityTags().contains("mcmcp_v2_fixture")).stream()
                .map(entity -> Map.<String, Object>of("id", entity.getUUID().toString(), "type",
                        BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(), "health", entity.getHealth())).toList();
        if (kind.equals("recovery_place_mob") || kind.equals("recovery_build_flight")) {
            for (int x = 315; x <= 324; x++) blocks.add(Map.of("x", x, "y", 120, "z", 310, "block",
                    BuiltInRegistries.BLOCK.getKey(context.level().getBlockState(new BlockPos(x, 120, 310)).getBlock()).toString()));
        }
        return Map.of("case", kind, "hazard_injected", injected, "minimum_health", minHealth,
                "maximum_speed", maxSpeed, "flying_ticks", flyingTicks, "landing_blocks", blocks, "mobs", entities,
                "flight_return", Map.of("landed", rescueLanded, "threat_spawned", postFlightThreat),
                "returning_mob", Map.of("hidden_at", hiddenAt, "returned_at", returnedAt,
                        "hit_after_return_at", returnHitAt, "player_hurt_after_return_at", returnHurtAt));
    }

    static List<String> commands(String kind) {
        var commands = new ArrayList<String>();
        commands.add("kill @e[tag=mcmcp_v2_fixture,x=290,y=100,z=290,dx=60,dy=46,dz=40]");
        commands.add("kill @e[type=minecraft:oak_boat,x=290,y=100,z=290,dx=60,dy=46,dz=40]");
        commands.add("kill @e[type=minecraft:item,x=290,y=100,z=290,dx=60,dy=46,dz=40]");
        int endX = kind.startsWith("flight_boost") ? 430 : 350;
        commands.add("fill 290 100 290 " + endX + " 100 330 minecraft:stone");
        for (int y = 101; y <= 141; y += 5)
            commands.add("fill 290 " + y + " 290 " + endX + " " + (y + 4) + " 330 minecraft:air");
        commands.add("fill 308 120 308 314 120 314 minecraft:stone");
        commands.add("tp @s 310.5 121 310.5 -90 45");
        String item = switch (kind) {
            case "recovery_bucket" -> "minecraft:water_bucket";
            case "recovery_web" -> "minecraft:cobweb";
            case "recovery_boat" -> "minecraft:oak_boat";
            case "recovery_slime" -> "minecraft:slime_block";
            case "recovery_hay" -> "minecraft:hay_block";
            case "recovery_bed" -> "minecraft:red_bed";
            case "recovery_mob", "recovery_neutral", "recovery_returning_mob", "recovery_trader_near",
                    "recovery_trader_push" -> "minecraft:diamond_sword";
            default -> null;
        };
        if (item != null) commands.add("item replace entity @s hotbar.0 with " + item);
        if (kind.equals("recovery_mob") || kind.equals("recovery_neutral") || kind.equals("recovery_returning_mob")
                || kind.startsWith("recovery_trader_")) {
            commands.add("fill 298 120 298 348 120 322 minecraft:stone");
            commands.add("difficulty normal");
        }
        if (kind.equals("recovery_neutral"))
            commands.add("summon minecraft:cow 312 121 310.5 {Tags:[\"mcmcp_v2_fixture\"],NoAI:1b}");
        if (kind.equals("recovery_trader_near"))
            commands.add("summon minecraft:wandering_trader 312 121 310.5 {Tags:[\"mcmcp_v2_fixture\"],NoAI:1b}");
        if (kind.equals("recovery_water")) commands.add("fill 307 101 307 315 102 315 minecraft:water");
        if (kind.equals("recovery_place_mob") || kind.equals("recovery_build_flight")) {
            if (kind.equals("recovery_place_mob")) commands.add("fill 298 119 298 348 119 322 minecraft:stone");
            commands.add("fill 298 120 298 348 120 322 minecraft:stone");
            commands.add("fill 315 120 310 324 120 310 minecraft:air");
            commands.add("item replace entity @s hotbar.0 with minecraft:black_concrete 64");
            commands.add("item replace entity @s hotbar.1 with minecraft:diamond_sword");
            commands.add("difficulty normal");
        }
        if (kind.equals("recovery_build_flight")) {
            commands.add("item replace entity @s armor.chest with minecraft:elytra");
            commands.add("item replace entity @s hotbar.2 with minecraft:firework_rocket 8");
            commands.add("fill 321 101 312 321 119 312 minecraft:stone");
            commands.add("fill 320 101 312 320 120 312 minecraft:ladder[facing=west]");
            commands.add("tp @s 310.5 121 310.5 -90 0");
        }
        if (kind.equals("recovery_elytra") || kind.startsWith("flight_")) {
            commands.add("item replace entity @s armor.chest with minecraft:elytra");
            commands.add("item replace entity @s hotbar.0 with minecraft:firework_rocket 8");
            commands.add("tp @s 310.5 121 310.5 -90 0");
        }
        if (kind.equals("flight_wall")) commands.add("fill 318 101 300 318 145 320 minecraft:stone");
        if (kind.equals("flight_boost_wall")) commands.add("fill 360 101 290 360 170 330 minecraft:stone");
        return List.copyOf(commands);
    }
}
