package dev.aodaruma.craftagent.fixture;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only entrypoint kept separate from the server-compatible GameTest entrypoint. */
@Mod(value = CraftAgentTestFixtureMod.MOD_ID, dist = Dist.CLIENT)
public final class CraftAgentTestFixtureClientMod {
    public CraftAgentTestFixtureClientMod() {
        if (!Boolean.getBoolean(CraftAgentTestFixtureMod.ENABLE_PROPERTY)) {
            throw new IllegalStateException("CraftAgent test fixture requires -D"
                    + CraftAgentTestFixtureMod.ENABLE_PROPERTY + "=true");
        }
        NeoForge.EVENT_BUS.addListener(FixtureCommands::register);
        NeoForge.EVENT_BUS.addListener(FixturePhase2Scenario::onServerTick);
        NeoForge.EVENT_BUS.addListener(FixturePhase2Scenario::onServerStopped);
        NeoForge.EVENT_BUS.addListener(FixturePhase4RouteBlocker::onServerTick);
        NeoForge.EVENT_BUS.addListener(FixturePhase4RouteBlocker::onServerStopped);
        FixturePhase3Autorun.installIfRequested(NeoForge.EVENT_BUS);
        FixturePhase5Autorun.installIfRequested(NeoForge.EVENT_BUS);
    }
}
