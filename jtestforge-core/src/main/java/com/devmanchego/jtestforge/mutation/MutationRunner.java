package com.devmanchego.jtestforge.mutation;

import com.devmanchego.jtestforge.model.MutationReport;

/**
 * Runs PIT against one scoped request and returns its parsed result —
 * jtestforge-specification.md §13.2.
 *
 * <p>Two implementations, selected by {@code harden.engine}: {@code PitestMavenPluginRunner}
 * (the {@code pitest-maven} plugin, the default) and {@code StandaloneWrapperRunner} (an
 * external wrapper fat jar, for environments where {@code org.pitest} artifacts are
 * blocked in the internal Maven repository). Both parse to the same {@link MutationReport}
 * shape, so nothing above this interface needs to know which one ran.
 */
public interface MutationRunner {

    MutationReport run(MutationRequest request) throws MutationException;
}
