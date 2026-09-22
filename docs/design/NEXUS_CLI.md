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
| `api GET\|POST\|PUT <path> [json\|@file]` | anything else |

Exit codes: 0 success, 1 the service refused or failed, 2 usage / not logged
in, 3 the session is not allowed (401/403).
