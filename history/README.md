# Historical archive

This directory preserves the retired Python SecurityExpert/neXus product and
its historical tests and documentation. The maintained product is `../ui2/`.
These files are not part of the Java build or default test discovery.
Historical device commands, approvals and deployment recipes do not authorize
execution today. Use a separately approved, isolated environment for historical
reproduction; no live credentials or runtime state belongs here.

PR1 archives the tracked scratch files identified in `../docs/repo_inventory.csv`
under `scratch/`, starting from PR0 baseline `9aeac7c4`.

PR2 archives the LEGACY_PRODUCT and TEST_OF_LEGACY entries from that inventory,
plus their templates, static assets, fixtures, render harness, legacy SQL and
Docker/Compose packaging. Baseline: `64be075650968b21f67a9fa2369fdf92e6d6d8ee`
(PR1 on `origin/main`). Each original path is preserved below this directory:
`utils/example.py` becomes `history/utils/example.py`. All 364 moved files are
byte-for-byte unchanged. Java is the live taxonomy/failover authority;
`utils/action_taxonomy.py` here is historical only.

## Offline tests: explicit opt-in

From the repository root, in an isolated offline environment with the legacy
dependencies already available:

```sh
cd history
python3 -m pytest --collect-only
# Select a reviewed synthetic test explicitly:
python3 -m pytest -q tests/test_known_safety_gaps.py
```

`history/pytest.ini` selects only archived tests; root `pytest.ini` still
selects the unchanged live-tooling manifest. `history/conftest.py` extends
the retained `utils` package only in this opt-in process so archived imports
can coexist with live `project_plan` and `repository_privacy` helpers. It uses
fresh temporary runtime/recovery directories instead of inheriting an
operator's configured runtime; collection otherwise probes that directory
while importing historical modules. The temporary directories and import/
environment overrides are cleaned up at pytest shutdown. It does not change
live tooling imports. Product requirements are archived here;
the live tooling requirements remain at `../requirements-dev.txt`. No
dependencies are installed by these commands. Do not run both suites in one
process or treat old integration/device recipes as offline test approval.

Full archive collection is currently **incomplete**: the local opt-in run
collected 2,701 cases and reported one collection error:
`tests/test_phase0_6_0a4_3_3_2_workflow_and_ha.py` reads `history/main.py` at
module import time. `main.py` is an UNSURE inventory row and remains at the
live root as directed, so that historical source inspection fails. No runtime guard
was weakened, source file duplicated or archived file edited to hide this.
Full historical reproduction needs a separately reviewed disposition of
those retained entry points and their resource paths. Missing optional
dependencies or root-relative governance resources may also prevent full
execution; no full archived-suite pass is claimed.

The archive is not self-contained deployment packaging: retained `deploy/`,
`project/`, `docs/` and live helpers deliberately remain outside it. Compose
and render recipes are preserved historical evidence, not a current build
entry point. The Java context remains `ui2 project docs`; no archive content
is added to it. Privacy scanning still covers this entire archive, with only
the same test-fixture treatment at the exact `history/tests/` root.

See [PR2 review notes](PR2_NOTES.md) for retained UNSURE paths, validation and
remaining gaps. PR4 starts documentation relocation with the
[old-to-new path map](docs/RELOCATION.md); current documentation starts at
[the Java documentation index](../docs/README.md).
