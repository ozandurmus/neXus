# architecture_convergence — Architecture convergence and project-state closure

## Summary

Source-audited the repository against its own documentation and repaired the drift: one action taxonomy (utils/action_taxonomy.py) replaces the no-longer-true "read-only" claim, six competing current-state authorities were reduced to one, and the two long-standing "order-dependent" test failures were root-caused to an unrestored module rebind in scripts/render_uitest.py and fixed.

## Evidence

Full suite green serially and under xdist; repository privacy gate PASS/0; project metadata_warnings == [] under the new cross-authority rules.

## Risks forward

CLASS 2 (operational state change) has no member yet and stays empty until every OP.2 prerequisite is met. Tests still write into the gitignored repo-root data/.
