# NXS-LOCAL-0392 — ASA saved-state relation, Script Execution slice 1, Java4Shell mitigation, security batch

## Summary

The ASA saved-state relation is computed in the backup and stored as a verdict with counts (1fa4e9f1, V250), Script Execution slice 1 adds encrypted write-only script versions with secret-scan warnings (f141bb2b, V251), and a differently formed digest read is UNKNOWN (8799a2ec). The Base64 intrinsic is off in every Java pod (ab88e264), the security batch remediates CodeQL and dependency findings (9b3f35e0), and policy installation status reads the live schema (68abde9e).
