package dev.aod.mcmcp.adminbridge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.aod.mcmcp.adminbridge.FixtureManifest.BlockPosition;
import static dev.aod.mcmcp.adminbridge.FixtureManifest.Bounds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestrictedCommandPolicyTest {
    private final RestrictedCommandPolicy policy = new RestrictedCommandPolicy();

    @Test
    void acceptsOnlyTheClosedFixtureGrammarInsideDeclaredBounds() throws Exception {
        var result = policy.validate(manifest(64), List.of(
                "setblock 1 64 1 minecraft:dirt replace",
                "fill 2 64 2 3 64 3 minecraft:air replace",
                "item replace block 4 64 4 container.0 with minecraft:wheat_seeds 64",
                "clear @s",
                "gamemode survival @s",
                "tp @s 0.5 64 0.5 180 0",
                "kill @e[type=minecraft:item,x=0,y=60,z=0,dx=10,dy=10,dz=10]"));

        assertThat(result).extracting(RestrictedCommandPolicy.ValidatedCommand::root)
                .containsExactly("setblock", "fill", "item", "clear", "gamemode", "tp", "kill");
        assertThat(result.stream().mapToLong(
                RestrictedCommandPolicy.ValidatedCommand::changedBlocks).sum()).isEqualTo(5L);
    }

    @Test
    void rejectsMetaCommandsAndArbitrarySelectors() {
        for (String command : List.of(
                "execute as @s run setblock 1 64 1 minecraft:dirt",
                "function mcmcp:test",
                "gamerule randomTickSpeed 3000",
                "gamemode creative @s",
                "gamemode survival @a",
                "clear @a",
                "kill @e[type=minecraft:zombie,x=0,y=60,z=0,dx=1,dy=1,dz=1]")) {
            assertThatThrownBy(() -> policy.validate(manifest(64), List.of(command)))
                    .isInstanceOf(FixtureFormatException.class);
        }
    }

    @Test
    void rejectsMutationsOutsideTheEnvelopeAndUndeclaredContainers() {
        assertCode("coordinate_outside_effect_inset",
                "setblock 11 64 1 minecraft:dirt");
        assertCode("coordinate_outside_effect_inset",
                "setblock 0 64 1 minecraft:dirt");
        assertCode("container_not_declared",
                "item replace block 5 64 5 container.0 with minecraft:wheat_seeds 1");
        assertCode("container_slot_invalid",
                "item replace block 4 64 4 container.54 with minecraft:wheat_seeds 1");
        assertCode("kill_selector_outside_bounds",
                "kill @e[type=minecraft:item,x=0,y=60,z=0,dx=11,dy=10,dz=10]");
    }

    @Test
    void accountsForFillVolumeBeforeDispatch() {
        assertThatThrownBy(() -> policy.validate(manifest(4), List.of(
                "fill 1 61 1 3 61 3 minecraft:air replace")))
                .isInstanceOfSatisfying(FixtureFormatException.class,
                        error -> assertThat(error.code()).isEqualTo("changed_block_budget_exceeded"));
    }

    @Test
    void rejectsInlineNbtAndCommandSeparators() {
        assertCode("command_lexically_forbidden",
                "setblock 1 64 1 minecraft:chest{Items:[]}");
        assertCode("command_lexically_forbidden",
                "setblock 1 64 1 minecraft:dirt; stop");
        assertCode("block_id_invalid",
                "setblock 1 64 1 minecraft:lava replace");
        assertCode("block_id_invalid",
                "setblock 1 64 1 minecraft:tnt replace");
    }

    private void assertCode(String code, String command) {
        assertThatThrownBy(() -> policy.validate(manifest(64), List.of(command)))
                .isInstanceOfSatisfying(FixtureFormatException.class,
                        error -> assertThat(error.code()).isEqualTo(code));
    }

    private static FixtureManifest manifest(int budget) {
        var bounds = new Bounds(new BlockPosition(0, 60, 0), new BlockPosition(10, 70, 10));
        return new FixtureManifest(1, "test", "minecraft:overworld", bounds, bounds,
                budget, List.of(new BlockPosition(4, 64, 4)), null);
    }
}
