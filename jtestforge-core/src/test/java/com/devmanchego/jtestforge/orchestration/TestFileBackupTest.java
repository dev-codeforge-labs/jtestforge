package com.devmanchego.jtestforge.orchestration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestFileBackupTest {

    @TempDir
    Path dir;

    @Test
    void copiesTheFileByteForByteKeepingItsPathRelativeToTheModule() throws IOException {
        Path module = dir.resolve("module");
        Path testFile = write(module.resolve("src/test/java/com/acme/PaymentServiceTest.java"),
                "// Año\nclass PaymentServiceTest {}\n".getBytes(StandardCharsets.ISO_8859_1));
        TestFileBackup backup = new TestFileBackup(dir.resolve("backups/run-1"), module);

        Path copy = backup.backupOnce("src/test/java/com/acme/PaymentServiceTest.java").orElseThrow();

        assertThat(copy).isEqualTo(dir.resolve("backups/run-1/src/test/java/com/acme/PaymentServiceTest.java"));
        assertThat(Files.readAllBytes(copy)).isEqualTo(Files.readAllBytes(testFile));
    }

    @Test
    void anAbsolutePathInsideTheModuleIsHandledTheSameAsARelativeOne() throws IOException {
        Path module = dir.resolve("module");
        Path testFile = write(module.resolve("src/test/java/FooTest.java"), "class FooTest {}".getBytes());
        TestFileBackup backup = new TestFileBackup(dir.resolve("backups/run-1"), module);

        Path copy = backup.backupOnce(testFile.toString()).orElseThrow();

        assertThat(copy).isEqualTo(dir.resolve("backups/run-1/src/test/java/FooTest.java"));
    }

    @Test
    void theFirstBackupIsTheOriginalAndLaterCallsNeverOverwriteIt() throws IOException {
        // A resumed run, or a second unit of the same class, must not replace the original with
        // a version that already contains generated tests.
        Path module = dir.resolve("module");
        Path testFile = write(module.resolve("FooTest.java"), "original".getBytes());
        TestFileBackup backup = new TestFileBackup(dir.resolve("backups/run-1"), module);
        Path copy = backup.backupOnce("FooTest.java").orElseThrow();

        Files.writeString(testFile, "original plus generated tests");

        assertThat(backup.backupOnce("FooTest.java")).isEmpty();
        assertThat(Files.readString(copy)).isEqualTo("original");
    }

    @Test
    void aFileThatDoesNotExistYetHasNothingToBackUp() {
        TestFileBackup backup = new TestFileBackup(dir.resolve("backups/run-1"), dir.resolve("module"));

        assertThat(backup.backupOnce("src/test/java/NewTest.java")).isEmpty();
        assertThat(dir.resolve("backups")).doesNotExist();
    }

    @Test
    void aFileOutsideTheModuleIsKeptUnderItsNameNeverUnderItsAbsolutePath() throws IOException {
        Path outside = write(dir.resolve("elsewhere/SharedTest.java"), "class SharedTest {}".getBytes());
        TestFileBackup backup = new TestFileBackup(dir.resolve("backups/run-1"), dir.resolve("module"));

        Path copy = backup.backupOnce(outside.toString()).orElseThrow();

        assertThat(copy).isEqualTo(dir.resolve("backups/run-1/SharedTest.java"));
    }

    @Test
    void aFailedCopyIsReportedAsAnExceptionRatherThanSwallowed() throws IOException {
        Path module = dir.resolve("module");
        write(module.resolve("FooTest.java"), "class FooTest {}".getBytes());
        // The backup root is an existing FILE, so no directory can be created under it.
        Path notADirectory = write(dir.resolve("backups"), "i am a file".getBytes());
        TestFileBackup backup = new TestFileBackup(notADirectory.resolve("run-1"), module);

        assertThatThrownBy(() -> backup.backupOnce("FooTest.java"))
                .isInstanceOf(java.io.UncheckedIOException.class)
                .hasMessageContaining("FooTest.java");
    }

    private static Path write(Path file, byte[] content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content);
        return file;
    }
}
