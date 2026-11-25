# nexus-cli — every screen action from a shell

## Status

**FROZEN — 2026-09-22**, backlog `cli_parity_for_every_ui_action` (Product Owner
P1: "the product must not be UI-bound; every button needs a CLI counterpart").

## Principle

The CLI is the screen's HTTP calls, nothing more: the same routes, the same
session cookie, the same CSRF token, the same role gates. It has no side door
into the database or the artefact store (the older `bootstrap-*`,
`credential-*` and `backup-retrieve` commands are deployment-time tools and
stay as they are). Whatever the screen may do, `api <METHOD> <path> [json]` can
do; the named commands are the buttons spelled out.

## Where it runs

Inside the shipped image, as the `cli` launcher role:

```bash
kubectl -n ui2 exec -it deploy/ui2-service -- /app/nexus-cli login http://localhost:8080 <username>
```

The password is read from `NEXUS_CLI_PASSWORD` or the console prompt — never
from an argument. The session (cookie + CSRF token) is stored under
`$NEXUS_CLI_HOME/session.json` (default `~/.nexus-cli`, mode 0600; inside the
pod `HOME=/app/home`, an emptyDir that dies with the pod). `logout` ends the
server session and deletes the file.

## Commands

| command | the screen's equivalent |
|---|---|
| `devices` | Inventory list |
| `backups [deviceId]` | Backup fleet table / one device's History (with `baseline_artefact_id`) |
| `backup-run <deviceId> <reason> [backup\|snapshot]` | Backup Now / Snapshot Now |
| `backup-run-all <reason>` | Run Fleet Backup |
| `backup-download <artefactId> <reason> <dest>` | Download (audited before the first byte) |
| `backup-contents <artefactId>`, `backup-relist <artefactId>` | Contents, List now |
| `backup-compare <older> <newer>` | Compare |
| `backup-baseline <deviceId> <artefactId\|clear>` | Set / Clear baseline |
| `backup-target <deviceId> on\|off` | Backup targets switch |
| `policy`, `policy-set <on\|off> <cron> <days> <depth>` | Retention & Policies |
| `jobs [k=v …]`, `jobs-export <dest.csv> [k=v …]` | Jobs screen filters, pages, Export CSV |
| `inventory-collect <deviceId>`, `configuration-collect <deviceId>` | Collect now |
| `zone-matrix-versions`, `zone-matrix-version <versionId> [blocks]` | Zone matrix: published versions; one version (blocks needs `zone_matrix_zones_read`) |
| `zone-matrix-draft [blocks]`, `zone-matrix-template`, `zone-matrix-policies` | Zone matrix: draft with diff and publish check; template; policies |
| `zone-matrix-lookup <address> [versionId]` | Zone matrix: Lookup |
| `zone-matrix-zone-add <json\|@file>`, `zone-matrix-zone-update <zoneId> <json\|@file>`, `zone-matrix-zone-delete <zoneId> <revision>` | Zone matrix: add / edit / delete zone |
| `zone-matrix-cell-set <json\|@file>`, `zone-matrix-cell-clear <json\|@file>`, `zone-matrix-default-set <json\|@file>` | Zone matrix: set / clear cell, set default |
| `zone-matrix-service-set <json\|@file>`, `zone-matrix-service-set-delete <json\|@file>` | Zone matrix: save / delete service set |
| `zone-matrix-template-import <revision> [replace]`, `zone-matrix-discard <revision>` | Zone matrix: Import template, Discard draft |
| `zone-matrix-confirm-reference <json\|@file>`, `zone-matrix-publish <json\|@file>` | Zone matrix: Confirm reference, Publish (four-eyes) |
| `zone-matrix-evaluate [policyId\|-] [versionId\|-] [witnesses]`, `zone-matrix-summary [policyId\|-] [outcome\|-] [limit]` | Zone matrix: Evaluate; Violations and Coverage summary |
| `api GET\|POST\|PUT <path> [json\|@file]` | anything else |

Exit codes: 0 success, 1 the service refused or failed, 2 usage / not logged
in, 3 the session is not allowed (401/403).
