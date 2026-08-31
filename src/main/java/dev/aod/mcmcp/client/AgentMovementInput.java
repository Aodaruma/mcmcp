package dev.aod.mcmcp.client;

import net.minecraft.client.Options;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

import java.util.Objects;

/** Keyboard input adapter whose final values can be replaced by an agent command after polling. */
public final class AgentMovementInput extends KeyboardInput {
    private AgentMovementInput(Options options, ClientInput previous) {
        super(Objects.requireNonNull(options, "options"));
        Objects.requireNonNull(previous, "previous");
        keyPresses = previous.keyPresses;
        moveVector = previous.getMoveVector();
    }

    /** Installs the adapter without changing the movement values collected for the current tick. */
    public static AgentMovementInput install(
            LocalPlayer player, Options options, ClientInput current) {
        Objects.requireNonNull(player, "player");
        if (current instanceof AgentMovementInput agentInput) {
            return agentInput;
        }
        var agentInput = new AgentMovementInput(options, current);
        player.input = agentInput;
        return agentInput;
    }

    public static AgentMovementInput install(LocalPlayer player, Options options) {
        return install(player, options, Objects.requireNonNull(player, "player").input);
    }

    /** Applies the shared command after {@link KeyboardInput#tick()} collected physical input. */
    public void apply(AgentInputState.MovementSnapshot agent) {
        var resolved = resolve(keyPresses, moveVector, agent);
        keyPresses = resolved.keyPresses();
        moveVector = resolved.moveVector();
    }

    static ResolvedMovement resolve(
            Input physicalKeys,
            Vec2 physicalMovement,
            AgentInputState.MovementSnapshot agent) {
        Objects.requireNonNull(physicalKeys, "physicalKeys");
        Objects.requireNonNull(physicalMovement, "physicalMovement");
        Objects.requireNonNull(agent, "agent");
        if (!agent.owned()) {
            return new ResolvedMovement(physicalKeys, physicalMovement);
        }

        var keys = new Input(
                agent.forward(),
                agent.backward(),
                agent.left(),
                agent.right(),
                agent.jump(),
                agent.crouch(),
                false);
        float forward = impulse(agent.forward(), agent.backward());
        float left = impulse(agent.left(), agent.right());
        return new ResolvedMovement(keys, new Vec2(left, forward).normalized());
    }

    private static float impulse(boolean positive, boolean negative) {
        if (positive == negative) {
            return 0.0F;
        }
        return positive ? 1.0F : -1.0F;
    }

    record ResolvedMovement(Input keyPresses, Vec2 moveVector) {
        ResolvedMovement {
            Objects.requireNonNull(keyPresses, "keyPresses");
            Objects.requireNonNull(moveVector, "moveVector");
        }
    }
}
