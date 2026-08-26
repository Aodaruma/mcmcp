package dev.aodaruma.craftagent.voice;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;

import java.net.URI;
import java.nio.file.Path;

/** Looks up the optional Simple Voice Chat runtime without loading its internal classes. */
public final class NeoForgeVoiceChatInstallationProbe implements VoiceChatInstallationProbe {
    public static final String MOD_ID = "voicechat";
    private static final String INTERNAL_CLIENT_PACKAGE =
            "de.maxhenkel.voicechat.voice.client";

    @Override
    public Installation inspect() {
        return ModList.get().getModContainerById(MOD_ID)
                .map(container -> Installation.installed(
                        container.getModInfo().getVersion().toString()))
                .orElseGet(Installation::absent);
    }

    /**
     * Resolves the defining loader from FML's game layer and the owning mod
     * file location. Using this instead of the thread context loader avoids
     * accidentally resolving a duplicate API jar or an unrelated parent-layer
     * class.
     *
     * @return the Voice Chat module loader, or {@code null} when the optional
     *         mod/layer/module cannot be proven to be the expected owner
     */
    public ClassLoader resolveInternalClassLoader() {
        try {
            var container = ModList.get().getModContainerById(MOD_ID).orElse(null);
            if (container == null) {
                return null;
            }
            var modFile = container.getModInfo().getOwningFile().getFile();
            var expectedPath = modFile.getFilePath().toAbsolutePath().normalize();
            var loader = FMLLoader.getCurrentOrNull();
            if (loader == null) {
                return null;
            }
            var layer = loader.getGameLayer();
            var candidates = layer.modules().stream()
                    .filter(module -> module.getPackages().contains(INTERNAL_CLIENT_PACKAGE))
                    .filter(module -> layer.configuration().findModule(module.getName())
                            .flatMap(resolved -> resolved.reference().location())
                            .map(location -> sameFile(expectedPath, location))
                            .orElse(false))
                    .toList();
            if (candidates.size() != 1) {
                return null;
            }
            return layer.findLoader(candidates.getFirst().getName());
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    private static boolean sameFile(Path expectedPath, URI moduleLocation) {
        if (!"file".equalsIgnoreCase(moduleLocation.getScheme())) {
            return false;
        }
        try {
            return expectedPath.equals(Path.of(moduleLocation).toAbsolutePath().normalize());
        } catch (RuntimeException invalidLocation) {
            return false;
        }
    }
}
