# html_render_optimization — 0.6.x polish follow-up: HTML render optimization from the html_render_performance findings

## Summary

fill_template's measured cost turned out to be almost entirely JSON serialization/escaping, not the regex substitution -- split into its own stage timer to prove it, and removed the compiled sentinel pattern's per-call re-sort/re-escape/re-compile via an lru_cache. build_compliance_posture deliberately left untouched (regex-timeout safety backstop + no real fleet-scale evidence yet). No payload/output change; full regression + render harness green.
