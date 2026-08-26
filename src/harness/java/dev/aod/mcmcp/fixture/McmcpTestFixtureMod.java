package dev.aod.mcmcp.fixture;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(McmcpTestFixtureMod.MOD_ID)
public final class McmcpTestFixtureMod {
    public static final String MOD_ID = "mcmcp_test_fixture";
    public static final String ENABLE_PROPERTY = "mcmcp.testHarness";
    private static final Logger LOGGER = LogUtils.getLogger();

    public McmcpTestFixtureMod(IEventBus modEventBus, ModContainer modContainer) {
        if (!Boolean.getBoolean(ENABLE_PROPERTY)) {
            throw new IllegalStateException("MCMCP test fixture requires -D" + ENABLE_PROPERTY + "=true");
        }

        FixtureGameTests.bootstrap(modEventBus);
        LOGGER.warn("MCMCP test fixture loaded; never distribute this development-only mod");
    }
}
