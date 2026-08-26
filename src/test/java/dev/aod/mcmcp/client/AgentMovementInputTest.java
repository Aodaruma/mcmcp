package dev.aod.mcmcp.client;

import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AgentMovementInputTest {
    @Test
    void unownedStateLeavesVanillaPhysicalInputUntouched() {
        var physicalKeys = new Input(true, false, false, false, true, true, true);
        var physicalMovement = new Vec2(0.25F, 0.75F);

        var resolved = AgentMovementInput.resolve(
                physicalKeys,
                physicalMovement,
                new AgentInputState().movementSnapshot());

        assertThat(resolved.keyPresses()).isSameAs(physicalKeys);
        assertThat(resolved.moveVector()).isSameAs(physicalMovement);
    }

    @Test
    void ownedStateReplacesPhysicalKeysAndBuildsTheFinalNormalizedVector() {
        var state = new AgentInputState();
        state.publishMovement(true, false, false, true, true);

        var resolved = AgentMovementInput.resolve(
                new Input(false, true, true, false, false, true, true),
                new Vec2(1.0F, -1.0F),
                state.movementSnapshot());

        assertThat(resolved.keyPresses())
                .isEqualTo(new Input(true, false, false, true, true, false, false));
        assertThat(resolved.moveVector().x).isCloseTo(-0.70710677F, within(0.000001F));
        assertThat(resolved.moveVector().y).isCloseTo(0.70710677F, within(0.000001F));
    }

    @Test
    void pausedOwnedStateOverridesPhysicalInputWithNeutralValues() {
        var state = new AgentInputState();
        state.publishMovement(true, false, false, false, false);
        state.setPaused(true, 10L);

        var resolved = AgentMovementInput.resolve(
                new Input(true, false, true, false, true, true, true),
                Vec2.ONE,
                state.movementSnapshot());

        assertThat(resolved.keyPresses()).isEqualTo(Input.EMPTY);
        assertThat(resolved.moveVector()).isEqualTo(Vec2.ZERO);
    }
}
