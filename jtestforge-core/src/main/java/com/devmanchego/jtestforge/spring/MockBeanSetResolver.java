package com.devmanchego.jtestforge.spring;

import com.devmanchego.jtestforge.model.Collaborator;
import com.devmanchego.jtestforge.model.MockBeanDeclaration;
import com.devmanchego.jtestforge.model.ProductionClass;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Derives a slice class's mock-bean set from the production class's collaborators —
 * jtestforge-specification.md §7.6.
 *
 * <p>Computed once per class, deliberately. The set of mock-bean definitions is part of
 * Spring's context cache key, so growing it test by test would fork a new context for
 * every test added. Deriving it up front from what the class actually depends on gets it
 * right the first time in the common case, and leaves the escalation path (§7.6) for the
 * rest.
 */
public final class MockBeanSetResolver {

    public List<MockBeanDeclaration> resolve(ProductionClass productionClass) {
        List<MockBeanDeclaration> declarations = new ArrayList<>();
        Set<String> declaredNames = new LinkedHashSet<>();
        for (Collaborator collaborator : productionClass.collaborators()) {
            if (declaredNames.add(collaborator.name())) {
                declarations.add(new MockBeanDeclaration(collaborator.name(), collaborator.typeFqn()));
            }
        }
        return List.copyOf(declarations);
    }
}
