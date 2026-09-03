# Verify the wiring `{{CLASS_FQN}}` performs

The behaviour of this class *is* its wiring: which beans exist, and under which conditions.
That is decided by Spring when the context is built, so no direct call to any method here
can observe it.

This test loads a context restricted to the smallest set of classes that reproduces the
wiring in question — never the whole application.

## What is currently unverified

{{FRAMEWORK_SEMANTIC_GAPS}}

Write one test per item.

## The configuration under test

```java
{{CLASS_SOURCE}}
```

## Access rules in scope

{{SECURITY_ANNOTATIONS}}

## Your task

For each conditional bean, assert both sides of its condition: that the bean is present
when the condition holds, and absent when it does not. Asserting only the present case
proves nothing about the condition — an unconditional bean would pass it too.

Where a bean's identity matters and not merely its existence, assert the property that
makes it the right one.

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
