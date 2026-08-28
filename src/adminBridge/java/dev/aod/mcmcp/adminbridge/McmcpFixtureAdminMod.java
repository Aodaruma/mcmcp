package dev.aod.mcmcp.adminbridge;

import com.mojang.logging.LogUtils;
import net.minecraft.client.server.IntegratedServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.lifecycle.ClientStartedEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStoppingEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Development-only client mod. Fixture data remains external and editable without recompiling. */
@Mod(value = McmcpFixtureAdminMod.MOD_ID, dist = Dist.CLIENT)
public final class McmcpFixtureAdminMod {
    static final String MOD_ID = "mcmcp_fixture_admin";
    static final String ENABLE_PROPERTY = "mcmcp.fixtureAdmin";
    private static final String MARKER_CONTENT = "MCMCP_FIXTURE_ADMIN_V1";
    private static final Logger LOGGER = LogUtils.getLogger();

    private AdminFixtureService service;
    private AdminBridgeHttpServer endpoint;
    private AdminRandomTickLease randomTickLease;

    public McmcpFixtureAdminMod(IEventBus modEventBus, ModContainer modContainer) {
        var events = NeoForge.EVENT_BUS;
        events.addListener(this::onClientStarted);
        events.addListener(this::onServerPreTick);
        events.addListener(this::onServerStopping);
        events.addListener(this::onClientStopping);
        LOGGER.warn("MCMCP fixture admin bridge mod is installed; it remains disabled without both gates");
    }

    private void onClientStarted(ClientStartedEvent event) {
        Path config = event.getClient().gameDirectory.toPath().toAbsolutePath().normalize()
                .resolve("config").resolve("mcmcp-fixture-admin");
        try {
            Files.createDirectories(config);
            if (Files.isSymbolicLink(config)
                    || !Files.isDirectory(config, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("admin bridge config directory is unsafe");
            }
            // Recovery is deliberately independent from endpoint activation. Merely keeping the
            // admin mod installed is enough to restore a crash-left lease in the matching save.
            randomTickLease = new AdminRandomTickLease(
                    config.resolve("random-tick-lease.json"));
            if (!Boolean.getBoolean(ENABLE_PROPERTY)) {
                LOGGER.info("MCMCP fixture admin bridge disabled: missing -D{}=true", ENABLE_PROPERTY);
                return;
            }
            Path marker = config.resolve("enabled-profile.marker");
            if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker)
                    || Files.size(marker) > 128L
                    || !MARKER_CONTENT.equals(Files.readString(marker, StandardCharsets.UTF_8).strip())) {
                LOGGER.error("MCMCP fixture admin bridge disabled: fixed-profile marker is absent or invalid");
                return;
            }
            String token = new AdminBearerTokenStore().loadOrCreate(config);
            Path fixtureRoot = config.resolve("fixtures");
            Files.createDirectories(fixtureRoot);
            if (Files.isSymbolicLink(fixtureRoot)
                    || !Files.isDirectory(fixtureRoot, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("admin bridge fixture directory is unsafe");
            }
            service = new AdminFixtureService(
                    new FixtureScriptLoader(fixtureRoot),
                    randomTickLease);
            int port = Integer.getInteger("mcmcp.fixtureAdmin.port", 18_766);
            endpoint = new AdminBridgeHttpServer(service, token, port);
            endpoint.start();
            LOGGER.warn("MCMCP fixture admin bridge listening on 127.0.0.1:{}; token is separate",
                    endpoint.localPort());
        } catch (IOException | RuntimeException failure) {
            LOGGER.error("MCMCP fixture admin bridge failed closed", failure);
            closeEndpoint();
            service = null;
        }
    }

    private void onServerPreTick(ServerTickEvent.Pre event) {
        if (service != null && event.getServer() instanceof IntegratedServer server) {
            try {
                service.onServerTick(server);
            } catch (RuntimeException failure) {
                LOGGER.error("MCMCP fixture admin lease tick failed", failure);
            }
        } else if (randomTickLease != null
                && event.getServer() instanceof IntegratedServer server) {
            try {
                AdminBridgeSecurity.Decision decision =
                        AdminBridgeSecurity.authorizeRecovery(server);
                if (decision.allowed()) {
                    randomTickLease.recoverIfPresent(decision.context());
                }
                randomTickLease.onServerTick(server);
            } catch (RuntimeException failure) {
                LOGGER.error("MCMCP fixture admin recovery tick failed closed", failure);
            }
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        if (service != null && event.getServer() instanceof IntegratedServer server) {
            try {
                service.onServerStopping(server);
            } catch (RuntimeException failure) {
                LOGGER.error("MCMCP fixture admin could not restore its lease before stop", failure);
            }
        } else if (randomTickLease != null
                && event.getServer() instanceof IntegratedServer server) {
            try {
                randomTickLease.onServerStopping(server);
            } catch (RuntimeException failure) {
                LOGGER.error("MCMCP fixture admin recovery lease stop failed", failure);
            }
        }
    }

    private void onClientStopping(ClientStoppingEvent event) {
        closeEndpoint();
    }

    private void closeEndpoint() {
        if (endpoint != null) {
            endpoint.close();
            endpoint = null;
        }
    }
}
