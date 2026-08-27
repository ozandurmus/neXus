---
applyTo: "**/*cp*.py,**/*checkpoint*.py,collectors/**,tests/**/*cp*.py,tests/**/*checkpoint*.py"
---

# Check Point Development Contract

Enterprise Check Point administrator accounts in the validated
environment log into Expert shell.

Do not assume interactive Gaia Clish for Enterprise Gaia.

From Expert, Gaia Clish commands must be explicitly invoked with:

clish -c '...'

Some estate devices expose direct Clish after SSH login.
Treat this as an observed shell capability.

Do NOT infer Quantum Spark/Gaia Embedded solely because a device
presents direct Clish.

Platform classification and collection capability are separate.

VSX actual evidence identity is:

physical endpoint + VSID

The Expert-shell `vsenv <VSID>` behavior is an important validated
context mechanism.

ClusterXL member differences are MEMBER_SPECIFIC unless expected-state
evidence proves otherwise.

Raw `show configuration` may contain secrets.

It must remain secret-aware and must not be casually persisted in:

- browser payload,
- support artifacts,
- logs,
- CAS/history raw payload.

Before adding/changing a Check Point command, evaluate:

1. read-only status,
2. shell,
3. platform,
4. VSX context behavior,
5. timeout,
6. retry,
7. connections/sessions per endpoint,
8. session reuse,
9. concurrency,
10. secret-bearing output.

Current priority is stability over collection speed. 