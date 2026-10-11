package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.agent.navigation.NavigationDistanceBudget;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.BiPredicate;

/** Conservative endpoint geometry; neither grants input permission nor observes hidden blocks. */
public final class V2BlockApproach {
    public static final double POSITION_TOLERANCE = 0.35D;

    private V2BlockApproach() { }

    public static boolean operable(NavCell cell, double eyeHeight, double reach,
            List<Vec3> points, BiPredicate<Vec3, Vec3> clearRay) {
        if (!Double.isFinite(eyeHeight) || eyeHeight <= 0 || !Double.isFinite(reach) || reach <= 0) return false;
        double limit = Math.min(reach, V2BlockAimResolver.MAX_REACH);
        for (var hit : points) {
            // The movement kernel accepts fractional support heights within the destination cell.
            var lowEye = new Vec3(cell.x() + 0.5D, cell.y() + eyeHeight, cell.z() + 0.5D);
            var highEye = lowEye.add(0, NavigationDistanceBudget.MAX_VERTICAL_POSITION_ERROR, 0);
            double horizontal = Math.hypot(lowEye.x - hit.x, lowEye.z - hit.z) + POSITION_TOLERANCE;
            double vertical = Math.max(Math.abs(lowEye.y - hit.y), Math.abs(highEye.y - hit.y));
            if (horizontal * horizontal + vertical * vertical <= limit * limit
                    && clearRay.test(lowEye, hit)) return true;
        }
        return false;
    }
}
