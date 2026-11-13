-- V211 -- the directory account name (sAMAccountName) a directory sign-in shows in the app bar.
--
-- Display only. The identity of a directory actor stays actor_fingerprint (a one-way hash of the bind DN), the
-- encrypted principal reference stays the bind DN used for revalidation and principal bindings, and no
-- authorization or audit decision reads this column. Before this column the app bar showed the decrypted bind DN.
--
-- Nullable and without a default: a metadata-only change that rewrites no row. Rows of sessions signed in before this
-- release keep NULL (the status endpoint then falls back to the previous value) until the user's next sign-in. The
-- table is the ephemeral cache of C3 §4.4; the session-end trigger and expireDirectory() remove the row, display name
-- included. The existing chk_actor_directory_proof constraint is unchanged.
ALTER TABLE actor_authz_state ADD COLUMN directory_display_name TEXT;
ALTER TABLE actor_authz_state ADD CONSTRAINT chk_actor_directory_display_name
    CHECK (directory_display_name IS NULL OR (length(directory_display_name) BETWEEN 1 AND 64 AND directory_profile_id IS NOT NULL));
