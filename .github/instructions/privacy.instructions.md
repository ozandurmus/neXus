---
applyTo: "**"
---

# SecurityExpert Privacy Guard

Follow `/PRIVACY_AND_DATA_HANDLING.md` and `AGENTS.md` "Sensitive identity
reporting law": compare locally, report the relationship — file + location
+ classification — never the matched value (passwords, API keys, private
keys, PSKs, SNMP communities, and the other CLASS 3 categories it lists).

Do not scan runtime directories (`data/`, `output/`, `logs/`, CAS runtime
objects, support artifacts, credential stores) unless the task explicitly
requires a narrow artifact; then read only the minimum necessary scope and
report a derived result, not the source artifact.

Use synthetic values in tests/docs whenever possible.
