package com.devmanchego.jtestforge.analysis;

import com.devmanchego.jtestforge.model.Collaborator;
import com.devmanchego.jtestforge.model.ProductionClass;
import com.devmanchego.jtestforge.model.ProductionMethod;
import com.devmanchego.jtestforge.model.ProductionParameter;
import com.devmanchego.jtestforge.model.Visibility;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Problem;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Scans a module's main source root for concrete, testable production classes —
 * jtestforge-specification.md §7.1.
 *
 * <p>"Concrete and testable" is decided structurally here, independent of any user
 * configuration: interfaces, abstract classes, enums and records are never candidates for
 * a Mockito unit test, so they never become a {@link ProductionClass} at all. Enums and
 * records are excluded simply by construction - this scanner only looks at
 * {@link ClassOrInterfaceDeclaration} nodes, and both are distinct JavaParser node kinds
 * that never appear as one. Interfaces and abstract classes share that node kind, so they
 * are filtered explicitly. Everything else - packages, glob patterns, annotations,
 * complexity - is configurable policy, applied afterward by {@code SelectionFilter}
 * against the data recorded here, not decided by this class.
 *
 * <p>Local classes (declared inside a method body) are also excluded: they cannot be
 * referenced by a generated test class living in a different file.
 */
public final class ProductionClassScanner {

    private final JavaParser javaParser;
    private final JavaParser fallbackParser;
    private final Path mainSourceRoot;

    public ProductionClassScanner(TypeSolver typeSolver, Path mainSourceRoot) {
        this(typeSolver, mainSourceRoot, 0);
    }

    /**
     * @param javaRelease the release the module is written for, 0 if unknown. Parsing at
     *                    that level accepts old code newer levels reject (e.g. {@code _} as
     *                    an identifier); a file that fails is retried at Java 21, so a
     *                    wrongly detected release cannot fail a scan that used to pass.
     */
    public ProductionClassScanner(TypeSolver typeSolver, Path mainSourceRoot, int javaRelease) {
        this.mainSourceRoot = mainSourceRoot;
        ParserConfiguration.LanguageLevel level = JavaLanguageLevels.forRelease(javaRelease);
        this.javaParser = new JavaParser(parserConfiguration(typeSolver, level));
        this.fallbackParser = level == ParserConfiguration.LanguageLevel.JAVA_21
                ? null
                : new JavaParser(parserConfiguration(typeSolver, ParserConfiguration.LanguageLevel.JAVA_21));
    }

    /**
     * The language level must be set explicitly. JavaParser's default is older than the
     * target modules JTestForge is built to read, and an unset level turns any modern
     * construct - a {@code record}, a sealed type, a pattern switch - into a parse
     * failure on a module that compiles perfectly well.
     */
    public static ParserConfiguration parserConfiguration(TypeSolver typeSolver) {
        return parserConfiguration(typeSolver, ParserConfiguration.LanguageLevel.JAVA_21);
    }

    public static ParserConfiguration parserConfiguration(
            TypeSolver typeSolver, ParserConfiguration.LanguageLevel languageLevel) {
        return new ParserConfiguration()
                .setLanguageLevel(languageLevel)
                .setSymbolResolver(new JavaSymbolSolver(typeSolver));
    }

    /**
     * @throws ProductionScanException if a {@code .java} file under the source root
     *                                 cannot be parsed - jtestforge-specification.md §2
     *                                 requires the module to already build green, so a
     *                                 parse failure means that precondition does not hold
     */
    public List<ProductionClass> scan() {
        List<Path> sourceFiles = listJavaFiles();
        List<ProductionClass> result = new ArrayList<>();
        for (Path sourceFile : sourceFiles) {
            CompilationUnit compilationUnit = parse(sourceFile);
            for (ClassOrInterfaceDeclaration declaration : concreteTopLevelAndNestedClasses(compilationUnit)) {
                result.add(toProductionClass(declaration, sourceFile));
            }
        }
        return List.copyOf(result);
    }

    private List<Path> listJavaFiles() {
        if (!Files.isDirectory(mainSourceRoot)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(mainSourceRoot)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    // Deterministic order: scan results (and therefore work-unit
                    // ordering downstream) must not depend on filesystem enumeration
                    // order, which varies by OS and is not itself meaningful.
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list source files under " + mainSourceRoot, e);
        }
    }

    private CompilationUnit parse(Path sourceFile) {
        ParseResult<CompilationUnit> result = parseWith(javaParser, sourceFile);
        if (!isUsable(result) && fallbackParser != null) {
            ParseResult<CompilationUnit> fallback = parseWith(fallbackParser, sourceFile);
            if (isUsable(fallback)) {
                return fallback.getResult().get();
            }
        }
        if (!isUsable(result)) {
            String problems = result.getProblems().stream()
                    .map(Problem::getVerboseMessage)
                    .collect(Collectors.joining("; "));
            throw new ProductionScanException(sourceFile, problems);
        }
        return result.getResult().get();
    }

    private static ParseResult<CompilationUnit> parseWith(JavaParser parser, Path sourceFile) {
        try {
            return parser.parse(sourceFile);
        } catch (IOException e) {
            throw new ProductionScanException(sourceFile, e.getMessage());
        }
    }

    private static boolean isUsable(ParseResult<CompilationUnit> result) {
        return result.isSuccessful() && result.getResult().isPresent();
    }

    private List<ClassOrInterfaceDeclaration> concreteTopLevelAndNestedClasses(CompilationUnit compilationUnit) {
        return compilationUnit.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(decl -> !decl.isInterface())
                .filter(decl -> !decl.isAbstract())
                .filter(decl -> !decl.isLocalClassDeclaration())
                .toList();
    }

    private ProductionClass toProductionClass(ClassOrInterfaceDeclaration declaration, Path sourceFile) {
        String fqn = resolveQualifiedName(declaration);
        List<String> annotations = resolveAnnotations(declaration);
        List<Collaborator> collaborators = CollaboratorExtractor.extract(declaration);
        List<ProductionMethod> methods = declaration.getMethods().stream()
                .map(this::toProductionMethod)
                .toList();
        return new ProductionClass(fqn, sourceFile.toAbsolutePath(), annotations,
                supertypeNames(declaration), collaborators, methods,
                collectAnnotationAttributes(declaration.getAnnotations()));
    }

    /** Simple names of every extended and implemented type, as written in source. */
    private List<String> supertypeNames(ClassOrInterfaceDeclaration declaration) {
        List<String> names = new ArrayList<>();
        declaration.getExtendedTypes().forEach(type -> names.add(type.getNameAsString()));
        declaration.getImplementedTypes().forEach(type -> names.add(type.getNameAsString()));
        return names;
    }

    private String resolveQualifiedName(ClassOrInterfaceDeclaration declaration) {
        try {
            return declaration.resolve().getQualifiedName();
        } catch (RuntimeException e) {
            return declaration.getFullyQualifiedName().orElseGet(() -> declaration.getNameAsString());
        }
    }

    private List<String> resolveAnnotations(ClassOrInterfaceDeclaration declaration) {
        CompilationUnit compilationUnit = declaration.findCompilationUnit().orElseThrow();
        return resolveAnnotationNames(declaration.getAnnotations(), compilationUnit);
    }

    private ProductionMethod toProductionMethod(MethodDeclaration method) {
        CompilationUnit compilationUnit = method.findCompilationUnit().orElseThrow();
        List<ProductionParameter> parameters = method.getParameters().stream()
                .map(parameter -> toProductionParameter(parameter, compilationUnit))
                .toList();
        List<String> thrownTypes = method.getThrownExceptions().stream()
                .map(ReferenceType::asString)
                .toList();
        Range range = method.getRange().orElse(null);
        int startLine = range != null ? range.begin.line : 0;
        int endLine = range != null ? range.end.line : 0;

        return new ProductionMethod(
                method.getNameAsString(),
                TypeResolution.resolve(method.getType()),
                parameters,
                visibilityOf(method),
                method.isStatic(),
                resolveAnnotationNames(method.getAnnotations(), compilationUnit),
                collectAnnotationAttributes(method.getAnnotations()),
                thrownTypes,
                startLine,
                endLine,
                CyclomaticComplexityCalculator.calculate(method));
    }

    private ProductionParameter toProductionParameter(Parameter parameter, CompilationUnit compilationUnit) {
        return new ProductionParameter(
                parameter.getNameAsString(),
                TypeResolution.resolve(parameter.getType()),
                resolveAnnotationNames(parameter.getAnnotations(), compilationUnit));
    }

    private List<String> resolveAnnotationNames(
            NodeList<AnnotationExpr> annotations, CompilationUnit compilationUnit) {
        return annotations.stream()
                .map(annotation -> AnnotationFqnResolver.resolve(annotation, compilationUnit))
                .toList();
    }

    /**
     * Records each annotation's attribute text verbatim, keyed by simple name - the
     * mapping path, {@code produces}, {@code rollbackFor} and so on. Kept as raw source
     * text rather than a parsed structure: the callers that read it (§7.5's gap scanner,
     * §6.1's prompt placeholders) only ever need to recognise a literal or quote it back
     * to the model, and a full attribute model would be far more machinery than either
     * use justifies.
     */
    private Map<String, String> collectAnnotationAttributes(NodeList<AnnotationExpr> annotations) {
        Map<String, String> attributes = new LinkedHashMap<>();
        for (AnnotationExpr annotation : annotations) {
            String simpleName = annotation.getNameAsString();
            simpleName = simpleName.substring(simpleName.lastIndexOf('.') + 1);
            if (annotation.isSingleMemberAnnotationExpr()) {
                attributes.put(simpleName, annotation.asSingleMemberAnnotationExpr().getMemberValue().toString());
            } else if (annotation.isNormalAnnotationExpr()) {
                attributes.put(simpleName, annotation.asNormalAnnotationExpr().getPairs().stream()
                        .map(pair -> pair.getNameAsString() + "=" + pair.getValue())
                        .collect(Collectors.joining(", ")));
            } else {
                attributes.put(simpleName, "");
            }
        }
        return attributes;
    }

    private Visibility visibilityOf(MethodDeclaration method) {
        if (method.isPublic()) {
            return Visibility.PUBLIC;
        }
        if (method.isProtected()) {
            return Visibility.PROTECTED;
        }
        if (method.isPrivate()) {
            return Visibility.PRIVATE;
        }
        return Visibility.PACKAGE_PRIVATE;
    }
}
