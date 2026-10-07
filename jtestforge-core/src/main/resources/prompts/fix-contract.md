# Your previous answer could not be used

_No tools here: every file read, directory listing, search or shell command is
refused and only delays your answer. Everything that exists for this task is
below. Emit nothing outside the two fenced blocks._

You were asked for new JUnit 5 tests for `{{TARGET_METHOD}}` in `{{CLASS_FQN}}`
(`{{TIER}}` test class), but your answer did not follow the required response format, so
none of it could be read. Nothing was added to the test class.

## What was wrong with it

{{CONTRACT_VIOLATION}}

## Your previous answer

````text
{{PREVIOUS_RESPONSE}}
````

## What to do now

Send the tests again - the same ones, if they were right - in **exactly** this shape:

````
```imports
org.junit.jupiter.api.Assertions.assertEquals
java.math.BigDecimal
```

```java
@Test
void describesWhatIsChecked() {
    ...
}
```
````

The usual ways an answer goes wrong here, and the fix for each:

- **Imports inside the `java` block, or under a `java` label.** Imports go in their own block
  labelled `imports`, one fully qualified name per line, with no `import` keyword and no `;`.
- **A whole class** (`package`, `class Foo {`, fields, `@BeforeEach`). Send only the `@Test`
  methods themselves; the test class, its fields and its setup already exist.
- **No `java` label on the block, or no block at all.** The tests must be inside a block that
  opens with exactly three backticks followed by `java`.
- **Only imports, no tests.** Write at least one `@Test` method.
- **The answer was cut off.** Write fewer, shorter tests so the whole answer fits.

## The method under test, for reference

```java
{{TARGET_METHOD_SOURCE}}
```

## The class under test

```java
{{CLASS_SOURCE}}
```

## Collaborators available as mocks

{{COLLABORATORS}}

## Test method names already taken in the test class

{{EXISTING_TEST_NAMES}}

## Test frameworks actually on this project's classpath

{{FRAMEWORK_VERSIONS}}

{{RULES}}
