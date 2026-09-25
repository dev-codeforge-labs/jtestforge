# JTestForge — Usage Guide

JTestForge is a Java 21 command-line tool that raises the quality of a Maven module's
test suite by driving an external AI CLI (Claude Code, Gemini CLI) in a verified loop: it
proposes a test, compiles it, runs it, measures whether coverage actually improved, and
only keeps what earns its place.

> **Status note.** This guide documents only what the CLI actually does today.
> `init`, `scan`, `status`, `clean`, `generate` and `report` are fully wired and work end
> to end — `generate` has been run for real, through this exact command line, against a
> live Spring Boot module and the real `claude` CLI (see
> [What doesn't work yet](#what-doesnt-work-yet) for the handful of its documented flags
> that aren't implemented). `harden` is **not yet implemented** — invoking it fails with an
> explicit "not implemented" error. `HardenEngine` itself is real and unit-tested, but
> wiring it to this command line needs a discovery component that does not exist yet
> (planned for implementation phase 20, alongside its first real verification against PIT).

---

## Requirements

- Java 21.
- The target project must be a Maven module (a directory with a `pom.xml`) that **builds
  green** before a run — JTestForge never starts from a red baseline.
- An AI CLI on `PATH` for `generate` (`claude` or `gemini.cmd` by default — see
  [Configuration](#configuration)). Not required for `init`, `scan`, `status`, `clean` or
  `report`.

## Building and running

JTestForge ships as a single module pair — `jtestforge-core` (the engine, usable as a
library) and `jtestforge-cli` (the command line front end). Build both with:

```bash
mvn -o clean install
```

This produces a runnable shaded jar at:

```
jtestforge-cli/target/jtestforge-1.0.0-SNAPSHOT.jar
```

Run it with:

```bash
java -jar jtestforge-cli/target/jtestforge-1.0.0-SNAPSHOT.jar <command> [options]
```

`--help` and `--version` work on the root command and on every subcommand (via Picocli's
standard help options).

---

## Common options

These apply to every subcommand documented below (`init`, `scan`, `status`, `clean`):

| Option | Description |
|---|---|
| `-c, --config <path>` | Path to the config file. Default: `./jtestforge.yaml`. |
| `-m, --module <path>` | Overrides `project.modulePath` from the config for this invocation. |
| `-v, --verbose` | Enables debug logging. Echoes every external command (`mvn ...`, the AI provider CLI) to the console as it runs, with its exit code once it finishes. On `generate`, also echoes every AI provider request and response - or, on failure, the full stdout/stderr - as it happens. |
| `--dependency-tree <file>` | `scan`/`generate`: read dependencies from a saved `mvn dependency:tree` instead of running Maven. Overrides `project.dependencyTreeFile`. |
| `--local-repository <dir>` | Local Maven repository holding that tree's jars. Overrides `project.localRepository`. |
| `--java-version <n>` | Java release of the module's code (`8`, `1.8`, `11`...). Overrides `project.javaVersion`. |
| `--java-home <dir>` | JDK every Maven call runs on, exported as `JAVA_HOME`. Overrides `project.javaHome`. |

**Config file resolution order** (`--config` beats both):
1. `--config <path>`, if given.
2. `./jtestforge.yaml`, if it exists in the current directory.
3. `<module>/jtestforge.yaml`, where `<module>` is `--module`'s value.
4. Otherwise, `./jtestforge.yaml` (even if it does not exist — the command then reports a
   configuration error).

---

## Commands

### `jtestforge init`

Scaffolds `jtestforge.yaml` and the eleven bundled prompt/rules template files a fresh
config points at by default (`prompts/new-test-class.md`, `prompts/rules.md`,
`prompts/spring-web-slice.md`, and so on), writing them under the config file's own
directory.

```bash
java -jar jtestforge.jar init
java -jar jtestforge.jar init --module ./my-service
```

- **Never overwrites** a file that already exists — config or template. Running `init`
  again after you've hand-edited `jtestforge.yaml` or tuned a prompt template is a safe
  no-op for those files; it only fills in whatever is still missing.
- Prints how many of the twelve files (1 config + 11 templates) it actually wrote versus
  left untouched.

### `jtestforge scan`

The cheap, read-only dry run. Analyzes the target module's source with the same AST
scanner and symbol solver a real generation run would use, classifies every eligible
method into a Spring test tier, and detects framework-semantic gaps — with **no coverage
measurement, no AI call, no file write, and no Spring context load**. The one process it
does start is `mvn dependency:build-classpath`, needed for accurate type resolution —
and with `project.dependencyTreeFile` (or `--dependency-tree`) it starts none; see
[Modules Maven can't resolve, and older Java](#modules-maven-cant-resolve-and-older-java).

```bash
java -jar jtestforge.jar scan
java -jar jtestforge.jar scan --config other-config.yaml --verbose
```

Typical output:

```
Scanned 4 production class(es) under src/main/java
3 class(es) selected, 1 excluded by `selection`
7 method(s) would be attempted, 2 excluded by `selection`

Tier             Methods
PLAIN_UNIT             5
WEB_SLICE               2

3 framework-semantic gap(s) currently open (§7.5)
```

Fails with a configuration/preflight error (exit code 1) if the resolved module path has
no `pom.xml`, or its `project.mainSourceRoot` doesn't exist.

### `jtestforge status`

Prints the current run state — the same `.jtestforge/state.json` file a real `generate`
or `harden` run would maintain — as a human-readable table. Purely read-only.

```bash
java -jar jtestforge.jar status
```

- If no run has ever happened, prints a one-line "nothing has been run yet" message and
  exits 0.
- Otherwise prints the run id, phase, module, timestamps, a per-status count table
  (`DONE`, `FAILED_COMPILE`, `PROVIDER_ERROR`, ...), the total unit count, and — for every
  unit that failed — its id and last error.
- Warns if any unit is stuck `IN_PROGRESS`, which means a previous run was interrupted; a
  future `generate --resume` would reconcile it against the current source rather than
  trust it blindly.

### `jtestforge generate`

Pass 1 (jtestforge-specification.md §9): resolves the module's real classpath and
dependency list, detects its Spring stack, measures baseline coverage (`mvn test
jacoco:report`), discovers work units, then drives the configured AI provider through a
verified per-unit loop — render prompt, invoke, parse, static guards, merge, compile,
scoped test run, value gate — keeping only what earns its place. Ends with a full-suite
verification.

```bash
java -jar jtestforge.jar generate
java -jar jtestforge.jar generate --tier PLAIN_UNIT     # fast run, loads no Spring context
java -jar jtestforge.jar generate --no-spring
java -jar jtestforge.jar generate -p gemini             # override aiProvider.active
java -jar jtestforge.jar generate --restart              # discard existing state, start fresh
java -jar jtestforge.jar generate --max-units 5
java -jar jtestforge.jar generate --force-unlock
```

Implemented options:

| Option | Description |
|---|---|
| `-p, --provider <id>` | Override `aiProvider.active` for this invocation. |
| `--tier <tier>` | Restrict to one or more tiers (repeatable): `PLAIN_UNIT`, `WEB_SLICE`, `DATA_SLICE`, `JSON_SLICE`, `CONTEXT_SLICE`. Mutually exclusive with `--no-spring`. |
| `--no-spring` | Restrict to `PLAIN_UNIT` only — a fast run that loads no Spring context. |
| `--resume` | Accepted for scripting clarity; this is already the default whenever a state file exists (§8.2.3) — a stale `DONE` claim is reconciled against the filesystem, not trusted blindly. |
| `--restart` | Discard any existing state for this module and start over with freshly discovered units. Mutually exclusive with `--resume`. |
| `--max-units <n>` | Cap the number of units this invocation processes (overrides `execution.maxUnitsPerRun`). |
| `--force-unlock` | Clear a stale lock (from a killed process) before acquiring it for this run. |

Real preconditions this command enforces before touching the AI provider: the module has
a `pom.xml` and its `project.mainSourceRoot`; the resolved `aiProvider.active` (or `-p`
override) names a provider actually defined under `aiProvider.providers`; and config
validation (`jtestforge-specification.md` §5.1) passes against the module's *real*
detected Spring facts — not a stubbed-out default — so an `spring.enabled: true` module
that genuinely has Spring on its classpath is never wrongly rejected.

**Not yet implemented** (declaring any of these fails with Picocli's own "unknown option"
rather than silently doing nothing): `--class`, `--method`, `--retry-failed`, `--dry-run`.
`execution.dryRun` in the config is likewise bound but not yet consumed by the engine —
setting it currently has no effect, so don't rely on it for safety.

Typical output:

```
Run 8f2c1e40-...: COMPLETED
  DONE: 3
  PENDING: 2
```

`PENDING` units are ones a `--tier`/`--no-spring` restriction left untouched this run —
still there, unchanged, for a future unrestricted invocation.

#### Diagnosing an AI provider failure (`PROVIDER_ERROR`)

`state.json`'s `lastError` is one line, sometimes with the process's own error message
truncated or mangled by console encoding (`sintaxis no v�lida` for a Spanish Windows
`cmd.exe`, say). Two ways to get the full picture instead:

- **Every attempt, always** — `<stateDir>/transcripts/<unitId>/<attempt>.prompt.md` has
  exactly what was sent; `<attempt>.response.md` has exactly what came back. On a failed
  attempt this is not the model's answer but the complete diagnostic dump: the resolved
  command, the exit code (or "(timed out)"), and the full stdout and stderr the process
  produced — not just the first line.
- **Live, as it happens** — add `-v`/`--verbose` to `generate` to also print every
  request and response (or failure diagnostics) to the console immediately, without
  waiting for the run to finish or digging through `transcripts/`.

### `jtestforge report`

Renders the last run's `.jtestforge/state.json` — never a second source of truth
(jtestforge-specification.md §15) — as Markdown, written to
`<stateDir>/report-<runId>.md` and also printed to stdout. Headline coverage/gap deltas,
per-tier cost, discard-reason breakdown, and per-class test/mutant detail.

```bash
java -jar jtestforge.jar report
java -jar jtestforge.jar report --html    # also writes report-<runId>.html
```

If no run has ever happened, prints a one-line message and exits 0 rather than erroring.

### `jtestforge clean`

Removes `execution.stateDir` (`.jtestforge/` by default) entirely — state, transcripts,
lock file, and backups — for the target module.

```bash
java -jar jtestforge.jar clean
```

- If nothing exists at the state directory, reports that and exits 0 without error.
- **Refuses to run** while another JTestForge process genuinely holds the lock on that
  directory (exit code 2), naming the holder's PID so you can decide whether to wait for
  it. A *stale* lock (holder no longer alive) does not block cleaning — it's simply one of
  the files removed.

---

## What doesn't work yet

`jtestforge harden` is a registered subcommand — it appears in `--help` and accepts no
options of its own yet — but its `call()` unconditionally throws a "not implemented"
error and exits with code 1. `HardenEngine` (pass 2: mutation-driven test hardening via
PIT) is itself real and unit-tested against hand-authored PIT report fixtures, but wiring
it to this command line needs a component that does not exist yet: something that derives
`List<TierScopedTestClass>` (which test class covers which production class, at which
tier) and a mutant-group-to-`UnitContext` lookup from a real module, the way
`WorkUnitDiscovery` does for `generate`. Building that without also verifying it against
a real PIT run would leave it exactly as unverified as not building it at all, so both are
planned together for implementation phase 20.

`generate`'s own `--class`, `--method`, `--retry-failed` and `--dry-run` flags are the
same kind of gap at smaller scale — see the option table above.

---

## Configuration

`jtestforge.yaml` (scaffolded by `init`) drives every command. The generated file is
heavily commented; the sections that matter even for the commands that work today are:

```yaml
project:
  modulePath: .                    # the Maven module JTestForge operates on
  mavenExecutable: mvn
  mavenArgs: ["-o", "-B"]
  # javaHome: C:/jdk8               # JDK every Maven call runs on (exported as JAVA_HOME)
  # javaVersion: 8                  # Java level of the code; default: detected
  # dependencyTreeFile: deps-tree.txt  # saved mvn dependency:tree; no Maven call for dependencies
  # localRepository: C:/m2/repo     # where that tree's jars live; default: from settings.xml
  testSourceRoot: src/test/java
  mainSourceRoot: src/main/java
  testClassSuffix: Test             # Foo -> FooTest
  testClassSuffixByTier:            # one class per Spring tier
    WEB_SLICE:     WebTest
    DATA_SLICE:    DataTest
    JSON_SLICE:    JsonTest
    CONTEXT_SLICE: ContextTest

selection:
  includePackages: []
  excludePackages: []
  excludeClasses: ["**/*Config", "**/*Application", "**/dto/**"]
  excludeAnnotations: ["jakarta.persistence.Entity", "lombok.Generated"]
  minComplexity: 2                  # methods below this are not worth a generated test
  includePrivateMethods: false
  order: lowestCoverageFirst        # lowestCoverageFirst | alphabetical | declaration

spring:
  enabled: auto                     # auto | true | false
  tiers:
    plainUnit: true                 # T0 - JUnit 5 + Mockito, no Spring context
    webSlice: true                  # T1 - @WebMvcTest + MockMvc
    dataSlice: true                 # T2 - @DataJpaTest + embedded database
    jsonSlice: true                 # T3 - @JsonTest / @ConfigurationProperties binding
    contextSlice: false             # T4 - minimal @SpringBootTest(classes = ...)
  preferLowestTier: true
  detectFrameworkSemanticGaps: true

execution:
  stateDir: .jtestforge             # never under target/ - `mvn clean` would wipe it
  backupOriginalTests: true
  consecutiveFailureAbort: 5
  maxUnitsPerRun: 0                 # 0 = unlimited
```

The AI provider, prompt templates, context-injection limits, and the `generate` tuning
block (`maxTestsPerMethod`, `maxRepairAttempts`, ...) are all live and consumed by
`generate` today. The `harden` block (`minMutationScore`, `maxTierForMutation`, ...) is
present in the scaffolded file but not yet consumed by anything, pending implementation
phase 20. API keys for the AI CLI are **never** read from this file — they are inherited
from the process environment, exactly as the underlying CLI (`claude`, `gemini`) itself
expects.

See `jtestforge-specification.md` §5 for the full config reference and validation rules,
and §14 for the complete command/exit-code specification this CLI is built against.

---

## Modules Maven can't resolve, and older Java

### `dependencyTreeFile`: no Maven call for dependencies

`scan` and `generate` normally ask Maven for the module's classpath
(`mvn dependency:build-classpath`) and dependency list (`mvn dependency:list`). Where that
fails — a plugin prefix the mirror can't resolve offline, a sibling module that was never
installed — give JTestForge a saved `mvn dependency:tree` instead. `scan` then starts no
process at all, and `generate` only runs Maven for the actual builds.

One file can describe the **whole** application: each module's block is found by its own
`groupId:artifactId`. Create it once from the reactor root:

```bash
mvn test-compile dependency:tree -DoutputFile=C:/tmp/deps-tree.txt -DappendOutput=true
```

- `test-compile` lets the reactor resolve sibling modules from their `target/classes`
  without `mvn install`; leave it out if everything is already installed.
- `-DoutputFile` must be absolute, otherwise each module writes a file of its own.
- A redirected console log (`mvn dependency:tree > deps-tree.txt`) is accepted too.
- If Maven says `No plugin found for prefix 'dependency'`, name the plugin in full:
  `org.apache.maven.plugins:maven-dependency-plugin:<a version in your repository>:tree`.

Then set `project.dependencyTreeFile` (relative to the config file) or pass
`--dependency-tree`. Jars are looked up in the local repository: `project.localRepository`
/ `--local-repository`, else detected the way Maven does it (`-Dmaven.repo.local` in
`mavenArgs`, `~/.m2/settings.xml`, the Maven installation's `conf/settings.xml`,
`~/.m2/repository`). A dependency on a sibling module resolves to its `target/classes`.
Anything not on disk is listed as a warning and skipped — its types resolve by name
only, and the run carries on.

### Java version of the target code

JTestForge works out which Java release the module is written for and prints it: the
compiler plugin's `<release>`/`<source>`/`<target>`, or the `maven.compiler.*` /
`java.version` properties, following parent poms (on disk, else in the local repository);
failing that, the class-file version of `target/classes` or of the module's jar in
`target/`. `project.javaVersion` / `--java-version` (`8` or `1.8` alike) overrides it.
The release is used to:

- parse the sources at that language level, so old code newer levels reject (such as `_`
  as an identifier) still scans; a file that fails is retried at Java 21;
- tell the model which language features and JDK APIs don't exist at that level, so a
  Java 8 module doesn't get tests using `var`, `List.of` or records.

The JDK Maven itself runs on is `project.javaHome`, exported as `JAVA_HOME` to every Maven
call JTestForge makes; a warning is printed when that JDK is older than the module's level.

---

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Success — `generate` completed (kept at least one test, or had nothing to discover in the first place). |
| 1 | Configuration or preflight error (bad config, no `pom.xml`, module didn't build green, unknown provider, or a not-yet-implemented command). |
| 2 | Another JTestForge process holds the lock on the module's state directory. |
| 3 | `generate` completed, but this invocation kept nothing new — every unit it actually attempted was discarded or failed, or there was nothing left `PENDING` to attempt (a re-run against an already-fully-processed module lands here, not on 0 — see the `generate` section above). |
| 4 | Every unit `generate` attempted failed with an AI provider transport error — the CLI is likely unreachable. |
| 5 | `generate` aborted after `execution.consecutiveFailureAbort` consecutive discarded units. |
| 6 | The module's full test suite is red at the end of a `generate` run (§9.5). |
| 7 | `harden` only: final mutation score below `harden.minMutationScore` *(not reachable — `harden` is not wired yet)*. |
| 8 | `spring.maxContextLoadsPerRun` exhausted during a `generate` run. |
| 130 | Interrupted (Ctrl-C) — reverts any `IN_PROGRESS` unit's partial edits and releases the lock before exiting. |

Exit code 7 is the only one not reachable through the CLI today, since it is `harden`-only.
All codes are defined by `jtestforge-specification.md` §14.1 and implemented in
`GenerateResult`/`HardenResult`/`ExitCodeMapper`.
