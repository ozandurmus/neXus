# frontend_rendering_boundary-contract — Frontend rendering boundary (XSS/CSP) -- contract + audit sampling

## Summary

Scoping pass for project/backlog.json frontend_rendering_boundary (P1) and docs/design/SERVER_PRODUCTIZATION_AND_MODULARIZATION_ARCHITECTURE.md section 3 item 3. Audited the current rendering boundary (no code change): escapeHtml() exists and is used pervasively, utils/html_export.py's _script_json already neutralizes </script> breakout, no CSP exists anywhere today, and no external resource/network call exists in the report at all. A heuristic scan of static/app.js's 97 innerHTML sinks flagged 28 candidates lacking a nearby escapeHtml() call; manual sampling of ~10 (including the highest-value device-name renderer) found the existing discipline sound where checked, but 87 of 97 sinks remain unverified. Froze docs/history/phase/FRONTEND_RENDERING_BOUNDARY.md: a CSP meta-tag design (default-src 'none' with unsafe-inline only for script/style, since the report is a portable single file with no server to set headers), the escaping rule, AC-1..AC-7, and a 6-step implementation plan for a fresh session.

## Evidence

No code changed; no test re-run required (last evidence, 881/23/2, unaffected). Repository privacy gate PASS/0 (docs only).

## Risks forward

The 28-candidate heuristic list is a starting point, not the audit -- the next session must review all ~97 sinks, not just the flagged ones. 'unsafe-inline' in the proposed CSP means it is defense-in-depth (blocks exfiltration/framing/embedding), not a backstop for a missed escaping gap -- the exhaustive sink audit (AC-2) is the actual control. A CSP violation can silently break a feature with no visible error, so the implementation plan requires a manual browser check in addition to the automated render harness.
