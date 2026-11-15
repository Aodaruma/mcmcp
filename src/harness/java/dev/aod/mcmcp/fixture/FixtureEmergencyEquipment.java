package dev.aod.mcmcp.fixture;

import dev.aod.mcmcp.client.AgentInputState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fixed equipment and native-arrow oracle, confined by its parent fixture's authorization/deadline. */
final class FixtureEmergencyEquipment {
    private final FixtureSecurity.Context context;
    private final String kind;
    private final List<Map<String, Object>> samples = new ArrayList<>();
    private int triggered = -1, equipped = -1, shots, blocks, flyingTicks, waterTicks;
    private float minHealth = 20, blockedDamage, mobMinimum = 20;
    private boolean unsafeEquip, carried;
    private WanderingTrader trader;
    private Skeleton mob;

    FixtureEmergencyEquipment(FixtureSecurity.Context context, String kind) {
        this.context = context; this.kind = kind;
    }

    void tick() {
        var player = context.player();
        int now = context.server().getTickCount();
        var chest = player.getItemBySlot(EquipmentSlot.CHEST);
        boolean armor = chest.is(Items.DIAMOND_CHESTPLATE);
        if (triggered < 0) {
            if (!AgentInputState.global().inputOwnershipSnapshot().movementOwned() || !player.onGround()) return;
            triggered = now;
            if (kind.endsWith("_water") || kind.endsWith("_flight")) {
                for (int x = 308; x <= 314; x++) for (int z = 308; z <= 314; z++)
                    context.level().setBlockAndUpdate(new BlockPos(x, 120, z), Blocks.AIR.defaultBlockState());
            } else {
                trader = new WanderingTrader(EntityTypes.WANDERING_TRADER, context.level());
                trader.setPos(player.getX() + .15, player.getY(), player.getZ());
                trader.setNoAi(true); trader.addTag("mcmcp_v2_fixture");
                context.level().addFreshEntity(trader);
            }
        }
        if (trader != null && now - triggered == 60) trader.teleportTo(298.5, 121, 299.5);
        if (armor && equipped < 0) {
            equipped = now;
            unsafeEquip = !player.onGround() || player.isInWater() || player.isFallFlying();
        }
        carried |= !player.containerMenu.getCarried().isEmpty();
        minHealth = Math.min(minHealth, player.getHealth());
        if (player.isFallFlying()) flyingTicks++;
        if (player.isInWater()) waterTicks++;
        if (equipped >= 0 && now - equipped >= 12 && mob == null
                && (kind.endsWith("_bow") || kind.endsWith("_shield"))) {
            mob = new Skeleton(EntityTypes.SKELETON, context.level());
            mob.setPos(player.getX() + 12, player.getY(), player.getZ());
            mob.setNoAi(true); mob.addTag("mcmcp_v2_fixture");
            mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
            mob.setTarget(player); context.level().addFreshEntity(mob);
            // One fixed attributed hit establishes an observed attacker before the finite arrow test.
            player.hurtServer(context.level(), context.level().damageSources().mobAttack(mob), 1);
        }
        if (mob != null) {
            mobMinimum = Math.min(mobMinimum, mob.getHealth());
            if (mob.isAlive() && now - equipped >= 25 && (now - equipped - 25) % 60 == 0 && shots < 8) {
                var arrow = new Arrow(context.level(), mob, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
                var delta = player.getBoundingBox().getCenter().subtract(arrow.position());
                arrow.shoot(delta.x, delta.y + delta.horizontalDistance() * .15, delta.z, 1.6f, 0);
                arrow.addTag("mcmcp_v2_fixture"); context.level().addFreshEntity(arrow); shots++;
            }
        }
        if (samples.size() < 800) {
            var sample = new LinkedHashMap<String, Object>();
            sample.put("tick", now); sample.put("chest", BuiltInRegistries.ITEM.getKey(chest.getItem()).toString());
            sample.put("position", List.of(player.getX(), player.getY(), player.getZ()));
            sample.put("health", player.getHealth()); sample.put("ground", player.onGround());
            sample.put("water", player.isInWater()); sample.put("flying", player.isFallFlying());
            sample.put("blocking", player.isBlocking()); sample.put("cursor_empty", player.containerMenu.getCarried().isEmpty());
            samples.add(Map.copyOf(sample));
        }
    }

    void shieldBlock(net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent event) {
        if (event.getEntity() == context.player() && event.getDamageSource().getDirectEntity() instanceof Arrow arrow
                && arrow.getOwner() == mob && event.getBlocked() && event.getBlockedDamage() > 0) {
            blocks++; blockedDamage += event.getBlockedDamage();
        }
    }

    Map<String, Object> report() {
        var result = new LinkedHashMap<String, Object>();
        result.put("case", kind); result.put("hazard_injected", triggered >= 0); result.put("equipped_tick", equipped);
        result.put("unsafe_equipment", unsafeEquip); result.put("carried_during_recovery", carried);
        result.put("minimum_health", minHealth); result.put("flying_ticks", flyingTicks); result.put("water_ticks", waterTicks);
        result.put("arrows_fired", shots); result.put("arrows_blocked", blocks); result.put("damage_blocked", blockedDamage);
        result.put("mob_minimum_health", mobMinimum); result.put("mob_alive", mob != null && mob.isAlive());
        result.put("trader_health", trader == null ? 20 : trader.getHealth()); result.put("samples", List.copyOf(samples));
        return Map.copyOf(result);
    }

    static List<String> commands(String kind) {
        var commands = new ArrayList<String>();
        if (!kind.endsWith("_water") && !kind.endsWith("_flight")) commands.add("fill 298 120 298 348 120 322 minecraft:stone");
        else commands.add("fill 307 101 307 315 102 315 minecraft:water");
        if (kind.endsWith("_full")) {
            for (int slot = 0; slot < 9; slot++) commands.add("item replace entity @s hotbar." + slot + " with minecraft:stone 64");
            for (int slot = 1; slot < 27; slot++) commands.add("item replace entity @s inventory." + slot + " with minecraft:stone 64");
        }
        commands.add("item replace entity @s armor.chest with minecraft:elytra[minecraft:damage=21]");
        if (kind.endsWith("_locked")) commands.add("item replace entity @s armor.chest with minecraft:elytra[minecraft:damage=21,minecraft:enchantments={'minecraft:binding_curse':1}]");
        commands.add("item replace entity @s hotbar.0 with minecraft:diamond_sword");
        commands.add("item replace entity @s hotbar.6 with sophisticatedbackpacks:netherite_backpack");
        if (kind.endsWith("_bow")) {
            commands.add("item replace entity @s hotbar.1 with minecraft:bow");
            commands.add("item replace entity @s hotbar.2 with minecraft:arrow 64");
        }
        if (kind.endsWith("_shield")) commands.add("item replace entity @s hotbar.3 with minecraft:shield");
        if (kind.endsWith("_flight")) commands.add("item replace entity @s hotbar.4 with minecraft:firework_rocket 8");
        commands.add("difficulty normal"); commands.add("tp @s 310.5 121 310.5 -90 0");
        return List.copyOf(commands);
    }
}
