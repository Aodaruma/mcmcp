package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.observation.ObservationPage;
import dev.aod.mcmcp.agent.observation.ObservationRecord.VisibleSurface;
import dev.aod.mcmcp.agent.observation.ObservationValues.BlockPosition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.SignBlockEntity;

/** Current client-side block data supplements the retained geometry frame; it is never action code. */
final class BlockEntityObservation {
    static final int MAX_BLOCKS = 32;
    static final int MAX_PAGE_CHARS = 65_536;
    static final int MAX_LINE_CHARS = 512;

    static Map<String, Object> enrich(Map<String, Object> wire, ObservationPage page, boolean includeNbt,
            BiFunction<List<VisibleSurface>, Boolean, Map<String, Object>> reader) {
        var groups = new LinkedHashMap<BlockPosition, List<VisibleSurface>>();
        for (var record : page.records()) {
            if (record instanceof VisibleSurface surface && (includeNbt || isSign(surface.block().value())))
                groups.computeIfAbsent(surface.position(), ignored -> new ArrayList<>()).add(surface);
        }
        if (groups.isEmpty()) return wire;
        var extras = new LinkedHashMap<Map<String, Object>, Map<String, Object>>();
        int queried = 0;
        int remaining = MAX_PAGE_CHARS;
        for (var group : groups.values()) {
            var surface = group.getFirst();
            Map<String, Object> data = queried >= MAX_BLOCKS || remaining <= 0
                    ? unavailable("budget_exceeded", includeNbt) : reader.apply(List.copyOf(group), includeNbt);
            queried++;
            int chars = data.toString().length();
            if (chars > remaining) data = unavailable("budget_exceeded", includeNbt);
            else remaining -= chars;
            var pos = surface.position();
            extras.put(Map.of("dimension", pos.dimension().value(), "x", pos.x(), "y", pos.y(), "z", pos.z()), data);
        }
        var records = new ArrayList<Map<String, Object>>();
        int emittedRemaining = MAX_PAGE_CHARS;
        for (Object raw : (List<?>) wire.get("records")) {
            @SuppressWarnings("unchecked") var record = (Map<String, Object>) raw;
            var data = extras.get(record.get("position"));
            if (data != null && ("block".equals(record.get("kind")) || "visible_surface".equals(record.get("kind")))) {
                int size = data.toString().length();
                if (size > emittedRemaining) data = unavailable("budget_exceeded", includeNbt);
                else emittedRemaining -= size;
                var enriched = new LinkedHashMap<>(record);
                enriched.put("block_entity", data);
                records.add(enriched);
            } else records.add(record);
        }
        var result = new LinkedHashMap<>(wire);
        result.put("records", List.copyOf(records));
        return result;
    }

    static boolean isSign(String block) { return block.endsWith("_sign"); }

    static Map<String, Object> unavailable(String status, boolean includeNbt) {
        var result = new LinkedHashMap<String, Object>();
        result.put("status", status);
        result.put("source", "client_block_entity");
        result.put("untrusted", true);
        if (includeNbt) result.put("nbt", Map.of("status", status, "format", "snbt", "source", "client_update_tag"));
        return result;
    }

    /** Caller must first reobserve the exact returned surface, including current fog and occlusion. */
    static Map<String, Object> readVisible(Minecraft minecraft, VisibleSurface surface, long tick, boolean includeNbt) {
        var p = surface.position();
        var pos = new net.minecraft.core.BlockPos(p.x(), p.y(), p.z());
        var entity = minecraft.level.getBlockEntity(pos);
        if (entity == null) return unavailable("no_block_entity", includeNbt);
        var result = new LinkedHashMap<>(unavailable("available", false));
        result.put("observed_tick", tick);
        result.put("type", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString());
        if (entity instanceof SignBlockEntity sign) {
            boolean front = sign.isFacingFrontText(minecraft.player);
            var text = sign.getText(front);
            var lines = new ArrayList<String>();
            boolean truncated = false;
            for (var line : text.getMessages(minecraft.isTextFilteringEnabled())) {
                String value = line.getString(MAX_LINE_CHARS + 1);
                truncated |= value.length() > MAX_LINE_CHARS;
                int end = Math.min(value.length(), MAX_LINE_CHARS);
                if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
                lines.add(value.substring(0, end));
            }
            result.put("sign", Map.of("side", front ? "front" : "back", "lines", List.copyOf(lines),
                    "color", text.getColor().getName(), "glowing", text.hasGlowingText(), "truncated", truncated));
        }
        if (includeNbt) {
            var nbt = new LinkedHashMap<String, Object>();
            nbt.put("source", "client_update_tag");
            nbt.put("format", "snbt");
            nbt.put("text_filter_applied", entity instanceof SignBlockEntity && minecraft.isTextFilteringEnabled());
            try {
                var tag = entity.getUpdateTag(minecraft.level.registryAccess());
                // Preserve the user's text-filter preference even in the optional raw representation.
                if (entity instanceof SignBlockEntity && minecraft.isTextFilteringEnabled()) tag = filterSignNbt(tag);
                nbt.put("data", BoundedNbt.encode(tag));
                nbt.put("status", "available");
            } catch (BoundedNbt.TooLarge limit) {
                nbt.put("status", "too_large");
            } catch (RuntimeException | LinkageError failure) {
                nbt.put("status", "unavailable");
            }
            result.put("nbt", nbt);
        }
        return result;
    }

    static net.minecraft.nbt.CompoundTag filterSignNbt(net.minecraft.nbt.CompoundTag original) {
        var result = new net.minecraft.nbt.CompoundTag();
        for (var entry : original.entrySet()) {
            if ((entry.getKey().equals("front_text") || entry.getKey().equals("back_text"))
                    && entry.getValue() instanceof net.minecraft.nbt.CompoundTag text) {
                var filteredText = new net.minecraft.nbt.CompoundTag();
                var filtered = text.get("filtered_messages");
                for (var field : text.entrySet()) {
                    filteredText.put(field.getKey(), field.getKey().equals("messages") && filtered != null
                            ? filtered : field.getValue());
                }
                result.put(entry.getKey(), filteredText);
            } else result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }
}
