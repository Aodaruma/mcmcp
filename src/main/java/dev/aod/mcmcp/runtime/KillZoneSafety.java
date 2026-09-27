package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.runtime.RuntimeFailures.RuntimeInvocationException;
import dev.aod.mcmcp.safety.ScopedEntityAttackConsentStore;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** kill-zoneの形状・攻撃対象・視線・ACK証拠の安全条件。 */
final class KillZoneSafety {
    private KillZoneSafety() {}

    static ActionDsl.OperateKillZone soleKillZone(ActionDsl.Program program) {
        return program.body().size() == 1
                        && program.body().getFirst() instanceof ActionDsl.OperateKillZone operation
                ? operation : null;
    }

    static AttackProfile requireKnownAttackProfile(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            throw new RuntimeInvocationException(
                    "unsupported_attack_profile", "The main hand has no supported attack item.",
                    false, Map.of());
        }
        String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        boolean sword = item.matches("minecraft:(wooden|stone|copper|iron|golden|diamond|netherite)_sword");
        boolean axe = item.matches("minecraft:(wooden|stone|copper|iron|golden|diamond|netherite)_axe");
        if (!sword && !axe) {
            throw new RuntimeInvocationException(
                    "unsupported_attack_profile",
                    "Only audited Vanilla swords and axes are supported; MOD profiles need an adapter.",
                    false, Map.of());
        }
        var canonical = new StringBuilder(item);
        for (var entry : stack.getComponentsPatch().entrySet()) {
            if (entry.getKey() != DataComponents.DAMAGE
                    && entry.getKey() != DataComponents.ENCHANTMENTS) {
                throw new RuntimeInvocationException(
                        "unsupported_attack_profile",
                        "The held stack has an unaudited attack-relevant component patch.",
                        false, Map.of());
            }
        }
        if (!stack.getEnchantments().isEmpty()) {
            throw new RuntimeInvocationException(
                    "unsupported_attack_profile",
                    "The initial production slice accepts only unenchanted Vanilla swords and axes.",
                    false, Map.of());
        }
        return new AttackProfile(
                RoutineIdentity.sha256Identity(canonical),
                sword
                        ? ScopedEntityAttackConsentStore.AttackSideEffectProfile.VANILLA_SWEEP
                        : ScopedEntityAttackConsentStore.AttackSideEffectProfile.VANILLA_SINGLE_TARGET);
    }

    static String killZoneStructureFingerprint(
            net.minecraft.client.multiplayer.ClientLevel level,
            ScopedEntityAttackConsentStore.Bounds station,
            ScopedEntityAttackConsentStore.Bounds zone) {
        int minX = Mth.floor(Math.min(station.minX(), zone.minX())) - 1;
        int minY = Mth.floor(Math.min(station.minY(), zone.minY())) - 1;
        int minZ = Mth.floor(Math.min(station.minZ(), zone.minZ())) - 1;
        int maxX = Mth.ceil(Math.max(station.maxX(), zone.maxX())) + 1;
        int maxY = Mth.ceil(Math.max(station.maxY(), zone.maxY())) + 1;
        int maxZ = Mth.ceil(Math.max(station.maxZ(), zone.maxZ())) + 1;
        long cells = Math.multiplyExact(
                Math.multiplyExact((long) maxX - minX + 1L, (long) maxY - minY + 1L),
                (long) maxZ - minZ + 1L);
        if (cells > 8_192L) {
            throw new RuntimeInvocationException(
                    "unsupported_kill_zone", "The structure witness exceeds 8192 loaded cells.",
                    false, Map.of());
        }
        var canonical = new StringBuilder();
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    var pos = new BlockPos(x, y, z);
                    if (!level.isLoaded(pos)) {
                        throw new RuntimeInvocationException(
                                "target_unknown", "Every structure witness cell must be loaded.",
                                true, Map.of());
                    }
                    BlockState state = level.getBlockState(pos);
                    RoutineIdentity.appendIdentity(canonical, x + "," + y + "," + z);
                    RoutineIdentity.appendIdentity(canonical,
                            BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                    state.getValues()
                            .map(value -> value.property().getName() + "=" + value.valueName())
                            .sorted()
                            .forEach(value -> RoutineIdentity.appendIdentity(canonical, value));
                    state.getCollisionShape(level, pos).toAabbs().stream()
                            .map(box -> box.minX + "," + box.minY + "," + box.minZ + ","
                                    + box.maxX + "," + box.maxY + "," + box.maxZ)
                            .sorted()
                            .forEach(value -> RoutineIdentity.appendIdentity(canonical, value));
                    var fluid = state.getFluidState();
                    RoutineIdentity.appendIdentity(canonical, fluid.isEmpty() ? "empty"
                            : BuiltInRegistries.FLUID.getKey(fluid.getType()).toString()
                                    + ":" + fluid.getAmount() + ":" + fluid.isSource());
                }
            }
        }
        return RoutineIdentity.sha256Identity(canonical);
    }

    static void requireKillZoneBarrier(
            net.minecraft.client.multiplayer.ClientLevel level,
            Player player,
            ScopedEntityAttackConsentStore.Bounds station,
            ScopedEntityAttackConsentStore.Bounds zone,
            List<String> allowedTypes) {
        Set<String> auditedTypes = Set.of(
                "minecraft:armor_stand",
                "minecraft:zombie",
                "minecraft:skeleton");
        if (!auditedTypes.containsAll(allowedTypes)) {
            throw unsafeKillZone(
                    "The initial fixture accepts only audited armor-stand and basic zombie/skeleton types.");
        }
        AABB playerBox = player.getBoundingBox();
        AABB safetyVolume = playerBox.inflate(8.0D);
        if (zone.minX() < safetyVolume.minX || zone.minY() < safetyVolume.minY
                || zone.minZ() < safetyVolume.minZ || zone.maxX() > safetyVolume.maxX
                || zone.maxY() > safetyVolume.maxY || zone.maxZ() > safetyVolume.maxZ) {
            throw unsafeKillZone(
                    "The complete kill zone must remain inside the eight-block hazard volume.");
        }
        int cellX = Mth.floor((playerBox.minX + playerBox.maxX) * 0.5D);
        int cellY = Mth.floor(playerBox.minY + 1.0e-5D);
        int cellZ = Mth.floor((playerBox.minZ + playerBox.maxZ) * 0.5D);
        if (Mth.floor(playerBox.minX) != Mth.floor(playerBox.maxX - 1.0e-5D)
                || Mth.floor(playerBox.minZ) != Mth.floor(playerBox.maxZ - 1.0e-5D)) {
            throw unsafeKillZone("The player must stand wholly inside one safety-cell column.");
        }

        double dx = (zone.minX() + zone.maxX()) * 0.5D - player.getX();
        double dz = (zone.minZ() + zone.maxZ()) * 0.5D - player.getZ();
        int frontX = cellX;
        int frontZ = cellZ;
        if (Math.abs(dx) >= Math.abs(dz) && dx > 0.0D && zone.minX() >= cellX + 2.0D) {
            frontX++;
        } else if (Math.abs(dx) >= Math.abs(dz) && dx < 0.0D
                && zone.maxX() <= cellX - 1.0D) {
            frontX--;
        } else if (Math.abs(dz) > Math.abs(dx) && dz > 0.0D
                && zone.minZ() >= cellZ + 2.0D) {
            frontZ++;
        } else if (Math.abs(dz) > Math.abs(dx) && dz < 0.0D
                && zone.maxZ() <= cellZ - 1.0D) {
            frontZ--;
        } else {
            throw unsafeKillZone(
                    "The kill zone must lie wholly beyond one cardinal face of the safety cell.");
        }

        BlockPos front = new BlockPos(frontX, cellY, frontZ);
        if (!exactFullCollisionCube(level, front)) {
            throw unsafeKillZone(
                    "The attack face lower block must be a full collision cube.");
        }
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] side : sides) {
            BlockPos low = new BlockPos(cellX + side[0], cellY, cellZ + side[1]);
            if (!low.equals(front) && !exactFullCollisionCube(level, low)) {
                throw unsafeKillZone("The other three lower safety-cell walls must be full cubes.");
            }
            if (low.equals(front)) {
                if (!exactCollisionBox(
                        level, low.above(), new AABB(0, 0.5D, 0, 1, 1, 1))) {
                    throw unsafeKillZone(
                            "The attack face requires an exact upper top slab over its half-block slit.");
                }
            } else if (!exactFullCollisionCube(level, low.above())) {
                throw unsafeKillZone("Every upper safety-cell wall must be a full cube.");
            }
        }
        if (!exactFullCollisionCube(level, new BlockPos(cellX, cellY - 1, cellZ))
                || !exactFullCollisionCube(level, new BlockPos(cellX, cellY + 2, cellZ))) {
            throw unsafeKillZone("The safety cell requires a full-cube support and roof.");
        }
        for (String allowedType : allowedTypes) {
            Identifier id = Identifier.tryParse(allowedType);
            var type = id == null ? null
                    : BuiltInRegistries.ENTITY_TYPE.get(id).map(Holder::value).orElse(null);
            if (type == null || type.getDimensions().height() <= 1.0F) {
                throw unsafeKillZone(
                        "Every allowed entity type must be taller than the fixed one-block opening.");
            }
        }
    }

    static RuntimeInvocationException unsafeKillZone(String message) {
        return new RuntimeInvocationException(
                "unsafe_kill_zone", message, false, Map.of());
    }

    static boolean exactFullCollisionCube(
            net.minecraft.client.multiplayer.ClientLevel level, BlockPos pos) {
        return exactCollisionBox(level, pos, new AABB(0, 0, 0, 1, 1, 1));
    }

    static boolean exactCollisionBox(
            net.minecraft.client.multiplayer.ClientLevel level,
            BlockPos pos,
            AABB expected) {
        if (!level.isLoaded(pos)) return false;
        List<AABB> boxes = level.getBlockState(pos).getCollisionShape(level, pos).toAabbs();
        return boxes.size() == 1 && boxes.getFirst().equals(expected);
    }

    record AttackProfile(
            String fingerprint,
            ScopedEntityAttackConsentStore.AttackSideEffectProfile sideEffects) {
        AttackProfile {
            Objects.requireNonNull(fingerprint, "fingerprint");
            Objects.requireNonNull(sideEffects, "sideEffects");
        }
    }

    record KillZoneAdmission(
            String policyBindingHash,
            ScopedEntityAttackConsentStore.Scope scope) {
        KillZoneAdmission {
            Objects.requireNonNull(policyBindingHash, "policyBindingHash");
            Objects.requireNonNull(scope, "scope");
        }
    }

    static boolean clearKillZoneCrosshairRay(
            Minecraft minecraft, EntityHitResult hit) {
        Vec3 eye = minecraft.player.getEyePosition();
        Vec3 hitLocation = hit.getLocation();
        if (!hit.getEntity().getBoundingBox().inflate(1.0e-5D).contains(hitLocation)
                || eye.distanceToSqr(hitLocation) < 1.0e-8D) {
            return false;
        }
        HitResult obstruction = minecraft.level.clip(new ClipContext(
                eye,
                hitLocation,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                minecraft.player));
        return obstruction.getType() == HitResult.Type.MISS;
    }

    static boolean killZoneCollateralSafe(
            net.minecraft.client.multiplayer.ClientLevel level,
            Player player,
            LivingEntity target,
            ScopedEntityAttackConsentStore.Scope scope) {
        if (scope.attackSideEffectProfile()
                == ScopedEntityAttackConsentStore.AttackSideEffectProfile.VANILLA_SINGLE_TARGET) {
            return true;
        }
        if (scope.attackSideEffectProfile()
                != ScopedEntityAttackConsentStore.AttackSideEffectProfile.VANILLA_SWEEP) {
            return false;
        }
        AABB effects = target.getBoundingBox().inflate(1.0D, 0.25D, 1.0D);
        for (LivingEntity candidate : level.getEntitiesOfClass(
                LivingEntity.class, effects, Entity::isAlive)) {
            if (candidate == player
                    || candidate instanceof Player
                    || !scope.entityTypeAllowlist().contains(entityType(candidate))
                    || !wholeBoxInside(scope.targetKillZoneBounds(), candidate.getBoundingBox())) {
                return false;
            }
        }
        return true;
    }

    static boolean wholeBoxInside(
            ScopedEntityAttackConsentStore.Bounds bounds, AABB box) {
        return bounds.contains(
                box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    static String entityType(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    static float effectiveHealth(Player player) {
        return player.getHealth() + player.getAbsorptionAmount();
    }

    static long armorStandLastHit(LivingEntity target) {
        return target instanceof ArmorStand stand ? stand.lastHit : Long.MIN_VALUE;
    }

    static boolean armorStandHitEventAdvanced(long before, long after) {
        return before != Long.MIN_VALUE && after > before;
    }

    static boolean killZonePendingMustClose(
            long currentTick, long effectDeadlineTick, boolean actionHardDeadlineReached) {
        return actionHardDeadlineReached || currentTick >= effectDeadlineTick;
    }

    static boolean healthDecreased(
            float previousHealth,
            float previousAbsorption,
            float currentHealth,
            float currentAbsorption) {
        return currentHealth < previousHealth
                || currentAbsorption < previousAbsorption
                || currentHealth + currentAbsorption
                        < previousHealth + previousAbsorption;
    }
}
