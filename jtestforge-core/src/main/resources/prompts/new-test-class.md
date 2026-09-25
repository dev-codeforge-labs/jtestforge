# Write the first tests for `{{TARGET_METHOD}}`

_No tools here: every file read, directory listing, search or shell command is
refused and only delays your answer. Everything that exists for this task is
below. If some detail is missing, stub it with the libraries listed as available
and carry on — do not go looking. Emit nothing outside the two fenced blocks._

`{{CLASS_FQN}}` has no tests at all yet. A test class has just been created for it, with
its collaborators already declared as mocks. Write the first tests for one method.

## The method under test

```java
{{TARGET_METHOD_SOURCE}}
```

## The full class, for context

```java
{{CLASS_SOURCE}}
```

## Collaborators available as mocks

{{COLLABORATORS}}

## Test frameworks on this project's classpath

{{FRAMEWORK_VERSIONS}}

Use only what is listed. In particular, do not use a mocking or assertion feature from a
version newer than the one shown.

## Your task

Write the smallest set of tests that would fail if `{{TARGET_METHOD}}` were broken in any
of the ways described below, and pass otherwise. Start from its branches and its declared
failure modes; if it has neither, one test asserting its actual computed result is enough.

{{RULES}}
