# left_vertical_product_navigation — Left vertical product navigation (architecture FROZEN; M2 accessibility closure merged via PR #85; availability_rule still pending)

## summary

ARCHITECTURE FROZEN 2026-09-05. docs/design/NAVIGATION_INFORMATION_ARCHITECTURE.md is FROZEN -- PRODUCT OWNER APPROVED, with fifteen frozen D-NAV decisions and section 19 acceptance criteria. The collapsible left rail (commit 5a5a1f7), driven by one model shared with the device-detail tab strip, merged to main via PR #85 (M2, 2026-09-06) once the four accessibility requirements (AC-A11Y-1..4) closed. The feature stays in_progress: its own availability_rule criterion (the remaining three of the four frozen capability predicates -- entity applicability, evidence state, future authorization) is genuinely pending and owned by later movements (M10+), independent of M2's scope.

## why

The flat one-root-per-view topbar strip could not express which entries are product domains, had no room left, and let a navigation entry exist without any backend behind it. The frozen contract settles the shape; the rail implements it; M2 closed the accessibility gate that made it mergeable and has merged; the four-predicate availability rule's remaining predicates are separate, later work.
