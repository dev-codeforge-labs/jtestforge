package com.devmanchego.jtestforge.orchestration;

/**
 * Processes one work unit — steps 3 to 9 of jtestforge-specification.md §9.4.
 *
 * <p>An interface, with {@link DefaultUnitProcessor} as the only production
 * implementation, so {@link GenerateEngine}'s own responsibilities - preflight, the
 * write-ahead marker, run limits, state transitions, final verification - can be tested
 * against scripted unit outcomes. Exercising those through a real generation would mean
 * every engine test also had to stand up an AI provider, a merger, a build and a coverage
 * report, and would test all of those far more than the engine.
 */
public interface UnitProcessor {

    UnitOutcome process(UnitContext context);
}
