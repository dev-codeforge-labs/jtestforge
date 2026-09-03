# Close the untested paths in `{{TARGET_METHOD}}`

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
