# Agent contracts for AGENT-99

Prompts live here as files, not only inside a session: `CronList` truncates
them, cron jobs are in-memory and die with the session, and a prompt that only
exists in a transcript cannot be reviewed or improved.

| file | who | role |
|---|---|---|
| `cron-masteragent.md` | the recurring 4h job | wakes MasterAgent for a work shift |
| `executor.md` | Sonnet subagent | runs arms, touches the stand |
| `validator.md` | Sonnet subagent, fresh context | independently checks what the executor claims |

## The split, and why

The executor and the validator are **separate agents, and the validator starts
with no shared context**. An agent that ran an arm is the worst possible judge of
whether that arm is valid: it already believes its own steps succeeded. The
validator only sees artefacts on disk and the live cluster, so it cannot inherit
that belief.

MasterAgent talks **only to the validator**; the validator talks to the executor.
A claim reaches MasterAgent only after something with no stake in it has checked
the evidence.

## Rules every subagent prompt must carry

These are not style preferences — each one was paid for.

1. **Never end your turn to wait for a background job.** Two agents did this and
   burned ~500k tokens between them while producing two mirrored images. Block on
   the job (`until` loop, `wait`) and continue. Long waits belong to whoever can
   afford them, which is usually the parent, not the agent.
2. **Bounded scope, explicit finish line.** "Bring up the stack and load the
   dataset and run an arm" is three tasks; the agent stalls between them. State
   exactly what "done" means.
3. **Model is Sonnet.** Opus is reserved for MasterAgent.
4. **Report blockers, do not route around them silently.** A workaround that is
   not reported becomes an invisible assumption in the results.
5. **Never treat a make invocation as a result.** Check the exit code *and* that
   the output file was rewritten; a failed arm leaves the previous TSV in place
   and it reads as a perfectly plausible new measurement.
6. **Selectel compute is never created or destroyed without asking the user.**
   Pulling images from `cr.selcloud.ru` is fine.
7. **Porto and non-Porto stands never run at once** — the laptop cannot host
   4+ VMs.
