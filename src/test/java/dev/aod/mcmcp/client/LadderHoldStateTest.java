package dev.aod.mcmcp.client;

import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LadderHoldStateTest {
    @Test
    void holdBridgesReleasedActionsWithoutBlockingTheNextMovementOrManualTakeover() {
        var hold = new LadderHoldState();
        var player = new Object();
        var level = new Object();
        var input = new AgentInputState();
        hold.acquire(player, level);
        hold.validate(player, level, true, true);
        assertThat(AgentMovementInput.resolve(Input.EMPTY, Vec2.ZERO, input.movementSnapshot(),
                hold.active(player, level)).keyPresses().shift()).isTrue();
        input.publishMovement(false, false, false, false, true, false);
        var climbing = AgentMovementInput.resolve(Input.EMPTY, Vec2.ZERO, input.movementSnapshot(), true);
        assertThat(climbing.keyPresses().jump()).isTrue();
        assertThat(climbing.keyPresses().shift()).isFalse();
        input.releaseMovement();
        assertThat(AgentMovementInput.resolve(Input.EMPTY, Vec2.ZERO, input.movementSnapshot(), true)
                .keyPresses().shift()).isTrue();
        var manual = new Input(true, false, false, false, false, false, false);
        hold.physicalInput(manual, false);
        assertThat(hold.active(player, level)).isFalse();
        assertThat(AgentMovementInput.resolve(manual, Vec2.ONE, input.movementSnapshot(), false)
                .keyPresses()).isSameAs(manual);
    }

    @Test
    void offLostLadderPlayerAndWorldBoundariesRemoveTheRestingOwner() {
        var hold = new LadderHoldState();
        var player = new Object();
        var level = new Object();
        for (int boundary = 0; boundary < 4; boundary++) {
            hold.acquire(player, level);
            hold.validate(boundary == 0 ? new Object() : player, boundary == 1 ? new Object() : level,
                    boundary != 2, boundary != 3);
            assertThat(hold.active(player, level)).isFalse();
        }
    }
}
