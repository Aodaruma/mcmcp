package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.client.AgentInputState;
import dev.aod.mcmcp.routine.MovementInputLease;
import dev.aod.mcmcp.routine.NavigationViewLease;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Ordinary equip, jump-to-glide and firework use with fresh forward collision witnesses. */
final class MinecraftElytraFlight implements AutoCloseable {
    enum Result { RUNNING, LANDED, UNAVAILABLE }
    private static final Duration HORIZON = Duration.ofMillis(500);
    private final Minecraft minecraft;
    private final LocalPlayer player;
    private final Object level;
    private final UUID session, owner = UUID.randomUUID();
    private V2HeldItemSelection selection;
    private NavigationViewLease view;
    private MovementInputLease jump;
    private ClientPredictionSignals.PredictionAttempt use;
    private boolean equipping, equipDispatched, deployed, uncertainUse;
    private long usedTick, started = -1, lastTick, lastFlyingTick = -1;
    private int rockets, boostedTicks, settled, relaunches;
    private String failure;
    private ElytraFlightPlanner.Plan lastPlan;
    private ElytraLandingCourse landingCourse;
    private long landingCourseStarted;
    private double observationRadius;
    private int observationCells;
    private Vec3 landingTarget;
    private boolean landingReserved;
    private int observedFlyingTicks, plannedFlyingTicks, noPlanTicks;
    private Map<String, Object> lastNoPlan;

    MinecraftElytraFlight(Minecraft minecraft, UUID session) {
        this.minecraft = minecraft; this.session = session;
        player = minecraft.player; level = minecraft.level;
    }

    static boolean usableElytra(ItemStack stack) {
        return stack.is(Items.ELYTRA) && LivingEntity.canGlideUsing(stack, EquipmentSlot.CHEST)
                && (!stack.isDamageableItem() || stack.getMaxDamage() - stack.getDamageValue() > 10);
    }
    static boolean safeRocket(ItemStack stack) {
        var fireworks = stack.get(DataComponents.FIREWORKS);
        return stack.is(Items.FIREWORK_ROCKET) && !stack.isEmpty() && fireworks != null
                && fireworks.explosions().isEmpty() && fireworks.flightDuration() <= 1;
    }
    static boolean available(LocalPlayer player) {
        if (usableElytra(player.getItemBySlot(EquipmentSlot.CHEST))) return true;
        for (int slot = 0; slot < 36; slot++) if (usableElytra(player.getInventory().getItem(slot))) return true;
        return false;
    }
    static boolean rocketsAvailable(LocalPlayer player) {
        for (int slot = 0; slot < 36; slot++) if (safeRocket(player.getInventory().getItem(slot))) return true;
        return false;
    }

    Result tick(Vec3 goal, long tick, int rocketLimit, BooleanSupplier allowed) {
        return tick(goal, null, tick, rocketLimit, false, Long.MAX_VALUE,
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, allowed);
    }

    Result tick(Vec3 goal, Vec3 threat, long tick, int rocketLimit, boolean rescue,
                long remainingTicks, double remainingDistance, double remainingCamera, BooleanSupplier allowed) {
        lastTick = tick;
        if (failure != null) return Result.UNAVAILABLE;
        if (started < 0) started = tick;
        if (!allowed.getAsBoolean() || minecraft.player != player || minecraft.level != level
                || player.isDeadOrDying() || player.isPassenger() || player.isInLava()
                || player.containerMenu != player.inventoryMenu || !player.inventoryMenu.getCarried().isEmpty())
            return fail("flight_context_changed");
        if (boostedTicks > 0) boostedTicks--;
        var observation = new FlightObservation(minecraft);
        landingReserved |= ElytraFlightPlanner.reserveLanding(player.position(), player.getDeltaMovement(),
                landingTarget == null ? goal : landingTarget, remainingTicks, remainingDistance, remainingCamera);
        int latency = 4;
        if (player.isFallFlying()) {
            var info = minecraft.getConnection().getPlayerInfo(player.getUUID());
            if (info != null) latency = Math.max(4, (info.getLatency() + 49) / 50 + 2);
        }
        BooleanSupplier landingAllowed = null;
        int provenCourseTicks = 0;
        if (rescue && player.isFallFlying() && boostedTicks == 0 && landingCourse != null
                && landingCourse.goal().equals(landingTarget)) {
            landingAllowed = ElytraLandingCourse.boundedSearch(allowed);
            double landingHeight = landingTarget.y;
            var proven = landingCourse.proveOrReplan(player.position(), player.getDeltaMovement(),
                    player.getYRot(), player.getXRot(), player.getGravity(),
                    (int) Math.min(Integer.MAX_VALUE, tick - landingCourseStarted), latency,
                    remainingTicks, remainingDistance, Math.max(0, remainingCamera - 16), observation::clear,
                    (body, motion) -> observation.stoppedLanding(body, motion, landingHeight), landingAllowed);
            if (proven == null) landingCourse = null;
            else {
                if (landingCourse != proven.course()) landingCourseStarted = tick;
                landingCourse = proven.course();
                provenCourseTicks = proven.verifiedTicks();
            }
        }
        if (rescue || landingReserved) {
            // The intent point may be hidden while every actual stopping support is visible.
            // Only this tick's complete course proof can replace its ordinary point check.
            if ((!ElytraLandingCourse.retainsProvenGoal(player.position(), landingTarget, threat, provenCourseTicks)
                        && !observation.supportedLanding(landingTarget))
                    || rescue && !ElytraLandingCourse.descendingGoal(player.position(), landingTarget)
                    || threat != null && landingTarget.distanceToSqr(threat) < 36) {
                landingTarget = !rescue && observation.supportedLanding(goal) ? goal : observation.findLanding(goal, threat, rescue);
            }
            if (landingTarget != null) goal = landingTarget;
        }
        if (use != null) {
            if (use.acknowledgement().acknowledged()) {
                use.close(); use = null;
                if (equipping) { releaseSelection(); equipping = false; }
            } else if (tick - usedTick > 80) {
                uncertainUse = true;
                return fail("flight_item_ack_missing");
            }
        }
        if (!usableElytra(player.getItemBySlot(EquipmentSlot.CHEST))) {
            if (deployed || equipping || uncertainUse) return equipping ? Result.RUNNING : fail("elytra_unavailable");
            if (equipDispatched) return fail("elytra_equip_unconfirmed");
            if (!select("minecraft:elytra", MinecraftElytraFlight::usableElytra, tick)) return Result.RUNNING;
            equipping = true;
            equipDispatched = true;
            sendUse(tick);
            return Result.RUNNING;
        }
        if (!player.isFallFlying()) {
            if (deployed) {
                settled = player.onGround() || player.isInWater() ? settled + 1 : 0;
                if (settled >= 3) {
                    close();
                    if (!rescue && !landingReserved && player.onGround()
                            && player.position().distanceTo(goal) > 8 && relaunches < 3) {
                        relaunches++; deployed = false; settled = 0; started = tick;
                        return Result.RUNNING;
                    }
                    return Result.LANDED;
                }
                if (settled == 0 && tick - lastFlyingTick > 80) return fail("flight_ended_aloft");
                return Result.RUNNING;
            }
            if (tick - started > 100 || player.isInWater()) return fail("elytra_deploy_failed");
            if (player.onGround() && !new FlightObservation(minecraft).clear(player.getBoundingBox().move(0, 1, 0)))
                return fail("elytra_takeoff_obstructed");
            float launchYaw = ElytraFlightPlanner.launchYaw(player.position(), goal, player.getYRot());
            if (player.onGround() && !observation.clear(player.getBoundingBox().move(0, 1, 0)
                    .expandTowards(Vec3.directionFromRotation(0, launchYaw).scale(12))))
                return fail("elytra_takeoff_corridor_unobserved");
            if (view == null) view = NavigationViewLease.acquire(player, owner);
            if (!view.matches(owner)) return fail("flight_view_changed");
            if (remainingCamera < 16) return fail("flight_deploy_camera_budget");
            view.turnToward(owner, launchYaw, 0);
            AgentInputState.global().allowElytraDeployment(player, level, System.nanoTime() + HORIZON.toNanos());
            if (jump == null) jump = MovementInputLease.acquire(minecraft, owner, System.nanoTime(), HORIZON);
            // One released tick between presses is required by Vanilla's double-jump deployment.
            var launchKeys = java.util.EnumSet.noneOf(MovementInputLease.MovementKey.class);
            if (tick % 4 == 0) launchKeys.add(MovementInputLease.MovementKey.JUMP);
            var forward = Vec3.directionFromRotation(0, player.getYRot()).scale(0.6);
            if (Math.abs(net.minecraft.util.Mth.wrapDegrees(launchYaw - player.getYRot())) < 8
                    && new FlightObservation(minecraft).clear(player.getBoundingBox().move(0, 0.01, 0).expandTowards(forward)))
                launchKeys.add(MovementInputLease.MovementKey.FORWARD);
            jump.setDesired(owner, launchKeys);
            if (!jump.heartbeat(owner, System.nanoTime(), HORIZON)) return fail("flight_jump_expired");
            return Result.RUNNING;
        }
        deployed = true;
        observedFlyingTicks++;
        lastFlyingTick = tick;
        if (jump != null) { jump.close(owner); jump = null; }
        AgentInputState.global().clearElytraDeployment(player);
        if (equipping) return Result.RUNNING;
        boolean wantBoost = !landingReserved && rockets < rocketLimit && boostedTicks == 0 && use == null && !uncertainUse
                && latency <= 20
                && player.position().distanceTo(goal) > 8 && player.getDeltaMovement().length() < 1.6
                && rocketsAvailable(player);
        if (wantBoost && !select("minecraft:firework_rocket", MinecraftElytraFlight::safeRocket, tick)) wantBoost = false;
        int newBoostTicks = wantBoost ? 10 * (1 + player.getMainHandItem().get(DataComponents.FIREWORKS).flightDuration()) + 11 : 0;
        wantBoost &= ElytraFlightPlanner.needsBoost(player.position(), goal, newBoostTicks, latency);
        ElytraFlightPlanner.Plan plan = null;
        if (rescue && landingTarget != null && boostedTicks == 0) {
            if (landingAllowed == null) landingAllowed = ElytraLandingCourse.boundedSearch(allowed);
            double landingHeight = landingTarget.y;
            java.util.function.BiPredicate<net.minecraft.world.phys.AABB, Vec3> stoppedLanding =
                    (body, motion) -> observation.stoppedLanding(body, motion, landingHeight);
            if (landingCourse != null && !landingCourse.goal().equals(landingTarget)) landingCourse = null;
            int elapsed = (int) Math.min(Integer.MAX_VALUE, tick - landingCourseStarted);
            int verified = landingCourse == null ? 0 : provenCourseTicks;
            // Every course needs the current padded body to be clear. Do not spend its finite
            // search while still under a lip; retry fresh evidence next tick, including after
            // the shared search deadline expires, instead of missing a brief admission window.
            if (landingCourse == null && observation.clear(player.getBoundingBox().inflate(.2))) {
                var selected = ElytraLandingCourse.select(player.position(), player.getDeltaMovement(),
                        player.getYRot(), player.getXRot(), landingTarget, player.getGravity(), latency,
                        remainingTicks, remainingDistance, Math.max(0, remainingCamera - 16),
                        observation::clear, stoppedLanding, landingAllowed);
                landingCourse = selected == null ? null : selected.course();
                verified = selected == null ? 0 : selected.verifiedTicks();
                landingCourseStarted = tick;
                elapsed = 0;
            }
            if (landingCourse != null && verified > 0) {
                landingReserved = ElytraFlightPlanner.reserveLanding(landingReserved, verified);
                plan = new ElytraFlightPlanner.Plan(landingCourse.yaw(), landingCourse.pitch(elapsed), false,
                        verified, verified, 0, true);
            }
        }
        if (plan == null) plan = wantBoost ? ElytraFlightPlanner.plan(player.position(), player.getDeltaMovement(),
                player.getYRot(), player.getXRot(), goal, player.getGravity(), newBoostTicks, true, latency,
                observation::clear, observation::landing, Math.max(0, remainingCamera - 16)) : null;
        if (plan == null) plan = ElytraFlightPlanner.plan(player.position(), player.getDeltaMovement(),
                player.getYRot(), player.getXRot(), goal, player.getGravity(), boostedTicks, false, latency,
                observation::clear, observation::landing, Math.max(0, remainingCamera - 16));
        observationRadius = observation.radius(); observationCells = observation.sampledCells();
        lastPlan = plan;
        if (plan == null) {
            noPlanTicks++;
            lastNoPlan = Map.of("tick", tick, "position", vector(player.position()),
                    "velocity", vector(player.getDeltaMovement()), "in_water", player.isInWater(),
                    "underwater", player.isUnderWater(), "observation", observation.diagnostic());
        } else plannedFlyingTicks++;
        // A short launch can settle back onto its platform before clearing the edge.
        // Permit only a witnessed low-speed landing, then retry from that supported position.
        if (plan == null && boostedTicks == 0
                && (observation.landing(player.getBoundingBox().move(player.getDeltaMovement()), player.getDeltaMovement())
                    || player.onGround() && player.getDeltaMovement().length() < .65
                        && observation.clear(player.getBoundingBox().move(0, .21, 0))))
            return Result.RUNNING;
        // Gliding continues physically after a rejected corridor; reobserve within the rescue budget without turning or boosting.
        if (plan == null && rescue) return Result.RUNNING;
        if (plan == null) return fail("flight_no_observed_escape_corridor");
        if (!allowed.getAsBoolean()) return fail("flight_context_changed");
        if (view == null) view = NavigationViewLease.acquire(player, owner);
        if (!view.matches(owner)) return fail("flight_view_changed");
        // Even with no turns left, keep observing a witnessed coast until landing or the other finite limits.
        if (remainingCamera >= 16) view.turnToward(owner, plan.yaw(), plan.pitch());
        if (plan.boost() && plan.fullyVerified() && use == null && safeRocket(player.getMainHandItem())) {
            rockets++;
            boostedTicks = newBoostTicks;
            sendUse(tick);
        }
        return Result.RUNNING;
    }

    private boolean select(String item, java.util.function.Predicate<ItemStack> filter, long tick) {
        if (selection != null && (!selection.stillSelected() || !selection.matchesRequested())) releaseSelection();
        if (selection == null) {
            if (view != null) { view.close(owner); view = null; }
            selection = new V2HeldItemSelection(minecraft, session, item, filter);
            if (!selection.begin(tick, false)) { releaseSelection(); failure = "flight_item_missing"; return false; }
        }
        var state = selection.poll(tick);
        if (state == V2InventorySwap.Result.FAILED) { failure = "flight_item_swap_failed"; return false; }
        return state == V2InventorySwap.Result.CONFIRMED && selection.matchesRequested();
    }

    private void sendUse(long tick) {
        use = ClientPredictionSignals.global().begin(minecraft.level, player.blockPosition(), tick);
        usedTick = tick;
        int before = use.sequenceBeforePrediction();
        uncertainUse = true;
        minecraft.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        if (use.captureIssuedPredictions() != before + 1) throw new IllegalStateException("flight item prediction missing");
        uncertainUse = false;
    }
    private Result fail(String reason) { failure = reason; return Result.UNAVAILABLE; }
    String failure() { return failure; }
    int usesDispatched() { return rockets + (equipDispatched ? 1 : 0); }
    Map<String, Object> result() {
        var result = new java.util.LinkedHashMap<String, Object>(Map.of("mode", "elytra", "rockets_dispatched", rockets, "deployed", deployed, "relaunches", relaunches,
                "observation_radius", observationRadius, "observation_cells", observationCells,
                "verified_flight_ticks", lastPlan == null ? 0 : lastPlan.verifiedTicks(),
                "prediction_horizon_ticks", lastPlan == null ? 0 : lastPlan.horizon(),
                "failure", failure == null ? "none" : failure));
        result.put("landing_reserved", landingReserved);
        result.put("observed_flying_ticks", observedFlyingTicks);
        result.put("planned_flying_ticks", plannedFlyingTicks);
        result.put("no_plan_ticks", noPlanTicks);
        if (lastNoPlan != null) result.put("last_no_plan", lastNoPlan);
        if (landingTarget != null) result.put("landing_target", Map.of("x", landingTarget.x, "y", landingTarget.y, "z", landingTarget.z));
        return Map.copyOf(result);
    }
    private static Map<String, Double> vector(Vec3 value) {
        return Map.of("x", value.x, "y", value.y, "z", value.z);
    }
    private void releaseSelection() {
        if (view != null) { view.close(owner); view = null; }
        if (selection != null) { selection.close(); selection = null; }
    }
    @Override public void close() {
        if (jump != null) { jump.close(owner); jump = null; }
        AgentInputState.global().clearElytraDeployment(player);
        releaseSelection();
        if (use != null) { use.close(); use = null; }
    }
}
