# Logs policy (structured JSON)

One **JSON object per line** (NDJSON) on the root logger (console in the fat JAR). Config: `logback.xml` includes `logback-encoder-masking.xml`. Stack: SLF4J 2, Logback, LogstashEncoder.
## Line format
Each log line is a single JSON object. Typical fields:
| Field | Source | Notes |
|--------|--------|--------|
| `timestamp` | Encoder | ISO-8601 offset datetime |
| `level` | Logback | e.g. `INFO`, `WARN`, `ERROR` |
| `message` | Logger | Human-readable text |
| `logger_name` | Logback | Java logger name |
| `thread_name` | Logback | Thread |
| `trace_id` | MDC | Request correlation; set for the whole CLI pull **and** for each daemon `POST /pull` |
| `component` | MDC | e.g. `app` |
| `operation` | MDC | Current phase (`request`, `manifest.fetch`, `source.select`, …) |
| `event` | Key-value | Present on **step / milestone** events |
| `result` | Key-value | `success` or `error` on step events |
| `duration_ms` | Key-value | Duration on step events |
| `error_kind` | Key-value | On failures (see below) |
| `error_code` | Key-value | On failures; module-local string |

## Fields

These are emitted via `MilestoneEventLogger` (and dispatcher helper) on the success path of a normal pull:
| `event` | Where | Typical `operation` (MDC) |
|---------|--------|-----------------------------|
| `request.start` | `CliApplication` | `request` |
| `manifest.fetch` | `ImageLoadingFacade` | `manifest.fetch` |
| `source.select` | `DispatcherMilestoneLogger` | `source.select` |
| `source.fetch` | `DispatcherMilestoneLogger` | `source.fetch` |
| `archive.build` | `OciArchiveBuilder` / dispatcher | `archive.build` |
| `engine.import` | `ImageLoadingFacade` | `engine.import` |
| `request.finish` | `CliApplication` | `request` |
**Milestone rows** should include `event`, `result`, and `duration_ms`. Error rows should add `error_kind` and `error_code`.


## Step events (`event`)

Typical pull order: `request.start` → `manifest.fetch` → `source.select` → `source.fetch` → `archive.build` → `engine.import` → `request.finish`. Emitters: `CliApplication`, `ImageLoadingFacade`, `DispatcherMilestoneLogger`, `OciArchiveBuilder`. Step rows must have `event`, `result`, `duration_ms`. `error_kind` values in use: `VALIDATION`, `NETWORK`, `RUNTIME`, `INTERNAL`.

## Masking

`MaskingJsonGeneratorDecorator` masks JSON paths `authorization`, `password`, `token`, `identityToken` and applies regex fallbacks (Bearer, `key=value`). Do not log raw secrets in messages—masking is a safety net.

Redaction test config: `src/test/moduled/resources/logback-redaction-test.xml`.

## `jq` examples

Pure NDJSON (e.g. `java -jar … &> riid.ndjson`):

```bash
jq -c 'select(.event != null)' riid.ndjson
jq -r 'select(.trace_id != null) | .trace_id' riid.ndjson | sort -u
jq -c 'select(.event != null) | {t: .timestamp, e: .event, r: .result, ms: .duration_ms}' riid.ndjson
```

Strip leading non-JSON text before piping to `jq` if your capture includes status lines.

## Daemon (`POST /pull`)

Each successful pull path (after JSON validates: `repository` / `reference` / `runtimeId`) sets **`trace_id`** before work is submitted to the pull executor:

- Default: new random UUID (same style as `CliApplication.run`).
- Optional: client may send **`X-Trace-Id`** or **`X-Request-Id`** (trimmed; up to 128 chars; letters, digits, `-`, `_`, `.`, `:` only). Invalid or missing → new UUID.

MDC is installed on the Jetty thread, **copied** onto the virtual-thread worker that runs `ImageLoader.load` (so milestones and nested layer pulls see the same `trace_id`), then **`MdcContext.clearRequestContext()`** runs in `finally` on the Jetty thread so the connector thread does not leak context between requests.

## Modules

**App:** sets MDC; request / manifest / archive / import milestones. **Daemon:** `PullHttpHandler` sets `trace_id` per pull and propagates MDC into the pull executor. **Dispatcher:** `source.select` / `source.fetch`. **Client / P2P / runtime:** avoid secrets and unsafe paths in log text.

## Code & tests

`MilestoneEventLogger`, `MdcContext`, `LogContextKeys`, `DispatcherMilestoneLogger`. Integration: `CliEndToEndLiveTest` (milestones + `trace_id` on non-milestone `riid.*` logs); `DaemonPullUnixSocketTest` / `PullHttpHandlerCorrelationTest` (daemon pull + `trace_id` on the loader thread).
