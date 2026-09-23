-- V55 -- difference signatures on the cluster member DIFF summary (UI review 2026-09-23, backlog
-- cluster_diff_member_specific_tuning). To separate member-specific differences from the rest, the product first
-- measures WHICH settings differ across clusters. Each differing setting is stored as a normalized signature
-- (section > attribute; identifiers with digits become <x>, principal names become <item>) with its count in that
-- cluster -- never a value, never a device, user or object name (raw-evidence law, sensitive identity law).
ALTER TABLE cluster_member_diff ADD COLUMN diff_signatures JSONB NOT NULL DEFAULT '{}'::jsonb;
