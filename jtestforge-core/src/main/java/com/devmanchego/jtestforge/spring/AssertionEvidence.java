package com.devmanchego.jtestforge.spring;

import com.devmanchego.jtestforge.model.AssertionShape;

/**
 * One assertion shape found in an existing test, with the endpoint it targets when the
 * shape is a web one.
 *
 * <p>Evidence is matched against a {@link com.devmanchego.jtestforge.model.SemanticGap}
 * by shape and target, never by test-method name: a name is a convention the target
 * project may not follow, and a test could satisfy any naming rule while asserting
 * nothing relevant.
 *
 * @param shape      the structural form observed
 * @param httpMethod HTTP method for web evidence, otherwise {@code null}
 * @param path       requested path for web evidence, otherwise {@code null}
 */
public record AssertionEvidence(AssertionShape shape, String httpMethod, String path) {

    public static AssertionEvidence of(AssertionShape shape) {
        return new AssertionEvidence(shape, null, null);
    }

    public static AssertionEvidence web(AssertionShape shape, String httpMethod, String path) {
        return new AssertionEvidence(shape, httpMethod, path);
    }
}
