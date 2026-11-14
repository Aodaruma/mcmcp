package dev.aod.mcmcp.runtime;

import java.util.List;
import java.util.Set;

/** Decisions use observed danger and owned inventory, never a mob's type alone. */
final class EmergencyRecoveryPolicy {
    private EmergencyRecoveryPolicy() { }

    enum Danger { NONE, FALL, WATER, LAVA, SUFFOCATION, MOB_ATTACK, PLAYER_ATTACK, PLAYER_INTERFERENCE, MOB_INTERFERENCE }
    enum Response { CONTINUE, ELYTRA, LAND_IN_WATER, CLUTCH, RETALIATE, RETREAT, DISCONNECT }

    record Situation(Danger danger, boolean elytraReady, boolean safeRocket, boolean waterBelow,
                     boolean waterEvaporates, boolean fatal, boolean canFight, Set<String> items) {
        Situation { items = Set.copyOf(items); }
    }

    record Choice(Response response, String item) { }

    static boolean unsupportedDescent(boolean onGround, boolean hasSupport, boolean climbing, double verticalSpeed) {
        // onGround can remain true for the packet tick in which the supporting block disappears.
        return !climbing && verticalSpeed <= 0 && (!onGround || !hasSupport);
    }

    static boolean displacedByContact(boolean touching, double horizontalSpeed) {
        return touching && horizontalSpeed > 0.03;
    }

    static boolean contactCleared(net.minecraft.world.phys.AABB body, net.minecraft.world.phys.AABB obstruction) {
        return !obstruction.inflate(0.75, 0, 0.75).intersects(body);
    }

    static Choice choose(Situation state, boolean elytra, boolean useItems, boolean placeBlocks,
                         boolean retaliation, boolean disconnect) {
        if (state.danger() == Danger.NONE) return new Choice(Response.CONTINUE, null);
        if (state.danger() == Danger.FALL) {
            if (elytra && useItems && state.elytraReady() && state.safeRocket())
                return new Choice(Response.ELYTRA, "minecraft:firework_rocket");
            if (state.waterBelow()) return new Choice(Response.LAND_IN_WATER, null);
            if (useItems && !state.waterEvaporates() && state.items().contains("minecraft:water_bucket"))
                return new Choice(Response.CLUTCH, "minecraft:water_bucket");
            if (placeBlocks && state.items().contains("minecraft:cobweb"))
                return new Choice(Response.CLUTCH, "minecraft:cobweb");
            if (useItems) {
                var boat = state.items().stream().filter(EmergencyRecoveryPolicy::boat).sorted().findFirst();
                if (boat.isPresent()) return new Choice(Response.CLUTCH, boat.orElseThrow());
            }
            if (placeBlocks) {
                for (String item : List.of("minecraft:slime_block", "minecraft:hay_block", "minecraft:honey_block"))
                    if (state.items().contains(item)) return new Choice(Response.CLUTCH, item);
                var bed = state.items().stream().filter(id -> id.startsWith("minecraft:") && id.endsWith("_bed"))
                        .sorted().findFirst();
                if (bed.isPresent()) return new Choice(Response.CLUTCH, bed.orElseThrow());
            }
            return new Choice(disconnect && state.fatal() ? Response.DISCONNECT : Response.RETREAT, null);
        }
        if (state.danger() == Danger.PLAYER_ATTACK && disconnect) return new Choice(Response.DISCONNECT, null);
        if (state.danger() == Danger.MOB_ATTACK && retaliation && state.canFight())
            return new Choice(Response.RETALIATE, null);
        return new Choice(Response.RETREAT, null);
    }

    static boolean boat(String item) {
        return item.startsWith("minecraft:") && (item.endsWith("_boat") || item.endsWith("_raft"))
                && !item.contains("chest");
    }

    static int weaponPriority(String item, boolean hasArrows, boolean rangedClear) {
        if (item.equals("minecraft:bow") && hasArrows && rangedClear) return 0;
        if (item.endsWith("_sword")) return 1;
        if (item.endsWith("_axe")) return 2;
        if (item.endsWith("_pickaxe") || item.endsWith("_shovel") || item.endsWith("_hoe")) return 3;
        return Integer.MAX_VALUE;
    }
}
