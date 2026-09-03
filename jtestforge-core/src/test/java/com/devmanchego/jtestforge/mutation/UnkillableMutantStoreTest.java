package com.devmanchego.jtestforge.mutation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** §10.1 step 6: the append-only unkillable-mutant list. */
class UnkillableMutantStoreTest {

    @Test
    void loadingANonExistentFileYieldsNoEntries(@TempDir Path dir) {
        UnkillableMutantStore store = new UnkillableMutantStore(dir.resolve("unkillable.txt"));

        assertThat(store.load()).isEmpty();
        assertThat(store.contains("anything")).isFalse();
    }

    @Test
    void anAppendedEntryIsFoundByLoadAndByContains(@TempDir Path dir) {
        UnkillableMutantStore store = new UnkillableMutantStore(dir.resolve("unkillable.txt"));

        store.append("com.acme.Calculator#add(II)I#MathMutator#3", "maxAttemptsPerMutant exhausted");

        assertThat(store.contains("com.acme.Calculator#add(II)I#MathMutator#3")).isTrue();
        assertThat(store.load()).containsExactly(
                new UnkillableMutantStore.UnkillableEntry(
                        "com.acme.Calculator#add(II)I#MathMutator#3", "maxAttemptsPerMutant exhausted"));
    }

    @Test
    void appendingCreatesTheParentDirectoryWhenMissing(@TempDir Path dir) {
        Path nested = dir.resolve("state").resolve("harden").resolve("unkillable.txt");
        UnkillableMutantStore store = new UnkillableMutantStore(nested);

        store.append("id-1", "reason");

        assertThat(Files.isRegularFile(nested)).isTrue();
    }

    @Test
    void multipleAppendsPreserveOrder(@TempDir Path dir) {
        UnkillableMutantStore store = new UnkillableMutantStore(dir.resolve("unkillable.txt"));

        store.append("id-1", "reason one");
        store.append("id-2", "reason two");
        store.append("id-3", "reason three");

        assertThat(store.load()).extracting(UnkillableMutantStore.UnkillableEntry::mutantId)
                .containsExactly("id-1", "id-2", "id-3");
    }

    @Test
    void aSecondStoreInstanceOverTheSameFileSeesEntriesFromTheFirst(@TempDir Path dir) {
        Path file = dir.resolve("unkillable.txt");
        new UnkillableMutantStore(file).append("id-1", "reason");

        UnkillableMutantStore secondInstance = new UnkillableMutantStore(file);

        assertThat(secondInstance.contains("id-1")).isTrue();
    }

    @Test
    void aReasonContainingTabsOrNewlinesIsSanitisedToOneLine(@TempDir Path dir) {
        UnkillableMutantStore store = new UnkillableMutantStore(dir.resolve("unkillable.txt"));

        store.append("id-1", "line one\twith a tab\nand a newline");

        List<UnkillableMutantStore.UnkillableEntry> entries = store.load();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).reason()).doesNotContain("\t").doesNotContain("\n");
    }

    @Test
    void loadIdsReturnsOnlyTheIdsWithoutReasons(@TempDir Path dir) {
        UnkillableMutantStore store = new UnkillableMutantStore(dir.resolve("unkillable.txt"));
        store.append("id-1", "reason one");
        store.append("id-2", "reason two");

        assertThat(store.loadIds()).containsExactlyInAnyOrder("id-1", "id-2");
    }
}
