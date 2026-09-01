# SecurityExpert / neXus (working name)

A multi-vendor network-security **evidence** platform. It collects and
reconciles runtime inventory and current configuration from Check Point
(MDS/CMA), Check Point VSX, and Palo Alto Panorama / PAN-OS — with run isolation,
completeness telemetry, last-known-good snapshots, UI freshness state, a
content-addressed configuration history, and privacy-preserving support bundles.

Product maturity axis: `SEE → VERIFY → TRACE → RECOVER → OPERATE`. `SEE`
(inventory) is mature; `VERIFY` (configuration, alignment, compliance) is in
progress; `RECOVER` has shipped its first controlled writes; `OPERATE` has
shipped its read-only half (the operator console).

**What the product may do is an explicit five-class taxonomy**, not a slogan —
`utils/action_taxonomy.py` is the single source of truth and `AI_START_HERE.md`
carries the table. In short: class 0 (read) is permitted and is most of the
product; class 1 (controlled recovery write — backup creation and exact
generated-artifact cleanup) is permitted only through the `RB.x` safety
contracts and is never console-submittable; class 2 (failover / operational
state change) has no member yet and is hard-gated; classes 3-4 (configuration
write, policy install / remediation) are prohibited.

Authoritative state: **`CURRENT_STATE.md`**.

## Install and run

```powershell
py.exe -m pip install -r requirements.txt
py.exe .\main.py
```

`py.exe .\main.py` is the full integration checkpoint. Development modes:
`--only cp` / `--only vsx` / `--only pan-config`, `--render-only`,
`--cp-config-collect --cp-config-stage all`. See `AI_START_HERE.md` for the full
CLI table.

No devices to hand? `py.exe .\scripts\render_sample.py` renders the UI from a
synthetic inventory so the shell and the Network Inventory / Overview / Project
Plan modules can be checked locally (it prints the `index.html` path).

Full runs write a shareable support bundle under the runtime `output/`. All run
artifacts live outside the repository (Windows:
`%LOCALAPPDATA%\SecurityExpert\runtime\`).

## Security boundary

Never commit `output/`, `data/state/`, `data/runs/`, `data/configs/`, `.env`,
keys, support HMAC keys, or real firewall configuration / inventory artifacts.
No credential, management IP, device name, serial, or raw configuration belongs
in any repository file — documentation and `project/*.json` metadata included.

## Documentation

| Doc | Purpose |
| --- | --- |
| `AI_START_HERE.md` | Cold-start entry point: the idea, how it works in 30 lines, the reading order |
| `docs/ARCHITECTURE.md` | Deep mechanism reference |
| `CURRENT_STATE.md` | Active build, next task, blockers, test baseline |
| `AI_HANDOVER.md` | Last session close and the next session's exact starting point |
| `AGENTS.md` · `docs/AI_DEVELOPMENT_PROTOCOL.md` | Engineering law and lifecycle |
| `project/roadmap.json` · `project/backlog.json` | Task source |
| `project/build_history.json` · `docs/history/INDEX.md` | Structured build timeline |
| `docs/design/` | Forward-looking architecture for major not-yet-built features (`FAILOVER_ENGINE_ARCHITECTURE.md`, `COMPLIANCE_ASSIGNMENT_AND_FRAMEWORKS.md`) |
| `docs/history/` | Archived phase agreements, validation reports, old handovers |
| `PROJECT_VISION.md` · `PRIVACY_AND_DATA_HANDLING.md` | North star and data-handling rules |
