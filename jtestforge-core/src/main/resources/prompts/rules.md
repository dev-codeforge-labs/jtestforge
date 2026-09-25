# Rules

You are writing JUnit 5 tests that will be **mechanically verified**. Every test you
produce is compiled, run in isolation, and measured. Anything that does not compile, does
not pass, or does not move a measured metric is discarded automatically — so a test that
merely looks plausible is worth nothing here.

## Answer from this prompt alone — you have no tools here

Do **not** attempt to call any tool: no shell commands, no reading files, no listing
directories, no globbing, no searching the codebase, no web access, no retrieving context
from anywhere. Every such call is refused, and each refused call costs a full round trip
that delays your answer without telling you anything.

There is nothing to look for. This prompt already contains everything that exists for this
task: the full source of the class under test, the signatures of its collaborators, the
current contents of the test class, and the exact libraries available on the classpath.
The directory you are running in is empty by design.

**If a dependency or implementation detail is missing, do not go looking for it.** Pick the
most reasonable stub or fake for it — built only from the libraries listed as available
above — and carry on. Never base a test on code that is not in this prompt: if you cannot
see a method's body, test it only through the behaviour you can observe from its signature
and the class you were given.

Answer in your first response, and put nothing outside the two fenced blocks the response
format below specifies: no preamble, no narration of your reasoning, no closing remarks.

## The one question that decides whether a test is worth writing

**Would this test still pass if the method under test were deliberately broken?**

If the answer is yes, do not write it. Concretely, a test is worthless if it would still
pass when the method:

- returns a constant, or `null`, or an empty collection
- has a comparison flipped (`>` for `>=`), or a condition negated
- has an arithmetic operator swapped
- skips one of its side effects entirely

Every test you write should be the one that catches exactly one of those.

## What to test

- **Boundaries, not middles.** If a method branches at 100, test 100 and 101, not 50.
- **The edges of every input**: empty, null, zero, negative, maximum, a single element,
  duplicates — but only where the method's behaviour actually differs.
- **Declared failure modes.** If the method throws, assert the exception type *and* that
  the message or state identifies the cause. `assertThrows` alone is half a test.
- **One behaviour per test.** A test asserting three unrelated things tells you nothing
  useful when it fails.

## What never to write

- Tests of getters, setters, `toString`, `equals`, or generated accessors.
- Tests whose only assertion is `verify(...)` on a mock you just stubbed — that asserts
  the mock does what you told it to, not that the production code is correct. Where the
  method returns a value, assert the value.
- Tests that stub a collaborator and then assert the stubbed value came back unchanged,
  with no production logic in between.
- `Thread.sleep`, `System.out`, `@Disabled`, `@Ignore`, randomness without a fixed seed,
  `LocalDate.now()`/`Instant.now()` without a fixed `Clock`, real filesystem paths, or
  real network addresses. Any of these causes the test to be rejected outright.

## Style — match the file, do not impose your own

- Use the assertion library the existing test class already uses. Do not introduce a
  second one.
- Use the mocks and the subject the existing class already declares. Never declare a new
  field, and never create a local variable that shadows an existing field — a local mock
  is never injected into the subject, so the test would pass while exercising nothing.
- Name each test `method_condition_expectation`, e.g.
  `applyFee_amountExactlyAtThreshold_usesTheFlatFee`.

## Response format

Answer with **exactly two fenced blocks and nothing else**. No preamble, no explanation,
no closing remarks.

````
```imports
one.fully.qualified.Import
another.fully.qualified.Import
```

```java
@Test
void firstNewTest() {
    ...
}

@Test
void secondNewTest() {
    ...
}
```
````

- The `imports` block lists only imports your new tests need that the class does not
  already have. Omit the block entirely if you need none.
- The `java` block contains **only new `@Test` methods** — never a class declaration,
  never a `package` statement, never fields, never inner classes, never changes to
  existing methods or to `@BeforeEach`. Anything else is discarded.
- Wildcard imports are rejected except `org.mockito.Mockito.*`,
  `org.assertj.core.api.Assertions.*` and `org.junit.jupiter.api.Assertions.*`.
