# ADR-03: HttpClientConfig ctor policy

## Context
`HttpClientConfig` is a record; the canonical ctor cannot be made private. PR4 required discouraging direct ctor use and moving validation to `ConfigValidator` (e.g., `maxRetries < 0`) while keeping builder as the main entry.

## Decision
- Mark canonical constructor as `@Deprecated` to signal consumers to use the builder.
- Keep builder as the supported way to instantiate; constructor remains public per record rules.
- Allow negative `maxRetries` to flow through so `ConfigValidator` enforces it (tests depend on this).

## Consequences
- Existing code using `new HttpClientConfig(...)` compiles but gets deprecation warnings; migration path is the builder.
- Validation responsibility is centralized in `ConfigValidator`; constructor performs minimal defaults only.