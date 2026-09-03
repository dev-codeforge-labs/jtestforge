package com.devmanchego.jtestforge.analysis;

import com.devmanchego.jtestforge.config.SelectionConfig;
import com.devmanchego.jtestforge.model.ProductionClass;
import com.devmanchego.jtestforge.model.ProductionMethod;
import com.devmanchego.jtestforge.model.Visibility;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.Objects;

/**
 * Applies the {@code selection} configuration block — jtestforge-specification.md §5 —
 * against classes and methods a {@link ProductionClassScanner} has already found.
 *
 * <p>Deliberately separate from the scanner: everything decided here is user-configurable
 * policy (which packages, which annotations, which complexity threshold), whereas the
 * scanner decides only what is structurally possible to generate a test for at all. That
 * split is what lets this class be tested against plain {@link ProductionClass} fixtures,
 * with no parsing or symbol resolution involved.
 *
 * <p>{@code excludeClasses} patterns (e.g. {@code "**&#47;*Config"}, {@code "**&#47;dto/**"})
 * are matched as glob patterns against the class's fully-qualified name rewritten as a
 * slash-separated path (e.g. {@code com/acme/dto/UserDto}) - not against the source file
 * path. The sample patterns in §5 carry no {@code .java} suffix, which only makes sense
 * read this way; matching against the actual file path would require every pattern to
 * repeat the extension.
 */
public final class SelectionFilter {

    private final SelectionConfig config;
    private final List<PathMatcher> excludeClassMatchers;

    public SelectionFilter(SelectionConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.excludeClassMatchers = config.excludeClasses().stream()
                .map(pattern -> FileSystems.getDefault().getPathMatcher("glob:" + pattern))
                .toList();
    }

    public boolean isClassIncluded(ProductionClass productionClass) {
        String packageName = productionClass.packageName();
        return isPackageIncluded(packageName)
                && !isPackageExcluded(packageName)
                && !isPathExcluded(productionClass.fqn())
                && !hasExcludedAnnotation(productionClass);
    }

    public boolean isMethodIncluded(ProductionMethod method) {
        if (!config.includePrivateMethods() && method.visibility() == Visibility.PRIVATE) {
            return false;
        }
        return method.cyclomaticComplexity() >= config.minComplexity();
    }

    private boolean isPackageIncluded(String packageName) {
        List<String> includePackages = config.includePackages();
        if (includePackages.isEmpty()) {
            return true;
        }
        return includePackages.stream().anyMatch(prefix -> matchesPackagePrefix(packageName, prefix));
    }

    private boolean isPackageExcluded(String packageName) {
        return config.excludePackages().stream().anyMatch(prefix -> matchesPackagePrefix(packageName, prefix));
    }

    private static boolean matchesPackagePrefix(String packageName, String prefix) {
        return packageName.equals(prefix) || packageName.startsWith(prefix + ".");
    }

    private boolean isPathExcluded(String fqn) {
        Path asPath = Path.of(fqn.replace('.', '/'));
        return excludeClassMatchers.stream().anyMatch(matcher -> matcher.matches(asPath));
    }

    private boolean hasExcludedAnnotation(ProductionClass productionClass) {
        return config.excludeAnnotations().stream().anyMatch(productionClass::hasAnnotation);
    }
}
