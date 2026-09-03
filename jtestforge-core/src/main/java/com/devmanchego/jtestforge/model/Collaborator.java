package com.devmanchego.jtestforge.model;

import java.util.Objects;

/**
 * One dependency a production class needs mocked — jtestforge-specification.md §7.1.
 * Sourced from the class's injection constructor parameters and any field explicitly
 * annotated {@code @Autowired}/{@code @Inject}/{@code @Resource} (§7.1, §6.1
 * {@code {{COLLABORATORS}}}).
 *
 * @param name    field or parameter name, used for the generated {@code @Mock} field
 * @param typeFqn resolved fully-qualified type, or the as-declared type name if
 *                resolution failed - one unresolvable collaborator must not block
 *                scanning the rest of the class
 */
public record Collaborator(String name, String typeFqn) {

    public Collaborator {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(typeFqn, "typeFqn");
    }
}
