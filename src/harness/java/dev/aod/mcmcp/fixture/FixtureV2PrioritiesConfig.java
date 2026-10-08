package dev.aod.mcmcp.fixture;

final class FixtureV2PrioritiesConfig {
    static boolean validRequest(String request) {
        return request.matches("[0-9]{1,8}:(components|cake|cancel|blueprint|inventory|water|water_air|boat|minecart|horse|mod_break|report)");
    }
}
