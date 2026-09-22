# Every UI action has a CLI counterpart (the product must not be UI-bound)

status: automated_validated · target: PO directive 2026-09-22; ui2/cli CliEntryPoint is the seed

2026-09-22: nexus-cli session commands shipped in the boot jar (launcher role 'cli', /app/nexus-cli in the image): login/logout/session, api <METHOD> <path> [json] as the general form, plus named commands for every Backup/Jobs/Inventory/Configuration screen action. docs/design/NEXUS_CLI.md. Same routes, cookie, CSRF and role gates as the screen; no side door.
