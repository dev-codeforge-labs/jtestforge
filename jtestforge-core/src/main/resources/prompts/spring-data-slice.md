# Verify what the queries on `{{CLASS_FQN}}` actually return

The query methods on this repository have no bodies to unit-test. What each one returns is
decided by Spring Data — from the method's name, or from its declared query text. Nothing
currently proves that what it returns is what its name claims.

These tests run against a real embedded database through `@DataJpaTest`.

## What is currently unverified

{{FRAMEWORK_SEMANTIC_GAPS}}

Write one test per item.

## The repository under test

```java
{{CLASS_SOURCE}}
```

## The persistence model these queries run against

{{PERSISTENCE_MODEL}}

## Your task

For each query, persist enough data through `TestEntityManager` that the query has to
discriminate — at least one row it must return **and** at least one row it must not. A
test whose fixture contains only matching rows would pass against a query that ignores its
criteria entirely.

Then assert what came back: how many rows, and which ones. Asserting only that the result
is non-empty proves nothing about the criteria.

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
