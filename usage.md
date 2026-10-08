# JTestForge — Usage Guide

JTestForge is a Java 21 command-line tool that raises the quality of a Maven module's
test suite by driving an external AI CLI (Claude Code, Gemini CLI, GitHub Copilot CLI, or
any other non-interactive AI CLI you configure - see [AI providers](#ai-providers)) in a
verified loop: it
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
- An AI CLI on `PATH` for `generate` (`claude`, `gemini.cmd` or `copilot` — see
  [AI providers](#ai-providers)). Not required for `init`, `scan`, `status`, `clean` or
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
| `--source-encoding <name>` | Character encoding of the module's Java sources (`UTF-8`, `ISO-8859-1`, `windows-1252`...). Overrides `project.sourceEncoding`. |
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

Scaffolds `jtestforge.yaml` and the twelve bundled prompt/rules template files a fresh
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

An answer that breaks the response format (no ```` ```java ```` block, a whole class instead
of test methods, only imports...) gets **one corrective re-prompt per unit**
(`prompts/fix-contract.md`): the model is shown what was wrong and its own answer, and asked to
resend its tests in the required shape. A second unusable answer ends the unit as
`DISCARDED_NO_VALUE`, with both reasons in the report. It does not count against
`generate.maxRepairAttempts`, which bounds compile and assertion repairs. A project scaffolded
by an older `init` has no `fix-contract.md`; the bundled default is used and the run warns -
run `init` again to get the file (it never overwrites existing ones).

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
| `--restart` | Discard any existing state for this module and start over with freshly discovered units. Whatever an interrupted unit left in a test file (half-merged tests, an empty test class it had just created) is taken out first, exactly as a resume would. Mutually exclusive with `--resume`. |
| `--max-units <n>` | Cap the number of units this invocation processes (overrides `execution.maxUnitsPerRun`). |
| `--dry-run` | Do everything up to the AI call and stop (see below). Same as `execution.dryRun: true`. |
| `--force-unlock` | Clear a stale lock (from a killed process) before acquiring it for this run. |

Real preconditions this command enforces before touching the AI provider: the module has
a `pom.xml` and its `project.mainSourceRoot`; the resolved `aiProvider.active` (or `-p`
override) names a provider actually defined under `aiProvider.providers`; and config
validation (`jtestforge-specification.md` §5.1) passes against the module's *real*
detected Spring facts — not a stubbed-out default — so an `spring.enabled: true` module
that genuinely has Spring on its classpath is never wrongly rejected.

**Dry run.** `--dry-run` (or `execution.dryRun: true`) runs discovery - which builds the module
and measures baseline coverage, touching only `target/` - then stops where the AI call would
start. It lists the units a real run would attempt, in order and within `--tier` /
`--max-units` - with an existing `state.json` that means the units of that state still pending
(not the ones already done), unless `--restart` is given - and writes the exact prompt each would send under `<stateDir>/dry-run/`, so a
prompt template or a context limit can be checked for free. No AI provider is called, no test
file is written, backed up or created, and `state.json` is left alone: a later real run starts
as if the dry run had never happened. (Only the lock file and the `dry-run/` prompts are
written.) With an HTTP provider the model server is not contacted either, so it can be used
before the server or the model is even set up.

**Backups.** With `execution.backupOriginalTests: true` (the default), an existing test file is
copied to `<stateDir>/backups/<runId>/` - same layout as the module - just before the first
unit that would change it, and never overwritten afterwards, so the copy is always the original.
Units are still undone by subtracting what they added, not by restoring a copy (a copy would also
discard what earlier units legitimately kept): the backup is a safety net for everything else. A
copy that cannot be made is reported as a warning and the run carries on. `clean` deletes them.

**Not yet implemented** (declaring any of these fails with Picocli's own "unknown option"
rather than silently doing nothing): `--class`, `--method`, `--retry-failed`.

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

`generate`'s own `--class`, `--method` and `--retry-failed` flags are the
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
  # sourceEncoding: ISO-8859-1      # encoding of the sources; default: from the pom, else UTF-8
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
  backupOriginalTests: true         # copies of existing test files under <stateDir>/backups/<runId>/
  # dryRun: true                    # list units and write their prompts, call no AI; or: generate --dry-run
  consecutiveFailureAbort: 5
  maxUnitsPerRun: 0                 # 0 = unlimited
```

The AI provider, prompt templates, context-injection limits, and the `generate` tuning
block (`maxTestsPerMethod`, `maxRepairAttempts`, ...) are all live and consumed by
`generate` today. The `harden` block (`minMutationScore`, `maxTierForMutation`, ...) is
present in the scaffolded file but not yet consumed by anything, pending implementation
phase 20. API keys for the AI CLI are **never** read from this file — they are inherited
from the process environment, exactly as the underlying CLI (`claude`, `gemini`,
`copilot`) itself expects.

See `jtestforge-specification.md` §5 for the full config reference and validation rules,
and §14 for the complete command/exit-code specification this CLI is built against.

---

## AI providers

`ProcessAiProvider` (jtestforge-specification.md §12.1) covers **any non-interactive AI
CLI** — the differences between providers are entirely in `jtestforge.yaml`'s
`aiProvider.providers.<id>` block (`command`, `args`, `promptDelivery`, `timeoutSeconds`,
`transportRetries`, `env`, and an optional per-provider `maxPromptChars` override), never
in JTestForge's own code. `aiProvider.active` (or `-p/--provider` for one invocation)
picks which entry runs.

Two things about a provider's settings that are easy to get wrong:

- **`env` is not secret-safe in the config file, but is in the `-v` output.** With `-v`, every command
  is echoed with the environment it was started with - the *name* of each override always, its
  *value* only for a few harmless variables (`JAVA_HOME`, `*_HOME`, `PATH`, `DEBUG`, `NO_COLOR`,
  `TERM`, `LANG`, `LC_ALL`); anything else shows as `***`, so a pasted log does not leak an API key
  or a proxy password. Still keep API keys in the real environment, never in `jtestforge.yaml`.
- **`promptDelivery: argument` with a `.cmd`/`.bat` launcher** (e.g. `gemini.cmd`) is warned about at
  startup: Windows runs those through `cmd.exe`, which interprets `& | < > ^ %` and quotes in the
  prompt - which is your source code - and caps the line at about 8191 characters. Use `stdin`
  (the default) or `file`.

### Providers verified against a real generation run

Each of these has actually produced kept tests against the project's fixture module
(`jtestforge-implementation-plan.md`'s "tuning loop") — not just a smoke test:

| Provider | `command` | Notes |
|---|---|---|
| `claude` (default) | `claude` | The primary provider this tool was built and tuned against - two full tuning-loop passes (implementation phase 19) found and fixed real `ResponseParser` gaps. |
| `gemini` | `gemini.cmd` | Prompt delivered on stdin with an empty `-p` argument - see the comment in the bundled `jtestforge.yaml.template` for why, and for the `.gemini/policies` setup that disables its tools outright. |
| `copilot` | `copilot` (GitHub Copilot CLI) | Verified 2026-09: response contract held exactly, including a static-import line written as a full `import static ...` statement instead of the bare form the prompt asks for - already handled by the existing import-normalisation logic. Two Spring `WEB_SLICE` tests were correctly rejected by the pre-existing `BARE_STATUS` quality guard (status-code-only assertions), which is the guard doing its job, not a defect. `--available-tools` with no values is Copilot's equivalent of Gemini's tool-deny policy file: it denies every tool outright, so `--allow-all-tools` (required by the CLI for non-interactive mode) has nothing left to approve. |
| `codex` | `codex exec` (OpenAI Codex CLI) | Verified 2026-09 - and found a real bug the first time round: Codex wrote a bare static-member import (`org.junit.jupiter.api.Assertions.assertTrue`) without the `static` keyword the wildcard-import handling already knew to restore for `Foo.*` forms. Merged as a plain (non-static) import, that doesn't compile - `assertTrue` is a method, not a nested type. Fixed in `ResponseParser` (any bare import whose last segment starts lower-case is now treated as a static member, the same convention every type name in this codebase already follows) and covered by a regression test; a second real pass afterward completed clean. `--sandbox read-only` denies the model's own tool calls at the OS level, on top of `isolateWorkingDirectory`; `--skip-git-repo-check` is required because `ai-cwd` is deliberately not a git repository; `--color never` keeps stdout free of ANSI escapes. Codex's banner, prompt echo and token-usage stats go to stderr, not stdout. |

A provider not in this table isn't necessarily broken — it just hasn't been run for real
yet. A local Ollama model is the most likely case to actually need the per-provider
`maxPromptChars` override, since its context window can be far smaller than a hosted
CLI's - see [Ollama](#ollama-local-model) below, which is configured but **not yet
verified**.

### Ollama (local model)

Configured, **not yet verified**: the `ollama` entry in `jtestforge.yaml` drives
`ollama run` through the same `ProcessAiProvider` as every CLI above, and has not yet
produced kept tests in a full pass against the fixture module. Treat the first run as an
experiment, and read the transcripts.

**1. Build the derived model once.** `ollama run` has no flag for `num_ctx`,
`temperature` or `num_predict`, so they have to live in the model:

```bash
ollama create jtestforge-qwen3-coder -f tools/ollama/Modelfile
```

`tools/ollama/Modelfile` derives from a model already on disk (edit its `FROM` line to
whichever one `ollama list` shows) and pins `num_ctx 32768`, `temperature 0.7` and
`num_predict 4096`. Creating it downloads nothing - it reuses the base model's weights.
Keep the temperature at the model authors' recommended value rather than lowering it for
"more deterministic code": with `0.2`, the Qwen3-Coder model tried here fell into
repetition loops on the web-slice units (the same import lines over and over until
`num_predict` ran out, 3 min 45 s per call). Then select it:

```bash
java -jar jtestforge.jar generate --provider ollama --tier PLAIN_UNIT
```

**Why `num_ctx` matters more here than for any other provider.** Ollama silently drops the
*start* of a prompt that does not fit `num_ctx`, and JTestForge puts the rules, the
response contract and the class under test first - so an undersized context does not fail,
it produces plausible tests for a class the model never saw. `context.maxPromptChars`
(60000 characters, roughly 17-20k tokens of Java) plus the answer needs about 32k tokens;
an unconfigured model gets the server default, which is usually far less. Setting
`OLLAMA_CONTEXT_LENGTH` in the provider's `env` block does **not** help: it reaches the
`ollama run` client, not the server that loads the model.

What to know before the first run:

- **A missing model is downloaded, not reported.** If the name in `args` is not in
  `ollama list`, `ollama run` starts pulling it inside the first unit, against that unit's
  timeout. Check `ollama list` first.
- **Cold start.** The first call also loads the model (about 17 s for a 12 GB model that
  is already on disk), hence `timeoutSeconds: 900`. `--keepalive 30m` keeps it loaded
  between units.
- **stdout is clean; stderr is noisy.** Observed with Ollama 0.35.1 and stdin redirected:
  stdout carries only the answer (no ANSI codes, no reasoning with `--hidethinking`), while
  stderr carries a loading spinner (several KB of escape sequences while the model loads)
  and, with `--verbose`, a block of timings. All of it ends up in
  `<stateDir>/transcripts/<unit>/N.stderr.log` - harmless, but not pretty.
- **Detecting a truncated prompt.** In that stats block, `prompt eval count` is the number
  of prompt tokens the model actually read. If it is close to `num_ctx`, the prompt was cut
  and the run is not trustworthy. Nothing checks this automatically yet.
- **A cut-off answer** shows up as an unclosed ```` ```java ```` block, which
  `ResponseParser` rejects as a contract violation; raise `num_predict` in the Modelfile if
  that happens.
- **Expect more rejections than from a hosted model, and read what it keeps.** A local
  quantised model is held to the same strict response contract and the same quality guards.
  Measured on the fixture module (single runs, not statistics): the 12 GB `IQ3_XXS` build kept
  0-2 of 5 units depending on settings, the 18 GB `Q4_K_M` build (`ollama pull
  qwen3-coder:30b`, what `tools/ollama/Modelfile` derives from) kept 3 of 5 - and even then
  two of the tests kept for `PricingRules#band` were copies of another test under names
  promising different cases. That case is now caught (the `DUPLICATE_BODY` guard rejects a test
  whose body is identical to another's), but a test can still be useless in ways no static guard
  sees, so with a local model review the generated tests; do not just count units marked `DONE`.
- **Speed.** The 22 GB `Q4_K_M` model was observed running 100 % on CPU, at roughly 10-14
  tokens/s, so a five-unit run took about 7 minutes.

To run the real pass against the checked-in fixture module, see the next section with
`-Dprovider=ollama`.

#### Ollama over HTTP (`type: http`)

Run end to end against the fixture module on 2026-10-07 (Ollama 0.40.0, `qwen3-coder:30b`, one
pass, not statistics): 2 of 5 units kept, 6 correct tests for `PricingRules#band` and `#fee`; the
other three were discarded by the quality guards or never passed. Every call finished with
`done_reason: stop` and between 1,759 and 2,633 prompt tokens against a `num_ctx` of 32,768, in
16-46 s each; the corrective re-prompt fired once, and no empty test skeleton was left behind.
Treat it as working, not as verified at the level of the CLI providers above.

The alternative to `ollama run`: JTestForge calls Ollama's native `POST /api/chat` itself. It needs
no derived model - the context size and sampling travel with every request - and it can tell you
things a CLI cannot. Uncomment the `ollama-http` entry in `jtestforge.yaml` (or write your own) and
select it with `aiProvider.active: ollama-http` or `generate -p ollama-http`:

```yaml
aiProvider:
  providers:
    ollama-http:
      type: http
      api: ollama                      # the only protocol for now
      baseUrl: http://localhost:11434
      model: qwen3-coder:30b
      options: { num_ctx: 32768, temperature: 0.7, num_predict: 4096 }
      keepAlive: 30m
      timeoutSeconds: 900
      maxPromptChars: 60000
```

- **`options`** is sent exactly as written (`num_ctx`, `temperature`, `num_predict`,
  `repeat_penalty`...). Keep `temperature` at the model authors' recommended value: `0.2` made one
  model loop (see above).
- **A prompt that does not fit `num_ctx` is refused, never cut.** JTestForge always sends
  `"truncate": false`. Without it Ollama silently keeps only part of an oversized prompt and
  answers as if nothing had happened: measured with Ollama 0.35.1, a 5,605-token prompt sent with
  `num_ctx: 512` was cut to 258 tokens and answered normally. With it the same request is an HTTP
  400 that names both sizes, and the unit reports it (raise `options.num_ctx` or lower
  `maxPromptChars`). **An Ollama too old to know `truncate` ignores it and goes back to cutting
  silently** - keep the server current.
- **Checked before the first unit** (two read-only requests, nothing generated): the server is
  running, it has the model, and `num_ctx` holds the prompts this run will build - a missing
  server or model stops the run with one clear line; an unset or too-small `num_ctx` is a
  warning, with how many characters would fit. The estimate assumes 3 characters per token,
  pessimistic on purpose (about 4.5 was measured for Java).
- **What each call records.** The answer's own numbers (`prompt_eval_count`, `eval_count`,
  duration, `done_reason`) go to `<stateDir>/transcripts/<unit>/N.stderr.log`, and a
  `done_reason: length` says outright that the answer ran out of output tokens.
- **Retries** follow the transport/content split of every provider: a server that cannot be
  reached, a timeout, 5xx/429 or an empty answer are retried up to `transportRetries`; a 4xx
  (an unknown model is 404) and the context refusal are not, since repeating them cannot help.
- **Privacy.** A `baseUrl` that is not this machine gets a warning: every prompt, including the
  full source of the classes under test, is sent there. `headers` are sent but never written to
  any message or log; API keys still belong in the real environment. A header the JDK's HTTP
  client refuses (`Host`, `Connection`, `Content-Length`, `Expect`, `Upgrade`, or an illegal
  name/value) is reported as a configuration error naming it.

### Adding and verifying a new provider

1. Add an entry under `aiProvider.providers.<id>` in your `jtestforge.yaml` (or in
   `jtestforge-core/src/main/resources/jtestforge.yaml.template` if it's going in
   permanently) with that CLI's `command`/`args`/`promptDelivery`.
2. Before trusting it for a real module, verify it the same way every provider above was
   verified: add a `ProviderProfile` entry to `TuningLoopRunner.KNOWN_PROVIDERS`
   (`jtestforge-core/src/test/java/.../e2e/TuningLoopRunner.java`) and run it against the
   checked-in fixture module (this talks to the real CLI/network and spends real
   invocations):
   ```bash
   cd jtestforge-core
   mvn -o test-compile org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
       -Dexec.mainClass=com.devmanchego.jtestforge.e2e.TuningLoopRunner \
       -Dexec.classpathScope=test -Dprovider=<id>
   ```
   Add `-DmaxPromptChars=<n>` to try a smaller context budget than the 60000-character
   default. The run prints a per-unit report and leaves every prompt/response transcript
   on disk (path printed at the end) for reading.
3. Read the transcripts. Two questions matter: did the response keep to the two-fenced-
   block contract (§6.2), and did anything the model wrote need a new tolerance in
   `ResponseParser` (as happened twice for Claude in phase 19)? A candidate test being
   discarded by an existing quality guard is not itself a reason to add tolerances - the
   guard rejecting a bad test is it working correctly.
4. Only once that's clean, add the provider to `jtestforge.yaml.template` for real (see
   the `claude`/`gemini`/`copilot` entries there for the pattern), and add it to
   `InitCommand.KNOWN_PROVIDERS` so `jtestforge init --provider <id>` can scaffold it.

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

### Source encoding of the target code

Every test class JTestForge merges into, reverts, or shows the model - and every production
class it scans - is read and written back in the module's own character encoding. It is
taken from the compiler plugin's `<encoding>`, then `maven.compiler.encoding`, then
`project.build.sourceEncoding` (following parent poms, like the Java version below);
`project.sourceEncoding` / `--source-encoding` overrides it. A pom that declares nothing is
assumed to be UTF-8, and the run says so as a warning.

Getting this right matters for legacy modules saved as `ISO-8859-1` or `windows-1252` with
accents in comments, strings or even identifiers:

- Files are read **strictly**. A test class that is not valid in the module's encoding is
  skipped as `SKIPPED_TEST_FILE_UNREADABLE` with the reason, never overwritten.
- A generated test containing a character the encoding cannot represent (a euro sign in
  `ISO-8859-1`, say) is refused rather than written with a replacement character.
- Without the right encoding, an accented *identifier* in a production class used to make the
  whole scan fail, and an accented comment made the prompt say "source unavailable".

Two scanners still decode leniently with UTF-8 - the Spring framework-gap scanner and the
Spring Data repository scanner. They never fail and only read structure (annotations, method
names), so the only effect is on a request path literal containing an accent.

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
