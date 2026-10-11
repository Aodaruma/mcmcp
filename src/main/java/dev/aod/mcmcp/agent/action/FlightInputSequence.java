package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.routine.MovementInputLease.MovementKey;
import java.util.EnumSet;
import java.util.Set;

/** Space also toggles vanilla flight: repeated ascent pulses must not form a double jump. */
final class FlightInputSequence {
    private long lastJumpStart = Long.MIN_VALUE;
    private boolean jumping;

    Set<MovementKey> apply(Set<MovementKey> desired, long tick) {
        var keys = desired.isEmpty() ? EnumSet.noneOf(MovementKey.class) : EnumSet.copyOf(desired);
        if (keys.contains(MovementKey.JUMP) && !jumping) {
            if (lastJumpStart != Long.MIN_VALUE && tick - lastJumpStart < 8) keys.remove(MovementKey.JUMP);
            else lastJumpStart = tick;
        }
        jumping = keys.contains(MovementKey.JUMP);
        return Set.copyOf(keys);
    }
}
