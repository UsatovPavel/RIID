# ADR-002: Use Apache HttpClient 5 Parser for WWW-Authenticate

## Context
Existing `AuthParser` was fragile; PR4 required strict parsing of `WWW-Authenticate` with a supported library instead of ad-hoc code.

## Decision
- Added dependency on Apache HttpClient 5 auth parser.
- `AuthParser` now delegates to HttpClient 5 parsing utilities and maps the first Bearer challenge to our model.

## Consequences
- More robust parsing of malformed/complex headers.
- Extra dependency in the client core; kept scope limited to parsing to avoid broader coupling.

