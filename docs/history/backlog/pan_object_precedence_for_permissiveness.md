# PAN object precedence for permissiveness

status: planned · target: docs/design/PO_DECISION_RECORD_2026_10_20_POLICY_PERMISSIVENESS_SCORING.md

2026-10-20 (open). Permissiveness scores a Palo Alto rule from the objects it names. Where the same object name exists in a device group and in shared, or in a parent device group, the effective object follows the Panorama precedence order; the product does not model that order, so such a rule reports Unknown with the cause 'precedence unknown' (fixed vocabulary). Closure: model the documented lookup order from the already collected object reads, or record a PO decision to keep it Unknown. Authority: docs/design/PO_DECISION_RECORD_2026_10_20_POLICY_PERMISSIVENESS_SCORING.md.
