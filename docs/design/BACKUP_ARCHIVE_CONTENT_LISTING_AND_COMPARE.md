# Backup archive content listing and compare

## Status

**FROZEN — 2026-09-22, under the Product Owner directive of the same day**
("at minimum the Backbox standard"; see
`PO_DECISION_RECORD_2026_09_22_BACKUP_HTTP_DOWNLOAD_WITH_RBAC.md` for the
directive's wording). Supersedes, for the listing only, C7 DV-3's "never a
structural summary for the opaque Gaia archive".

## What is recorded (V41)

For every stored backup artefact (Check Point `add backup local` .tgz, Palo Alto
device-state .tgz) the worker, after the manifest row is on record, decrypts the
artefact once more in memory, walks the tar stream and records per member:

| field | content |
|---|---|
| `entry_path` | the member's path inside the archive |
| `entry_type` | `file` / `dir` / `symlink` / `other` |
| `entry_bytes` | the member's size (0 for non-files) |
| `entry_sha256` | SHA-256 of the member's content, files only |

`backup_artefact_content_listing` carries the outcome per artefact: `LISTED`
with the entry count, or `FAILED` with the reason (not a tar, truncated, more
than 250 000 entries). The listing is **best effort**: it never fails the backup
job, which is already verified (device digest = received digest) and stored
when the listing runs.

## What is never recorded

A member's content. The stream is read once for the digest and discarded
(raw-evidence law). A Gaia backup's `etc/shadow` therefore appears as a name, a
size and a digest — the digest changes when the account database changes, which
is the compare's whole value, without the product ever holding the file.

## Compare

`GET /backups/{a}/compare?with={b}`: both artefacts must belong to the same device and
both must be `LISTED`; otherwise the response says which side is not listed and
why, and no diff is invented. Pairs are joined on path and classified `added` /
`removed` / `changed` (digest or type differs) / `unchanged`. A compare over
entry names and digests is structural: it says *which files* changed, never
*what* changed inside them. Semantic diffs of configuration text remain the
Configuration plane's concern (`SemanticDeviationEngine` for Palo Alto XML).

## Audit

Neither V41 table carries an audit trigger. Both are derived caches that a
re-listing regenerates; the audited record of the backup is the manifest row.
Reads are posture reads, gated exactly like `GET /backups` (`device_backup_read`).
