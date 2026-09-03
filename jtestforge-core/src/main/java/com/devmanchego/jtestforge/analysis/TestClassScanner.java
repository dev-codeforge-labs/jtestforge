package com.devmanchego.jtestforge.analysis;

import com.devmanchego.jtestforge.model.AssertionLibrary;
import com.devmanchego.jtestforge.model.InjectionStyle;
import com.devmanchego.jtestforge.model.MockField;
import com.devmanchego.jtestforge.model.TestClassInfo;
import com.devmanchego.jtestforge.state.TestFileInspector;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reads an existing test class — jtestforge-specification.md §7.2.
 *
 * <p>No symbol solver is configured. Everything read here - method names, field
 * annotations, imports - is present in the source text, and a test file frequently
 * references types (the subject, its collaborators) that are not on JTestForge's own
 * classpath and never will be. Requiring resolution would make the scan fail on exactly
 * the files it exists to read.
 *
 * <p>Also the real implementation of {@link TestFileInspector}, the port phase 2's
 * {@code ResumeReconciler} was written against.
 */
public final class TestClassScanner implements TestFileInspector {

    private static final Set<String> TEST_ANNOTATIONS =
            Set.of("Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate");
    private static final Set<String> MOCK_ANNOTATIONS =
            Set.of("Mock", "Spy", "MockBean", "SpyBean", "MockitoBean", "MockitoSpyBean");
    private static final Set<String> SUBJECT_ANNOTATIONS = Set.of("InjectMocks");

    private final JavaParser javaParser = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

    public Optional<TestClassInfo> scan(Path testFile) {
        return parse(testFile).flatMap(compilationUnit -> toTestClassInfo(compilationUnit, testFile));
    }

    @Override
    public Set<String> testMethodNames(Path testFile) {
        return scan(testFile).map(TestClassInfo::testMethodNames).orElseGet(Set::of);
    }

    private Optional<TestClassInfo> toTestClassInfo(CompilationUnit compilationUnit, Path testFile) {
        Optional<ClassOrInterfaceDeclaration> topLevel = compilationUnit.getTypes().stream()
                .filter(ClassOrInterfaceDeclaration.class::isInstance)
                .map(ClassOrInterfaceDeclaration.class::cast)
                .findFirst();
        if (topLevel.isEmpty()) {
            return Optional.empty();
        }
        ClassOrInterfaceDeclaration testClass = topLevel.get();

        return Optional.of(new TestClassInfo(
                testFile.toAbsolutePath(),
                testClass.getNameAsString(),
                compilationUnit.getPackageDeclaration().map(p -> p.getNameAsString()).orElse(""),
                // Nested test methods are included: a generated method with the same name
                // would still be a duplicate as far as a reader is concerned.
                collectTestMethodNames(compilationUnit),
                collectMockFields(testClass),
                detectInjectionStyle(testClass),
                detectAssertionLibrary(compilationUnit),
                resolveClassAnnotations(testClass, compilationUnit),
                collectClassAnnotationAttributes(testClass),
                collectImports(compilationUnit, false),
                collectImports(compilationUnit, true)));
    }

    private Set<String> collectTestMethodNames(CompilationUnit compilationUnit) {
        Set<String> names = new LinkedHashSet<>();
        for (MethodDeclaration method : compilationUnit.findAll(MethodDeclaration.class)) {
            if (hasAnyAnnotationNamed(method.getAnnotations(), TEST_ANNOTATIONS)) {
                names.add(method.getNameAsString());
            }
        }
        return names;
    }

    private List<MockField> collectMockFields(ClassOrInterfaceDeclaration testClass) {
        List<MockField> mockFields = new ArrayList<>();
        for (FieldDeclaration field : testClass.getFields()) {
            Optional<String> mockAnnotation = annotationNameIn(field.getAnnotations(), MOCK_ANNOTATIONS);
            if (mockAnnotation.isEmpty()) {
                continue;
            }
            for (VariableDeclarator variable : field.getVariables()) {
                mockFields.add(new MockField(
                        variable.getNameAsString(), variable.getType().asString(), mockAnnotation.get()));
            }
        }
        return mockFields;
    }

    private InjectionStyle detectInjectionStyle(ClassOrInterfaceDeclaration testClass) {
        boolean hasSpringMockBeans = testClass.getFields().stream()
                .anyMatch(field -> annotationNameIn(field.getAnnotations(), MOCK_ANNOTATIONS)
                        .map(name -> new MockField("x", "X", name).isSpringMockBean())
                        .orElse(false));
        if (hasSpringMockBeans) {
            return InjectionStyle.SPRING_MOCK_BEANS;
        }
        boolean hasInjectMocks = testClass.getFields().stream()
                .anyMatch(field -> hasAnyAnnotationNamed(field.getAnnotations(), SUBJECT_ANNOTATIONS));
        if (hasInjectMocks) {
            return InjectionStyle.INJECT_MOCKS;
        }
        boolean constructsSomethingInSetup = testClass.getMethods().stream()
                .anyMatch(method -> hasAnyAnnotationNamed(method.getAnnotations(), Set.of("BeforeEach", "BeforeAll"))
                        && method.getBody().map(body -> body.toString().contains("new ")).orElse(false));
        return constructsSomethingInSetup ? InjectionStyle.MANUAL_CONSTRUCTION : InjectionStyle.NONE;
    }

    private AssertionLibrary detectAssertionLibrary(CompilationUnit compilationUnit) {
        String source = compilationUnit.toString();
        boolean usesAssertJ = source.contains("org.assertj");
        boolean usesJUnit = source.contains("org.junit.jupiter.api.Assertions")
                || source.contains("org.junit.Assert");
        boolean usesHamcrest = source.contains("org.hamcrest");

        int distinctLibraries = (usesAssertJ ? 1 : 0) + (usesJUnit ? 1 : 0) + (usesHamcrest ? 1 : 0);
        if (distinctLibraries > 1) {
            return AssertionLibrary.MIXED;
        }
        if (usesAssertJ) {
            return AssertionLibrary.ASSERTJ;
        }
        if (usesJUnit) {
            return AssertionLibrary.JUNIT;
        }
        return usesHamcrest ? AssertionLibrary.HAMCREST : AssertionLibrary.NONE;
    }

    private List<String> resolveClassAnnotations(
            ClassOrInterfaceDeclaration testClass, CompilationUnit compilationUnit) {
        return testClass.getAnnotations().stream()
                .map(annotation -> AnnotationFqnResolver.resolve(annotation, compilationUnit))
                .toList();
    }

    /** Attribute text of each class annotation, verbatim, keyed by its simple name. */
    private java.util.Map<String, String> collectClassAnnotationAttributes(
            ClassOrInterfaceDeclaration testClass) {
        java.util.Map<String, String> attributes = new java.util.LinkedHashMap<>();
        for (AnnotationExpr annotation : testClass.getAnnotations()) {
            String simpleName = annotation.getNameAsString();
            simpleName = simpleName.substring(simpleName.lastIndexOf('.') + 1);
            if (annotation.isSingleMemberAnnotationExpr()) {
                attributes.put(simpleName,
                        annotation.asSingleMemberAnnotationExpr().getMemberValue().toString());
            } else if (annotation.isNormalAnnotationExpr()) {
                attributes.put(simpleName, annotation.asNormalAnnotationExpr().getPairs().stream()
                        .map(pair -> pair.getNameAsString() + "=" + pair.getValue())
                        .collect(java.util.stream.Collectors.joining(", ")));
            } else {
                attributes.put(simpleName, "");
            }
        }
        return attributes;
    }

    private Set<String> collectImports(CompilationUnit compilationUnit, boolean staticImports) {
        Set<String> imports = new LinkedHashSet<>();
        for (ImportDeclaration importDeclaration : compilationUnit.getImports()) {
            if (importDeclaration.isStatic() == staticImports) {
                imports.add(importDeclaration.getNameAsString()
                        + (importDeclaration.isAsterisk() ? ".*" : ""));
            }
        }
        return imports;
    }

    private boolean hasAnyAnnotationNamed(Iterable<AnnotationExpr> annotations, Set<String> wanted) {
        return annotationNameIn(annotations, wanted).isPresent();
    }

    private Optional<String> annotationNameIn(Iterable<AnnotationExpr> annotations, Set<String> wanted) {
        for (AnnotationExpr annotation : annotations) {
            String simpleName = annotation.getNameAsString();
            simpleName = simpleName.substring(simpleName.lastIndexOf('.') + 1);
            if (wanted.contains(simpleName)) {
                return Optional.of(simpleName);
            }
        }
        return Optional.empty();
    }

    private Optional<CompilationUnit> parse(Path testFile) {
        if (!Files.isRegularFile(testFile)) {
            return Optional.empty();
        }
        try {
            return javaParser.parse(testFile).getResult();
        } catch (Exception e) {
            // A test file that will not parse is not this class's problem to report: the
            // build would already be failing, and callers treat "no info" as "nothing
            // known about this file", which is the safe reading.
            return Optional.empty();
        }
    }
}
