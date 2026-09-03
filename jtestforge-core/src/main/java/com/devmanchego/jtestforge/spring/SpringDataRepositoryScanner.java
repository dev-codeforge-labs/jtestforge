package com.devmanchego.jtestforge.spring;

import com.devmanchego.jtestforge.analysis.ProductionScanException;
import com.devmanchego.jtestforge.model.ProductionClass;
import com.devmanchego.jtestforge.model.ProductionMethod;
import com.devmanchego.jtestforge.model.ProductionParameter;
import com.devmanchego.jtestforge.model.Visibility;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.Problem;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds Spring Data repository interfaces — jtestforge-specification.md §7.4.
 *
 * <p>Deliberately separate from {@code ProductionClassScanner}, which excludes every
 * interface because an interface cannot take a Mockito unit test. That exclusion is right
 * for T0 and wrong for T2: a Spring Data derived query method has no body, so there is
 * literally nothing to unit-test, and the only way to verify it is a {@code @DataJpaTest}
 * against a real database. Keeping this in the {@code spring} package puts the reason for
 * including these interfaces where the Spring knowledge lives, instead of teaching the
 * general-purpose scanner about a framework it should not know about.
 *
 * <p>Detection is by supertype name only, not resolved type hierarchy: Spring Data is
 * never on JTestForge's own classpath, and a target project's own base repository
 * interface (a common pattern) would not resolve either way.
 */
public final class SpringDataRepositoryScanner {

    private static final Set<String> REPOSITORY_SUPERTYPES = Set.of(
            "Repository", "CrudRepository", "ListCrudRepository",
            "PagingAndSortingRepository", "ListPagingAndSortingRepository",
            "JpaRepository", "MongoRepository", "ReactiveCrudRepository", "R2dbcRepository");

    private final JavaParser javaParser;
    private final Path mainSourceRoot;

    public SpringDataRepositoryScanner(JavaParser javaParser, Path mainSourceRoot) {
        this.javaParser = javaParser;
        this.mainSourceRoot = mainSourceRoot;
    }

    public List<ProductionClass> scan() {
        if (!Files.isDirectory(mainSourceRoot)) {
            return List.of();
        }
        List<ProductionClass> repositories = new ArrayList<>();
        for (Path sourceFile : listJavaFiles()) {
            CompilationUnit compilationUnit = parse(sourceFile);
            for (ClassOrInterfaceDeclaration declaration :
                    compilationUnit.findAll(ClassOrInterfaceDeclaration.class)) {
                if (declaration.isInterface() && extendsSpringDataRepository(declaration)) {
                    repositories.add(toProductionClass(declaration, sourceFile));
                }
            }
        }
        return List.copyOf(repositories);
    }

    private boolean extendsSpringDataRepository(ClassOrInterfaceDeclaration declaration) {
        for (ClassOrInterfaceType extended : declaration.getExtendedTypes()) {
            if (REPOSITORY_SUPERTYPES.contains(extended.getNameAsString())) {
                return true;
            }
        }
        return false;
    }

    private ProductionClass toProductionClass(ClassOrInterfaceDeclaration declaration, Path sourceFile) {
        String fqn = declaration.getFullyQualifiedName().orElseGet(declaration::getNameAsString);
        List<ProductionMethod> methods = declaration.getMethods().stream()
                .map(this::toQueryMethod)
                .toList();
        List<String> supertypes = declaration.getExtendedTypes().stream()
                .map(ClassOrInterfaceType::getNameAsString)
                .toList();
        // No collaborators: a repository interface has no dependencies to mock, and is
        // never instantiated by a generated test - the container supplies it.
        return new ProductionClass(fqn, sourceFile.toAbsolutePath(), List.of(), supertypes,
                List.of(), methods);
    }

    private ProductionMethod toQueryMethod(MethodDeclaration method) {
        List<ProductionParameter> parameters = method.getParameters().stream()
                .map(parameter -> new ProductionParameter(
                        parameter.getNameAsString(), parameter.getType().asString(), List.of()))
                .toList();
        Range range = method.getRange().orElse(null);
        String queryAttribute = method.getAnnotationByName("Query")
                .map(annotation -> annotation.isSingleMemberAnnotationExpr()
                        ? annotation.asSingleMemberAnnotationExpr().getMemberValue().toString()
                        : annotation.toString())
                .orElse(null);

        return new ProductionMethod(
                method.getNameAsString(),
                method.getType().asString(),
                parameters,
                Visibility.PUBLIC,
                false,
                method.getAnnotations().stream().map(a -> a.getNameAsString()).toList(),
                queryAttribute == null ? java.util.Map.of() : java.util.Map.of("Query", queryAttribute),
                List.of(),
                range != null ? range.begin.line : 0,
                range != null ? range.end.line : 0,
                // A derived query has no body at all; complexity is meaningless here and
                // must not accidentally filter these out via selection.minComplexity.
                Integer.MAX_VALUE);
    }

    private List<Path> listJavaFiles() {
        try (Stream<Path> walk = Files.walk(mainSourceRoot)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list source files under " + mainSourceRoot, e);
        }
    }

    private CompilationUnit parse(Path sourceFile) {
        ParseResult<CompilationUnit> result;
        try {
            result = javaParser.parse(sourceFile);
        } catch (IOException e) {
            throw new ProductionScanException(sourceFile, e.getMessage());
        }
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            throw new ProductionScanException(sourceFile, result.getProblems().stream()
                    .map(Problem::getVerboseMessage).collect(Collectors.joining("; ")));
        }
        return result.getResult().get();
    }
}
