package com.devmanchego.jtestforge.coverage;

import com.devmanchego.jtestforge.model.ClassCoverage;
import com.devmanchego.jtestforge.model.MethodCoverage;
import com.devmanchego.jtestforge.model.ProductionClass;
import com.devmanchego.jtestforge.model.ProductionMethod;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Matches AST-scanned {@link ProductionMethod}s to their JaCoCo {@link MethodCoverage} —
 * jtestforge-implementation-plan.md phase 8's "fiddly part": JaCoCo identifies a method by
 * name and JVM descriptor, the AST scan by name and resolved parameter types. Getting an
 * overload wrong here silently attributes coverage to the wrong method, which would
 * corrupt the value gate (§9.4) for both methods at once - one would look covered when it
 * is not, the other uncovered when it is.
 *
 * <p>Matching is on <b>erased</b> parameter types on both sides: a JVM descriptor is
 * already erased ({@code List<String>} and {@code List<Integer>} are both
 * {@code Ljava/util/List;}), so the AST's fully-generic type strings
 * (from {@code TypeResolution}, e.g. {@code java.util.List<java.lang.String>}) are erased
 * to match by dropping the type-argument suffix.
 *
 * <p>Constructors ({@code <init>}) and static initializers ({@code <clinit>}) are never
 * matched: neither has a corresponding {@code ProductionMethod} (§7.1 scans declared
 * methods only), and synthetic entries JaCoCo emits for lambdas
 * ({@code lambda$foo$0}) never collide with a real method's name either, so no special
 * filtering is needed for those.
 *
 * <p><b>Varargs are a genuine AST/bytecode mismatch, not just an erasure question.</b>
 * For {@code int... values}, JavaParser's resolved parameter type is the component type
 * {@code int} - not the array type {@code int[]} the JVM descriptor actually declares
 * ({@code ACC_VARARGS} is a method flag, invisible to the descriptor and to
 * {@code TypeResolution}). A strict type-list comparison would never match a varargs
 * method to its coverage entry at all. This is handled narrowly: only the last parameter
 * is allowed to match when the JVM side is exactly the AST side plus one array dimension,
 * so an ordinary {@code int[]} parameter (which already matches exactly) is never
 * affected by the allowance.
 */
public final class CoverageMethodJoiner {

    public Optional<MethodCoverage> find(ProductionMethod method, ClassCoverage classCoverage) {
        List<String> wantedParameterTypes = erasedTypes(method.parameterTypes());
        return classCoverage.methods().stream()
                .filter(candidate -> !candidate.isConstructorOrStaticInitializer())
                .filter(candidate -> candidate.name().equals(method.name()))
                .filter(candidate -> parameterTypesMatch(
                        wantedParameterTypes, MethodDescriptorMapper.parameterTypesOf(candidate.jvmDescriptor())))
                .findFirst();
    }

    private boolean parameterTypesMatch(List<String> astTypes, List<String> jvmTypes) {
        if (astTypes.equals(jvmTypes)) {
            return true;
        }
        if (astTypes.size() != jvmTypes.size() || astTypes.isEmpty()) {
            return false;
        }
        int lastIndex = astTypes.size() - 1;
        for (int i = 0; i < lastIndex; i++) {
            if (!astTypes.get(i).equals(jvmTypes.get(i))) {
                return false;
            }
        }
        // Varargs allowance, last parameter only: int (AST) vs int[] (JVM descriptor).
        return jvmTypes.get(lastIndex).equals(astTypes.get(lastIndex) + "[]");
    }

    /** Every method of {@code productionClass} that has a matching JaCoCo entry. */
    public Map<ProductionMethod, MethodCoverage> joinAll(
            ProductionClass productionClass, ClassCoverage classCoverage) {
        Map<ProductionMethod, MethodCoverage> joined = new LinkedHashMap<>();
        for (ProductionMethod method : productionClass.methods()) {
            find(method, classCoverage).ifPresent(coverage -> joined.put(method, coverage));
        }
        return Map.copyOf(joined);
    }

    /** Drops generic type arguments, e.g. {@code java.util.List<java.lang.String>} -> {@code java.util.List}. */
    private List<String> erasedTypes(List<String> parameterTypes) {
        return parameterTypes.stream().map(this::erase).toList();
    }

    private String erase(String typeName) {
        int typeArgumentsStart = typeName.indexOf('<');
        return typeArgumentsStart < 0 ? typeName : typeName.substring(0, typeArgumentsStart);
    }
}
