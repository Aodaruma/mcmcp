package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.safety.MinecraftRecoveryGovernor;
import dev.aod.mcmcp.agent.safety.ObservedThreats;
import dev.aod.mcmcp.agent.safety.FallDamageEstimate;
import dev.aod.mcmcp.client.AgentInputState;
import dev.aod.mcmcp.client.McmcpClientConfig;
import dev.aod.mcmcp.routine.MovementInputLease;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Owns the finite emergency interval after the interrupted job has relinquished all input. */
final class MinecraftEmergencyRecovery implements AutoCloseable {
    enum State { RECOVERING, STABLE, EXHAUSTED, DISCONNECT }
    record Hazard(EmergencyRecoveryPolicy.Danger danger, Entity threat, boolean waterBelow, boolean fatal) { }
    private static final Duration HORIZON = Duration.ofMillis(500);
    private final Minecraft minecraft;
    private final LocalPlayer player;
    private final Object level;
    private final UUID session, owner = UUID.randomUUID();
    private final long started;
    private final int maxTicks, maxInteractions;
    private final double maxDistance, maxCamera;
    private final Vec3 safeAnchor;
    private final Supplier<WorldSessionTracker.Snapshot> sessions;
    private Vec3 previous, threatOrigin;
    private Entity contactObstruction;
    private double travelled;
    private double cameraTravel;
    private float previousYaw, previousPitch;
    private int stableTicks;
    private MovementInputLease movement;
    private MinecraftEmergencyCombat combat;
    private MinecraftEmergencyClutch clutch;
    private MinecraftElytraFlight flight;
    private MinecraftEmergencyWaystone warp;
    private boolean chatAttempted, flightRejected, warpAttempted;
    private final Set<String> unavailableItems = new HashSet<>();
    private boolean clutchDispatched;
    private int flightUses, clutchUses, warpUses;
    private EmergencyRecoveryPolicy.Danger reason;
    private String strategy = "starting", failure;
    private final Map<String, Object> history = new LinkedHashMap<>();

    MinecraftEmergencyRecovery(Minecraft minecraft, UUID session, long tick, Vec3 safeAnchor, Hazard hazard,
                               Supplier<WorldSessionTracker.Snapshot> sessions) {
        this.minecraft = minecraft; this.session = session; this.started = tick;
        this.sessions = sessions;
        this.safeAnchor = safeAnchor == null ? minecraft.player.position().add(8, 6, 0) : safeAnchor;
        player = minecraft.player; level = minecraft.level; previous = player.position(); reason = hazard.danger();
        maxTicks = McmcpClientConfig.recoveryMaxTicks(); maxInteractions = McmcpClientConfig.recoveryMaxInteractions();
        maxDistance = McmcpClientConfig.recoveryMaxDistance(); maxCamera = McmcpClientConfig.recoveryMaxCameraDegrees();
        previousYaw = player.getYRot(); previousPitch = player.getXRot();
        threatOrigin = hazard.threat() == null ? null : hazard.threat().position();
        if (hazard.danger() == EmergencyRecoveryPolicy.Danger.MOB_INTERFERENCE) contactObstruction = hazard.threat();
    }

    static Hazard inspect(Minecraft minecraft, boolean deliberateWater, boolean expectedFall) {
        var player = minecraft.player;
        if (player == null || player.isDeadOrDying() || player.isSpectator() || player.getAbilities().invulnerable)
            return new Hazard(EmergencyRecoveryPolicy.Danger.NONE, null, false, false);
        Entity threat = minecraft.level.getEntities(player, player.getBoundingBox().inflate(16),
                entity -> player.hasLineOfSight(entity) && ObservedThreats.attacking(entity, player)).stream()
                .min(Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
        boolean falling = !expectedFall && !player.isInWater() && !player.isInLava()
                && !player.isPassenger() && !player.getAbilities().flying && !player.isFallFlying()
                && EmergencyRecoveryPolicy.unsupportedDescent(player.onGround(),
                    !minecraft.level.noCollision(player, player.getBoundingBox().move(0, -0.08, 0)),
                    player.onClimbable(), player.getDeltaMovement().y);
        if (falling) {
            var hit = minecraft.level.clip(new ClipContext(player.position().add(0, 0.2, 0),
                    player.position().add(player.getDeltaMovement().x * 3, -16, player.getDeltaMovement().z * 3),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, player));
            boolean water = hit.getType() == HitResult.Type.BLOCK && minecraft.level.getFluidState(hit.getBlockPos()).is(FluidTags.WATER);
            double distance = hit.getType() == HitResult.Type.MISS ? Double.POSITIVE_INFINITY
                    : player.fallDistance + Math.max(0, player.getY() - hit.getLocation().y);
            boolean fatal = FallDamageEstimate.predict(player, distance) + 2 >= player.getHealth() + player.getAbsorptionAmount();
            if (water || !Double.isFinite(distance) || FallDamageEstimate.predict(player, distance) > 0)
                return new Hazard(EmergencyRecoveryPolicy.Danger.FALL, threat, water, fatal);
        }
        if (player.isInLava() || player.isOnFire()) return new Hazard(EmergencyRecoveryPolicy.Danger.LAVA, threat, false, true);
        if (player.isInWall()) return new Hazard(EmergencyRecoveryPolicy.Danger.SUFFOCATION, threat, false, false);
        if (player.isInWater() && (!deliberateWater || player.getAirSupply() < 160))
            return new Hazard(EmergencyRecoveryPolicy.Danger.WATER, threat, false, false);
        if (threat != null) return new Hazard(threat instanceof Player ? EmergencyRecoveryPolicy.Danger.PLAYER_ATTACK
                : EmergencyRecoveryPolicy.Danger.MOB_ATTACK, threat, false, false);
        if (ObservedThreats.recentAttack(player)) return new Hazard(ObservedThreats.recentPlayerAttack(player)
                ? EmergencyRecoveryPolicy.Danger.PLAYER_ATTACK : EmergencyRecoveryPolicy.Danger.MOB_ATTACK, null, false, false);
        var interference = minecraft.level.getEntities(player, player.getBoundingBox().inflate(2), entity ->
                entity instanceof FishingHook hook && hook.getHookedIn() == player && hook.getOwner() instanceof Player
                || (entity instanceof Player || entity instanceof Mob) && entity.isAlive() && !entity.isSpectator()
                    && !entity.isPassengerOfSameVehicle(player)
                    && EmergencyRecoveryPolicy.displacedByContact(
                        entity.getBoundingBox().inflate(0.08).intersects(player.getBoundingBox()),
                        player.getDeltaMovement().horizontalDistance())).stream().findFirst().orElse(null);
        if (interference instanceof FishingHook hook) interference = hook.getOwner();
        return new Hazard(interference == null ? EmergencyRecoveryPolicy.Danger.NONE
                : interference instanceof Player ? EmergencyRecoveryPolicy.Danger.PLAYER_INTERFERENCE
                : EmergencyRecoveryPolicy.Danger.MOB_INTERFERENCE, interference, false, false);
    }

    State tick(long tick, long revision, Hazard hazard,
               Supplier<List<MinecraftRecoveryGovernor.Candidate>> candidates, BooleanSupplier allowed) {
        if (!allowed.getAsBoolean() || minecraft.player != player || minecraft.level != level || player.isDeadOrDying()) {
            failure = "recovery_context_changed"; return State.EXHAUSTED;
        }
        travelled += player.position().distanceTo(previous); previous = player.position();
        cameraTravel += Math.abs(net.minecraft.util.Mth.wrapDegrees(player.getYRot() - previousYaw))
                + Math.abs(player.getXRot() - previousPitch);
        previousYaw = player.getYRot(); previousPitch = player.getXRot();
        if (tick - started >= maxTicks || travelled > maxDistance) {
            failure = "recovery_budget_exhausted";
            return McmcpClientConfig.recoveryDisconnect() && hazard.fatal() ? State.DISCONNECT : State.EXHAUSTED;
        }
        if (hazard.threat() != null) threatOrigin = hazard.threat().position();
        if (hazard.danger() == EmergencyRecoveryPolicy.Danger.MOB_INTERFERENCE) contactObstruction = hazard.threat();
        if (hazard.danger() != EmergencyRecoveryPolicy.Danger.NONE) reason = hazard.danger();
        if ((reason == EmergencyRecoveryPolicy.Danger.PLAYER_ATTACK || reason == EmergencyRecoveryPolicy.Danger.PLAYER_INTERFERENCE)
                && !chatAttempted) {
            chatAttempted = true;
            String text = McmcpClientConfig.recoveryPlayerMessage();
            if (!text.isBlank()) {
                try { minecraft.getConnection().sendChat(McmcpClientConfig.agentChatSettings().format(text)); }
                catch (IllegalArgumentException invalid) { history.put("chat", "invalid_configured_message"); }
            }
        }
        if (warp != null) {
            stopMovement();
            var result = warp.tick(tick, allowed);
            history.put("waystone", warp.result());
            if (result == MinecraftEmergencyWaystone.State.WAITING) return State.RECOVERING;
            if (result == MinecraftEmergencyWaystone.State.TRANSFERRED) return State.STABLE;
            warp.close(); warp = null;
        }
        if (flight != null) {
            stopMovement();
            var result = flight.tick(safeAnchor, hazard.threat() == null ? null : hazard.threat().position(), tick,
                    Math.max(0, Math.min(4, maxInteractions - flightUses - clutchUses - warpUses - combatUses() - 1)),
                    true, maxTicks - (tick - started) - McmcpClientConfig.recoverySafeTicks(),
                    maxDistance - travelled, maxCamera - cameraTravel, allowed);
            history.put("flight", flight.result());
            if (result == MinecraftElytraFlight.Result.RUNNING) return State.RECOVERING;
            flightUses += flight.usesDispatched(); flight.close(); flight = null;
            if (result == MinecraftElytraFlight.Result.UNAVAILABLE) flightRejected = true;
            return State.RECOVERING;
        }
        Set<String> items = new HashSet<>();
        for (int slot = 0; slot < 36; slot++) {
            var stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) items.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        }
        items.removeAll(unavailableItems);
        int remainingUses = maxInteractions - flightUses - clutchUses - warpUses - combatUses();
        if (remainingUses < 2) items.removeIf(EmergencyRecoveryPolicy::boat);
        var state = new EmergencyRecoveryPolicy.Situation(hazard.danger(), !flightRejected && MinecraftElytraFlight.available(player),
                MinecraftElytraFlight.rocketsAvailable(player), hazard.waterBelow(),
                minecraft.level.environmentAttributes().getValue(net.minecraft.world.attribute.EnvironmentAttributes.WATER_EVAPORATES,
                        player.blockPosition()), hazard.fatal(), player.getHealth() > 6, items);
        var choice = EmergencyRecoveryPolicy.choose(state, McmcpClientConfig.recoveryElytra(),
                McmcpClientConfig.emergencyItemUse() && remainingUses > 0 && !clutchDispatched,
                McmcpClientConfig.emergencyBlockPlacement() && McmcpClientConfig.recoveryMaxPlacements() > 0 && remainingUses > 0 && !clutchDispatched,
                McmcpClientConfig.recoveryRetaliate(), McmcpClientConfig.recoveryDisconnect());
        strategy = choice.response().name().toLowerCase(java.util.Locale.ROOT);
        if (choice.response() == EmergencyRecoveryPolicy.Response.DISCONNECT) return State.DISCONNECT;
        if (choice.response() == EmergencyRecoveryPolicy.Response.ELYTRA) {
            if (combat != null) { combat.close(); }
            if (clutch != null) {
                history.put("clutch", clutch.result()); clutchDispatched |= clutch.dispatched();
                clutchUses += clutch.usesDispatched(); clutch.close(); clutch = null;
            }
            flight = new MinecraftElytraFlight(minecraft, session);
            return State.RECOVERING;
        }
        if (choice.response() == EmergencyRecoveryPolicy.Response.CLUTCH) {
            if (maxCamera - cameraTravel < 16) { failure = "recovery_camera_budget"; return State.EXHAUSTED; }
            stopMovement();
            if (combat != null) { combat.close(); }
            if (clutch == null) clutch = new MinecraftEmergencyClutch(minecraft, session, choice.item());
            clutch.tick(tick, allowed); history.put("clutch", clutch.result());
            if (clutch.failure() != null && !clutch.dispatched()) {
                unavailableItems.add(choice.item()); clutch.close(); clutch = null;
            }
            return State.RECOVERING;
        }
        if (clutch != null) {
            clutch.tick(tick, allowed);
            history.put("clutch", clutch.result()); clutchDispatched |= clutch.dispatched();
            if (clutch.evidenceSettled(tick)) {
                clutchUses += clutch.usesDispatched();
                clutch.close(); clutch = null;
            }
        }
        if (choice.response() == EmergencyRecoveryPolicy.Response.RETALIATE && hazard.threat() instanceof Mob mob) {
            if (combat == null) combat = new MinecraftEmergencyCombat(minecraft, session);
            combat.tick(mob, tick, Math.max(0, maxInteractions - flightUses - clutchUses - warpUses),
                    Math.max(0, maxCamera - cameraTravel), allowed);
            history.put("combat", combat.result());
            if (combat.cameraBlocked()) strategy = "retreat_camera_budget";
            // Running away while aiming keeps a pursuing melee mob out of reach indefinitely.
            if (combat.readyToFight() && player.onGround() && !player.isInWater()) {
                stableTicks = 0;
                stopMovement();
                return State.RECOVERING;
            }
        } else if (combat != null) { history.put("combat", combat.result()); combat.close(); }
        if (!warpAttempted && choice.response() == EmergencyRecoveryPolicy.Response.RETREAT
                && player.onGround() && !McmcpClientConfig.recoveryWaystoneDestination().isBlank()
                && McmcpClientConfig.emergencyItemUse() && remainingUses >= 2 && items.contains(V2WaystoneArguments.ITEM)) {
            warpAttempted = true;
            warpUses = 2;
            if (combat != null) { combat.close(); }
            warp = new MinecraftEmergencyWaystone(minecraft, session, sessions);
            return State.RECOVERING;
        }
        boolean settledBoat = player.getVehicle() instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat boat
                && boat.isAlive() && (boat.onGround() || boat.isInWater())
                && boat.getDeltaMovement().horizontalDistance() < 0.08 && !player.isUnderWater();
        boolean contactCleared = contactObstruction == null || !contactObstruction.isAlive()
                || !player.hasLineOfSight(contactObstruction)
                || EmergencyRecoveryPolicy.contactCleared(player.getBoundingBox(), contactObstruction.getBoundingBox());
        if (!contactCleared) {
            threatOrigin = contactObstruction.position();
            if (hazard.danger() == EmergencyRecoveryPolicy.Danger.NONE) strategy = "separating_from_mob";
        }
        boolean stable = contactCleared && hazard.danger() == EmergencyRecoveryPolicy.Danger.NONE
                && (player.onGround() && !player.isInWater() || settledBoat)
                && !player.isOnFire() && player.getHealth() > 6;
        stableTicks = stable ? stableTicks + 1 : 0;
        if (stableTicks >= McmcpClientConfig.recoverySafeTicks()) return State.STABLE;
        if (stable || choice.response() == EmergencyRecoveryPolicy.Response.LAND_IN_WATER) { stopMovement(); return State.RECOVERING; }
        var candidate = candidates.get().stream().filter(MinecraftRecoveryGovernor.Candidate::knownNonWorsening)
                .filter(option -> hazard.danger() != EmergencyRecoveryPolicy.Danger.WATER
                        || option.kind() == MinecraftRecoveryGovernor.CandidateKind.REACH_BREATHING_SPACE)
                .sorted(Comparator.comparingInt((MinecraftRecoveryGovernor.Candidate option) -> option.reachesStableState() ? 0 : 1)
                        .thenComparingDouble(MinecraftRecoveryGovernor.Candidate::goalDeviation)).findFirst().orElse(null);
        if (candidate == null) { stopMovement(); strategy = "waiting_for_observed_retreat"; return State.RECOVERING; }
        if (movement == null) movement = MovementInputLease.acquire(minecraft, owner, System.nanoTime(), HORIZON);
        movement.setDesired(owner, candidate.movement());
        if (!movement.heartbeat(owner, System.nanoTime(), HORIZON)) { failure = "recovery_input_expired"; return State.EXHAUSTED; }
        AgentInputState.global().requireRecoveryMovementSafety(player, level, revision,
                Math.max(0, McmcpClientConfig.recoveryMaxDistance() - travelled), candidate.intent());
        return State.RECOVERING;
    }
    Vec3 threatOrigin() { return threatOrigin; }
    private int combatUses() { return combat == null ? 0 : combat.attacksDispatched(); }
    boolean allowsScreenChange() { return warp != null; }
    Map<String, Object> result(long tick, String status) {
        var result = new LinkedHashMap<String, Object>(history);
        result.put("status", status); result.put("reason", reason.name().toLowerCase(java.util.Locale.ROOT));
        result.put("strategy", strategy); result.put("elapsed_ticks", tick - started);
        result.put("distance", travelled); result.put("stable_ticks", stableTicks);
        result.put("camera_degrees", cameraTravel);
        result.put("interactions_dispatched_or_reserved", flightUses + clutchUses + warpUses + combatUses()
                + (flight == null ? 0 : flight.usesDispatched()) + (clutch == null ? 0 : clutch.usesDispatched()));
        if (failure != null) result.put("failure", failure);
        return Map.copyOf(result);
    }
    private void stopMovement() { if (movement != null) { movement.close(owner); movement = null; } }
    @Override public void close() {
        stopMovement();
        if (combat != null) { combat.close(); }
        if (clutch != null) { clutch.close(); clutch = null; }
        if (flight != null) { flight.close(); flight = null; }
        if (warp != null) { warp.close(); warp = null; }
    }
}
