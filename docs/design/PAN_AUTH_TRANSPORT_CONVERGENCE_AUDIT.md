# PAN Authentication Transport Convergence — Audit

Status: **AUDIT ONLY — no code behavior changed by this document or its
movement.** Authority: `project/backlog.json` id `pan_auth_transport_convergence`
(P1), whose own note instructs: converge only in an explicit security build
together with a TLS trust review; do not change current behavior implicitly.
This document is the audit and severity assessment the backlog note asked
for. It does not authorize implementation.

## AC-1 — the two live paths, named precisely

Two independent PAN XML-API client implementations exist in this repository
and both are wired into live, non-dead-code call chains:

**Older runtime path** — `panorama/panorama_runtime_runner.py`
- `get_api_key(cfg, host, *, verify=False)` (lines 66-77)
- `op_cmd(host, key, cmd, target, *, verify=False)` (lines 80-98)
- `get_devices(host, key, *, verify=False)` (lines 104-129)
- Entry point `run_panorama_runtime(cfg)` (line 233), invoked from
  `application/workflows/checkpoint.py:564` for `--only panorama` and
  `--only all` — this is the main checkpoint collection flow, not an
  unused/legacy code path.
- Also reused (not reimplemented) by `panorama/panorama_recovery_collector.py`
  (`PanDeviceStateCollector`, RB.2 device-state recovery, invoked from
  `application/workflows/recovery.py:262`): `_key_for()` calls
  `get_api_key` from `panorama_runtime_runner.py` directly (line 26/52), and
  `collect()` (lines 65-70) performs its own `requests.get(..., params={"key":
  key})` export call in the same style.

**Newer configuration path** — `configuration/panorama_config_collector.py`
- `_keygen(cfg, host, *, verify, timeout, operation)` (lines 157-173),
  wrapped by `get_api_key()` (176-183, Panorama) and `get_firewall_api_key()`
  (186-193, direct firewall)
- `api_post(host, key, data, *, verify, timeout, operation)` (196-212), used
  by every subsequent call: `get_devices()` (215-...), `get_direct_system_info()`
  (643), `get_direct_active_config()` (669), `get_direct_operational_config()`
  (687), etc.
- Entry point `run_panorama_config_evidence(...)`, invoked from
  `application/workflows/checkpoint.py:238` for `--only pan-config`/`all`.
- Also the exclusive key/session source for `panorama/preflight_collector.py`
  (OP.0b S6 dedicated PAN preflight collector — imports `api_post`,
  `get_firewall_api_key` directly from this module; no vendor semantics or
  session shape borrowed from the older path, per that file's own header).

Both paths are live in the same binary today, selected by which `--only`
mode / workflow the operator runs, not by any feature flag or environment
override. An operator running `--only all` (the common case) exercises
**both** paths in the same collection run.

## AC-2 — the exact divergence

### 2.1 Credential and session-key transport (the material divergence)

**Older path — `panorama_runtime_runner.py::get_api_key`** (lines 66-71):

```python
r = requests.get(f"{host}/api/", params={
    "type": "keygen",
    "user": cfg.auth.principal,
    "password": cfg.auth.secret
}, verify=verify, timeout=10)
```

The PAN admin **username and password are sent as HTTPS GET query-string
parameters**. Every subsequent call in this path carries the session API
key the same way — `op_cmd()` (line 89) and `get_devices()` (line 105) both
pass `"key": key` inside `params=` on a `requests.get(...)` call, never as a
header. `panorama_recovery_collector.py::collect()` (lines 65-69) follows
the identical pattern for the device-state export call.

**Newer path — `panorama_config_collector.py::_keygen`** (lines 157-168),
with an explicit inline rationale comment already present in the source:

```python
# Credentials are POST body fields, never URL query parameters.
response = requests.post(
    f"{host}/api/",
    data={"type": "keygen", "user": cfg.auth.principal, "password": cfg.auth.secret},
    verify=verify,
    timeout=timeout,
)
```

Every subsequent call in this path goes through `api_post()` (lines
196-212), which sends the session key as an `X-PAN-KEY` request **header**,
never in the URL:

```python
response = requests.post(f"{host}/api/", data=data,
                          headers={"X-PAN-KEY": key}, verify=verify, timeout=timeout)
```

`panorama_config_collector.py:2978` and `:3004` even record this choice as
provenance metadata in the evidence store: `"api_key_transport": "X-PAN-KEY
header"`, `"authentication": "per-firewall keygen using runtime credentials;
API key held in memory only"`.

So the divergence is not incidental style drift: the newer path contains a
comment and provenance metadata that show this was a deliberate, documented
hardening decision — one that was never back-ported to the older runtime
path or to the recovery collector that reuses it.

### 2.2 Why this matters even under TLS

TLS encrypts the request in transit between this process and the PAN
management plane, but the URL (including its query string) is still:
- written verbatim into the PAN device's own management-httpd access logs
  on the device itself (outside this repository's control or redaction);
- written into the logs of any TLS-terminating intermediary in the path —
  a corporate forward/inspection proxy, load balancer, or WAF fronting the
  management interface;
- visible in a packet capture taken with `SSLKEYLOGFILE`/TLS decryption for
  unrelated troubleshooting, where a header value would not obviously read
  as a credential the way a query string carrying the `password` and `key`
  parameters directly does.

This repository's own `register_sensitive_value(key, "[API_KEY:REDACTED]")`
call (`panorama_runtime_runner.py:248`) only redacts the key from
**this application's own** log output. It has no reach into the PAN
device's server-side logs or any intermediary — the exposure this call is
trying to mitigate is not actually closed by it for the older path, because
the key (and, at keygen time, the username/password) is already on the
wire in the URL before this process ever logs anything.

### 2.3 Secondary divergence — TLS trust configuration granularity

`panorama_config_collector.py` distinguishes Panorama-proxied trust from
direct-firewall trust (`_tls_verify_setting()` vs. `_direct_tls_verify_setting()`,
lines 87-104): a dedicated `SECURITYEXPERT_PAN_DIRECT_CA_BUNDLE` /
`SECURITYEXPERT_PAN_DIRECT_TLS_VERIFY` pair, falling back to the shared
Panorama settings. `panorama_runtime_runner.py::_tls_verify_setting()`
(lines 18-30) has only the one, shared setting (`SECURITYEXPERT_PAN_CA_BUNDLE`
/ `SECURITYEXPERT_PAN_TLS_VERIFY`) — it never talks to a direct firewall, so
this is a narrower/expected divergence, not a security defect: both paths
default to `verify=False` (compat mode) and both run
`preflight_pan_tls_ca_bundle()` before any request when a CA bundle is
configured, so no path silently downgrades trust relative to the other.
This is flagged here for completeness (AC-2 asks for "the exact
divergence"), not as part of the urgent finding below.

### 2.4 Operator/device impact

Both paths authenticate against the same class of target (Panorama, and for
the recovery collector, a direct firewall management IP) with the same
credential material (`cfg.auth`/`RuntimeAuth`). Whether an operator's
run is affected by 2.1 depends entirely on which `--only` mode or workflow
they invoke:
- `--only panorama`, `--only all`, or an RB.2 device-state recovery run →
  credentials and session key go out via URL query string (older path).
- `--only pan-config`, `--only all` (the config-evidence stage), or an
  OP.0b preflight run → POST body / `X-PAN-KEY` header (newer path).

`--only all` exercises both in the same run, so the same admin credential
is exposed via the vulnerable transport at least once per run regardless of
which mode an operator picks, as long as `all` or the plain `panorama`/
recovery modes are ever used.

## AC-3 — severity assessment: **URGENT, live security exposure**

This is not a benign inconsistency. The older runtime path — which is the
default/main collection path (`--only panorama`/`all`) and is also reused
verbatim by the RB.2 recovery collector — transmits the PAN admin
**username, password, and every subsequent session API key** as HTTPS GET
URL query parameters, a transport this codebase's own newer path explicitly
identified and fixed (with an inline comment and provenance record) but
never back-ported. This matches the backlog's own trigger condition ("one
path skips a check the other performs") and AC-3's explicit criterion.

Per AC-3 and this movement's own invariant, implementation-thinking stops
here. No fix is proposed or attempted in this movement. A `RELAY_NOTE`
flagging this urgent is issued for the Product Owner (see relay entry
accompanying this document). AC-4's convergence-design step is conditioned
on the divergence *not* being urgent; since it is, this document does not
produce that design. The observation worth carrying forward for whoever the
Product Owner directs to build the eventual fix: the newer path's
`_keygen`/`api_post` pattern is already the target shape (POST body for
credentials, `X-PAN-KEY` header for the session key) — the work is
replacing the older path's call sites with it, not designing a new
transport. That replacement is exactly the kind of authentication/transport
behavior change this movement is scoped to *not* make, and per the
backlog's own instruction still requires a dedicated security build
together with a TLS trust review before it is implemented, regardless of
urgency.

## AC-4 — convergence design

Not produced in this document. Per AC-3, the divergence found here is
urgent rather than benign, so AC-4's design-proposal step does not apply as
written; it is superseded by the RELAY_NOTE. The Product Owner may still
direct that a convergence design be produced as a follow-up movement (which
would, per the backlog's own instruction, still require an explicit
security build + TLS trust review before any implementation) — that
decision belongs to the Product Owner, not to this audit.
