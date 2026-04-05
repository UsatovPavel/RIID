# ADR-06: Isolate piped commands into BCPE

## Context
`BoundedCommandExecution` (BCE) originally executed both regular external commands and piped import flow (`tar -cf - ... | podman load -q`) on a shared bounded executor.

The piped flow adds three concurrent tasks per operation:
- producer `stderr` reader;
- consumer `stderr` reader;
- stdout->stdin transfer task.

Under parallel pulls this could starve the shared pool and manifest as:
- hanging `POST /pull`;
- degraded or hanging `/metrics`;
- live daemon process with active connectors but stalled progress.

## Decision
- Introduce a dedicated owner class: `BoundedPipedCommandExecutor` (BCPE).
- Move all logic added for piped operations from BCE into BCPE.
- Keep BCE public API unchanged; delegate piped execution to BCPE.
- Use a dedicated executor for BCPE (`cmd-pipe-io-*`), isolated from regular BCE `run(...)` commands.
- Enforce a concurrency limit at BCPE level: maximum 5 simultaneously executing piped operations.

## Consequences
- Regular BCE commands no longer compete with long-running pipe transfer tasks.
- Pipe overload is bounded and degrades predictably via BCPE limit instead of saturating shared command execution.
- Reduced risk of starvation/deadlock-like behavior in mixed workloads.
- Slightly higher code complexity due to separate lifecycle/executor ownership.
