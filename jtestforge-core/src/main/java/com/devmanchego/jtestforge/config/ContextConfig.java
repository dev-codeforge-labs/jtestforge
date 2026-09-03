package com.devmanchego.jtestforge.config;

/** {@code context} block — jtestforge-specification.md §5, §6.1. */
public record ContextConfig(
        Boolean includeFullProductionClass,
        Boolean includeCollaboratorSignatures,
        Integer maxCollaborators,
        Boolean includeExistingTestClass,
        Integer maxExistingTestChars,
        Boolean includeDetectedFrameworkVersions,
        Integer maxPromptChars) {

    public ContextConfig {
        includeFullProductionClass = includeFullProductionClass == null ? Boolean.TRUE : includeFullProductionClass;
        includeCollaboratorSignatures = includeCollaboratorSignatures == null ? Boolean.TRUE : includeCollaboratorSignatures;
        maxCollaborators = maxCollaborators == null ? 12 : maxCollaborators;
        includeExistingTestClass = includeExistingTestClass == null ? Boolean.TRUE : includeExistingTestClass;
        maxExistingTestChars = maxExistingTestChars == null ? 20000 : maxExistingTestChars;
        includeDetectedFrameworkVersions = includeDetectedFrameworkVersions == null ? Boolean.TRUE : includeDetectedFrameworkVersions;
        maxPromptChars = maxPromptChars == null ? 60000 : maxPromptChars;
    }
}
