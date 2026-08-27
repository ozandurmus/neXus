# SecurityExpert Living Project Plan Contract

The files in this directory are product-planning metadata, not device evidence.
They are embedded into the local HTML Project Plan module on every render.

Update this directory in every packaged build that changes scope, delivery state,
known debt, or future sequencing:

1. `roadmap.json` — current build, current major track, Now / Next / Upcoming and track mapping.
2. `feature_registry.json` — feature purpose, value, weighted acceptance criteria and delivery state.
3. `backlog.json` — open/deferred technical, security, collection, reliability and UX work.
4. `build_history.json` — append/update the current build and preserve prior build outcomes.

Progress percentages are calculated by `utils/project_plan.py` from weighted
acceptance criteria. They are not calendar estimates, completion promises or an
attempt to hide unresolved validation. A criterion remains pending until the
required implementation or real-environment validation is complete.

Future major-version numbers are planning labels until explicitly frozen by a
phase checkpoint. If implementation order changes, preserve the original target
in history and mark the rebase instead of silently rewriting history.

No credentials, management IPs, device names, raw configuration, serial numbers,
or other estate-specific evidence belongs in these metadata files.
