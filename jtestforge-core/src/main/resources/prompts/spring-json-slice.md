# Verify how `{{CLASS_FQN}}` binds and serialises

Constructing this type directly bypasses everything that matters about it. How external
values bind onto it, what its defaults are when a value is absent, how relaxed name
matching resolves, whether a declared constraint actually rejects a bad value, how it
serialises — none of that happens when a test just calls `new`.

## What is currently unverified

{{FRAMEWORK_SEMANTIC_GAPS}}

Write one test per item.

## The type under test

```java
{{CLASS_SOURCE}}
```

## Constraints declared on it

{{VALIDATION_CONSTRAINTS}}

## Your task

Cover, where each applies:

- a value supplied externally binds to the field it should
- a field left unsupplied keeps its declared default
- a supplied value that violates a constraint is rejected, and the failure identifies
  **which** constraint — not merely that binding failed
- round-tripping through serialisation preserves the values

## The existing test class

```java
{{EXISTING_TEST_CLASS}}
```

Tests that already exist — do not duplicate these, and do not reuse their names:

{{EXISTING_TEST_NAMES}}

## What this project's Spring version supports

{{SPRING_CONTEXT}}

{{SPRING_RULES}}

{{RULES}}
