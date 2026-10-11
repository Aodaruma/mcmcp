package dev.aod.mcmcp.runtime;

import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/** Preserves SNBT types without constructing an unbounded intermediate string. */
final class BoundedNbt {
    static final int MAX_CHARS = 16_384;
    private final StringBuilder output = new StringBuilder();
    private int nodes;

    static String encode(Tag tag) {
        var writer = new BoundedNbt();
        writer.write(tag, 0);
        return writer.output.toString();
    }

    private void write(Tag tag, int depth) {
        if (depth > 16 || ++nodes > 4096) throw new TooLarge();
        if (tag instanceof CompoundTag compound) {
            append("{");
            boolean first = true;
            for (var entry : compound.entrySet()) {
                if (!first) append(",");
                first = false;
                quoted(entry.getKey());
                append(":");
                write(entry.getValue(), depth + 1);
            }
            append("}");
        } else if (tag instanceof CollectionTag collection) {
            append(switch (tag.getId()) {
                case Tag.TAG_BYTE_ARRAY -> "[B;";
                case Tag.TAG_INT_ARRAY -> "[I;";
                case Tag.TAG_LONG_ARRAY -> "[L;";
                default -> "[";
            });
            for (int i = 0; i < collection.size(); i++) {
                if (i > 0) append(",");
                write(collection.get(i), depth + 1);
            }
            append("]");
        } else if (tag instanceof StringTag string) quoted(string.value());
        else append(tag.toString());
    }

    private void quoted(String text) {
        if (text.length() > MAX_CHARS) throw new TooLarge();
        append(StringTag.quoteAndEscape(text));
    }

    private void append(String text) {
        if (output.length() + text.length() > MAX_CHARS) throw new TooLarge();
        output.append(text);
    }

    static final class TooLarge extends RuntimeException { }
}
