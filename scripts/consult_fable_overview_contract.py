#!/usr/bin/env python3
"""Fable drafts the Overview exception-and-evidence screen contract (PO request 2026-09-22).

Per AGENTS.md "External model and second-opinion consultation law" the draft is written by the external
reviewer through its own CLI (`claude -p`, Fable model) and saved unaltered. The engineering session then
implements from it after the Product Owner accepts it.
"""
import subprocess
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parents[1]
OUT = BASE_DIR / "docs" / "design" / "OVERVIEW_EXCEPTION_SCREEN_CONTRACT_FABLE_DRAFT.md"
INPUTS = [
    "docs/design/OVERVIEW_COUNCIL_2026_09_22_ASTRA.md",
    "docs/design/OVERVIEW_COUNCIL_2026_09_22_FABLE.md",
]

SCHEMA_FACTS = """Stored data available to the service (PostgreSQL, one database):
- devices(device_id, vendor_hint check_point|palo_alto, role gateway|management_server, enrollment_state,
  observed_hostname, observed_model, observed_software_version, observed_ha_role, cluster_member_ref, backup_target)
- jobs(job_id, job_type, target_device_id, state REQUESTED|CLAIMED|EXECUTING|COMPLETED|FAILED, outcome,
  terminal_reason, submitted_at, finished_at); job types include cp_/pan_ inventory_collect,
  configuration_collect, gateway_backup / device_state_backup, device_confirm_*
- device_inventory_run(run_id, device_id, collected_at, virtual_systems) + device_inventory_ha(run_id, context, role)
- device_configuration_run(run_id, device_id, collected_at, read_kind, is_primary, canonical_hash,
  change_state changed|unchanged|first_run) + device_configuration_index(run_id, context, section, entry_count)
- cluster member DIFF is computed today in the browser from each member's sanitized text (not stored)
- backup_artefact(artefact_id, device_id, artefact_class, vendor, created_at, ciphertext_bytes) + backup_policy
  (schedule cron, enabled, retention) + devices.backup_target
- device_platform_facts(device_id, serial_number, hotfix_level e.g. 'R81.20 Jumbo Take 119', platform_family,
  content_versions jsonb {app,threat,av,wildfire,url}, uptime_text, observed_at)
- compliance: evaluated on read by the service per device from the latest configuration (cached 10 min);
  overview figures: evaluated firewalls, observed %, evidence coverage %, critical deficiencies, data gaps,
  per-framework pass/fail/unavailable. No stored history of assessments.
- failover preflight per cluster: read-only API, may return no checks.
- GET /api/v2/jobs/stats (total, total_24h, completed_24h, failed_24h, running).
Constraints: aiview persona sees masked names (server-side masking on the response); no device command from
the screen; one aggregate endpoint, target < 300 ms server time on 105 devices / 39 clusters; every figure
evidenced or UNKNOWN; English engineering text; status line DRAFT until the Product Owner accepts."""

PROMPT_HEAD = """You are Fable, writing the implementation contract for the neXus Overview screen, following the two
design-council answers below (yours and Astra's). The Product Owner accepted the council's direction and wants
the contract now; an engineer (Opus) implements it afterwards.

Write ONE Markdown contract document with these sections:
1. Status (DRAFT -- Product Owner acceptance pending), scope, non-goals.
2. Screen layout, section by section, first release only (the council's minimal first release), with exact
   copy for titles and empty/UNKNOWN states.
3. For every figure: name, exact definition (numerator/denominator, time window, thresholds such as the 24 h
   staleness), the SQL-level source from the schema facts, UNKNOWN rule, and the click target (screen + filter).
4. API contract: one GET endpoint (path, JSON shape with field names and types, masking notes, caching and
   refresh), and which existing routes the click targets need (and filters they must accept).
5. Data that must be added (e.g. storing the cluster DIFF count server-side, retaining the previous HA role)
   -- only what the first release needs, each with a migration sketch; everything else deferred explicitly.
6. Tests that must exist (unit, API, UI, aiview masking) and the acceptance checklist the PO will run.
7. Deferred items (second release) with the data they need.
Be precise and short. No generic advice. Do not invent data sources outside the schema facts.
"""


def main():
    parts = [PROMPT_HEAD, "\n## Schema facts\n", SCHEMA_FACTS]
    for rel in INPUTS:
        parts.append(f"\n\n## Council input: {rel}\n\n")
        parts.append((BASE_DIR / rel).read_text(encoding="utf-8"))
    prompt = "".join(parts)
    cmd = ["/Users/OzanDur/.local/bin/claude", "-p", prompt, "--model", "claude-fable-5-1", "--tools", "",
           "--dangerously-skip-permissions"]
    try:
        proc = subprocess.run(cmd, text=True, capture_output=True, cwd=str(BASE_DIR), check=True, timeout=1500)
    except Exception as err:  # noqa: BLE001 -- report verbatim
        print(f"fable: failed: {err}", file=sys.stderr)
        return 1
    OUT.write_text(proc.stdout.strip() + "\n", encoding="utf-8")
    print(f"fable: ok -> {OUT} ({OUT.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
