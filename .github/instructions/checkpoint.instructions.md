---
applyTo: "**/*cp*.py,**/*checkpoint*.py,collectors/**,tests/**/*cp*.py,tests/**/*checkpoint*.py"
---

# Check Point Development Contract

Path-scoped delta only. `AGENTS.md` "Check Point" is canonical (Expert/Clish
shell model, VSX identity, `ClusterXL` `MEMBER_SPECIFIC` rule, raw-config
handling, command-gate requirement) — do not restate it here or let this
file drift from it.

Genuinely collector-specific, not stated elsewhere:

- Some estate devices expose direct Clish after SSH login — an observed
  shell capability, not proof of platform (do not infer Quantum
  Spark/Gaia Embedded from it alone; `AGENTS.md` "Check Point" already
  states the platform-classification rule this supports).
- Before adding/changing a Check Point command, evaluate connections/
  sessions per endpoint, session reuse and concurrency in addition to the
  network-device command gate's own list (`docs/AI_DEVELOPMENT_PROTOCOL.md`).
- Current priority is stability over collection speed.
