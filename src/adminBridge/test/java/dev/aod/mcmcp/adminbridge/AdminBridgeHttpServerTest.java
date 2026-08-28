package dev.aod.mcmcp.adminbridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AdminBridgeHttpServerTest {
    private static final String TOKEN = "admin-test-token-0123456789-abcdefghijklmnopqrstuvwxyz";

    @TempDir
    Path temporaryDirectory;
    private AdminBridgeHttpServer endpoint;

    @AfterEach
    void closeEndpoint() {
        if (endpoint != null) {
            endpoint.close();
        }
    }

    @Test
    void validatesExternalFixtureOverAuthenticatedLoopbackAndRejectsPrefixPaths()
            throws Exception {
        Path fixture = temporaryDirectory.resolve("fixture-a");
        Files.createDirectories(fixture);
        Files.writeString(fixture.resolve("fixture.json"), """
                {
                  "schema_version":1,
                  "id":"fixture-a",
                  "dimension":"minecraft:overworld",
                  "mutation_bounds":{"min":{"x":0,"y":60,"z":0},"max":{"x":1,"y":61,"z":1}},
                  "max_changed_blocks":4,
                  "containers":[]
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(fixture.resolve("setup.mcfunction"),
                "gamemode survival @s\n", StandardCharsets.UTF_8);
        var service = validationOnlyApi();
        endpoint = new AdminBridgeHttpServer(service, TOKEN, 0);
        endpoint.start();

        HttpResponse<String> accepted = send("/v1/fixtures/validate", TOKEN);
        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(accepted.body()).contains("\"ok\":true", "\"fixture_id\":\"fixture-a\"");

        assertThat(send("/v1/fixtures/validate", "wrong").statusCode()).isEqualTo(401);
        assertThat(send("/v1/fixtures/validate/extra", TOKEN).statusCode()).isEqualTo(404);
        assertThat(send("/v1/fixtures/validate?unexpected=true", TOKEN).statusCode()).isEqualTo(404);
    }

    @Test
    void rejectsBrowserOriginAndWrongContentType() throws Exception {
        var service = validationOnlyApi();
        endpoint = new AdminBridgeHttpServer(service, TOKEN, 0);
        endpoint.start();
        URI uri = URI.create("http://127.0.0.1:" + endpoint.localPort()
                + "/v1/fixtures/validate");
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest origin = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json")
                .header("Origin", "http://localhost")
                .POST(HttpRequest.BodyPublishers.ofString("{\"fixture_id\":\"fixture-a\"}"))
                .build();
        assertThat(client.send(origin, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(403);

        HttpRequest text = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("{\"fixture_id\":\"fixture-a\"}"))
                .build();
        assertThat(client.send(text, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(415);
    }

    @Test
    void ownerOnlyTokenAndEphemeralLoopbackStatusWorkTogether() throws Exception {
        Path config = temporaryDirectory.resolve("admin-config");
        AdminBearerTokenStore tokenStore = new AdminBearerTokenStore();
        String token = tokenStore.loadOrCreate(config);
        assertThat(tokenStore.loadOrCreate(config)).isEqualTo(token);
        assertThat(token).matches("[A-Za-z0-9_-]{43}");

        AdminBridgeApi api = new AdminBridgeApi() {
            @Override
            public FixtureScript validate(String fixtureId) throws AdminBridgeException {
                throw new AdminBridgeException("not_available_in_status_test");
            }

            @Override
            public Status status() {
                return new Status("ready", "test-world-session", 1.25D, 64.0D, -2.5D,
                        new LeaseStatus(true, 3000, 120L));
            }

            @Override
            public ApplyResult apply(
                    String fixtureId, String expectedHash, String expectedWorldSession)
                    throws AdminBridgeException {
                throw new AdminBridgeException("not_available_in_status_test");
            }
        };
        endpoint = new AdminBridgeHttpServer(api, token, 0);
        endpoint.start();
        assertThat(endpoint.localPort()).isBetween(1, 65_535);

        HttpResponse<String> accepted = getStatus(token);
        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(accepted.body())
                .contains("\"state\":\"ready\"")
                .contains("\"world_session_id\":\"test-world-session\"")
                .contains("\"target\":3000");
        assertThat(getStatus("wrong").statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> send(String path, String token) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + endpoint.localPort() + path);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString("{\"fixture_id\":\"fixture-a\"}"))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getStatus(String token) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + endpoint.localPort() + "/v1/status");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private AdminBridgeApi validationOnlyApi() {
        return new AdminBridgeApi() {
            @Override
            public FixtureScript validate(String fixtureId) throws AdminBridgeException {
                try {
                    return new FixtureScriptLoader(temporaryDirectory).load(fixtureId);
                } catch (FixtureFormatException failure) {
                    throw new AdminBridgeException(failure.code(), failure);
                }
            }

            @Override
            public Status status() throws AdminBridgeException {
                throw new AdminBridgeException("not_available_in_pure_test");
            }

            @Override
            public ApplyResult apply(
                    String fixtureId, String expectedHash, String expectedWorldSession)
                    throws AdminBridgeException {
                throw new AdminBridgeException("not_available_in_pure_test");
            }
        };
    }
}
