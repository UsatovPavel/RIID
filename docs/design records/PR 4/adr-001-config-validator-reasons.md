# ADR-001: Standardize Config Validation Reasons

## Context
`ConfigValidator` threw string literals, making assertions brittle and hard to audit for completeness. PR4 tasks required full branch coverage and enum-based reasons.

## Decision
- Introduced `ConfigValidationException.Reason` enum with canonical messages.
- `ConfigValidator` throws only these enum messages (plus fallback for unknown fields).
- Tests assert against enum messages; reflection used to reach all branches.

## Consequences
- Messages are consistent and centrally managed.
- Adding new validation paths requires adding an enum value.
- Tests are stable across refactors and cover all throw branches.

