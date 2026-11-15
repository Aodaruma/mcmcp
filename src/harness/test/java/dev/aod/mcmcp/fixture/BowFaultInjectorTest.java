package dev.aod.mcmcp.fixture;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class BowFaultInjectorTest {
    @TempDir Path directory;

    @Test void everyModeNeedsItsExactPointAndAuthorizationAndFiresOnlyOnce() {
        for (String point : java.util.List.of("cancel_first", "cancel_second", "fire")) {
            for (String type : java.util.List.of("runtime", "linkage")) {
                var fault = new BowFaultInjector.Fault(point + "_" + type);
                assertThatCode(() -> fault.fire(point, false)).doesNotThrowAnyException();
                assertThatCode(() -> fault.fire("other_point", true)).doesNotThrowAnyException();
                assertThat(fault.matches(point, true)).isTrue();
                var failure = catchThrowable(() -> fault.fire(point, true));
                assertThat(failure).isExactlyInstanceOf(type.equals("runtime") ? IllegalStateException.class : LinkageError.class);
                assertThat(fault.matches(point, true)).isFalse();
                assertThatCode(() -> fault.fire(point, true)).doesNotThrowAnyException();
            }
        }
        var disabled = new BowFaultInjector.Fault("disabled");
        assertThatCode(() -> disabled.fire("fire", true)).doesNotThrowAnyException();
    }

    @Test void fixedControlFileRejectsUnknownOversizedAndNonRegularRequests() throws Exception {
        var request = directory.resolve("bow-fault.txt");
        assertThat(BowFaultInjector.readMode(request)).isEqualTo("disabled");
        Files.writeString(request, "\n");
        assertThat(BowFaultInjector.readMode(request)).isEqualTo("disabled");
        Files.writeString(request, "cancel_second_linkage\n");
        assertThat(BowFaultInjector.readMode(request)).isEqualTo("cancel_second_linkage");
        for (String invalid : java.util.List.of("fire_runtime\nfire_linkage", "../fire_runtime", "fire_runtime".repeat(8))) {
            Files.writeString(request, invalid);
            assertThatThrownBy(() -> BowFaultInjector.readMode(request)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> BowFaultInjector.readMode(directory)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BowFaultInjector.Fault("unknown")).isInstanceOf(IllegalArgumentException.class);
    }
}
