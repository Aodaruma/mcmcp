package dev.aod.mcmcp.agent.safety;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure decomposition of Vanilla's deterministic axis-ordered collision result. */
public final class SweptAabbPath {
    private SweptAabbPath() {
    }

    /**
     * Builds independent axis sweeps. The broad-phase envelope must never replace this list.
     * Horizontal order follows Vanilla 26.2: Y-Z-X only when |x| &lt; |z|, otherwise Y-X-Z.
     */
    public static List<AxisSegment> segments(
            AABB start,
            Vec3 intendedDelta,
            Vec3 resolvedDelta) {
        Objects.requireNonNull(start, "start");
        requireFinite(Objects.requireNonNull(intendedDelta, "intendedDelta"));
        requireFinite(Objects.requireNonNull(resolvedDelta, "resolvedDelta"));

        var order = Math.abs(intendedDelta.x) < Math.abs(intendedDelta.z)
                ? List.of(Axis.Y, Axis.Z, Axis.X)
                : List.of(Axis.Y, Axis.X, Axis.Z);
        var result = new ArrayList<AxisSegment>(3);
        var cursor = start;
        for (var axis : order) {
            double amount = axis.component(resolvedDelta);
            if (amount == 0.0D) {
                continue;
            }
            var delta = axis.vector(amount);
            var end = cursor.move(delta);
            result.add(new AxisSegment(axis, cursor, cursor.expandTowards(delta), end, amount));
            cursor = end;
        }
        return List.copyOf(result);
    }

    public static List<Axis> axisOrder(Vec3 intendedDelta) {
        requireFinite(Objects.requireNonNull(intendedDelta, "intendedDelta"));
        return Math.abs(intendedDelta.x) < Math.abs(intendedDelta.z)
                ? List.of(Axis.Y, Axis.Z, Axis.X)
                : List.of(Axis.Y, Axis.X, Axis.Z);
    }

    private static void requireFinite(Vec3 vector) {
        if (!Double.isFinite(vector.x)
                || !Double.isFinite(vector.y)
                || !Double.isFinite(vector.z)) {
            throw new IllegalArgumentException("movement delta must be finite");
        }
    }

    public enum Axis {
        X {
            @Override double component(Vec3 vector) { return vector.x; }
            @Override Vec3 vector(double amount) { return new Vec3(amount, 0.0D, 0.0D); }
        },
        Y {
            @Override double component(Vec3 vector) { return vector.y; }
            @Override Vec3 vector(double amount) { return new Vec3(0.0D, amount, 0.0D); }
        },
        Z {
            @Override double component(Vec3 vector) { return vector.z; }
            @Override Vec3 vector(double amount) { return new Vec3(0.0D, 0.0D, amount); }
        };

        abstract double component(Vec3 vector);

        abstract Vec3 vector(double amount);
    }

    public record AxisSegment(
            Axis axis,
            AABB start,
            AABB swept,
            AABB end,
            double delta) {
        public AxisSegment {
            Objects.requireNonNull(axis, "axis");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(swept, "swept");
            Objects.requireNonNull(end, "end");
            if (!Double.isFinite(delta) || delta == 0.0D) {
                throw new IllegalArgumentException("axis segment delta must be finite and non-zero");
            }
        }
    }
}
