-- Phase 4b (request-owned): provisioning retry/reaper backoff state.
--
-- 4a's worker only polls status=APPROVED. 4b adds a reaper for rows stuck in PROVISIONING (the task that
-- claimed them died mid-provision). Two columns make the reaper safe and non-hot:
--
--   next_attempt_at  -- the provisioning LEASE / backoff gate. markProvisioning arms it to now+lease, so a
--                       still-running-but-slow live worker is NOT double-called by the reaper before the
--                       lease (which must exceed worst-case provision duration) elapses. NULL = due now
--                       (fail-safe: a claim that crashed before arming is reaped immediately).
--   provision_attempts -- reaper re-claim counter; the worker pushes next_attempt_at out exponentially per
--                         attempt so a permanently-failing request backs off instead of looping hot
--                         (retry-until-success + no terminal FAILED state would otherwise flood
--                         provisioning_failed/notify/audit and hammer Graph). A true terminal "give up" is
--                         the future FAILED-state CR; backoff is the now-fix.
--
-- The guarded reaper re-claim (UPDATE ... WHERE status=PROVISIONING AND version=:expected, bumping version
-- + provision_attempts) means two reapers can't both call Graph for the same row — same serializer idiom
-- as the APPROVED->PROVISIONING claim.

ALTER TABLE request.requests
    ADD COLUMN provision_attempts int         NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at    timestamptz;

-- Reaper scan: status=PROVISIONING AND (next_attempt_at IS NULL OR next_attempt_at <= now()).
CREATE INDEX requests_reaper_idx ON request.requests (status, next_attempt_at);
