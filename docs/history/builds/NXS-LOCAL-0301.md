# NXS-LOCAL-0301 — LDAP Dynamic Reloading

## Summary

Refactored DirectoryAuthenticationConfiguration to fetch active DirectoryProfile dynamically from the database on every bind and revalidate call, instead of static loading at startup.
