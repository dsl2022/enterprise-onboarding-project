# CR-20260629-1510 — v1 de-flake `RequestEngineTest.two_approvers_one_wins_one_conflict`

- **ID:** CR-20260629-1510
- **Date:** 2026-06-29 15:10 EDT
- **Author:** Senior-engineer (Claude), architect-endorsed during the 6a design-note review
- **Target:** PR #—（Phase 6a branch `v1-phase6a`); test `app/src/test/java/com/eop/request/RequestEngineTest.java`
- **Status:** Applied (separate commit on the 6a branch — see Resolution)
- **Related:** [app/src/test/java/com/eop/request/RequestEngineTest.java](../../app/src/test/java/com/eop/request/RequestEngineTest.java) · [app/src/main/java/com/eop/request/RequestService.java](../../app/src/main/java/com/eop/request/RequestService.java) (the guarded serializer under test)

## Context

`two_approvers_one_wins_one_conflict` fires two concurrent `decide(APPROVE)` calls at the same
`UNDER_REVIEW` request (same expected version) and asserts the guarded transition
(`UPDATE … WHERE status = :from AND version = :expected`) admits **exactly one** winner — one
`RequestEntity` success, one `ConflictException`, final state `APPROVED`.

It flaked **once** under CI lock contention: the run observed `successes == 0` (both racing
transactions rolled back). The serializer invariant it verifies is real and correct — the flake is in the
**test's** treatment of a *transient* DB outcome (a deadlock victim / lock-acquisition failure under heavy
parallel CI load) as if it were a *logical* outcome. A transient `TransientDataAccessException` is exactly
the condition a caller is expected to retry; surfaced raw, it masquerades as "nobody won," failing the
`successes == 1` assertion even though the serializer behaved correctly. Now that `main` requires these
checks, an intermittently-red test is real merge friction, so the fix has extra value.

## Requested change

Make the test deterministic **without weakening the invariant**: retry only on Spring's
`TransientDataAccessException` (deadlock loser, lock-acquisition timeout, serialization failure — the
genuinely retryable infra contention), with a small bounded backoff. A non-transient outcome
(`ConflictException`, `PreconditionFailedException`) is a **logical** result and is never retried. After
retries converge, the assertions are unchanged: exactly one success, exactly one conflict, final
`APPROVED`.

This is correct because retry drives the contention to its deterministic resolution: whichever transaction
commits the APPROVE is the winner; the other re-attempts, observes `status = APPROVED ≠ UNDER_REVIEW`, and
deterministically gets `ConflictException` (rowcount 0 on the guarded UPDATE). A transient that aborts the
*winner* simply lets the other commit on its next try — still exactly one winner. The production engine is
untouched; this is a test-only robustness fix.

## Resolution

Applied on the `v1-phase6a` branch as a **separate commit** (not bundled into the audit feature), per the
architect's instruction. `attempt(...)` (used only by the two concurrency callables) now retries the action
on `TransientDataAccessException` up to a small cap with backoff, and returns the result-or-logical-exception
as before. No change to `RequestService` or the guarded serializer.
