# Palo Alto discovery — Python step map (know-how audit)

## Status

**DRAFT — DO NOT FREEZE. NOT implementation authority.** This document
authorizes nothing: no schema, no screen, no contract, no command approval,
no port of anything. It is produced under movement `NXS-LOCAL-0137`, per
`docs/design/PO_DECISION_RECORD_2026_09_12.md` §2 (the existing Python is
know-how only — read to understand, never ported, wrapped, transliterated or
invoked) and `docs/design/PO_DECISION_RECORD_2026_09_13B_PAN_DISCOVERY_COLLECTION_GATE.md`
(the gate that permits *reading* this source and, separately, that lifted the
Palo Alto discovery collection gate for two named methods — see §7). Reading
source code contacts nothing and needed no gate of its own.

**Nothing was executed.** No device, Panorama, or firewall was contacted. No
collector was run, in any mode, including dry-run. Every statement below is
read from the repository source at the cited path and line.

**Predecessor.** `docs/design/DISCOVERY_VS_COLLECTION_PYTHON_KNOW_HOW_AUDIT_2026_09_12.md`
(DRAFT) §5/§6.1 already named these route-level call sites. This document
goes one level deeper, for discovery only: what each call returns, what is
parsed out of it, what is kept, and what is thrown away. It does not restate
that document's Check Point content, which is out of scope here.

**Scope discipline.** This document maps the **discovery** path (obtaining
the managed-device list and the fields riding on it) in full. It **names**
where the **collection** path is reached (interfaces, routes, active/
effective-running/merged/pushed-template configuration, the direct-firewall
identity gate) so the boundary between discovery and collection is visible,
then stops — collection is not this document's subject and is not mapped in
detail here.

---

## 1. The two collectors, at a glance

Two independent Python modules read Panorama's managed-device list and reach
the vendor differently:

| | `panorama/panorama_runtime_runner.py` | `configuration/panorama_config_collector.py` |
|---|---|---|
| Produces | `output/panorama_runtime.json`, `output/panorama_telemetry.json` (interfaces/routes — collection, named only) | `pan_config_telemetry.json` and configuration evidence artifacts (collection, named only) |
| Auth transport | `requests.get` — credentials as URL query parameters | `requests.post` — credentials as POST body fields, with an explicit in-code comment that they are never in a URL |
| Device-list transport | `requests.get` — `key` as a URL query parameter | `requests.post` — `key` as an HTTP header (`X-PAN-KEY`) |
| Filters discovered devices by `connected`? | No — processes every device with a non-empty serial | Yes — narrows to `connected == "yes"` before doing anything further |
| Applies a `limit`/explicit-target selector? | No | Yes (`limit`, or an exact-match `target_serials` selector) |
| Fields kept from the shared parser | `serial`, `hostname`, `connected`, `management_ip` only | The full parsed dict (all nine fields) |

Both go through the one shared parser, `panorama.pan_identity.parse_pan_managed_device_entry`
(§4). Everything past that point diverges, and the divergence is recorded
below rather than resolved.

---

## 2. Step: obtain an API key (authentication)

Both collectors authenticate against a PAN-OS/Panorama XML API `keygen`
operation before issuing any other call. They build the request differently.

### 2a. `panorama/panorama_runtime_runner.py:66-77` — GET, credentials as URL query parameters

```
requests.get(f"{host}/api/", params={
    "type": "keygen",
    "user": cfg.auth.principal,
    "password": cfg.auth.secret
}, verify=verify, timeout=10)
```

- Call as issued: `GET {host}/api/?type=keygen&user=...&password=...`. `requests`
  places every entry of `params` on the URL query string; there is no
  separate body.
- Response carries: an XML document; on success, `<result><key>...</key></result>`
  somewhere under the root (`_parse_xml_response`, lines 50-60, checks
  `root.get("status") == "success"` first).
- Parsed: `root.findtext(".//key")` (line 74) — the key text, wherever
  `<key>` appears in the document.
- Kept: the key string, returned to the caller.
- Discarded: the rest of the response body (any other element).
- Written to disk: nothing here. The caller (`run_panorama_runtime`,
  line 246) immediately calls `register_sensitive_value(key, "[API_KEY:REDACTED]")`
  so the key is redacted wherever the logger would otherwise print it — a
  behaviour, not a parse step.
- `verify` defaults to `False` (`_tls_verify_setting`, lines 18-30) unless
  `SECURITYEXPERT_PAN_CA_BUNDLE` or `SECURITYEXPERT_PAN_TLS_VERIFY` is set.
  `timeout=10` is a literal in the call, not read from any environment
  variable in this file.

### 2b. `configuration/panorama_config_collector.py:157-193` — POST, credentials as body fields

```
# Credentials are POST body fields, never URL query parameters.
requests.post(f"{host}/api/", data={
    "type": "keygen",
    "user": cfg.auth.principal,
    "password": cfg.auth.secret,
}, verify=verify, timeout=timeout)
```

The comment at line 158 is verbatim from the source. `_keygen` (lines
157-173) is the shared implementation; `get_api_key` (176-183, used against
Panorama) and `get_firewall_api_key` (186-193, used for a direct-firewall
key) are both thin wrappers over it that differ only in the `operation=`
label passed for error messages.

- Call as issued: `POST {host}/api/` with body `type=keygen&user=...&password=...`
  (`data=` on `requests.post` is URL-encoded into the request body, not the
  query string).
- Response carries / parsed / kept / discarded: identical shape to §2a —
  `root.findtext(".//key")` (line 170), same success-status precondition
  (`_parse_xml_response`, lines 140-154).
- Written to disk: nothing. `run_panorama_config_evidence` (line 2364) calls
  `register_sensitive_value(panorama_key, "[API_KEY:REDACTED]")` immediately
  after obtaining the key, same redaction behaviour as §2a.
- `verify` here comes from `_tls_verify_setting` (lines 87-91), same
  default-`False`-unless-env-set shape as §2a but a separately defined
  function in this file. `timeout` is `_timeout_seconds()` (lines 107-113),
  environment-overridable (`SECURITYEXPERT_PAN_CONFIG_TIMEOUT`, default 90s)
  — unlike §2a's hard-coded `10`.

**Record, not judge:** one collector sends the operator's username and
password on the URL query string of a GET request; the other sends the same
two values as POST body fields and says so in a comment. Both reach the same
`type=keygen` operation on the same API.

---

## 3. Step: managed-device discovery (the enumeration call)

Both collectors then issue the vendor's managed-device-discovery operational
command and walk its response through the shared parser (§4).

### 3a. `panorama/panorama_runtime_runner.py:104-127`

```
requests.get(f"{host}/api/", params={
    "type": "op",
    "cmd": "<show><devices><all></all></devices></show>",
    "key": key
}, verify=verify, timeout=10)
```

- Call as issued: `GET {host}/api/?type=op&cmd=<show><devices><all></all></devices></show>&key=...`.
  The op-command XML is built as a Python string literal (line 107) and put
  on the query string unescaped by the caller — `requests` URL-encodes the
  whole `params` dict, so the literal characters `<`/`>` are percent-encoded
  on the wire, but the command text itself is exactly this literal.
- Response carries: an XML document whose root, on success, contains
  `<result><devices><entry name="...">...</entry>...</devices></result>`
  (inferred from the XPath the code applies, not asserted as vendor-proven —
  see UNKNOWN register §9).
- Parsed: `tree.xpath("//devices/entry")` (line 115) — every `<entry>`
  element anywhere under any `<devices>` element in the document (a
  double-slash XPath, not scoped to the top-level `<result><devices>` only).
  Each matched `<entry>` is passed to `parse_pan_managed_device_entry` (§4).
- Filter applied here: `if not parsed["serial"]: continue` (lines 117-118) —
  an entry whose parsed `serial` is empty is dropped before it ever reaches
  the caller. This is the first of the drops catalogued in §5.
- Kept, per surviving entry (lines 120-125): `serial`, `hostname`,
  `connected`, `management_ip` — four of the nine fields the shared parser
  returns.
- Discarded, per surviving entry: `model`, `sw_version`,
  `shared_policy_status`, `template_status`, `ha_state` — the parser reads
  all five, this caller drops them immediately after the call returns (never
  written anywhere, never logged).
- Written to disk: nothing at this step. (`run_panorama_runtime` later
  writes `output/panorama_telemetry.json` with a `device`/`serial`/
  `connected`/`management_ip` row per device, §1 collection summary —
  named only, that write is downstream of collection, not this step.)

### 3b. `configuration/panorama_config_collector.py:215-233`

```
api_post(host, key, {
    "type": "op",
    "cmd": "<show><devices><all></all></devices></show>",
}, verify=verify, timeout=timeout, operation="Panorama managed device discovery")
```

`api_post` (lines 196-212) issues `requests.post(f"{host}/api/", data=data,
headers={"X-PAN-KEY": key}, verify=verify, timeout=timeout)` — the op-command
identical to §3a, but as a POST body field, with the key carried in an HTTP
header rather than on the URL or in the body.

- Call as issued: `POST {host}/api/` with body `type=op&cmd=<show><devices><all></all></devices></show>`,
  header `X-PAN-KEY: <key>`.
- Response carries: same shape as §3a (this is the same vendor operation).
- Parsed: `root.xpath("//devices/entry")` (line 228) — identical XPath to
  §3a. Each entry goes through the same shared parser.
- Filter applied here: `if not parsed["serial"]: continue` (lines 230-231) —
  the same empty-serial drop as §3a, independently written in this file.
- Kept, per surviving entry: the **entire** parsed dict (line 232,
  `devices.append(parsed)`) — all nine fields the shared parser returns.
  Nothing is discarded at this step; the caller's own later selection (§5)
  is what narrows the field set actually used, not this function.
- Written to disk: nothing at this step.

**Divergence recorded:** the two `get_devices` functions issue the
*identical* op-command text against the *identical* vendor operation, through
the *identical* shared entry parser, and reach different outcomes only
because one throws away five of the nine parsed fields immediately and the
other does not.

---

## 4. Step: `parse_pan_managed_device_entry` — the one shared parser

`panorama/pan_identity.py:39-67`. Both `get_devices` implementations (§3a,
§3b) call this function once per `<entry>` element and nothing else in either
collector re-parses this response shape independently — the module docstring
(lines 1-22) states this is deliberate, closing a prior divergence where the
two callers normalized hostname whitespace differently.

Field-by-field, in the order the function returns them:

| Key | Source expression | XML source | Absence behaviour |
|---|---|---|---|
| `serial` | `entry.findtext("serial") or entry.get("name") or ""`, then `.strip()` (line 48) | `<serial>` child text, **or** the `<entry>` element's own `name` XML attribute | Never `None`; empty string `""` if both are absent/blank. The module docstring (lines 44-46) states Panorama "echoes the serial" into the entry's `name` attribute — this is a code-comment claim, not a vendor-cited proof (UNKNOWN §9.1). |
| `hostname` | `normalize_pan_hostname(entry.findtext("hostname"), serial=serial)` (line 51) | `<hostname>` child text | `normalize_pan_hostname` (lines 28-36): stripped `<hostname>` text if non-empty, else the already-computed `serial` (stripped). Never `None`. |
| `connected` | `(entry.findtext("connected") or "").strip().lower()` (line 52) | `<connected>` child text | Empty string `""` if absent. No enum validation — any text PAN-OS sends is lower-cased and kept verbatim; the code does not assert that `"yes"`/`"no"` are the only two values PAN-OS can return (UNKNOWN §9.2). |
| `management_ip` | `(entry.findtext("ip-address") or "").strip() or None` (line 53) | `<ip-address>` child text | `None` if absent or blank. |
| `model` | `(entry.findtext("model") or "").strip() or None` (line 54) | `<model>` child text | `None` if absent or blank. |
| `sw_version` | `(entry.findtext("sw-version") or "").strip() or None` (line 55) | `<sw-version>` child text | `None` if absent or blank. |
| `shared_policy_status` | `(entry.findtext("shared-policy-status") or entry.findtext("shared-policy") or "").strip() or None` (lines 56-60) | `<shared-policy-status>` child text, falling back to `<shared-policy>` | `None` if both absent/blank. The two-name fallback implies the code itself does not know which single tag name is authoritative across PAN-OS/Panorama versions (UNKNOWN §9.3). |
| `template_status` | `(entry.findtext("template-status") or entry.findtext("template") or "").strip() or None` (lines 61-65) | `<template-status>` child text, falling back to `<template>` | Same two-name-fallback shape as `shared_policy_status`. |
| `ha_state` | `(entry.findtext("ha-state") or "").strip() or None` (line 66) | `<ha-state>` child text | `None` if absent or blank. This is the only HA-relevant field this parser reads (see §7). |

No field on this list is a `vsys` indicator or a nested per-vsys entry — see
§6 for that absence as its own finding.

`findtext` (lxml/ElementTree) returns the first matching child's text or
`None` if no such child exists; every field above reads a *direct child* of
`<entry>` (no XPath wildcard, no descendant search), except `serial`'s
attribute fallback, which reads an attribute of `<entry>` itself.

---

## 5. Step: what happens to the enumeration result — filtering, selection, ordering, limit

The shared parser (§4) and both `get_devices` wrappers (§3) run, in effect,
*inside* the vendor's single response — no additional call is issued to
filter or page it. Everything in this section is in-memory post-processing
of that one response.

1. **Empty-serial drop**, in both `get_devices` implementations
   (`panorama/panorama_runtime_runner.py:117-118`;
   `configuration/panorama_config_collector.py:230-231`): an `<entry>` whose
   parsed `serial` is `""` never becomes a candidate device in either
   collector. This is on the `serial` field, not any other.

2. **`connected` filter — `configuration/panorama_config_collector.py:2516-2518` only**:

   ```
   devices = get_devices(...)
   connected = [d for d in devices if d.get("connected") == "yes"]
   disconnected = [d for d in devices if d.get("connected") != "yes"]
   ```

   Exact string equality against the literal `"yes"` (already lower-cased by
   the shared parser, §4). Every device whose `connected` is not literally
   `"yes"` — including `"no"`, `""`, or any other vendor text — lands in
   `disconnected` and is excluded from every subsequent step in this run
   (`disconnected` is only used for a count in telemetry output, never
   contacted). **`panorama/panorama_runtime_runner.py`'s `run_panorama_runtime`
   applies no such filter** — it iterates every device `get_devices` returned
   (after the empty-serial drop) regardless of `connected` value (lines
   257-347). This is a measured behavioural divergence between the two
   collectors over the identical discovery response, not merely a coding
   style difference.

3. **Ordering**: neither collector sorts the device list at any point. The
   order candidates are processed in is exactly the order `tree.xpath(...)`/
   `root.xpath(...)` yielded them, which is document order of `<entry>`
   elements in Panorama's XML response — i.e., whatever order Panorama chose
   to emit them in. Nothing in this repository asserts that order is stable
   run-to-run (UNKNOWN §9.4).

4. **`limit` truncation — `configuration/panorama_config_collector.py:2519-2530`**,
   inside `run_panorama_config_evidence` (default `limit=5`, keyword
   argument at line 2305):

   ```
   if target_serials:
       selected = _apply_pan_target_selector(devices, connected, target_serials)
       stage = f"explicit-{len(selected)}-target(s)"
   elif limit is None:
       selected = connected
       stage = "all-connected"
   else:
       selected = connected[: max(0, int(limit))]
       stage = f"first-{max(0, int(limit))}-connected"
   ```

   When no explicit target list is supplied, `selected` is the first `limit`
   entries of the already-`connected`-filtered list, in Panorama's response
   order (point 3) — a positional truncation, not a priority selection on any
   named field. `panorama/panorama_runtime_runner.py` has no `limit`
   parameter or equivalent; it always processes every surviving device.

5. **Explicit target selection — `_apply_pan_target_selector`,
   `configuration/panorama_config_collector.py:2238-2299`** (used only when
   the caller supplies `target_serials`; takes precedence over `limit`,
   which is then not applied on top of it, per the comment at lines
   2519-2522):

   - Matches strictly on `serial` — exact string equality against the
     already-discovered/`connected` set (line 2260's `by_serial` index keyed
     on `str(device.get("serial") or "")`), never hostname, substring, or
     wildcard.
   - An unknown serial (not present in `devices` at all), an ambiguous one
     (matches more than one discovered device — `by_serial[s]` with more
     than one entry, line 2283), or a currently-disconnected one (present in
     `devices` but absent from `connected`, line 2291) makes the function
     **raise** `ValueError` and contact nothing — this is fail-closed
     narrowing, never a silent drop, for this path specifically.
   - Lines 2262-2274: on an unknown serial, the function builds a
     leading-zero-insensitive index (`s.lstrip("0") or "0"`) purely to
     compose a human-readable *hint* string in the raised error message
     ("did you mean ...?"). This normalization never participates in the
     actual match (`by_serial`, `connected_serials`, and the final returned
     mapping at lines 2298-2299 all key on the untouched string) — recorded
     under measured defects (§10.3) because it is a digit-normalization of
     an opaque identifier appearing in the codebase regardless of its
     advisory-only role.

No other filter, sort, or cap exists on the discovery result in either file.

---

## 6. Virtual systems

**Absence, stated explicitly: no code in the discovery path reads a
multi-vsys indicator or a nested vsys entry from the managed-device-discovery
response.**

- `parse_pan_managed_device_entry` (§4) reads nine named fields from each
  `<entry>`; none of them is `vsys`, and no code path in `panorama/pan_identity.py`
  reads any descendant of `<entry>` other than the nine listed children/
  attribute.
- Neither `get_devices` wrapper (§3a, §3b) adds a vsys-related key to the
  dict it keeps.
- The literal string `vsys` does appear elsewhere in these six files, but
  **only in the collection path, never in discovery**:
  - `panorama/panorama_runtime_runner.py:145,172` — `parse_interfaces` reads
    a `<vsys>` child of each `<ifnet><entry>` from a **per-device**
    `show interface all` response (a collection-path op command, §7/§8
    boundary table), not from the managed-device-discovery response.
  - `configuration/panorama_config_collector.py:745,1075,1305,2199,2773,2822`
    — vsys counts/tokens derived from the **retrieved running-configuration
    content** (via `configuration.pan_config_structure.analyze_pan_config_structure`,
    a module this movement does not read — named only, per WORKER.md's
    file boundary) and from Panorama's own management-configuration content,
    both collection-plane artifacts, not the discovery response.
- The discovery XPath itself, `//devices/entry` (both callers, §3), is a
  double-slash (unanchored-depth) match. Mechanically, this means it would
  match an `<entry>` nested arbitrarily deep under *any* `<devices>` element
  anywhere in the response document, not only a flat top-level list — but
  since no code reads a `vsys`-shaped child of any matched entry, whether
  the vendor response for a multi-vsys firewall would even present as
  additional matching `<entry>` nodes under this XPath, or as a differently-
  shaped nested structure this XPath would not reach at all, is not
  something this source resolves either way (UNKNOWN §9.5).

Per the brief: this absence is a finding, not proof the vendor does not
supply multi-vsys information on this response — see UNKNOWN §9.5.

---

## 7. High availability

**HA field obtained in the discovery path itself:** `ha_state`, read by the
shared parser from the `<ha-state>` child of the managed-device-discovery
`<entry>` (`panorama/pan_identity.py:66`; see §4). This field rides on the
same response as the device list — no separate call.

- `panorama/panorama_runtime_runner.py`'s `get_devices` (§3a) parses this
  field (via the shared parser) and then **discards** it — it is not one of
  the four keys kept at lines 120-125, and `run_panorama_runtime` never
  reads it again.
- `configuration/panorama_config_collector.py`'s `get_devices` (§3b) keeps
  it (the full parsed dict is retained). Downstream, `_collect_device_row`
  (lines 1785-1876) prefers it when present:

  ```
  if row.get("ha_state"):
      row["ha_runtime"] = {"status": "success",
                            "source": "panorama_managed_device_discovery", ...}
  else:
      # issue a separate per-device op command, see below
  ```

  (lines 1834-1841). When `ha_state` from the discovery response is falsy
  (empty/`None`), the code issues a **separate, per-device** operational
  command — `<show><high-availability><state></state></high-availability></show>`
  with `target=<serial>` (`get_target_ha_runtime_state`,
  `configuration/panorama_config_collector.py:480-533`, request built at
  lines 505-516) — and uses only `local-info/state` from that response as
  `ha_state` if it comes back non-empty (lines 1864-1865). This second read
  is **outside** the two gate-lifted discovery methods (§8 boundary table);
  it is a Panorama-proxied, per-device targeted read triggered by, but not
  part of, the enumeration call.

**Whether `ha-state` on the discovery entry and `local-info/state` from
`show high-availability state` mean the same thing** is never asserted by
this code beyond both being written into the same `row["ha_state"]` key —
see UNKNOWN §9.6.

**Pairing of two devices — does it happen, and on what key?** Not inside the
discovery path itself: nothing in §2-§6 above compares one device's fields
against another's. The only place in these six files that correlates HA
identity *across* two devices is
`_finalize_pan_runtime_peer_serial_correspondence`
(`configuration/panorama_config_collector.py:1745-1782`), reached only when:

- the caller of `run_panorama_config_evidence` opts in with
  `pan_ha_peer_diagnostic=True` (default `False`, keyword parameter at line
  2311) — off by default;
- the per-device HA-state op command above (a collection-path read) was
  actually issued and diagnosed for at least one device this run
  (`_apply_pan_ha_peer_identity_diagnostic`, lines 1683-1742).

That function's cross-device key is a **tokenized, identity-gated `serial`**
(`identity_gated_serial_token`, an HMAC token of `row.get("serial")` — the
same `serial` the discovery step produced and the collector's own direct-
firewall identity gate elsewhere cross-verifies), compared against another
device's own tokenized identity-gated serial from the same run (lines
1764-1781). It is never compared on hostname, management IP, or list
position. The result is one of `MATCH`/`MISMATCH`/`MISSING`/`NOT_EVALUABLE`
(lines 1774-1782), and the function's own docstring (lines 1745-1757)
states explicitly that a `MATCH` here is evidence only and must never be
read as an established pair identity. This entire mechanism sits in the
collection path (it runs after per-device configuration collection has
already started) and is named here only because the brief requires the
pairing-key question answered regardless of which path it lives in.

---

## 8. Boundary table

"Inside" = the step is satisfied entirely by one of the two methods
`docs/design/PO_DECISION_RECORD_2026_09_13B_PAN_DISCOVERY_COLLECTION_GATE.md`
lifted the discovery collection gate for: (A) one authenticated Panorama
session (`type=keygen`), or (B) one read-only
`<show><devices><all/></devices></show>` op command — including in-memory
post-processing of method B's own response that issues no further call.
"Outside" names the plane the step actually touches.

| Step | File : lines | Inside A/B? | Plane if outside |
|---|---|---|---|
| Panorama keygen (GET, query params) | `panorama/panorama_runtime_runner.py:66-77` | Inside (A) | — |
| Panorama keygen (POST, body fields) | `configuration/panorama_config_collector.py:157-193` | Inside (A) | — |
| Managed-device discovery request (GET) | `panorama/panorama_runtime_runner.py:104-127` | Inside (B) | — |
| Managed-device discovery request (POST) | `configuration/panorama_config_collector.py:215-233` | Inside (B) | — |
| Shared entry parser | `panorama/pan_identity.py:39-67` | Inside (B) | — |
| Empty-serial drop (both callers) | `panorama/panorama_runtime_runner.py:117-118`; `configuration/panorama_config_collector.py:230-231` | Inside (B) | — |
| `connected == "yes"` filter | `configuration/panorama_config_collector.py:2517-2518` | Inside (B) | — |
| `limit` truncation / explicit target selector | `configuration/panorama_config_collector.py:2238-2299`, `2519-2530` | Inside (B) | — |
| Interface op command (`show interface all`, per serial) | `panorama/panorama_runtime_runner.py:276-294` | Outside | Panorama-proxied per-device targeted read |
| Route op command (`show routing route`, per serial) | `panorama/panorama_runtime_runner.py:306-324` | Outside | Panorama-proxied per-device targeted read |
| Panorama's own active management config | `configuration/panorama_config_collector.py:607-623` | Outside | Panorama configuration |
| Active running config via Panorama (`target=serial`) | `configuration/panorama_config_collector.py:581-603` | Outside | Panorama-proxied per-device targeted read |
| HA runtime state via Panorama (`target=serial`, HA fallback) | `configuration/panorama_config_collector.py:480-533` | Outside | Panorama-proxied per-device targeted read |
| Direct firewall keygen | `configuration/panorama_config_collector.py:186-193` | Outside | Direct firewall |
| Direct system info identity gate (`show system info`) | `configuration/panorama_config_collector.py:625-648` | Outside | Direct firewall |
| Direct active / effective-running / merged / pushed-template config | `configuration/panorama_config_collector.py:651-688` | Outside | Direct firewall |
| HA preflight battery (P1 identity gate, P2 HA state, P4 path monitoring) — single explicitly-selected entity, no enumeration | `panorama/preflight_collector.py` (whole file); command text/extraction: `panorama/pan_preflight_extraction.py:39-88` | Outside | Direct firewall, per caller-selected member — no Panorama session is opened at all in this file |
| Cross-device peer-serial correspondence | `configuration/panorama_config_collector.py:1745-1782` | Outside | In-memory only, but downstream of a collection-plane read (HA runtime state) |

---

## 9. UNKNOWN register

Per AGENTS.md's vendor-semantics law, a field's meaning is not settled by
the code reading it. Every item below is a question the source cannot
answer.

1. **`entry.get("name")` as a serial fallback.** `pan_identity.py`'s
   docstring (lines 44-46) asserts "Panorama echoes the serial there too" —
   this is a code comment, not a cited vendor document or a captured live
   response in this repository. If the `<entry name="...">` attribute is not
   always the serial (e.g., it could be a different Panorama-internal
   identifier on some PAN-OS/Panorama version), a device with an empty
   `<serial>` child would silently be identified by whatever that attribute
   actually holds. **Settled by:** a real Panorama capture of a
   managed-device-discovery response for at least one device whose `<serial>`
   child is absent, or official Palo Alto XML API schema documentation for
   this response.

2. **`<connected>` vocabulary.** The code treats only the literal `"yes"`
   (case-insensitively, since the parser lower-cases it) as "connected" and
   everything else as "not connected" (§5.2). Whether `"yes"`/`"no"` are
   PAN-OS's complete vocabulary for this field, or whether other values
   (e.g., a pending/unknown state) exist on some version, is not stated
   anywhere in this source. **Settled by:** vendor documentation of the
   `show devices all` response schema, or a live capture across more than
   one connectivity state.

3. **`shared-policy-status`/`shared-policy` and `template-status`/`template`
   two-name fallbacks** (`pan_identity.py:56-65`). The fallback pattern
   itself shows the code does not commit to one tag name; which PAN-OS/
   Panorama version(s) use which name, and whether both can legitimately
   appear together with different meanings rather than as synonyms, is
   unresolved. **Settled by:** per-version vendor documentation of the
   managed-device-discovery response schema.

4. **Response ordering stability.** `limit`'s "first-N" selection (§5.4)
   depends on Panorama's `<devices><entry>` emission order being meaningful
   or at least stable across polls; nothing in this source states or tests
   that. **Settled by:** vendor documentation of the operation's ordering
   guarantees (if any), or repeated live capture showing the order is (or is
   not) stable across successive calls against an unchanged estate.

5. **Multi-vsys presentation on this response.** §6 establishes the code
   never reads a vsys indicator from the discovery response, and that the
   discovery XPath (`//devices/entry`) is unanchored-depth. Whether a real
   multi-vsys firewall's entry in this response carries a nested vsys list
   this code would (or would not) incidentally match, or whether vsys
   information is absent from this particular operation's response
   entirely (as opposed to appearing on a different op command not in this
   audit's scope), is unresolved. **Settled by:** official Palo Alto/
   Panorama XML API schema documentation for `<show><devices><all/></devices></show>`,
   or a live capture against a known multi-vsys-configured managed firewall.

6. **`ha-state` (discovery entry) vs. `local-info/state` (`show
   high-availability state`) semantic equivalence.** §7 shows the code
   writes both into the same `row["ha_state"]` key, treating a value from
   either source as interchangeable, but no source in this audit asserts
   the two fields carry the same meaning or the same granularity (e.g.,
   whether the discovery entry's `ha-state` can lag the live per-device
   read, or represents a different state machine). **Settled by:** vendor
   documentation of both fields, or a live capture where both are read for
   the same device in the same run and compared.

7. **Pagination.** No code in the discovery path checks a `count`, `total`,
   or cursor-style field, or issues more than one `show devices all` call
   per collector run. Whether Panorama's managed-device-discovery response
   can be paginated or truncated for a large managed-device estate is
   unresolved by this source. **Settled by:** vendor documentation of
   response size limits for this operation, or a live capture against an
   estate large enough to test it.

---

## 10. Measured defects

Recorded with file and line; not fixed, per the brief.

1. **Hard-coded 10-second timeout, no environment override**, on every
   discovery-path call in `panorama/panorama_runtime_runner.py`: `get_api_key`
   (line 71), `op_cmd` (line 93), `get_devices` (line 109) — all pass a
   literal `timeout=10`. `configuration/panorama_config_collector.py`'s
   equivalent calls read `_timeout_seconds()` (lines 107-113,
   `SECURITYEXPERT_PAN_CONFIG_TIMEOUT`, default 90) instead.

2. **TLS verification defaults to disabled unless an environment variable is
   explicitly set**, in both collectors' discovery-step calls:
   `panorama/panorama_runtime_runner.py:_tls_verify_setting` (lines 18-30,
   returns `False` unless `SECURITYEXPERT_PAN_CA_BUNDLE` or
   `SECURITYEXPERT_PAN_TLS_VERIFY` is set) and
   `configuration/panorama_config_collector.py:_tls_verify_setting` (lines
   87-91, same default via `_env_bool(..., default=False)`). This is a
   measured default in the current code, not a hypothetical.

3. **Advisory leading-zero normalization of an opaque identifier.**
   `configuration/panorama_config_collector.py:2269-2274` builds
   `s.lstrip("0") or "0"` over requested/discovered `serial` values purely to
   compose an error-message hint when `_apply_pan_target_selector` rejects
   an unknown serial. It does not affect matching (§5.5 confirms the actual
   comparison keys are untouched strings) but is a digit-normalization of an
   identifier value appearing in the codebase, which AGENTS.md's identity
   law flags regardless of whether it is load-bearing.

4. **Raw per-device XML persisted to disk**, immediately downstream of the
   discovery step in the same file/function:
   `panorama/panorama_runtime_runner.py:286-287`
   (`(raw_root / f"{serial}_interfaces.xml").open("wb")`) and `:316-317`
   (`{serial}_routes.xml`) write the full, unmodified interface/route
   operational-command response to `output/panorama_raw/`. This is the
   collection portion of `run_panorama_runtime` (interfaces/routes, not the
   managed-device enumeration itself — see §8), flagged here because it
   sits one step past discovery in the same run and is directly relevant to
   AGENTS.md's raw-evidence law.

5. **Kept-field asymmetry between the two `get_devices` implementations**
   (§3a vs. §3b): both parse the identical response through the identical
   shared function, but one collector discards `model`, `sw_version`,
   `shared_policy_status`, `template_status`, and `ha_state` immediately
   (`panorama/panorama_runtime_runner.py:120-125`) while the other keeps all
   nine fields (`configuration/panorama_config_collector.py:232`). Not a
   bug in either file taken alone; a measured fact a single discovery
   contract must reconcile.

6. **`connected`-filter asymmetry between the two collectors** (§5.2):
   `configuration/panorama_config_collector.py` excludes every
   non-`"yes"`-connected device from all further discovery-triggered
   processing; `panorama/panorama_runtime_runner.py` has no equivalent
   filter and processes every device with a non-empty serial regardless of
   `connected`.

---

## 11. What this document does not do

- It does not propose a Java shape, a schema, or an architecture for a
  Palo Alto discovery contract.
- It does not resolve any UNKNOWN in §9 — each requires evidence this
  read-only audit could not gather (no device was contacted).
- It does not map the collection path (interfaces, routes,
  effective-running/merged/active/pushed-template configuration, the
  direct-firewall identity gate) beyond naming where the discovery path
  hands off to it (§7, §8).
- It does not change contract status, and it does not authorize
  implementation of anything it describes.
