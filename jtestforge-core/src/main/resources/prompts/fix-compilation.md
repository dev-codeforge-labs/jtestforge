# The tests you wrote do not compile

The tests you just wrote for `{{CLASS_FQN}}` were added to its `{{TIER}}` test class, and
the module no longer compiles. Fix them.

## Compiler errors

```
{{COMPILER_ERRORS}}
```

## The class under test, for reference

```java
{{CLASS_SOURCE}}
```

## Collaborators available as mocks

{{COLLABORATORS}}

## Test frameworks actually on this project's classpath

{{FRAMEWORK_VERSIONS}}

Most compilation failures here come from using a type, method or annotation that is not on
this list. Check what you used against it before rewriting.

## Your task

Return corrected versions of the failing test methods, with the same names. Do not change
what they assert — only what stops them compiling. If a test cannot be made to compile
without weakening what it checks, leave it out entirely rather than gutting it.

{{RULES}}
