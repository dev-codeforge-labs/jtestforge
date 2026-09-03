# Write the first tests for `{{TARGET_METHOD}}`

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
