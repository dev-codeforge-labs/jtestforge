# JTestForge

JTestForge is a Java 21 command-line tool that raises the **quality** of a Maven
module's JUnit 5 test suite — plain Mockito unit tests and Spring unit/slice tests —
by driving an external AI CLI (Claude CLI, Gemini CLI, GitHub Copilot CLI, or any other
non-interactive AI CLI you configure) in a closed, verified loop.

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

Every candidate has to compile, pass and survive the quality guards before it is kept. By
default (`generate.requireCoverageGain: false`) that is all it takes - a test that compiles and
passes is kept even if it moves no number, and coverage is not even measured per unit. Set
`requireCoverageGain: true` to hold candidates to the stricter rule: one that doesn't move
coverage (or, for a Spring tier, close a framework-semantic gap) is discarded, not kept for
style points. Either way, read what a model kept - a unit marked `DONE` is not a verdict on
the tests' quality.

## Status

| Command | Status |
|---|---|
| `init` | Works. Scaffolds `jtestforge.yaml` and the bundled prompt templates. |
| `scan` | Works. Read-only dry run — no AI calls, no writes. |
| `generate` | Works. Verified end to end against a real Spring Boot module and the real `claude` CLI. `--dry-run` lists the units and writes the prompts without calling any AI. |
| `status` | Works. Prints the current run state. |
| `report` | Works. Renders Markdown/HTML from the last run's state. |
| `clean` | Works. Removes state, transcripts, backups. |
| `harden` | **Not implemented yet.** The mutation-testing engine exists and is unit-tested, but it isn't wired to this command line — see [usage.md](usage.md#what-doesnt-work-yet). |

Full detail on every command, flag, exit code and config option is in
**[usage.md](usage.md)**. This file is a quick overview, not the manual.

## Requirements

- Java 21
- The target project: a Maven module (a `pom.xml`) that builds green before a run
- An AI CLI on `PATH` for `generate` — `claude`, `gemini`, `copilot` or `codex`, all
  verified; see [usage.md](usage.md#ai-providers) for how to add another
- Or a local model through Ollama, driven by command or directly over its HTTP API, so the
  code under test never leaves your machine (see [usage.md](usage.md#ollama-local-model)).
  A small local model follows the response format less reliably than a hosted one; expect
  more discarded units

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
java -jar jtestforge.jar generate --dry-run         # list the units and write their prompts; no AI call
java -jar jtestforge.jar generate --tier PLAIN_UNIT # fast run: plain Mockito tests, no Spring context
java -jar jtestforge.jar report                     # what changed, and why anything was discarded
```

## How it works

For each eligible production method, `generate`:

1. Renders a prompt from the method's signature, its collaborators, existing test
   class, and (for Spring-tier work) the module's detected Spring stack facts.
2. Invokes the configured AI provider (a CLI process, or an HTTP call to a local model server).
3. Parses the response - tolerating the usual slips of a model, such as imports under the
   wrong label or a stray code fence, and asking once for a corrected answer when it still
   cannot be read - and applies static quality guards (banned constructs, missing
   assertions, mock-only tests, disallowed wildcard imports, tests that merely repeat
   another test's body...).
4. Merges surviving candidates into the test class's AST and compiles.
5. Runs only the new test methods, scoped — not the whole suite yet.
6. Checks the value gate - when `requireCoverageGain` is on: did coverage actually move, or
   did this close a framework-semantic gap the class couldn't otherwise prove?
7. Keeps it, or reverts and records why it was discarded.

A method that already has a passing test and 100% coverage is not necessarily done —
if it's a Spring-mediated behaviour (a mapping, a validation rule, a security
annotation) that no test actually exercises through the framework, JTestForge still
proposes a slice test for it. Every run ends with a full-suite verification.

## Your tests are not put at risk

JTestForge writes into test files you own, so it is built to leave them as it found them
whenever something goes wrong:

- **Edits are recorded before they are made.** Kill the process at any moment (Ctrl+C, or
  a hard kill) and the next run - or the shutdown hook - takes out exactly what the
  interrupted unit had merged, including a test class it had just created and left empty.
  A unit whose own cleanup failed keeps its record, so it is not forgotten.
- **Reverts subtract, they never restore a copy.** A failed unit removes only what it
  added, so work that earlier units kept in the same class survives.
- **An existing test file that cannot be read or parsed is never overwritten** - the unit is
  skipped and the report says why.
- **Source encoding is respected.** It is read from the pom (or `project.sourceEncoding` /
  `--source-encoding`) and files are written back in it, so a module saved as ISO-8859-1
  keeps its accents (see [usage.md](usage.md#source-encoding-of-the-target-code)).
- **Optional backups** of each existing test file before its first change
  (`execution.backupOriginalTests`, on by default).
- **State is crash-safe and resumable**, and a resumed run reconciles against what is
  actually on disk before continuing.
- **`-v` output masks environment values** you configure for an AI CLI, except a short list
  of harmless ones, so it is safe to paste into a bug report.

### Ollama over HTTP

Besides driving `ollama run`, a provider can be declared `type: http` and call the server
directly. A request can then set the context size, temperature and output limit per call,
and JTestForge asks the server to refuse a prompt that does not fit instead of silently
cutting off its start. A check before the first unit tells you whether the server is up,
has the model, and has a context large enough for the prompt budget. See
[usage.md](usage.md#ollama-over-http-type-http).

## Configuration

`jtestforge.yaml`, scaffolded by `init`, drives everything: which classes/methods are
eligible, the AI provider and its invocation shape, which Spring test tiers are
allowed, context-injection limits, and the acceptance thresholds for `generate` and
`harden`. See [usage.md](usage.md#configuration) for the annotated reference.

## Project layout

- `jtestforge-core` — the engine: AST analysis, Spring detection, prompt assembly,
  response parsing, quality guards, coverage/mutation measurement, state management.
  Usable as a library independent of the CLI.
- `jtestforge-cli` — the Picocli command-line front end over `jtestforge-core`.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
