# pcp_server_enrollment_exposure — May console enrollment (manual or candidate-based) be exposed in non-loopback / server mode, i.e. beyond the explicitly controlled local profile decided in pcp_console_registry_write_gate?

## Options

- Blocked until DEPLOY.1A supplies real OIDC/RBAC authorization (current position)
- Permitted earlier under some other compensating control (not proposed by any frozen contract)

## Recommendation

BLOCKED until DEPLOY.1A. CON.0 C-D5 and section 4.1 both condition the local enrollment permission on the loopback binding itself; server mode re-asks the question rather than inheriting the answer. DEPLOY.1A/M14 does not retroactively validate any local shortcut.
