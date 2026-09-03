package com.devmanchego.jtestforge.model;

import java.util.List;

/**
 * Matching of recorded annotation names against a wanted annotation.
 *
 * <p>Annotation names are resolved best-effort from import statements (see
 * {@code AnnotationFqnResolver}), so a recorded name may be fully qualified
 * ({@code org.springframework.web.bind.annotation.GetMapping}) or, when a wildcard import
 * left no way to qualify it, just the simple name ({@code GetMapping}). Matching accepts
 * either form so that classification does not silently miss a signal purely because the
 * target project happened to use {@code import ...web.bind.annotation.*}.
 *
 * <p>Comparing simple names risks a false positive from an unrelated same-named
 * annotation. That trade is deliberate: for the Spring, Jakarta and Lombok annotations
 * this tool matches on, a collision is far less likely than a wildcard import, and
 * missing a real {@code @PreAuthorize} is the more costly error - it would leave a
 * security contract unverified with no indication that anything was skipped.
 */
public final class AnnotationNames {

    private AnnotationNames() {
    }

    public static boolean contains(List<String> recordedAnnotations, String wanted) {
        String wantedSimpleName = simpleNameOf(wanted);
        for (String recorded : recordedAnnotations) {
            if (recorded.equals(wanted) || simpleNameOf(recorded).equals(wantedSimpleName)) {
                return true;
            }
        }
        return false;
    }

    public static boolean containsAny(List<String> recordedAnnotations, List<String> wanted) {
        return wanted.stream().anyMatch(name -> contains(recordedAnnotations, name));
    }

    public static String simpleNameOf(String annotationName) {
        int lastDot = annotationName.lastIndexOf('.');
        return lastDot < 0 ? annotationName : annotationName.substring(lastDot + 1);
    }
}
