# Close the untested paths in `{{TARGET_METHOD}}`

_No tools here: every file read, directory listing, search or shell command is
refused and only delays your answer. Everything that exists for this task is
below. If some detail is missing, stub it with the libraries listed as available
and carry on — do not go looking. Emit nothing outside the two fenced blocks._

`{{CLASS_FQN}}` already has tests, but parts of `{{TARGET_METHOD}}` are never executed by
any of them. Add tests that reach exactly those parts.

## The method under test

```java
{{TARGET_METHOD_SOURCE}}
```

## Lines no existing test reaches

{{UNCOVERED_LINES}}

Each test you write should be there to reach one of these. If a line is unreachable
through the method's public contract, skip it rather than contorting a test to hit it.

## Lines that run, but only take one outcome

{{UNCOVERED_BRANCHES}}

These lines already execute in every existing test, but always the same way - a condition
that is always true, an `Optional` that is always (or never) present. This can be the
*only* reason this method needs a test at all, even with every line above already covered:
write the test that drives the other outcome. If a line here is also listed above, its
untaken outcome and its unreached one are different things - the test you write for it may
need to do both.

## The full class, for context

```java
{{CLASS_SOURCE}}
```

## Collaborators available as mocks

{{COLLABORATORS}}

## The existing test class

```java
{{EXISTING_TEST_CLASS}}
```

Tests that already exist — do not duplicate these, and do not reuse their names:

{{EXISTING_TEST_NAMES}}

## Test frameworks on this project's classpath

{{FRAMEWORK_VERSIONS}}

{{RULES}}
