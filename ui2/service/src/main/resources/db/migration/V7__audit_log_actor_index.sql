-- V7 — audit_log actor_fingerprint index
-- (UI2_0_B1_08_AUDIT_LOGS_SCREEN_CONTRACT.md §5.3)
--
-- actor_fingerprint carries no index in V1, and role:viewer's `read_own`
-- scope filters on it on every request. B1-8 originally reserved V6 for
-- this; V6 was taken by the fn_audit_capture correctness fix
-- (B1-2a Amendment A-2), which states the index takes the next free
-- number. One statement, nothing else.

CREATE INDEX idx_audit_log_actor_fingerprint ON audit_log(actor_fingerprint);
