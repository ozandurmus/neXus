# NXS-LOCAL-0331 — Check Point object-dump parser hardening for quoted strings and display-name fallback

## Summary

Made CpObjectDumpParser handle quoted strings containing parentheses and fall back to the object name for DISPLAY_NAME. Enabled stack traces in the management path for diagnosis.
