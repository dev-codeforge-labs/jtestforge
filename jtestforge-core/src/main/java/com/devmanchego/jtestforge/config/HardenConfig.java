package com.devmanchego.jtestforge.config;

import com.devmanchego.jtestforge.model.Tier;

/** {@code harden} block — jtestforge-specification.md §5, §10, §10.3. */
public record HardenConfig(
        HardenEngine engine,
        String standaloneWrapperJar,
        String mutators,
        Integer maxMutantsPerPrompt,
        Integer maxAttemptsPerMutant,
        Integer minMutationScore,
        Tier maxTierForMutation,
        String unkillableMutantsFile,
        String historyFile) {

    public HardenConfig {
        engine = engine == null ? HardenEngine.PITEST_MAVEN_PLUGIN : engine;
        mutators = mutators == null ? "DEFAULTS" : mutators;
        maxMutantsPerPrompt = maxMutantsPerPrompt == null ? 6 : maxMutantsPerPrompt;
        maxAttemptsPerMutant = maxAttemptsPerMutant == null ? 2 : maxAttemptsPerMutant;
        minMutationScore = minMutationScore == null ? 70 : minMutationScore;
        maxTierForMutation = maxTierForMutation == null ? Tier.PLAIN_UNIT : maxTierForMutation;
        unkillableMutantsFile = unkillableMutantsFile == null ? ".jtestforge/unkillable.txt" : unkillableMutantsFile;
        historyFile = historyFile == null ? ".jtestforge/pit-history.bin" : historyFile;
    }
}
