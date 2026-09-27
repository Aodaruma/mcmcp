package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.Code;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.PlanningException;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.Pose;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.agent.observation.ObservationRecord;
import dev.aod.mcmcp.agent.observation.ObservationValues;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

import static dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.CAMERA_QUANTIZATION_RESERVE_DEGREES;
import static dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.WAIT_WITNESS_EYE_EPSILON_BLOCKS;

/** 姿勢の不確かさを含め、視点・ray・到達距離・角度を計算する。 */
final class AgentPlannerGeometry {
    private AgentPlannerGeometry() {
    }

    static final double MAX_BREAK_REACH_BLOCKS = 4.5D;

    static final double MAX_BREAK_EYE_ORIGIN_DRIFT = 0.125D;

    static boolean directlyAbove(
            ActionDsl.Position target, ActionDsl.Position support) {
        return target.dimension().equals(support.dimension())
                && target.x() == support.x()
                && target.y() == support.y() + 1
                && target.z() == support.z();
    }

    static ActionDsl.Position offset(
            ActionDsl.Position origin, int x, int y, int z) {
        return new ActionDsl.Position(
                origin.dimension(),
                Math.addExact(origin.x(), x),
                Math.addExact(origin.y(), y),
                Math.addExact(origin.z(), z));
    }

    static boolean mutationSurfaceValid(
            Pose pose, ObservationRecord.VisibleSurface surface) {
        return mutationSurfaceValid(pose, surface, MAX_BREAK_REACH_BLOCKS);
    }

    static boolean mutationSurfaceValid(
            Pose pose, ObservationRecord.VisibleSurface surface, double maxAimDistance) {
        var eye = surface.eyeOrigin();
        if (!eye.dimension().value().equals(pose.cell().dimension())) {
            return false;
        }
        double poseEyeY = pose.y() + pose.eyeHeight();
        if (Math.hypot(eye.x() - pose.x(), eye.z() - pose.z())
                        > pose.horizontalPositionError() + MAX_BREAK_EYE_ORIGIN_DRIFT
                || Math.abs(eye.y() - poseEyeY)
                        > Math.max(pose.yErrorBelow(), pose.yErrorAbove())
                                + MAX_BREAK_EYE_ORIGIN_DRIFT) {
            return false;
        }
        var hit = surface.rayHit();
        double distance = Math.sqrt(
                square(hit.x() - pose.x())
                        + square(hit.y() - poseEyeY)
                        + square(hit.z() - pose.z()));
        double poseError = Math.hypot(
                pose.horizontalPositionError(),
                Math.max(pose.yErrorBelow(), pose.yErrorAbove()));
        return distance + poseError <= maxAimDistance;
    }

    static Vec3 supportFaceCenter(
            ActionDsl.Position position, ActionDsl.BlockFace face) {
        double x = position.x() + 0.5D;
        double y = position.y() + 0.5D;
        double z = position.z() + 0.5D;
        return switch (face) {
            case DOWN -> new Vec3(x, position.y(), z);
            case UP -> new Vec3(x, position.y() + 1.0D, z);
            case NORTH -> new Vec3(x, y, position.z());
            case SOUTH -> new Vec3(x, y, position.z() + 1.0D);
            case WEST -> new Vec3(position.x(), y, z);
            case EAST -> new Vec3(position.x() + 1.0D, y, z);
        };
    }

    static boolean interactionPointReachable(Pose pose, Vec3 point) {
        double eyeY = pose.y() + pose.eyeHeight();
        double distance = Math.sqrt(
                square(point.x - pose.x())
                        + square(point.y - eyeY)
                        + square(point.z - pose.z()));
        double poseError = Math.hypot(
                pose.horizontalPositionError(),
                Math.max(pose.yErrorBelow(), pose.yErrorAbove()));
        return distance + poseError <= MAX_BREAK_REACH_BLOCKS;
    }

    static boolean waitWitnessOriginMatches(
            Pose pose, ObservationValues.WorldPosition eyeOrigin) {
        if (!eyeOrigin.dimension().value().equals(pose.cell().dimension())) {
            return false;
        }
        double epsilonSquared = WAIT_WITNESS_EYE_EPSILON_BLOCKS
                * WAIT_WITNESS_EYE_EPSILON_BLOCKS;
        return square(eyeOrigin.x() - pose.x())
                + square(eyeOrigin.y() - (pose.y() + pose.eyeHeight()))
                + square(eyeOrigin.z() - pose.z()) <= epsilonSquared;
    }

    static double distanceSquared(
            Pose pose, ObservationValues.WorldPosition point) {
        return square(point.x() - pose.x())
                + square(point.y() - (pose.y() + pose.eyeHeight()))
                + square(point.z() - pose.z());
    }

    static Vec3 rayHit(ObservationRecord.VisibleSurface surface) {
        var hit = Objects.requireNonNull(surface.rayHit(), "rayHit");
        return new Vec3(hit.x(), hit.y(), hit.z());
    }

    static void requireBreakPose(
            Pose pose,
            ObservationRecord.VisibleSurface surface,
            Vec3 point) {
        var observedEye = surface.eyeOrigin();
        double poseEyeY = pose.y() + pose.eyeHeight();
        double horizontalDrift = Math.hypot(
                observedEye.x() - pose.x(), observedEye.z() - pose.z());
        double verticalDrift = Math.abs(observedEye.y() - poseEyeY);
        if (horizontalDrift
                        > pose.horizontalPositionError() + MAX_BREAK_EYE_ORIGIN_DRIFT
                || verticalDrift
                        > Math.max(pose.yErrorBelow(), pose.yErrorAbove())
                                + MAX_BREAK_EYE_ORIGIN_DRIFT) {
            throw new PlanningException(
                    Code.TARGET_UNKNOWN,
                    "Visible break face was not observed from the planned interaction pose");
        }
        double nominalDistance = Math.sqrt(
                square(point.x - pose.x())
                        + square(point.y - poseEyeY)
                        + square(point.z - pose.z()));
        double poseError = Math.hypot(
                pose.horizontalPositionError(),
                Math.max(pose.yErrorBelow(), pose.yErrorAbove()));
        if (nominalDistance + poseError > MAX_BREAK_REACH_BLOCKS) {
            throw new PlanningException(
                    Code.TARGET_UNKNOWN,
                    "Visible break face is outside the proven interaction reach");
        }
    }

    static Aim aim(Pose pose, ActionDsl.Position target) {
        return aim(pose, new Vec3(
                target.x() + 0.5D, target.y() + 0.5D, target.z() + 0.5D));
    }

    static Aim aim(Pose pose, Vec3 target) {
        double dx = target.x - pose.x();
        double dy = target.y - (pose.y() + pose.eyeHeight());
        double dz = target.z - pose.z();
        double horizontal = Math.hypot(dx, dz);
        if (horizontal < 1.0e-9D && Math.abs(dy) < 1.0e-9D) {
            throw new PlanningException(Code.TARGET_UNKNOWN, "Face target coincides with the eye position");
        }
        return new Aim(
                (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D),
                (float) -Math.toDegrees(Math.atan2(dy, horizontal)));
    }

    static AimError aimError(Pose pose, ActionDsl.Position target, Aim nominal) {
        return aimError(pose, new Vec3(
                target.x() + 0.5D, target.y() + 0.5D, target.z() + 0.5D), nominal);
    }

    static AimError aimError(Pose pose, Vec3 target, Aim nominal) {
        double dx = target.x - pose.x();
        double dy = target.y - (pose.y() + pose.eyeHeight());
        double dz = target.z - pose.z();
        double horizontal = Math.hypot(dx, dz);
        double horizontalError = pose.horizontalPositionError();
        double yawError = horizontal <= horizontalError
                ? 180.0D
                : Math.toDegrees(Math.asin(Math.min(1.0D, horizontalError / horizontal)));

        double dyMin = dy - pose.yErrorAbove();
        double dyMax = dy + pose.yErrorBelow();
        double horizontalMin = Math.max(0.0D, horizontal - horizontalError);
        double horizontalMax = horizontal + horizontalError;
        double pitchError;
        if (horizontalMin == 0.0D && dyMin <= 0.0D && dyMax >= 0.0D) {
            pitchError = Math.max(
                    Math.abs(-90.0D - nominal.pitch()),
                    Math.abs(90.0D - nominal.pitch()));
        } else {
            pitchError = 0.0D;
            for (double candidateDy : new double[] {dyMin, dyMax}) {
                for (double candidateHorizontal : new double[] {horizontalMin, horizontalMax}) {
                    double candidate = -Math.toDegrees(
                            Math.atan2(candidateDy, candidateHorizontal));
                    pitchError = Math.max(pitchError, Math.abs(candidate - nominal.pitch()));
                }
            }
        }
        return new AimError(yawError, pitchError);
    }

    static double square(double value) {
        return value * value;
    }

    static double angularError(
            float yaw, float pitch, float desiredYaw, float desiredPitch) {
        return Math.abs(Mth.wrapDegrees((double) desiredYaw - yaw))
                + Math.abs((double) Mth.clamp(desiredPitch, -90.0F, 90.0F) - pitch);
    }

    static double withCameraQuantizationReserve(double geometricDegrees) {
        if (!Double.isFinite(geometricDegrees) || geometricDegrees < 0.0D) {
            throw new IllegalArgumentException("camera travel must be finite and non-negative");
        }
        return Math.min(360.0D,
                geometricDegrees + CAMERA_QUANTIZATION_RESERVE_DEGREES);
    }

    static NavCell navCell(ActionDsl.Position position) {
        Objects.requireNonNull(position, "position");
        return new NavCell(position.dimension(), position.x(), position.y(), position.z());
    }

    static int floor(double value) {
        double result = Math.floor(value);
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("position is outside the navigation coordinate range");
        }
        return (int) result;
    }

    record Aim(float yaw, float pitch) {
    }

    record AimError(double yawDegrees, double pitchDegrees) {
        AimError {
            if (!Double.isFinite(yawDegrees) || !Double.isFinite(pitchDegrees)
                    || yawDegrees < 0.0D || pitchDegrees < 0.0D) {
                throw new IllegalArgumentException("aim error must be finite and non-negative");
            }
        }

        double totalDegrees() {
            return Math.min(360.0D, yawDegrees + pitchDegrees);
        }
    }
}
