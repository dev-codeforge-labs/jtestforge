# Verify the HTTP contract of `{{CLASS_FQN}}`

This is a `{{SPRING_STEREOTYPE}}`. Its handler bodies are already unit-tested by calling
them directly — but calling a handler directly bypasses everything Spring does around it.
The path it answers on, how a request binds to its arguments, whether constraints are
enforced, how failures become status codes, whether access is actually restricted: none of
that runs when a test calls the method itself.

That is what these tests are for. Write them against `MockMvc`, through the real request
pipeline.

## What is currently unverified

{{FRAMEWORK_SEMANTIC_GAPS}}

Write one test per item. Each must fail if that behaviour were wrong.

## Endpoints on this class

{{REQUEST_MAPPINGS}}

## Constraints declared on the request types

{{VALIDATION_CONSTRAINTS}}

## Access rules in scope

{{SECURITY_ANNOTATIONS}}

## Failure translation in scope

{{EXCEPTION_HANDLERS}}

## Mock beans this test class declares

{{MOCK_BEANS}}

Stub only these. They are the only collaborators the slice provides.

## The class under test

```java
{{CLASS_SOURCE}}
```

## The existing test class

```java
{{EXISTING_TEST_CLASS}}
```

Tests that already exist — do not duplicate these, and do not reuse their names:

{{EXISTING_TEST_NAMES}}

## What this project's Spring version supports

{{SPRING_CONTEXT}}

Use exactly the annotations and helper types named here. A different version's names will
not compile.

{{SPRING_RULES}}

{{RULES}}
