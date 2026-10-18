-- V165 -- the result of a device's last pinned-identity comparison (PO decision 2026-10-18).
--
-- "Identity verified" on the device header used to mean only "enrolled". It now follows the real comparison the
-- worker makes on every connection: the SSH host key (or, for HTTPS appliances, the TLS leaf certificate) the device
-- presented against the fingerprint pinned for it. One row per device holds the verdict of its LAST such comparison:
--   MATCH       the presented fingerprint equalled the pinned one;
--   MISMATCH    it did not (the connection may still have run under WARN mode; ssh_host_key_observation keeps the detail);
--   FIRST_USE   nothing was pinned yet, this connection pinned it: there was nothing to compare, so it is not a match.
-- No fingerprint, address or key is stored here, only the verdict, the transport and when. A device never compared
-- (or one whose transport has no pin, such as Palo Alto XML API with verification off by PO decision 2026-09-21)
-- has no row, and the screen says "Not verified yet". Written by the worker, never by an operator: no audit trigger,
-- like device_policy_install (V60); the jobs row of the connection is the audited record.
CREATE TABLE device_identity_check (
    device_id  TEXT        PRIMARY KEY REFERENCES devices(device_id) ON DELETE CASCADE,
    transport  TEXT        NOT NULL CHECK (transport IN ('SSH_HOST_KEY', 'HTTPS_CERTIFICATE')),
    verdict    TEXT        NOT NULL CHECK (verdict IN ('MATCH', 'MISMATCH', 'FIRST_USE')),
    checked_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT, UPDATE, DELETE ON device_identity_check TO ui2_app;
