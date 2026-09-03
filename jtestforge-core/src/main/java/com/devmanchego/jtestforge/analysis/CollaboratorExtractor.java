package com.devmanchego.jtestforge.analysis;

import com.devmanchego.jtestforge.model.Collaborator;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Finds the dependencies a production class needs mocked — jtestforge-specification.md
 * §7.1.
 *
 * <p>Two sources, matching how Spring/plain-Java classes actually declare their
 * dependencies:
 *
 * <ol>
 *   <li><b>Constructor injection</b> - the common case. If the class declares exactly one
 *       constructor, every parameter is a collaborator. With more than one, the
 *       {@code @Autowired}/{@code @Inject}-annotated constructor is used if present
 *       (Spring itself requires this annotation to disambiguate); otherwise the
 *       constructor with the most parameters is used, as an approximation of "the
 *       greediest constructor is the real one" - documented as a heuristic because Spring's
 *       actual resolution can depend on bean definitions this scanner cannot see.</li>
 *   <li><b>Field injection</b> - only fields explicitly annotated
 *       {@code @Autowired}/{@code @Inject}/{@code @Resource}. Sweeping in every field
 *       would misclassify plain value fields (e.g. a cached default) as things to mock.</li>
 * </ol>
 *
 * <p>Both sources are matched by annotation simple name only, deliberately not resolved
 * to a FQN: a target module's Spring/Jakarta annotations are never on JTestForge's own
 * classpath (see {@link AnnotationFqnResolver}'s Javadoc for the same reasoning applied
 * to class-level exclusion).
 */
final class CollaboratorExtractor {

    private static final Set<String> INJECTION_ANNOTATION_NAMES = Set.of("Autowired", "Inject", "Resource");

    private CollaboratorExtractor() {
    }

    static List<Collaborator> extract(ClassOrInterfaceDeclaration classDeclaration) {
        Map<String, Collaborator> byName = new LinkedHashMap<>();

        selectInjectionConstructor(classDeclaration).ifPresent(constructor -> {
            for (Parameter parameter : constructor.getParameters()) {
                String name = parameter.getNameAsString();
                byName.put(name, new Collaborator(name, resolveTypeFqn(parameter)));
            }
        });

        for (FieldDeclaration field : classDeclaration.getFields()) {
            if (!hasInjectionAnnotation(field.getAnnotations())) {
                continue;
            }
            for (VariableDeclarator variable : field.getVariables()) {
                String name = variable.getNameAsString();
                byName.putIfAbsent(name, new Collaborator(name, resolveTypeFqn(variable)));
            }
        }

        return List.copyOf(byName.values());
    }

    private static Optional<ConstructorDeclaration> selectInjectionConstructor(
            ClassOrInterfaceDeclaration classDeclaration) {
        List<ConstructorDeclaration> constructors = classDeclaration.getConstructors();
        if (constructors.isEmpty()) {
            return Optional.empty();
        }
        if (constructors.size() == 1) {
            return Optional.of(constructors.get(0));
        }
        return constructors.stream()
                .filter(c -> hasInjectionAnnotation(c.getAnnotations()))
                .findFirst()
                .or(() -> constructors.stream()
                        .max(Comparator.comparingInt(c -> c.getParameters().size())));
    }

    private static boolean hasInjectionAnnotation(Iterable<AnnotationExpr> annotations) {
        for (AnnotationExpr annotation : annotations) {
            if (INJECTION_ANNOTATION_NAMES.contains(annotation.getNameAsString())) {
                return true;
            }
        }
        return false;
    }

    private static String resolveTypeFqn(Parameter parameter) {
        return TypeResolution.resolve(parameter.getType());
    }

    private static String resolveTypeFqn(VariableDeclarator variable) {
        return TypeResolution.resolve(variable.getType());
    }
}
