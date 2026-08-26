package dev.aodaruma.craftagent.voice;

import de.maxhenkel.voicechat.voice.client.ClientManager;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SimpleVoiceChat2622AdapterTest {
    @Test
    void rejectsAbsentAndUnpinnedVersionsWithoutLoadingInternals() {
        var rejectingLoader = new ClassLoader(null) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("de.maxhenkel.voicechat")) {
                    throw new AssertionError("internals must not be loaded");
                }
                return super.loadClass(name, resolve);
            }
        };
        var absent = new SimpleVoiceChat2622Adapter(
                VoiceChatInstallationProbe.Installation::absent,
                rejectingLoader,
                () -> true);
        var incompatible = new SimpleVoiceChat2622Adapter(
                () -> VoiceChatInstallationProbe.Installation.installed("2.6.23+26.2"),
                rejectingLoader,
                () -> true);

        assertThat(absent.probe().availability()).isEqualTo(VoiceChatAdapter.Availability.NOT_INSTALLED);
        assertThat(incompatible.probe().availability()).isEqualTo(VoiceChatAdapter.Availability.INCOMPATIBLE);
        assertThat(incompatible.readState().success()).isFalse();
    }

    @Test
    void validatesExactReflectionSurfaceAndPerformsReadWriteInputs() {
        ClientManager.reset();
        var adapter = new SimpleVoiceChat2622Adapter(
                () -> VoiceChatInstallationProbe.Installation.installed(
                        SimpleVoiceChat2622Adapter.SUPPORTED_MOD_VERSION),
                SimpleVoiceChat2622AdapterTest.class.getClassLoader(),
                () -> true);

        assertThat(adapter.probe().ready()).isTrue();
        assertThat(adapter.readState().state()).isEqualTo(new VoiceChatAdapter.State(false, false));
        assertThat(adapter.setMuted(true).success()).isTrue();
        assertThat(adapter.readState().state()).isEqualTo(new VoiceChatAdapter.State(false, true));
    }

    @Test
    void refusesInternalCallsOffTheClientThread() {
        var adapter = new SimpleVoiceChat2622Adapter(
                () -> VoiceChatInstallationProbe.Installation.installed(
                        SimpleVoiceChat2622Adapter.SUPPORTED_MOD_VERSION),
                SimpleVoiceChat2622AdapterTest.class.getClassLoader(),
                () -> false);

        assertThat(adapter.readState().failureCode()).isEqualTo("wrong_client_thread");
        assertThat(adapter.setMuted(true).failureCode()).isEqualTo("wrong_client_thread");
    }

}
