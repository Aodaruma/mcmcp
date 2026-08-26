package dev.aod.mcmcp.voice;

import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientVoicechatConnectionEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophoneMuteEvent;

/** Public Simple Voice Chat API integration; discovered only when Voice Chat is present. */
@ForgeVoicechatPlugin
public final class McmcpVoiceChatPlugin implements VoicechatPlugin {
    public static final int SAFETY_EVENT_PRIORITY = Integer.MAX_VALUE;
    private static final short[] SILENCE = new short[0];

    @Override
    public String getPluginId() {
        return "mcmcp";
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(
                ClientSoundEvent.class,
                this::onClientSound,
                SAFETY_EVENT_PRIORITY);
        registration.registerEvent(
                ClientVoicechatConnectionEvent.class,
                this::onConnectionChanged,
                SAFETY_EVENT_PRIORITY);
        registration.registerEvent(
                MicrophoneMuteEvent.class,
                this::onMuteChanged,
                SAFETY_EVENT_PRIORITY);
        VoiceChatEventBridge.GLOBAL.markGuardRegistered();
    }

    private void onClientSound(ClientSoundEvent event) {
        var guard = VoiceTransmissionGuard.GLOBAL;
        if (!guard.blocksTransmission()) {
            return;
        }
        // Both paths are intentional: cancellation stops dispatch immediately,
        // while an empty buffer remains safe if a future API revision ignores it.
        event.setRawAudio(SILENCE);
        event.cancel();
        guard.recordSuppressedFrame();
    }

    private void onConnectionChanged(ClientVoicechatConnectionEvent event) {
        VoiceChatEventBridge.GLOBAL.connectionChanged(event.isConnected());
    }

    private void onMuteChanged(MicrophoneMuteEvent event) {
        VoiceChatEventBridge.GLOBAL.muteChanged(event.isDisabled());
    }
}
