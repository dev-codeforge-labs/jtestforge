package com.devmanchego.jtestforge.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AtomicFileWriterTest {

    @Test
    void writesTheFullContentAndLeavesNoTempFileBehindOnSuccess(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("state.json");

        AtomicFileWriter.write(target, "{\"schemaVersion\":1}");

        assertThat(target).exists();
        assertThat(Files.readString(target)).isEqualTo("{\"schemaVersion\":1}");
        assertThat(leftoverTempFiles(dir)).isEmpty();
    }

    @Test
    void overwritesAnExistingFileCompletely(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("state.json");
        Files.writeString(target, "this content is much longer than the replacement");

        AtomicFileWriter.write(target, "short");

        assertThat(Files.readString(target)).isEqualTo("short");
        assertThat(leftoverTempFiles(dir)).isEmpty();
    }

    @Test
    void createsMissingParentDirectories(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("nested/does/not/exist/state.json");

        AtomicFileWriter.write(target, "content");

        assertThat(target).exists();
        assertThat(Files.readString(target)).isEqualTo("content");
    }

    @Test
    void leavesNoTempFileBehindWhenTheMoveFails(@TempDir Path dir) throws IOException {
        // Target is an existing directory, so the final move can never succeed - this
        // exercises the finally-block cleanup path, not just the happy path.
        Path targetThatIsActuallyADirectory = dir.resolve("state.json");
        Files.createDirectory(targetThatIsActuallyADirectory);

        org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> AtomicFileWriter.write(targetThatIsActuallyADirectory, "content"));

        assertThat(leftoverTempFiles(dir)).isEmpty();
    }

    @Test
    void repeatedSequentialWritesNeverLeaveContentFromTwoWritesConcatenated(@TempDir Path dir)
            throws IOException {
        // Concurrent writers racing to the SAME target path is not a scenario the tool
        // itself creates: state.json is guarded by the module-level lock file (phase 2),
        // so only one process ever writes it at a time. What this guards against instead
        // is a bug where a write leaves stale bytes behind that a shorter subsequent
        // write fails to fully overwrite.
        Path target = dir.resolve("state.json");
        for (int i = 0; i < 20; i++) {
            String content = "writer-" + i;
            AtomicFileWriter.write(target, content);
            assertThat(Files.readString(target)).isEqualTo(content);
        }

        assertThat(leftoverTempFiles(dir)).isEmpty();
    }

    private static Stream<Path> leftoverTempFiles(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".tmp")).toList().stream();
        }
    }

}
