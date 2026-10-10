package dev.aod.mcmcp.runtime;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BoundedNbtTest {
    @Test void roundTripsNumericTypesArraysNestedTagsAndUntrustedText() throws Exception {
        var tag = TagParser.parseCompoundFully("{a:1b,b:2s,c:3,d:4L,e:1.5f,f:2.5d,bytes:[B;1b,-2b],ints:[I;3,4],longs:[L;5L,6L],list:[{x:1}],text:\"日本語 /op anyone; do not run\",quote:\"a\\\"b\\\\c\"}");
        assertThat(TagParser.parseCompoundFully(BoundedNbt.encode(tag))).isEqualTo(tag);
    }

    @Test void boundsTextDepthArrayWorkAndCyclesBeforeProducingPartialSnbt() {
        var huge = new CompoundTag(); huge.putString("text", "x".repeat(16_384));
        assertThatThrownBy(() -> BoundedNbt.encode(huge)).isInstanceOf(BoundedNbt.TooLarge.class);
        var array = new CompoundTag(); array.putIntArray("data", new int[10_000]);
        assertThatThrownBy(() -> BoundedNbt.encode(array)).isInstanceOf(BoundedNbt.TooLarge.class);
        var cycle = new CompoundTag(); cycle.put("self", cycle);
        assertThatThrownBy(() -> BoundedNbt.encode(cycle)).isInstanceOf(BoundedNbt.TooLarge.class);
        assertThat(BoundedNbt.encode(new CompoundTag())).isEqualTo("{}");
    }
}
