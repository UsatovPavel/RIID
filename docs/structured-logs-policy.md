# Structured Logs Policy (PR12)

This document defines structured logging rules for RIID in PR12 scope.

## Scope Boundaries
- Included: structured logs format, `trace_id`, `event`/`step`, milestone markers, redaction policy.
- Included: offline metric counting from logs.
- Excluded: Prometheus/Grafana metrics export (planned for PR10b).
- Excluded: tracing backend/collector (only `trace_id` in logs for now).

## Required Event Fields
Each structured event must include:
- `timestamp`
- `level`
- `event`
- `trace_id`
- `component`
- `operation`
- `result`
- `duration_ms`
- `error_code`
- `error_kind`

## Milestone Fields
For milestone events add:
- `milestone` = `true`
- `milestone_type` = one of:
  - `performance`
  - `traffic`
  - `stability`
  - `comparison`

Current PR12 usage:
- `app` critical-path events use `milestone_type=performance`.
- `dispatcher` source strategy events (`source.select`/`source.fetch`) use `milestone_type=comparison`.

## Security and Redaction
- Raw secrets must not be logged.
- Do not log raw command values in runtime logs/errors without redaction.
- Redaction is performed by `riid.core.logging.LogRedactor`.
- P2P logging uses sanitized URL/path values.

JVM flag:
- `-Driid.log.redaction.enabled=true|false` (default: `true`)

## Offline Counting (PR12)
Use logs for offline counting of source fetch outcomes.

Example (`jq`) for `source.fetch` by source/result:
```bash
jq -r 'fromjson? | select(.event=="source.fetch") | [.source,.result] | @tsv' smoke-logs.txt \
  | sort | uniq -c
```

If log lines have a non-JSON prefix (logger timestamp/thread), extract JSON first:
```bash
sed -E 's/^.* -- (\{.*\})$/\1/' smoke-logs.txt \
  | jq -r 'fromjson? | select(.event=="source.fetch") | [.source,.result] | @tsv' \
  | sort | uniq -c
```

## Notes
- Runtime and P2P operational events may be verbose and are not all milestones.
- Milestones are used as a stable layer for analysis/reporting.
