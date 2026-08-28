package dev.aod.mcmcp.fixture;

import dev.aod.mcmcp.client.McmcpClientConfig;

/** Harness-source-only bridge for a reversible in-process observation-rate override. */
final class McmcpTestHarnessConfigBridge {
    private McmcpTestHarnessConfigBridge() {
    }

    static FixtureRaysPerTickLease.OverrideAccess acquireRaysPerTickAccess() {
        var access = McmcpClientConfig.acquireTestHarnessRaysPerTickAccess();
        return new FixtureRaysPerTickLease.OverrideAccess() {
            @Override
            public Integer current() {
                return access.currentOverride();
            }

            @Override
            public void set(Integer value) {
                access.setOverride(value);
            }
        };
    }
}
