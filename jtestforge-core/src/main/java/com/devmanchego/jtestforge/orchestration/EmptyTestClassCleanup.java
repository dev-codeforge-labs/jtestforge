package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.analysis.TestClassScanner;
import com.devmanchego.jtestforge.analysis.TestFileScan;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Removes a test class a unit created once nothing of that unit is left in it - shared by the
 * unit's own failure path ({@link DefaultUnitProcessor}) and by the cleanup of an interrupted
 * unit ({@link PartialEditReverter}), so both apply the same rule.
 *
 * <p>The rule is deliberately narrow: the file is deleted only if it parses and declares no
 * test method. A file that holds a test - someone else's, or one merged after the skeleton -
 * or that cannot be read is not the unit's to delete.
 */
final class EmptyTestClassCleanup {

    private EmptyTestClassCleanup() {
    }

    /**
     * @return whether the file was deleted
     * @throws UncheckedIOException if it qualified but could not be deleted
     */
    static boolean deleteIfNoTests(TestClassScanner scanner, Path testFile) {
        if (!(scanner.inspect(testFile) instanceof TestFileScan.Parsed parsed)
                || !parsed.info().testMethodNames().isEmpty()) {
            return false;
        }
        try {
            return Files.deleteIfExists(testFile);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to remove the empty test class skeleton " + testFile, e);
        }
    }
}
