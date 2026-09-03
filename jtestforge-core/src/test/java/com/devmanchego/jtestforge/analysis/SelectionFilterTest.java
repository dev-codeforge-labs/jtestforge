package com.devmanchego.jtestforge.analysis;

import com.devmanchego.jtestforge.config.SelectionConfig;
import com.devmanchego.jtestforge.config.SelectionOrder;
import com.devmanchego.jtestforge.model.ProductionClass;
import com.devmanchego.jtestforge.model.ProductionMethod;
import com.devmanchego.jtestforge.model.Visibility;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SelectionFilterTest {

    @Test
    void withNoIncludePackagesEverythingIsIncludedByDefault() {
        SelectionFilter filter = filterWith(config(List.of(), List.of(), List.of(), List.of()));

        assertThat(filter.isClassIncluded(classNamed("com.acme.PaymentService"))).isTrue();
    }

    @Test
    void includePackagesRestrictsToTheListedPackagesAndTheirSubpackages() {
        SelectionFilter filter = filterWith(
                config(List.of("com.acme.service"), List.of(), List.of(), List.of()));

        assertThat(filter.isClassIncluded(classNamed("com.acme.service.PaymentService"))).isTrue();
        assertThat(filter.isClassIncluded(classNamed("com.acme.service.billing.Invoicer"))).isTrue();
        assertThat(filter.isClassIncluded(classNamed("com.acme.web.PaymentController"))).isFalse();
    }

    @Test
    void includePackagesDoesNotMatchAPackageThatMerelySharesAPrefixString() {
        // "com.acme.service" must not match "com.acme.serviceRegistry" - a naive
        // String.startsWith without a dot boundary would get this wrong.
        SelectionFilter filter = filterWith(
                config(List.of("com.acme.service"), List.of(), List.of(), List.of()));

        assertThat(filter.isClassIncluded(classNamed("com.acme.serviceRegistry.Foo"))).isFalse();
    }

    @Test
    void excludePackagesRemovesMatchingPackagesEvenIfIncluded() {
        SelectionFilter filter = filterWith(
                config(List.of("com.acme"), List.of("com.acme.generated"), List.of(), List.of()));

        assertThat(filter.isClassIncluded(classNamed("com.acme.PaymentService"))).isTrue();
        assertThat(filter.isClassIncluded(classNamed("com.acme.generated.Proto"))).isFalse();
    }

    @Test
    void excludeClassesGlobMatchesASuffixPattern() {
        SelectionFilter filter = filterWith(
                config(List.of(), List.of(), List.of("**/*Config"), List.of()));

        assertThat(filter.isClassIncluded(classNamed("com.acme.AppConfig"))).isFalse();
        assertThat(filter.isClassIncluded(classNamed("com.acme.PaymentService"))).isTrue();
    }

    @Test
    void excludeClassesGlobMatchesAMiddlePackageSegment() {
        SelectionFilter filter = filterWith(
                config(List.of(), List.of(), List.of("**/dto/**"), List.of()));

        assertThat(filter.isClassIncluded(classNamed("com.acme.dto.UserDto"))).isFalse();
        assertThat(filter.isClassIncluded(classNamed("com.acme.domain.User"))).isTrue();
    }

    @Test
    void excludeAnnotationsRemovesAClassCarryingAnyOfTheConfiguredAnnotations() {
        SelectionFilter filter = filterWith(config(List.of(), List.of(), List.of(),
                List.of("jakarta.persistence.Entity", "lombok.Generated")));
        ProductionClass entity = new ProductionClass("com.acme.CustomerEntity",
                Path.of("CustomerEntity.java"), List.of("jakarta.persistence.Entity"),
                List.of(), List.of(), List.of());

        assertThat(filter.isClassIncluded(entity)).isFalse();
    }

    @Test
    void aClassWithNoneOfTheExcludedAnnotationsIsIncluded() {
        SelectionFilter filter = filterWith(config(List.of(), List.of(), List.of(),
                List.of("jakarta.persistence.Entity")));

        assertThat(filter.isClassIncluded(classNamed("com.acme.PaymentService"))).isTrue();
    }

    @Test
    void privateMethodsAreExcludedByDefault() {
        SelectionFilter filter = filterWith(config(List.of(), List.of(), List.of(), List.of()));

        assertThat(filter.isMethodIncluded(methodWith(Visibility.PRIVATE, 5))).isFalse();
        assertThat(filter.isMethodIncluded(methodWith(Visibility.PUBLIC, 5))).isTrue();
    }

    @Test
    void privateMethodsAreIncludedWhenConfigured() {
        SelectionConfig config = new SelectionConfig(
                List.of(), List.of(), List.of(), List.of(), null, true, SelectionOrder.DECLARATION);
        SelectionFilter filter = new SelectionFilter(config);

        assertThat(filter.isMethodIncluded(methodWith(Visibility.PRIVATE, 5))).isTrue();
    }

    @Test
    void methodsBelowMinComplexityAreExcluded() {
        SelectionConfig config = new SelectionConfig(
                List.of(), List.of(), List.of(), List.of(), 3, false, SelectionOrder.DECLARATION);
        SelectionFilter filter = new SelectionFilter(config);

        assertThat(filter.isMethodIncluded(methodWith(Visibility.PUBLIC, 2))).isFalse();
        assertThat(filter.isMethodIncluded(methodWith(Visibility.PUBLIC, 3))).isTrue();
    }

    private SelectionFilter filterWith(SelectionConfig config) {
        return new SelectionFilter(config);
    }

    private SelectionConfig config(
            List<String> includePackages, List<String> excludePackages,
            List<String> excludeClasses, List<String> excludeAnnotations) {
        return new SelectionConfig(includePackages, excludePackages, excludeClasses,
                excludeAnnotations, null, null, null);
    }

    private ProductionClass classNamed(String fqn) {
        return new ProductionClass(fqn, Path.of(fqn.replace('.', '/') + ".java"),
                List.of(), List.of(), List.of(), List.of());
    }

    private ProductionMethod methodWith(Visibility visibility, int complexity) {
        return new ProductionMethod("m", "int", List.of(), visibility, false,
                List.of(), java.util.Map.of(), List.of(), 1, 1, complexity);
    }
}
