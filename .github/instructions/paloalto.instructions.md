---
applyTo: "**/*pan*.py,**/*palo*.py,**/*panorama*.py,tests/**/*pan*.py"
---

# Palo Alto Development Contract

Panorama and direct firewall evidence are separate planes.

Panorama provides:

- discovery,
- topology,
- Template / Template Stack,
- Device Group,
- central intent,
- provenance.

Direct firewall provides actual/effective device evidence.

Primary current configuration evidence is:

effective-running

Do not replace direct effective evidence with Panorama configuration.

Direct firewall evidence requires identity verification.

Configuration and Alignment are separate concepts:

Configuration:
How is the device actually configured now?

Alignment:
Does central expected intent match actual/effective state?

Production TLS must use trusted corporate CA verification.
Historical POC TLS verification exceptions are technical debt,
not production design.

Do not expose secret-bearing XML/configuration in browser/support output.