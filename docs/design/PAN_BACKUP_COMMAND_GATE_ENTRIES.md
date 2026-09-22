# Palo Alto backup — network-device command gate entries

## Status

**FROZEN — 2026-09-22.** Backlog `pan_backup_include_set_format_config` (Product
Owner P0, 2026-09-22). Measurement: Backbox trail 34411050 (PAN-OS firewall,
"cURL (Device-State)" job) — the XML API half is V40's two rows; this document
adds the CLI half. Gate rows: V43 / `gate_registry_fixture.yaml`; capability
`pan_set_config_read.yaml`.

## The bundle

One artefact per run, a gzip tar with two members:

| member | source | size measured |
|---|---|---|
| `device-state.tgz` | XML API `type=export&category=device-state` (V40) | 0.6–2.9 MB |
| `running-config.set` | CLI `show config running` in set format (this document) | hundreds of lines |

Both halves are required: a run whose CLI read is unavailable fails with
`pan_set_config_unavailable: <reason>` rather than storing half a bundle.

## Entries (all `read`, one interactive SSH session, port 22, the device's collection credential, host key trusted on first use)

| # | gate_id | command | timeout | note |
|---|---|---|---|---|
| 1 | `pan_backup_ssh_scripting_mode_on` | `set cli scripting-mode on` | 20 s | session setting, answers with the prompt only |
| 2 | `pan_backup_ssh_pager_off` | `set cli pager off` | 20 s | without it the output stops at `--more--` |
| 3 | `pan_backup_ssh_config_output_format_set` | `set cli config-output-format set` | 20 s | selects the set rendering for entry 4 |
| 4 | `pan_backup_ssh_show_config_running` | `show config running` | 180 s | secret-bearing (password hashes, keys); stored only inside the envelope-encrypted bundle, never parsed, never surfaced |

Entries 1–3 answer with an empty line and the prompt; the interactive shell
reports that as "empty output", which the reader treats as success — only a
timeout is a failure there. Entry 4 must answer at least five `set ` lines;
fewer is a refusal or an error page and fails the run with the count.

## Deviation from Backbox, stated

Backbox runs `configure` then `show`, which prints the **candidate**
configuration. neXus runs `show config running` in operational mode: the
**running** configuration is what the device enforces, which is what a backup
must restore, and staying in operational mode keeps a single prompt for the
whole session (the interactive shell learns one prompt per session). Should
the Product Owner want the candidate as well, it is one more gated read
(`show config candidate`), not a redesign.
