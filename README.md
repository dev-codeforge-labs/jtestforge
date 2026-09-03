# JTestForge

JTestForge is a Java 21 command-line tool that raises the **quality** of a Maven
module's JUnit 5 test suite — plain Mockito unit tests and Spring unit/slice tests —
by driving an external AI CLI (Claude CLI, Gemini CLI) in a closed, verified loop.

It is not "ask a model for a test." Every candidate is compiled, run, and measured
before it is kept:

1. **Line/branch coverage** — for methods with no test, or only partial coverage.
2. **Framework-semantic coverage** — for behaviour a direct method call can't reach at
   all and that therefore never shows up as a coverage gap: request mappings, bean
   validation, exception-to-status translation, method security, transaction
   boundaries. A Spring `@RestController` can sit at 100% line coverage with none of
   its actual contract verified.
3. **Mutation coverage** *(opt-in, `harden` pass)* — for methods that are covered but
   whose tests don't actually detect defects, evidenced by mutants PIT proves survive.

A generated candidate that doesn't move one of these numbers is discarded, not kept
for style points.

## Status

| Command | Status |
|---|---|
| `init` | Works. Scaffolds `jtestforge.yaml` and the bundled prompt templates. |
| `scan` | Works. Read-only dry run — no AI calls, no writes. |
| `generate` | Works. Verified end to end against a real Spring Boot module and the real `claude` CLI. |
| `status` | Works. Prints the current run state. |
| `report` | Works. Renders Markdown/HTML from the last run's state. |
| `clean` | Works. Removes state, transcripts, backups. |
| `harden` | **Not implemented yet.** The mutation-testing engine exists and is unit-tested, but it isn't wired to this command line — see [usage.md](usage.md#what-doesnt-work-yet). |

Full detail on every command, flag, exit code and config option is in
**[usage.md](usage.md)**. This file is a quick overview, not the manual.

## Requirements

- Java 21
- The target project: a Maven module (a `pom.xml`) that builds green before a run
- An AI CLI on `PATH` for `generate` — `claude` or `gemini` by default

## Building

```bash
mvn -o clean install
```

Produces a self-contained shaded jar at `jtestforge-cli/target/jtestforge-<version>.jar`.

## Quick start

```bash
cd my-maven-module

java -jar jtestforge.jar init                     # scaffold jtestforge.yaml + prompt templates
java -jar jtestforge.jar scan                      # see what generate would attempt, for free
java -jar jtestforge.jar generate --tier PLAIN_UNIT # fast run: plain Mockito tests, no Spring context
java -jar jtestforge.jar report                     # what changed, and why anything was discarded
```

## How it works

For each eligible production method, `generate`:

1. Renders a prompt from the method's signature, its collaborators, existing test
   class, and (for Spring-tier work) the module's detected Spring stack facts.
2. Invokes the configured AI provider.
3. Parses the response and applies static quality guards (banned constructs, missing
   assertions, mock-only tests, disallowed wildcard imports...).
4. Merges surviving candidates into the test class's AST and compiles.
5. Runs only the new test methods, scoped — not the whole suite yet.
6. Checks the value gate: did coverage actually move, or did this close a
   framework-semantic gap the class couldn't otherwise prove?
7. Keeps it, or reverts and records why it was discarded.

A method that already has a passing test and 100% coverage is not necessarily done —
if it's a Spring-mediated behaviour (a mapping, a validation rule, a security
annotation) that no test actually exercises through the framework, JTestForge still
proposes a slice test for it. Every run ends with a full-suite verification, and every
run's state is crash-safe and resumable: kill the process mid-run and the next
invocation reconciles against what's actually on disk before continuing.

## Configuration

`jtestforge.yaml`, scaffolded by `init`, drives everything: which classes/methods are
eligible, the AI provider and its invocation shape, which Spring test tiers are
allowed, context-injection limits, and the acceptance thresholds for `generate` and
`harden`. See [usage.md](usage.md#configuration) for the annotated reference, or
[jtestforge-specification.md](jtestforge-specification.md) §5 for the complete spec.

## Project layout

- `jtestforge-core` — the engine: AST analysis, Spring detection, prompt assembly,
  response parsing, quality guards, coverage/mutation measurement, state management.
  Usable as a library independent of the CLI.
- `jtestforge-cli` — the Picocli command-line front end over `jtestforge-core`.

## Design documents

- [jtestforge-specification.md](jtestforge-specification.md) — the full functional and
  technical specification.
- [jtestforge-implementation-plan.md](jtestforge-implementation-plan.md) — the
  phase-by-phase build log, including what each phase verified and any real defects it
  found along the way.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
