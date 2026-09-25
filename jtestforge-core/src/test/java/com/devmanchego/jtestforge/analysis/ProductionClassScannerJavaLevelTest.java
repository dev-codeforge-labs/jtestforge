package com.devmanchego.jtestforge.analysis;

import com.devmanchego.jtestforge.model.ProductionClass;
import com.github.javaparser.resolution.TypeSolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code _} is a legal Java 8 identifier and a keyword from Java 9 on. */
class ProductionClassScannerJavaLevelTest {

    @TempDir
    Path mainSourceRoot;

    @Test
    void java8CodeUsingUnderscoreAsAnIdentifierParsesAtItsOwnLevel() throws IOException {
        writeSource("Legacy", "public int twice(int _) {\n        return _ * 2;\n    }");

        List<ProductionClass> classes = new ProductionClassScanner(solver(), mainSourceRoot, 8).scan();

        assertThat(classes).extracting(ProductionClass::fqn).containsExactly("com.acme.Legacy");
    }

    @Test
    void theSameCodeFailsAtTheDefaultJava21Level() throws IOException {
        writeSource("Legacy", "public int twice(int _) {\n        return _ * 2;\n    }");

        assertThatThrownBy(() -> new ProductionClassScanner(solver(), mainSourceRoot).scan())
                .isInstanceOf(ProductionScanException.class);
    }

    @Test
    void aWronglyDetectedOldLevelFallsBackToJava21InsteadOfFailingTheScan() throws IOException {
        writeSource("Modern", "public String describe(Object o) {\n"
                + "        if (o instanceof String s) {\n            var t = s.strip();\n            return t;\n        }\n"
                + "        return \"\";\n    }");

        List<ProductionClass> classes = new ProductionClassScanner(solver(), mainSourceRoot, 8).scan();

        assertThat(classes).extracting(ProductionClass::fqn).containsExactly("com.acme.Modern");
    }

    private TypeSolver solver() {
        return ProductionTypeSolvers.forModule(mainSourceRoot, List.of());
    }

    private void writeSource(String className, String body) throws IOException {
        Path file = mainSourceRoot.resolve("com/acme/" + className + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package com.acme;\n\npublic class " + className + " {\n    " + body + "\n}\n");
    }
}
