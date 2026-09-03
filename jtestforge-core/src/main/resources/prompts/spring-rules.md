# Spring slice rules

These tests run inside a Spring test slice. The slice's `ApplicationContext` is **cached
and shared** across every test class that declares the same configuration, and loading one
costs seconds. Anything that changes what this class declares forks a second context, or
evicts the shared one — a cost the project's CI then pays on every build, forever.

## Never change the class's shape

The test class's annotations, its fields, and its set of mock beans are **fixed**. You are
adding methods to it and nothing else.

Never write any of these on a test method — each one is rejected before it is even
written to the file:

`@DirtiesContext`, `@TestPropertySource`, `@ActiveProfiles`, `@ContextConfiguration`,
`@MockitoBean`, `@MockBean`, `@SpyBean`, `@Import`, a nested `@TestConfiguration`, or any
slice annotation (`@WebMvcTest`, `@DataJpaTest`, `@JsonTest`, `@SpringBootTest`).

Stub only the mock beans the class already declares — they are listed for you. If a test
you want to write genuinely needs a collaborator that is not declared, do not add it:
write the other tests instead and leave that one out.

Never start a real server, and never use `TestRestTemplate`, `WebTestClient` against a
live port, `webEnvironment = RANDOM_PORT`, Testcontainers, or a real datasource. Those
turn a slice into an integration test and are rejected.

## Assert the outcome, never just the status

A request assertion that only checks `status().isOk()` proves the endpoint is mapped and
nothing more. For any endpoint that returns a body, assert the body's content as well —
`content()`, `jsonPath(...)`, or an equivalent.

For a rejected request, assert the status **and** something that identifies *which* rule
rejected it. A 400 alone does not distinguish a failed constraint from a malformed
payload.

For an authorisation rule, assert **both** outcomes: that an unauthorised caller is turned
away, and that a properly authorised one still gets through. Only one of the two proves
nothing about the restriction.

## `@DataJpaTest`

Always `flush()` and `clear()` the `TestEntityManager` before asserting a mapping.
Reading back an entity you just saved without flushing returns the same in-memory instance
from the first-level cache — the mapping is never actually exercised, and the test passes
regardless of whether it is correct.
