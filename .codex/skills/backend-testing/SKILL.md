---
name: backend-testing
description: Use when the user asks to generate, improve, review, or debug backend tests, especially unit tests and API tests for Java/Spring services, controllers, repositories, Kafka consumers, Redis integrations, and other server-side modules.
---

# Backend Testing

Use this skill when the task is about backend test generation or test quality. Default to writing tests, not just describing them.

## Goals

- Add tests that protect real behavior, not implementation trivia.
- Prefer a small number of high-value tests over broad but shallow coverage.
- Cover normal flow, edge cases, failure paths, and regression cases from recent code changes.

## Default Workflow

1. Read the target code and identify the public behavior worth testing.
2. Classify the target:
   - Pure business/service logic
   - Controller / HTTP layer
   - Repository / persistence
   - Event consumer / async processor
   - External integration
3. Write a short test matrix before coding:
   - happy path
   - validation / boundary inputs
   - dependency failure
   - idempotency / duplicate processing if relevant
   - cache / message / persistence side effects if relevant
4. Choose the narrowest test type that can prove the behavior.
5. Implement the tests.
6. Run the tests and fix compile or assertion issues.

## Test Type Selection

### Unit Test

Use for service logic, helpers, mappers, validators, and deterministic business rules.

Preferred stack:
- JUnit 5
- Mockito
- AssertJ if already used by the repo

Rules:
- Mock external dependencies only.
- Do not mock value objects, DTOs, or simple collections.
- Assert business outputs, state changes, and important interactions.

### Web / Controller Test

Use for request validation, auth behavior, HTTP status codes, and response bodies.

Preferred stack:
- `@WebMvcTest`
- `MockMvc`

Rules:
- Verify status code, payload shape, and validation errors.
- Mock service dependencies behind the controller.
- Do not load the whole application unless required.

### Repository / Database Test

Use for SQL behavior, mappings, query correctness, and persistence constraints.

Preferred stack:
- `@DataJpaTest`, mapper tests, or the repo's existing DB test pattern
- Testcontainers when the query depends on real database behavior

Rules:
- Avoid mocking the database.
- Prefer realistic fixture data.

### Integration Test

Use when correctness depends on multiple layers working together or on framework configuration.

Examples:
- Redis cache behavior
- Kafka consumer flow
- transaction + outbox logic
- serialization / deserialization

Rules:
- Keep the scope targeted.
- Only use full-context tests when a narrower slice would miss the bug.

## Spring / Java Guidance

- For service classes, start with JUnit 5 + Mockito.
- For controllers, prefer `MockMvc` over full boot tests.
- For Kafka listeners, test deserialization, handler branching, retry / ack behavior, and idempotency assumptions.
- For Redis or cache code, verify cache hit, miss, invalidation, and stale-data behavior.
- For event-driven code, test both success and failure branches, especially whether offsets / acknowledgements / retries behave correctly.

## What To Cover

Always consider these and include the ones that matter:

- success path
- invalid input
- null / empty / missing fields
- boundary values
- dependency throws exception
- repeated call / duplicate message
- wrong state transition
- serialization or mapping edge cases
- regression for the bug or diff that motivated the test

## What To Avoid

- Do not test private methods directly.
- Do not assert every internal call if the behavior can be proven from outputs.
- Do not over-mock framework code.
- Do not add brittle sleeps for async code if latches, polling, or deterministic hooks exist.
- Do not generate large fixture blobs when a focused fixture is enough.

## Quality Bar

Before finishing:

- Make sure each test name describes a behavior.
- Check that assertions would fail for a real regression.
- Remove duplicate or low-signal tests.
- Run the relevant test target if possible.
- If some paths are not testable without refactoring, say so explicitly.

## Prompt Pattern

When you need a strong default prompt, use this structure:

```text
Generate backend tests for this target.
First list the test matrix, then implement the tests.
Prefer the narrowest test type that proves behavior.
Cover success, edge cases, failure paths, and regressions from the current change.
Use the repo's existing test stack and style.
Avoid over-mocking and avoid testing private implementation details.
Run the tests if possible and fix failures.
```
