# The tests you wrote compile but fail

_No tools here: every file read, directory listing, search or shell command is
refused and only delays your answer. Everything that exists for this task is
below. If some detail is missing, stub it with the libraries listed as available
and carry on — do not go looking. Emit nothing outside the two fenced blocks._

The tests below run against `{{CLASS_FQN}}` in its `{{TIER}}` test class, and do not pass.

## Failures

```
{{TEST_FAILURES}}
```

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

## Your task

Decide, for each failure, which of these it is:

1. **The test's expected value is wrong.** The production code is correct and your
   assertion encoded a different expectation. Correct the assertion to match what the code
   actually specifies — read the method again rather than copying the observed value.
2. **The test's setup is wrong.** A collaborator is unstubbed, or stubbed with the wrong
   arguments, so the method never reaches the path you meant to exercise. Fix the setup.
3. **The test found a real defect in the production code.** In that case, do not weaken
   the test to make it pass — leave that test out of your response entirely and say
   nothing about it. Production code is never modified here.

Never make a test pass by deleting its assertion, by asserting the value the code happens
to produce today without checking that value is right, or by wrapping it in a try/catch.

Return corrected versions of the failing test methods, with the same names.

{{RULES}}
