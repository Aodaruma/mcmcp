package dev.aod.mcmcp.fixture;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only entrypoint kept separate from the server-compatible GameTest entrypoint. */
@Mod(value = McmcpTestFixtureMod.MOD_ID, dist = Dist.CLIENT)
public final class McmcpTestFixtureClientMod {
    public McmcpTestFixtureClientMod() {
        if (!Boolean.getBoolean(McmcpTestFixtureMod.ENABLE_PROPERTY)) {
            throw new IllegalStateException("MCMCP test fixture requires -D"
                    + McmcpTestFixtureMod.ENABLE_PROPERTY + "=true");
        }
        NeoForge.EVENT_BUS.addListener(FixtureCommands::register);
        NeoForge.EVENT_BUS.addListener(FixturePhase2Scenario::onServerTick);
        NeoForge.EVENT_BUS.addListener(FixturePhase2Scenario::onServerStopped);
        NeoForge.EVENT_BUS.addListener(FixturePhase4RouteBlocker::onServerTick);
        NeoForge.EVENT_BUS.addListener(FixturePhase4RouteBlocker::onServerStopped);
        NeoForge.EVENT_BUS.addListener(FixtureRandomTicks::onServerStopping);
        NeoForge.EVENT_BUS.addListener(FixtureRandomTicks::onServerStopped);
        FixturePhase3Autorun.installIfRequested(NeoForge.EVENT_BUS);
        FixturePhase5Autorun.installIfRequested(NeoForge.EVENT_BUS);
    }
}
