package com.devmanchego.jtestforge.build;

import com.devmanchego.jtestforge.model.CompilerError;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompilerErrorParserTest {

    @Test
    void aGreenBuildYieldsNoErrors() {
        List<CompilerError> errors = CompilerErrorParser.parse("""
                [INFO] Scanning for projects...
                [INFO] --------------------< com.acme:core >--------------------
                [INFO] --- compiler:3.13.0:compile (default-compile) @ core ---
                [INFO] Nothing to compile - all classes are up to date.
                [INFO] BUILD SUCCESS
                """);

        assertThat(errors).isEmpty();
    }

    @Test
    void parsesASingleErrorWithNoContinuationLines() {
        List<CompilerError> errors = CompilerErrorParser.parse(
                "[ERROR] /home/dev/project/src/main/java/com/acme/Foo.java:[12,34] "
                        + "';' expected");

        assertThat(errors).singleElement().satisfies(error -> {
            assertThat(error.file()).isEqualTo("/home/dev/project/src/main/java/com/acme/Foo.java");
            assertThat(error.line()).isEqualTo(12);
            assertThat(error.column()).isEqualTo(34);
            assertThat(error.message()).isEqualTo("';' expected");
        });
    }

    @Test
    void foldsSymbolAndLocationContinuationLinesIntoTheSameError() {
        // Real maven-compiler-plugin output: "cannot find symbol" alone is nearly
        // useless without the symbol/location detail that follows on separate,
        // still-[ERROR]-prefixed lines.
        List<CompilerError> errors = CompilerErrorParser.parse("""
                [ERROR] COMPILATION ERROR :\s
                [ERROR] /home/dev/project/src/main/java/com/acme/Foo.java:[12,34] cannot find symbol
                [ERROR]   symbol:   variable bar
                [ERROR]   location: class com.acme.Foo
                [ERROR] -> [Help 1]
                """);

        assertThat(errors).singleElement().satisfies(error ->
                assertThat(error.message()).isEqualTo(
                        "cannot find symbol\n  symbol:   variable bar\n  location: class com.acme.Foo"));
    }

    @Test
    void parsesMultipleIndependentErrorsInOneRun() {
        List<CompilerError> errors = CompilerErrorParser.parse("""
                [ERROR] COMPILATION ERROR :\s
                [ERROR] /home/dev/project/src/main/java/com/acme/Foo.java:[12,34] cannot find symbol
                [ERROR]   symbol:   variable bar
                [ERROR] /home/dev/project/src/main/java/com/acme/Baz.java:[7,1] ';' expected
                [ERROR] 2 errors
                [ERROR] -> [Help 1]
                """);

        assertThat(errors).hasSize(2);
        assertThat(errors.get(0).file()).endsWith("Foo.java");
        assertThat(errors.get(0).line()).isEqualTo(12);
        assertThat(errors.get(1).file()).endsWith("Baz.java");
        assertThat(errors.get(1).line()).isEqualTo(7);
    }

    @Test
    void aWindowsDriveLetterPathIsNotMistakenForTheLineColumnSeparator() {
        List<CompilerError> errors = CompilerErrorParser.parse(
                "[ERROR] C:\\work\\project\\src\\main\\java\\com\\acme\\Foo.java:[5,10] "
                        + "incompatible types");

        assertThat(errors).singleElement().satisfies(error ->
                assertThat(error.file()).isEqualTo("C:\\work\\project\\src\\main\\java\\com\\acme\\Foo.java"));
    }

    @Test
    void aPlainErrorLineWithNoFileLocationIsNotTreatedAsADiagnostic() {
        // e.g. "[ERROR] Failed to execute goal ..." - real, but not a source diagnostic.
        List<CompilerError> errors = CompilerErrorParser.parse(
                "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:"
                        + "3.13.0:compile (default-compile) on project core: Compilation failure");

        assertThat(errors).isEmpty();
    }

    @Test
    void nullOrBlankOutputYieldsNoErrors() {
        assertThat(CompilerErrorParser.parse(null)).isEmpty();
        assertThat(CompilerErrorParser.parse("")).isEmpty();
        assertThat(CompilerErrorParser.parse("   \n  ")).isEmpty();
    }
}
