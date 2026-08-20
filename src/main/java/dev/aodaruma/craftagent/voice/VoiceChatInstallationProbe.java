package dev.aodaruma.craftagent.voice;

/** Read-only mod-loader probe kept separate so the internal adapter is unit-testable. */
@FunctionalInterface
public interface VoiceChatInstallationProbe {
    Installation inspect();

    record Installation(boolean installed, String version) {
        public Installation {
            if (installed && (version == null || version.isBlank())) {
                throw new IllegalArgumentException("an installed mod must have a version");
            }
            if (!installed) {
                version = null;
            }
        }

        public static Installation absent() {
            return new Installation(false, null);
        }

        public static Installation installed(String version) {
            return new Installation(true, version);
        }
    }
}
