---
applyTo: "**"
---

# SecurityExpert Privacy Guard

Follow `/PRIVACY_AND_DATA_HANDLING.md`.

Never expose or reproduce:

- passwords,
- API keys,
- private keys,
- PSKs,
- SNMP communities,
- authentication secrets,
- unredacted secret-bearing configuration.

Do not scan runtime directories unless the task explicitly requires
a narrow artifact.

If sensitive operational evidence must be inspected locally:

1. read only the minimum necessary scope,
2. derive the required safe result,
3. do not dump the source artifact into chat/output.

When identifying repository privacy findings, report:

file + location + classification

without echoing the sensitive value.

Use synthetic values in tests/docs whenever possible.