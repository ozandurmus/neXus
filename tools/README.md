# Live repository tooling

The maintained product stays in `ui2/`. These tools operate its delivery,
security, e2e, privacy and governance workflows; they add no device permissions.

- `delivery/`: standalone and relay orchestration, Workbench (including its
  dashboard assets), hooks, queue/project-plan and index helpers, deployment
  and integration wrappers, integration summary and test runner.
- `security/`: scanner preparation, manifests, source/image gates and summaries.
- `e2e/`: host and preview wrappers, image/canary helpers and AIView CLI tools.
- `privacy/`: standalone repository gate and its scanner implementation.
- `tests/`: live tooling tests and their synthetic fixtures. Root `pytest.ini`
  selects this directory; `history/` and remaining root tests stay opt-in.

Run from the repository root, using the existing test dependencies:

```sh
python3 -m pytest -q -n 2 --dist worksteal
python3 tools/privacy/repository_privacy_check.py
python3 tools/delivery/project_queue.py check
python3 tools/delivery/build_history_index.py --check
node --test tools/tests/aiview_diag.test.mjs tools/tests/aiview_readiness.test.mjs
```

Python file entrypoints resolve the repository independently of caller CWD.
Host/cluster operations still require their existing authorization. The native
`.github/`, `.githooks/`, `.claude/`, `bin/` and plugin entrypoints stay in place.
Security mounts use `tools/security/`; integration archives include the two
required `tools/delivery/` files. The Java image context stays `ui2 project docs`.
`project/`, security acceptances and product sources remain in place.

## Deprecated compatibility entrypoints

These four installed caller paths forward the same arguments and exit status
by replacing the process. Use the new paths for new callers.

| Deprecated path | Current entrypoint |
| --- | --- |
| `scripts/standalone_orchestrate.py` | `tools/delivery/standalone_orchestrate.py` |
| `scripts/hosta_deploy.sh` | `tools/delivery/hosta_deploy.sh` |
| `scripts/hosta_e2e.sh` | `tools/e2e/hosta_e2e.sh` |
| `scripts/repository_privacy_check.py` | `tools/privacy/repository_privacy_check.py` |

Other files retained under `scripts/` are UNSURE: consultation and one-off
review/measurement helpers, staged review runners, the legacy fixture extractor,
`hosta_export_all.sh` and `measurement/`. They are not default live tooling;
retention does not authorize execution. `utils/__init__.py` remains in place.

The inventory and plan are baseline evidence. Archived files, closed relay
records, fixture snapshots and engineering-owned state/handover records retain
their historical paths. Git rename history provides the relocation record.
Installed systemd/launchagent/cron definitions and external callers require
separate owner validation; this lane does not inspect or update hosts.

The historical scanner reference `utils/repository_privacy.py` resolves to
`tools/privacy/repository_privacy.py`; `utils/project_plan.py` moved to
`tools/delivery/project_plan.py`. Historical project records are not rewritten.
Index/queue `check` accepts either exact old or new generator header, while
requiring every generated record to match. Subsequent authorized regeneration
writes the new header. The contract-citation guard pins the new fingerprint of
one paragraph whose only change is its relocated test path; its authority
classification and exemption scope are unchanged.

Privacy baseline exports retain the former scanner self-exemption only when
the current scanner path is absent. A file at the old scanner location in a
current tree is scanned as ordinary source. No findings are auto-accepted or
remapped across paths, and an unavailable baseline still fails closed.
