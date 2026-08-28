package dev.aod.mcmcp.adminbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RandomTickLeaseJournalTest {
    private static final String WORLD_HASH = "a".repeat(64);
    private static final String WORLD_ID = "b2f3d78c-d56f-49ee-b797-899fa2721145";

    @TempDir
    Path temporaryDirectory;

    @Test
    void atomicallyPersistsReadsAndDeletesRecoveryMaterial() {
        RandomTickLeaseJournal journal = new RandomTickLeaseJournal(
                temporaryDirectory.resolve("lease.json"));
        var entry = new RandomTickLeaseJournal.Entry(WORLD_HASH, WORLD_ID, 3, 3000);

        journal.write(entry);
        assertThat(journal.exists()).isTrue();
        assertThat(journal.read()).isEqualTo(entry);
        assertThatThrownBy(() -> journal.write(entry))
                .isInstanceOfSatisfying(RandomTickLeaseJournal.JournalException.class,
                        error -> assertThat(error.code())
                                .isEqualTo("random_tick_journal_already_exists"));

        journal.delete();
        assertThat(journal.exists()).isFalse();
    }

    @Test
    void rejectsMalformedOrLinkedJournal() throws Exception {
        Path path = temporaryDirectory.resolve("lease.json");
        Files.writeString(path, "{\"schema_version\":1}");
        RandomTickLeaseJournal journal = new RandomTickLeaseJournal(path);
        assertThatThrownBy(journal::read)
                .isInstanceOf(RandomTickLeaseJournal.JournalException.class);
    }
}
