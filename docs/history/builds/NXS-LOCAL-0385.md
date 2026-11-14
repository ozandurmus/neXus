# NXS-LOCAL-0385 — Directory sign-in: Active Directory service bind, TLS handshake, display name and role refresh fixes

## Summary

Sign-in uses an Active Directory service-bind model with group-to-role sync bindings; the TLS handshake completes once and names its failures, users show by sAMAccountName, and a directory group set refreshed on its own observation clock no longer reads as stale. Directory sign-in is in live use; the 2026-11-13 role-refresh fix has no recorded live check yet.
