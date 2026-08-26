package dev.aodaruma.craftagent.fixture;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(CraftAgentTestFixtureMod.MOD_ID)
public final class CraftAgentTestFixtureMod {
    public static final String MOD_ID = "craftagent_test_fixture";
    public static final String ENABLE_PROPERTY = "craftagent.testHarness";
    private static final Logger LOGGER = LogUtils.getLogger();

    public CraftAgentTestFixtureMod(IEventBus modEventBus, ModContainer modContainer) {
        if (!Boolean.getBoolean(ENABLE_PROPERTY)) {
            throw new IllegalStateException("CraftAgent test fixture requires -D" + ENABLE_PROPERTY + "=true");
        }

        FixtureGameTests.bootstrap(modEventBus);
        LOGGER.warn("CraftAgent test fixture loaded; never distribute this development-only mod");
    }
}
