# Verify behaviour that nothing currently checks in `{{TARGET_METHOD}}`

`{{TARGET_METHOD}}` is already executed by the existing tests, but they do not actually
check parts of what it does: the method could be changed in specific ways and every
existing test would still pass.

## What is not being verified

{{BEHAVIOUR_GAPS}}

Each of these describes a real behaviour of the method that no assertion currently
observes. Write one test per gap, and make sure that test would **fail** if that behaviour
were wrong.

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

## The existing test class

```java
{{EXISTING_TEST_CLASS}}
```

Tests that already exist — do not duplicate these, and do not reuse their names:

{{EXISTING_TEST_NAMES}}

## Test frameworks on this project's classpath

{{FRAMEWORK_VERSIONS}}

{{RULES}}
