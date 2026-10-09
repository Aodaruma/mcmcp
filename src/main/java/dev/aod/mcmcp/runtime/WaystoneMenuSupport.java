package dev.aod.mcmcp.runtime;

import com.mojang.datafixers.util.Either;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Reads only the received, currently owned selection menu of one verified Waystones build. */
final class WaystoneMenuSupport {
    static final String MENU = "waystones:warp_stone_selection";
    private static final String MENU_CLASS = "net.blay09.mods.waystones.menu.WaystoneSelectionMenu";
    private static final String SCREEN_CLASS = "net.blay09.mods.waystones.client.gui.screen.WaystoneSelectionScreen";
    private static final KnownMenuProfileSupport.ArtifactRequirement ARTIFACT =
            new KnownMenuProfileSupport.ArtifactRequirement("waystones", "26.2.0.9",
                    "5b9e47b8f94a3a544e828a8063a088fc2d832b36d33a22f7f0167610a624f4c1");
    private static Boolean available;

    static boolean available() {
        if (available == null) {
            try { available = KnownMenuProfileSupport.activeArtifactMatches(ARTIFACT); }
            catch (java.io.IOException | RuntimeException unavailable) { available = false; }
        }
        return available;
    }

    static List<V2WaystoneReferences.Destination> read(Minecraft minecraft, AbstractContainerMenu menu) {
        if (!available() || minecraft.gui.screen() == null || !SCREEN_CLASS.equals(minecraft.gui.screen().getClass().getName())
                || !MENU_CLASS.equals(menu.getClass().getName()) || minecraft.player.containerMenu != menu
                || !MENU.equals(ScreenOwnershipSignals.registeredMenuTypeId(menu).orElse(null))
                || !menu.slots.isEmpty() || !menu.getCarried().isEmpty())
            throw new IllegalArgumentException("unsupported Waystones menu");
        try {
            Class<?> waystoneType = Class.forName("net.blay09.mods.waystones.api.Waystone");
            Object rows = menu.getClass().getMethod("getWaystones").invoke(menu);
            if (!(rows instanceof Collection<?> waystones) || waystones.size() > 256)
                throw new IllegalArgumentException("destination list exceeds bound");
            // The native getter defaults missing keys to an empty allowed cost; require actual received entries.
            var field = menu.getClass().getDeclaredField("warpRequirements");
            field.setAccessible(true);
            if (!(field.get(menu) instanceof Map<?, ?> requirements))
                throw new IllegalArgumentException("destination requirements unavailable");
            Object source = menu.getClass().getMethod("getWaystoneFrom").invoke(menu);
            UUID sourceId = source == null ? null : (UUID) waystoneType.getMethod("getWaystoneUid").invoke(source);
            var seen = new HashSet<UUID>();
            var result = new ArrayList<V2WaystoneReferences.Destination>();
            for (Object row : waystones) {
                UUID id = (UUID) waystoneType.getMethod("getWaystoneUid").invoke(row);
                if (!seen.add(id) || !(requirements.get(id) instanceof Either<?, ?> costs))
                    throw new IllegalArgumentException("ambiguous or missing destination requirements");
                var pos = (BlockPos) waystoneType.getMethod("getPos").invoke(row);
                var dimension = (ResourceKey<?>) waystoneType.getMethod("getDimension").invoke(row);
                var name = (Component) waystoneType.getMethod("getEffectiveName").invoke(row);
                boolean nativeAllowed = costs.left().isPresent();
                Object values = nativeAllowed ? costs.left().orElseThrow() : costs.right().orElseThrow();
                if (!(values instanceof List<?> list) || list.size() > 32)
                    throw new IllegalArgumentException("unbounded destination requirements");
                var parsed = readCosts(list);
                result.add(new V2WaystoneReferences.Destination(id, boundedText(name.getString()),
                        dimension.identifier().toString(), pos.getX(), pos.getY(), pos.getZ(), parsed.levels(), parsed.points(),
                        nativeAllowed && parsed.supported() && !id.equals(sourceId), parsed.messages()));
            }
            return List.copyOf(result);
        } catch (ReflectiveOperationException | ClassCastException incompatible) {
            throw new IllegalArgumentException("Waystones menu protocol unavailable", incompatible);
        }
    }

    private static Costs readCosts(List<?> requirements) throws ReflectiveOperationException {
        int levels = 0, points = 0;
        boolean supported = true;
        var messages = new ArrayList<String>();
        for (Object value : requirements) {
            String type = value.getClass().getName();
            if (type.equals("net.blay09.mods.shogi.common.effect.cost.ExperienceLevelCostInformation")
                    || type.equals("net.blay09.mods.shogi.common.effect.cost.ExperiencePointsCostInformation")) {
                int required = (int) value.getClass().getMethod("required").invoke(value);
                // Shogi uses long for experience points and int for levels.
                long available = ((Number) value.getClass().getMethod("available").invoke(value)).longValue();
                if (required < 0 || required > 1_000_000) throw new IllegalArgumentException("unbounded experience cost");
                supported &= available >= required;
                if (type.endsWith("ExperienceLevelCostInformation")) levels = Math.addExact(levels, required);
                else points = Math.addExact(points, required);
            } else if (type.equals("net.blay09.mods.shogi.common.effect.server.cooldown.CooldownInformation")) {
                long remaining = ((Number) value.getClass().getMethod("remainingTicks").invoke(value)).longValue();
                supported &= remaining <= 0;
                messages.add("cooldown_remaining_ticks:" + Math.max(0, remaining));
            } else {
                // Item costs, custom effects and refusals are visible but cannot authorize payment yet.
                supported = false;
                messages.add("unsupported_requirement:" + boundedText(value.getClass().getSimpleName()));
            }
        }
        return new Costs(levels, points, supported, List.copyOf(messages));
    }

    static void select(Minecraft minecraft, UUID destination) {
        if (!available()) throw new IllegalArgumentException("Waystones provider unavailable");
        try {
            var type = Class.forName("net.blay09.mods.waystones.network.message.ServerboundSelectWaystonePacket");
            var payload = (CustomPacketPayload) type.getConstructor(UUID.class).newInstance(destination);
            minecraft.getConnection().send(new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(payload));
        } catch (ReflectiveOperationException | ClassCastException incompatible) {
            throw new IllegalStateException("Waystones selection protocol unavailable", incompatible);
        }
    }

    private static String boundedText(String value) {
        String safe = value.replaceAll("[\\p{Cntrl}]", " ");
        return safe.substring(0, Math.min(256, safe.length()));
    }
    private record Costs(int levels, int points, boolean supported, List<String> messages) { }
}
