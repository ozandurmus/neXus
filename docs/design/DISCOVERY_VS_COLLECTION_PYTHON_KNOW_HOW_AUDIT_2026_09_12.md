# Discovery vs collection in the existing Python — behaviour map (know-how audit)

## Status

**DRAFT — DO NOT FREEZE. NOT implementation authority.** This is a read-only
behaviour map of the existing Python product, produced under movement
`NXS-LOCAL-0125` so the discovery feature can be built in Java from observed
vendor behaviour rather than from a script. It decides nothing: it names no
schema, no screen, no contract, no command approval and no port of anything.
`docs/design/PO_DECISION_RECORD_2026_09_12.md` §2 states the existing Python is
know-how only — read to understand, never ported, wrapped, transliterated or
invoked — and §1's collection gate is still closed. This document was written
under both and changes neither.

**Nothing was executed.** No device, management station, firewall, Panorama or
any other network endpoint was contacted. No collector was run, in any mode,
including dry-run. Every statement below is read from repository source at the
cited path and line.

**Command-gate warning.** A command appearing in the source below is *not*
command approval (`AGENTS.md`, "Network action taxonomy"). §8 reports the gate
status of each command and route separately from its presence in code, and
records `UNKNOWN: requires gate entry` wherever the repository has no gate row.

---

## 1. Quick answers

The questions a reader should not have to re-read the sources for.

| Question | Answer | Source |
|---|---|---|
| How is a Check Point device found? | A bash collector uploaded to the management station enumerates every CMA (`$MDSVERUTIL AllCMAs`) and asks each one `cpmiquerybin attr "" network_objects <query>` for six attributes. | `checkpoint/scripts/cp_inventory.sh:78-83` |
| How is a Check Point gateway then read? | Not by the product. The management station runs `cprid_util -server <ip> rexec` against the gateway; the product only talks to the management station. | `checkpoint/scripts/cp_inventory.sh:112-113` |
| …on what port, with what transport? | Product → management station: SSH, paramiko's default port 22 (no port is passed). Management station → gateway: CPRID, port not expressed in this repository. | `checkpoint/cp_runner.py:733`; `checkpoint/scripts/cp_inventory.sh:112` |
| How is a Check Point gateway reached *directly*? | SSH to the management IP the management plane reported, port from `SECURITYEXPERT_CP_CONFIG_SSH_PORT` (default 22), interactive PTY shell. | `configuration/checkpoint_config_probe.py:573`, `:593-603`; `configuration/checkpoint_config_collector.py:1400-1408` |
| How is a Palo Alto device found? | HTTPS XML API to Panorama: `type=keygen`, then `type=op` with `<show><devices><all></all></devices></show>`. | `configuration/panorama_config_collector.py:157-168`, `:215-226` |
| How is a firewall read directly? | HTTPS XML API straight to the firewall's management IP, its own `keygen`, an identity gate on `<show><system><info/></system></show>`, then config reads. | `configuration/panorama_config_collector.py:1542-1576` |
| Which artefact is primary PAN configuration evidence? | Direct `effective-running`, not Panorama. | `configuration/panorama_config_collector.py:1674-1679`, `:2942` |
| How many sessions per device? | CP inventory: one to the management station per run, zero to each device. CP config: one transport per host, multiple channels. PAN: no session — one HTTPS request per read. | §9 |
| What does the code know about a device *before* heavy collection? | CP: six management attributes, no serial/model/version. PAN: serial, hostname, connected, IP, model, version, HA state. | §7 |
| Is there a standalone discovery step an operator can inspect before collection? | No. In every vendor path, enumeration and collection happen inside one call. | §6.2 |

---

## 2. Entry points and the three `--only` modes

`application/cli.py:42-49` defines `--only` with recommended modes `cp`, `vsx`,
`pan-config`, `all`, plus legacy diagnostics `panorama`, `merge`, `verify`,
`html`, `support`. Default is `all`. Every mode falls through the dispatch chain
to `checkpoint_wf.integration_checkpoint` (`application/cli.py:914`), which holds
the staged pipeline.

Credentials are resolved once, before any collector, and only for the planes the
mode needs: `require_cp` for `cp`/`vsx`/`all`, `require_panorama` for
`panorama`/`pan-config`/`all` (`application/workflows/checkpoint.py:262-268`). A
`RunContext` (the run-scoped artefact lifecycle) is created **only** for
`--only all` (`:270`); every partial mode writes into the flat output root.

### 2.1 `--only cp` (AC-4)

1. Fails fast unless `cp.json`, `vsx.json` and `panorama_runtime.json` already
   exist from a previous full checkpoint (`:300-302`, via `_require_partial_inputs`).
2. Deletes `cp.json`, `cp_telemetry.json`, `cp_direct_ssh_probe.json` (`:348`).
3. Runs `run_cp(cfg, exclude_vsx=True)` — `exclude_vsx` is set precisely because
   the mode is `cp` (`:353`), which selects the narrower management query
   (§3.1.2) and announces "PHYSICAL NON-VSX ONLY" (`:344`).
4. Re-merges the three existing inventory files into `unified.json` and
   re-renders HTML, reusing whatever PAN and CP configuration telemetry is
   already on disk (`:299-331`).

It does **not** run CP configuration collection: that stage is gated on
`args.only == "all"` (`:427`).

### 2.2 `--only vsx` (AC-4)

1. Requires `vsx.json` to exist (`:303`).
2. Deletes `vsx_raw.json`, `vsx_telemetry.json`, `vsx.json` (`:399`).
3. Runs `run_vsx(cfg)` — the VSX raw stage (`:404`).
4. Runs `run_vsx_parse(output_root)` as a separate stage (`:417-418`).
5. Same partial merge + render as `--only cp` (`:425`).

### 2.3 `--only pan-config` (AC-4)

1. Requires `unified.json` (`:535`) — it reuses existing inventory and refreshes
   only configuration.
2. Runs `run_panorama_config_evidence` with a default limit of **5 connected
   firewalls** when no `--pan-config-limit`/`--pan-config-stage` is given
   (`:284`), workers from `--pan-config-workers` (default 3, `application/cli.py:168-172`).
3. Renders HTML from the reused `unified.json` plus the fresh PAN configuration
   result (`:536-548`).

It does **not** run the Panorama runtime inventory stage (`:553`, gated on
`panorama`/`all`).

### 2.4 Where the modes overlap (AC-4)

| | `cp` | `vsx` | `pan-config` | `all` |
|---|---|---|---|---|
| CP management SSH + `cp_inventory.sh` | yes (non-VSX query) | no | no | yes (baseline query) |
| CP direct-SSH fallback probe | yes (inside `run_cp`) | no | no | yes |
| VSX discovery + per-VS reads | no | yes | no | yes |
| CP configuration collection | no | no | no | yes |
| Panorama runtime inventory | no | no | no | yes |
| PAN configuration evidence | no | no | yes (limit 5) | yes (full fleet) |
| Merge → verify → HTML | partial render | partial render | render only | full staged |
| `RunContext` / snapshot / support bundle | no | no | no | yes |

The only genuine overlap between the three named modes is the terminal
merge/render tail, and `--only all`, which runs every stage in sequence
(`:338-757`) with an optional inter-stage cooldown (`:41-59`, default 0 seconds).

---

## 3. Check Point (AC-1)

### 3.1 Step 1 — reach the management entry point

| Field | Value | Source |
|---|---|---|
| Module / function | `checkpoint/cp_runner.py::run_cp` | `:708` |
| Transport | SSH (paramiko `SSHClient`) | `:729-733` |
| Port | not passed → paramiko default 22 | `:733` |
| Endpoint | `cfg.mds_ip`, a runtime input, never in source | `config.py:15-19` |
| Authentication | username + password from the process `Config` | `checkpoint/cp_runner.py:733` |
| Host-key policy | strict only when `SECURITYEXPERT_CP_MDS_STRICT_HOST_KEY` is set; otherwise `AutoAddPolicy` with a warning | `:728-732` |
| Returns | an authenticated SSH client; nothing device-specific yet | — |

#### 3.1.1 What is uploaded

`_deploy_collection_script` opens an SFTP channel, uploads
`checkpoint/scripts/cp_inventory.sh` to `/home/admin/cp_inventory.sh`, and
verifies the upload byte-for-byte by SHA-256, raising if it differs
(`:184-213`). It then removes the previous run's markers
(`/home/admin/cp_raw/.collection_meta`, `.collection_status.tsv`) so stale raw
output can never be accepted as belonging to this run (`:205-206`, `:777-779`).

#### 3.1.2 What the management station is asked

`_remote_collection_command` builds exactly one command
(`checkpoint/cp_runner.py:244-251`):

```
SECURITYEXPERT_CP_EXCLUDED_DEVICE_NAMES=<quoted> [SECURITYEXPERT_CP_EXCLUDE_VSX=1] bash -l /home/admin/cp_inventory.sh
```

Inside that script (`checkpoint/scripts/cp_inventory.sh`):

- `:3` sources the Check Point environment profile.
- `:78` `for CMA_NAME in $($MDSVERUTIL AllCMAs)` — the domain enumeration.
- `:79` `mdsenv "$CMA_NAME"` — per-domain context switch.
- `:81-83` the object query:

  ```
  cpmiquerybin attr "" network_objects " $CP_QUERY" \
      -a __name__,ipaddr,connection_state,type,vsx_cluster_member,vs_cluster_member
  ```

Two query forms exist and the mode picks one (`:57-70`):

| Scope | Query | Set by |
|---|---|---|
| `physical-non-vsx` | `(type='cluster_member' & (! vsx_cluster_member='true') & (! vs_cluster_member='true')) \| (type='gateway' & cp_products_installed='true' & (! vs_netobj='true') & (! vsx_netobj='true'))` | `SECURITYEXPERT_CP_EXCLUDE_VSX=1`, i.e. `--only cp` |
| `baseline-all-managed-cp` | `(type='cluster_member' & vsx_cluster_member='true' & vs_cluster_member='true') \| (type='cluster_member' & (! vs_cluster_member='true')) \| (vsx_netobj='true') \| (type='gateway' & cp_products_installed='true' & (! vs_netobj='true'))` | default, i.e. `--only all` |

Exclusions are applied by awk on **exact name match only**, from a runtime
policy file; the repository ships no default identities (`:76`, `:84-91`,
`utils/inventory_exclusions.py`).

**Return shape:** lines of `CMA <name>` interleaved with one whitespace-separated
row per object carrying the six queried attributes, written to `/tmp/gw_list.txt`
(`:84-91`). A `TOTAL_GW=` count is echoed to stdout (`:93-94`).

### 3.2 Step 2 — read each gateway (still from the management station)

The product never opens a session to a Check Point gateway in this path. The
uploaded script does, through CPRID (`:112-113`, retry at `:131-132`):

```
timeout <FIRST_TIMEOUT> "$CPDIR/bin/cprid_util" -server "$IP" -verbose rexec -rcmd bash -c "<REMOTE_COMMAND>"
```

| Read | Command | Condition | Source |
|---|---|---|---|
| Interfaces | `ip -details -4 addr show` | every attempted device | `:220` |
| Routes | `ip -4 route show table all` | every attempted device | `:238` |
| Cluster virtual interfaces | `cphaprob -a -m if` | only when the management object type is `cluster_member` | `:268-269` |

Execution parameters, all environment-overridable inside the script
(`:21-33`): parallelism 6 (bounded fan-out, `:333-335`), first timeout 10 s,
retry timeout 30 s, **maximum 1 retry, hard-capped** (`:33`). Interface and route
reads stay sequential *within* one gateway; different gateways run concurrently
(`:171-175`).

A device the management plane reports as not `communicating` is **never
contacted** — the worker records `management_down` and returns without issuing a
remote command (`:202-208`). This is the one fail-closed branch in the inventory
path.

**Return shape:** per device, `/home/admin/cp_raw/<SAFE_GW>_interfaces.txt`,
`_routes.txt`, `_cluster_if.txt`, plus a 21-column TSV row in
`.collection_status.tsv` (`:282-287`) carrying, in order: device, interface rc,
route rc, attempt counts, first/final rcs, error classes, management state,
collection outcome, management IP, CMA, object type, `vsx_cluster_member`,
`vs_cluster_member`, and the cluster-probe rc/attempts/error. A key/value
`.collection_meta` carries the run counters (`:372-392`).

### 3.3 Step 3 — how the product knows the remote run finished (AC-8)

`_run_remote_collection` (`checkpoint/cp_runner.py:286-383`) does not rely on the
exit status alone. It drains both streams tightly, waits for
`exit_status_ready()` with both streams empty (`:330-341`), and then requires
**both**:

- exit status 0, else `RuntimeError` (`:359-364`);
- a literal `DONE` marker line seen on stdout, else `RuntimeError` naming
  processed/total counts (`:365-372`).

Additionally `_validate_new_collection_marker` requires a `.collection_meta` with
coherent start/completion epochs and a non-negative discovered count (`:386-401`)
— and because the old marker was deleted before execution, a valid marker proves
this invocation produced the raw set (`:777-779`). stderr is never persisted or
logged raw: a bounded 8 KiB sample is classified into static shell-error tokens
and then explicitly discarded (`:254-283`, `:357`).

### 3.4 Step 4 — download and parse

A second SFTP channel on the same client downloads every `*.txt` in
`/home/admin/cp_raw` plus the two markers (`:755-822`). Parsing is local:
`parse_interfaces` (`:407-463`), `parse_routes` (`:469-550`),
`parse_cluster_virtual_interfaces` (`:556-608`). Output: `output/cp.json`
(parsed rows) and `output/cp_telemetry.json` (status/meta/summary, `:854-926`).

Cluster grouping is derived here, not from a management object:
`enrich_cluster_topology` fingerprints `sha256(CMA + sorted (interface, VIP)
rows)` into a 16-hex `group_id` (`:639-686`). The `-CLS` display label is
explicitly marked `inferred_member_pattern` and the code states cluster identity
never depends on it (`:611-636`).

### 3.5 Step 5 — the direct-SSH fallback probe

Called from inside `run_cp` (`:868-869`), so it runs during the inventory stage,
not as a separate mode.

| Field | Value | Source |
|---|---|---|
| Module | `checkpoint/direct_ssh_probe.py::probe_direct_ssh_fallback` | `:380` |
| Candidates | only rows whose `collection_outcome` is `collection_failed` or `partial` **and** whose management state is `communicating`/`unknown` | `:390-394` |
| Transport / port | SSH, `FBUDDY_CP_DIRECT_SSH_PORT`, default 22 | `:444` |
| Timeouts | connect 8 s, command 20 s; one bounded connect retry on reachability errors only, never on auth or host-key failure | `:445-446`, `:255-257`, `:293-312` |
| Parallelism | 4 (max 12) | `:447` |
| Host key | strict only if `FBUDDY_CP_DIRECT_SSH_STRICT_HOST_KEY`; default compatibility mode with a warning | `:448`, `:451-452` |
| Commands | three families, each a first-supported ladder | `:22-44` |
| Returns | `output/cp_direct_ssh_probe.json`; observe-only | `:494-497`, `:382-388` |

The ladders (`:22-44`), tried in order until one succeeds (`:178-197`):

- version: `show version all` → `show version` → `clish -c "show version all"` → `clish -c "show version"`
- interfaces: `show interfaces table` → `show interfaces` → `clish -c "show interfaces table"` → `clish -c "show interfaces"`
- routes: `show route all` → `show route` → `clish -c "show route all"` → `clish -c "show route"`

The module states explicitly that it does **not** collect configuration, does not
promote its output into `cp.json`, and does not make a failed device LIVE
(`:23-25`, `:382-388`). `platform_hint` is derived from output text and is
marked as a hint, never an authoritative classification (`:200-213`).

### 3.6 Step 6 — direct configuration collection

`configuration/checkpoint_config_collector.py::run_checkpoint_config_collection`
(`:1976`). This stage runs only in `--only all` (§2.4) or through the explicit
`--cp-config-collect` mode (`application/cli.py:899-900`).

**Target resolution is from artefacts, not from a fresh query.** `_resolve_targets`
(`:1222-1311`) reads `output/cp_telemetry.json` (`remote_command_status`),
`output/cp.json` and `output/vsx.json`, and raises if the telemetry is absent
(`:1226-1230`). A row with no management IP is skipped (`:1239-1241`); an object
type that is neither `gateway` nor `cluster_member` (and not VSX) is skipped as
`unsupported_object_type` rather than guessed (`:1252-1255`). Entity type is
decided as: VSX status → `vsx_host`; `cluster_member` → `clusterxl_member`;
`gateway` → `standalone_gateway` (`:1246-1252`). VS contexts are attached only
from observed non-zero numeric VSIDs in `vsx.json` — never invented
(`:1275-1305`).

Selection: `--cp-config-targets` (exact `entity_id`, fail-closed, refuses before
any SSH opens, `:1932-1968`) takes precedence over `stage`; otherwise
`stage=sample` picks one standalone + one cluster pair + one VSX host with one
context (`:1909-1929`), and `stage=all` takes everything. Workers clamp to 1–12
(`:1997`), one thread per physical host (`:2027-2040`).

**Per host** (`_collect_host`, `:1350`):

| Step | Command / action | Shell context | Source |
|---|---|---|---|
| Connect | SSH to `target.management_ip`, port `SECURITYEXPERT_CP_CONFIG_SSH_PORT` (default 22), connect timeout 8 s, one retry on reachability errors only | — | `configuration/checkpoint_config_probe.py:572-631`; collector `:1400`, `:1995` |
| Open shell | one `invoke_shell(term="vt100", width=4096, height=10000)`, drain banner, learn prompt | PTY | `:549-589`, `:1408` |
| Prove surface | `show hostname`; if unusable, `clish -c 'show hostname'` | direct Clish, else Expert + explicit clish | `:753-767` |
| Version | `show version all` → `show version` | as proven above | `:1433-1435` |
| Identity/asset | `cpstat os -f hw_info` — the **only** non-`show` command the read gate allows, an explicit literal exception | as proven above | `:770-783`, `:1446-1448` |
| HA role | `cphaprob stat`, only for `clusterxl_member` / `vsx_host` | interactive session when Expert-explicit-clish, else a fresh exec channel | `:1489-1495` |
| Configuration | `show configuration`, timeout raised to ≥ 60 s, requires canonical `set` lines | as proven above | `:1543-1550` |

If the PTY shell cannot be opened or the interactive handshake cannot be framed,
the collector falls back to an exec-channel adapter (`:1407-1429`).

**Identity gate** (`_collector_identity_gate`, `:1113-1156`): the base gate is
hostname + version; when that fails, the exact management-selected endpoint +
authentication + observed hostname + a successful read-only `show configuration`
is accepted at **MEDIUM** confidence, with the status distinguishing a hostname
match from an observed hostname difference (`:1142-1154`). Platform
classification stays independent of the gate (`:1128`). If the gate rejects, no
VS context is collected at all (`:1632-1634`).

**Returns:** one row per physical host plus one per VS, and a sanitized snapshot
written to the content-addressed evidence store as
`gaia-show-configuration.redacted.txt` with `raw_configuration_persisted: false`
(`:1573-1613`). Raw stdout is cleared from the result dict immediately after
sanitization (`:1625-1627`).

---

## 4. VSX (AC-3)

### 4.1 The inventory path (`--only vsx`, and inside `--only all`)

`checkpoint/vsx_runner.py::run_vsx` (`:259`).

**Discovery** (`discover_vsx`, `:144-170`): the runner writes an inline bash
script to `/var/tmp/vsx_disc.sh` over SFTP, `chmod +x`, then `bash <path>`
(`:146-153`). The script (`:18-26`) is a narrower form of the CP query:

```
for CMA in $($MDSVERUTIL AllCMAs); do
    mdsenv "$CMA"
    cpmiquerybin attr "" network_objects "type='cluster_member' & vsx_cluster_member='true'" -a __name__,ipaddr
done
```

Only two attributes are requested: name and IP.

**Then the code filters by name shape.** `:166-168` keeps only names matching
`-[12]$`, commented "real cluster members only". This is a presentation-ordinal
filter used as a collection-scope gate, which `AGENTS.md` ("Presentation identity
!= security identity") separates from identity. Recorded here as observed
behaviour; see §11 U-1.

**Per member** (`worker`, `:190-256`):

1. A **new** SSH connection to the management station (`cfg.mds_ip`), not to the
   member (`:196-197`), with a bounded connect timeout (default 10 s, `:40`).
2. `invoke_shell()` and a 1 s settle (`:198-199`).
3. A **nested interactive SSH from the management station to the member**:
   `ssh <principal>@<ip>` typed into the shell, answering a `yes/no` host-key
   prompt with `yes` and an `assword` prompt with the secret (`:202-208`).
4. `cphaprob stat`; if the output contains `Standby`, the member is skipped
   entirely (`:211-214`) — an availability check used as a collection-scope
   decision.
5. `vsx stat -v` to enumerate virtual systems; rows are parsed by splitting on
   `|` and keeping rows whose first field is numeric (`:173-187`).
6. Per VS, two reads on the same shell (`:227-236`):
   - `vsenv <id>; fw ctl set int vsid <id>; ifconfig` (max wait 90 s)
   - `vsenv <id>; fw ctl set int vsid <id>; ip route` (max wait 120 s)
7. Three members are processed concurrently (`:275`).

Command completion is inferred from the Expert prompt regex
`\[Expert@[^\]]+\]#\s*$` (`:65`), with an idle-grace fallback and a hard
`max_wait` ceiling (`:75-141`). There is no exit status on this path.

**Returns:** `output/vsx_raw.json` (raw interface/route text per VS, plus device,
device IP, VS name, VS id) and `output/vsx_telemetry.json` (per-command duration,
bytes, lines, prompt-seen, timeout) (`:280-285`). `checkpoint/vsx_parser.py:148-178`
then parses `ifconfig` and `ip route` text into `output/vsx.json`.

### 4.2 How the VSX path differs from the gateway path (AC-3)

| Dimension | CP gateway path | VSX path |
|---|---|---|
| Who contacts the device | the management station, via `cprid_util rexec` | the management station's **interactive shell**, via a nested `ssh` typed at a prompt |
| Product's own sessions | 1 to the management station, reused | 1 to the management station **per member**, concurrent ×3 |
| Discovery query attributes | 6 (`__name__,ipaddr,connection_state,type,vsx_cluster_member,vs_cluster_member`) | 2 (`__name__,ipaddr`) |
| Candidate filter | management `connection_state` + exact-name exclusion policy | regex on the name's trailing ordinal (`-1`/`-2`) |
| Availability handling | non-`communicating` devices are skipped and recorded `management_down` | `Standby` members are skipped after a live `cphaprob stat` |
| Enumeration unit | one physical device | one physical member × N virtual systems (`vsx stat -v`) |
| Context mechanism | none | `vsenv <VSID>` (and `fw ctl set int vsid <VSID>`) per read |
| Completion proof | exit status **and** a `DONE` marker | Expert-prompt match / idle fallback |
| Read commands | `ip -details -4 addr show`, `ip -4 route show table all` | `ifconfig`, `ip route` (different commands, different parsers) |
| Attribution of a VS to a member | n/a | carried in the row as `device` + `device_ip` + `vs_id`; the config collector later re-keys it as `<device>__vsid_<vsid>` (`configuration/checkpoint_config_collector.py:1344-1347`) |

### 4.3 The VSX configuration path

Distinct again from §4.1. Inside `_collect_host`, after the physical host's own
identity gate passes, each attached context is read by a **fresh exec channel**
(`configuration/checkpoint_config_collector.py:1691-1695`):

```
vsenv <VSID> >/dev/null 2>&1; clish -c 'show configuration'
```

If that fails or returns no `set` lines, the fallback opens one `clish` process
and sends `set virtual-system <VSID>` / `show configuration` / `exit` into it
(`:1699`; implementation `configuration/checkpoint_config_probe.py:399-424`),
whose docstring states the context selector must not be split across unrelated
`clish -c` processes. Per-VS HA role is then read with
`vsenv <VSID> >/dev/null 2>&1; cphaprob stat` (`:1776-1778`), explicitly from
that VS's own buffer and never inherited silently — an inherited role is labelled
`inherited_from_physical_member` until a per-VS probe confirms it
(`:1658-1665`). VSIDs are numeric-validated before interpolation (`:1683-1688`,
`:1303`).

---

## 5. Palo Alto (AC-2)

Two independent collectors reach Panorama, with different HTTP shapes.

### 5.1 Panorama as management entry point — runtime inventory

`panorama/panorama_runtime_runner.py::run_panorama_runtime` (`:231`).

| Step | Method / route | Notes | Source |
|---|---|---|---|
| Host normalization | `https://` prefixed if absent | `cfg.panorama_ip` is a runtime input | `:43-47`, `:244` |
| TLS | `SECURITYEXPERT_PAN_CA_BUNDLE` > `SECURITYEXPERT_PAN_TLS_VERIFY` > **False** | default is unverified, with a warning | `:18-30`, `:237-242` |
| Key generation | `GET /api/?type=keygen&user=…&password=…` | credentials as **URL query parameters** | `:66-77` |
| Device discovery | `GET /api/?type=op&cmd=<show><devices><all></all></devices></show>` | timeout 10 s | `:104-109` |
| Per device, interfaces | `GET /api/?type=op&cmd=<show><interface>all</interface></show>&target=<serial>` | | `:278-284` |
| Per device, routes | `GET /api/?type=op&cmd=<show><routing><route></route></routing></show>&target=<serial>` | | `:308-314` |

Every response is checked for `status="success"` on the XML root, raising with
the vendor's `//msg` text otherwise (`:50-60`). Devices are processed
**sequentially** with a 0.2 s pause between them (`:257`, `:347`).

**Raw XML is written to disk** as `output/panorama_raw/<serial>_interfaces.xml`
and `<serial>_routes.xml` (`:286-288`, `:316-318`). See §11 U-5.

**Returns:** `output/panorama_runtime.json` (source, device, serial, management
IP, parsed interfaces, parsed routes) and `output/panorama_telemetry.json`
(discovered / connected yes / connected no / successful / failed, plus per-device
timings) (`:354-366`).

### 5.2 Panorama as management entry point — configuration evidence

`configuration/panorama_config_collector.py::run_panorama_config_evidence`
(`:2302`). This collector uses **POST** for everything and carries the API key in
an `X-PAN-KEY` header rather than the URL (`:196-212`); `keygen` sends
credentials as POST body fields with an explicit comment that they are never URL
query parameters (`:157-168`).

Order of operations:

1. CA-bundle preflight for both the Panorama and the direct planes, before any
   network call (`:2339-2351`).
2. Panorama `keygen`; the key is registered with the redactor (`:2363-2364`).
3. **Panorama's own configuration**, read once, with no `target`:
   `type=config, action=show, xpath=/config` — the source of Template / Template
   Stack / Device Group assignment provenance (`:607-623`, `:2374-2377`).
4. Device discovery: `type=op, cmd=<show><devices><all></all></devices></show>`
   (`:215-226`, `:2516`).
5. Selection (`:2517-2530`): `connected == "yes"` first; then explicit
   `target_serials` (fail-closed, §5.4) > `limit is None` → all connected >
   `limit` → first *n* connected.
6. Per selected device, in a thread pool of 1–6 workers (`:123-130`, `:2560-2561`).

Per device (`_collect_device_row`, `:1785`):

| Read | Route | Condition | Source |
|---|---|---|---|
| HA runtime state | `type=op, cmd=<show><high-availability><state></state></high-availability></show>, target=<serial>` | **only** when Panorama's discovery response did not already carry `ha-state`; timeout capped at 10 s | `:480-516`, `:1834-1854` |
| Panorama-side active config | `type=config, action=show, xpath=/config, target=<serial>` | always | `:581-603`, `:1884-1890` |
| HA peer address | *no call* — parsed from the bytes already fetched above, at `.//deviceconfig/high-availability/group/peer-ip` | additive parse only | `:536-566`, `:1908-1915` |

### 5.3 The direct firewall

`_collect_direct_compare` (`:1508`), reached when `direct_compare` is true
(default, `:2306`).

| Step | Route | Source |
|---|---|---|
| Address | `management_ip` **from Panorama's discovery response**; a missing IP fails the device before any call | `:1519`, `:1529-1537`, `:2984` |
| Key | per-firewall `keygen` POST against the firewall itself; key held in memory and registered with the redactor | `:1542-1543` |
| **Identity gate** | `type=op, cmd=<show><system><info></info></system></show>`; the returned serial must equal the Panorama-discovered serial, else `identity_mismatch` and the device is abandoned | `:625-648`, `:1562-1576` |
| Active config | `type=config, action=show, xpath=/config` | `:651-666` |
| Effective running | `type=op, cmd=<show><config><effective-running></effective-running></config></show>` | `:669-688` |
| Merged | `type=op, cmd=<show><config><merged></merged></config></show>` | `:669-688` |
| Pushed template | `type=op, cmd=<show><config><pushed-template></pushed-template></config></show>` | **disabled by default**; `--pan-probe-pushed-template` only | `:1628-1648`; `application/cli.py:173-180` |

The mode string is validated against a closed set before interpolation
(`:677-678`). Direct timeout defaults to 20 s against Panorama's 90 s
(`:107-120`). `effective-running` alone decides primary evidence success;
alignment completeness additionally requires merged and active (`:1674-1679`).

### 5.4 Fail-closed target selection

`_apply_pan_target_selector` (`:2238-2299`) matches on **serial only** — the same
value the direct identity gate cross-checks — never a hostname, label, substring,
regex or wildcard. Unknown, ambiguous, or not-currently-connected serials raise
before a single direct API call. A leading-zero hint is printed to help the
operator but **never** changes matching (`:2264-2281`) — consistent with
`AGENTS.md`'s identity law.

### 5.5 Managed-device discovery parse

Both PAN collectors go through one parser, `parse_pan_managed_device_entry`
(`panorama/pan_identity.py:39-67`), precisely so the two parses cannot drift
(`:1-22`). It returns: `serial` (from `<serial>` or the entry's `name`
attribute), `hostname` (normalized; falls back to the serial), `connected`,
`management_ip`, `model`, `sw_version`, `shared_policy_status`,
`template_status`, `ha_state`.

---

## 6. The discovery / collection boundary (AC-5)

### 6.1 Classification of every mapped step

Classes: **D** discovery (identifies a candidate and the minimum needed to decide
whether to import it) · **I** inventory (runtime/operational state) · **C**
configuration (configured state) · **R** running-config (effective/running
document).

| # | Step | Class | Source |
|---|---|---|---|
| 1 | `$MDSVERUTIL AllCMAs` + `mdsenv` | **D** | `cp_inventory.sh:78-79` |
| 2 | `cpmiquerybin attr "" network_objects <query> -a …` | **D** | `cp_inventory.sh:81-83` |
| 3 | exact-name exclusion filter | **D** | `cp_inventory.sh:84-91` |
| 4 | management-state skip (`!= communicating`) | **D** | `cp_inventory.sh:202-208` |
| 5 | `ip -details -4 addr show` (via CPRID) | **I** | `cp_inventory.sh:220` |
| 6 | `ip -4 route show table all` (via CPRID) | **I** | `cp_inventory.sh:238` |
| 7 | `cphaprob -a -m if` (via CPRID) | **I**, consumed as **D** (cluster grouping) | `cp_inventory.sh:269`; `cp_runner.py:639-686` |
| 8 | direct-SSH `show version*` ladder | **D** (capability/platform hint) | `direct_ssh_probe.py:26-31` |
| 9 | direct-SSH `show interfaces*` / `show route*` ladders | **I**, used as **D** (CLI-capability proof only; never promoted to inventory) | `direct_ssh_probe.py:32-43`, `:382-388` |
| 10 | VSX `cpmiquerybin … vsx_cluster_member='true'` | **D** | `vsx_runner.py:18-26` |
| 11 | VSX nested `ssh <principal>@<ip>` | transport, no class | `vsx_runner.py:202` |
| 12 | VSX `cphaprob stat` (standby skip) | **I**, consumed as **D** (scope decision) | `vsx_runner.py:211-214` |
| 13 | `vsx stat -v` | **D** (enumerates the VS candidates) | `vsx_runner.py:175` |
| 14 | `vsenv <id>; fw ctl set int vsid <id>; ifconfig` | **I** | `vsx_runner.py:227-232` |
| 15 | `vsenv <id>; fw ctl set int vsid <id>; ip route` | **I** | `vsx_runner.py:233-236` |
| 16 | CP config `show hostname` | **D** (identity gate input) | `checkpoint_config_collector.py:755-759` |
| 17 | CP config `show version all` / `show version` | **D** (identity gate input) | `:1433-1435` |
| 18 | CP config `cpstat os -f hw_info` | **D** (serial/model) | `:1446-1448` |
| 19 | CP config `cphaprob stat` | **I** | `:1489-1495` |
| 20 | CP config `show configuration` | **C** | `:1543-1550` |
| 21 | VSX ctx `vsenv <id> …; clish -c 'show configuration'` | **C** | `:1691-1695` |
| 22 | VSX ctx `clish` + `set virtual-system <id>` + `show configuration` | **C** | `checkpoint_config_probe.py:399-424` |
| 23 | VSX ctx `vsenv <id> …; cphaprob stat` | **I** | `:1776-1778` |
| 24 | PAN `type=keygen` (Panorama and direct) | authentication, no class | `panorama_config_collector.py:157-168` |
| 25 | PAN `<show><devices><all/></devices></show>` | **D** | `:215-226` |
| 26 | PAN Panorama `xpath=/config` (no target) | **C** (management intent) | `:607-623` |
| 27 | PAN `<show><high-availability><state/></high-availability></show>` target | **I** | `:480-516` |
| 28 | PAN `xpath=/config` target=serial | **C** (Panorama's view) | `:581-603` |
| 29 | PAN direct `<show><system><info/></system></show>` | **D** (identity gate) | `:625-648` |
| 30 | PAN direct `xpath=/config` | **C** | `:651-666` |
| 31 | PAN direct `<show><config><effective-running/></config></show>` | **R** (primary) | `:669-688`, `:2942` |
| 32 | PAN direct `<show><config><merged/></config></show>` | **R** | `:669-688` |
| 33 | PAN direct `<show><config><pushed-template/></config></show>` | **C** (intent), opt-in | `:669-688`, `:1628-1648` |
| 34 | PAN runtime `<show><interface>all</interface></show>` target | **I** | `panorama_runtime_runner.py:281` |
| 35 | PAN runtime `<show><routing><route/></routing></show>` target | **I** | `panorama_runtime_runner.py:311` |

### 6.2 Where the existing code interleaves them, and why (AC-5)

There is no step in this codebase at which an operator is shown candidates and
asked what to import. Seven concrete interleavings:

1. **CP: discovery and inventory are one remote script run.** `run_cp` uploads
   and executes `cp_inventory.sh` as a single command
   (`checkpoint/cp_runner.py:744-748`); the script enumerates (`:78-91`) and then
   immediately collects from every non-excluded, communicating device
   (`:299-336`). Rows 1–7 of §6.1 are indivisible today. *Why:* the script is the
   unit of remote execution; the product sees only its finished output.
2. **CP: cluster identity is derived from an inventory read.** The `group_id`
   that decides which members form one operational unit is a fingerprint of the
   VIPs returned by `cphaprob -a -m if` (`checkpoint/cp_runner.py:639-686`) — a
   discovery-grade fact that only exists after a device read.
3. **CP: a second transport runs inside the inventory stage.** The direct-SSH
   probe is invoked from within `run_cp` (`:868-869`), so a run that "only"
   collects inventory may also open direct SSH sessions to failed devices.
4. **VSX: discovery and per-VS collection are one call.** `run_vsx` discovers
   (`:270`) and immediately fans out workers (`:275-278`); a VS is enumerated and
   read in the same worker without any intervening decision (`:216-249`).
5. **VSX: an availability read decides collection scope.** `cphaprob stat` is run
   to skip standby members (`:211-214`) — an inventory read taken before, and
   used to gate, collection.
6. **CP configuration: identity, inventory and configuration share one session
   pass.** `_collect_host` runs hostname → version → asset → `cphaprob stat` →
   `show configuration` back to back on one host
   (`configuration/checkpoint_config_collector.py:1418-1550`). The identity gate
   that authorizes collection is itself computed from reads taken on the same
   visit, and the gate can be satisfied *by* the configuration read succeeding
   (`:1142-1154`).
7. **PAN: discovery, selection and collection are one function.**
   `run_panorama_config_evidence` reads Panorama's own configuration, discovers
   devices, selects them, and collects — in one call (`:2374-2377`, `:2516-2561`).
   The runtime runner does the same in a single loop
   (`panorama_runtime_runner.py:248-347`).

### 6.3 The one place the code does separate them

CP configuration collection **cannot** run without inventory artefacts: it
resolves its targets from `cp_telemetry.json` / `cp.json` / `vsx.json` on disk
and raises if they are missing
(`configuration/checkpoint_config_collector.py:1226-1230`; same for the probe,
`configuration/checkpoint_config_probe.py:146-150`). So a persisted
discovery result already sits between the two phases for Check Point — but it is
an artefact dependency, not an operator decision point, and it has no
import/exclude semantics beyond the runtime exclusion policy applied at
discovery time (`cp_inventory.sh:84-91`).

`utils/discovery_lifecycle.py` defines a five-state machine
(`DISCOVERED → VALIDATED → STABLE`, plus `EXCLUDED` / `REMOVED`) with confidence
0–100 and an `evidence_plane` of `management` / `direct` / `unknown`
(`:35-84`, `:91-119`). It is the closest thing in the repository to the
discovery/import boundary, and it is **I/O-free and persistence-free by
construction** (`:218-223`). It is a vocabulary, not a wired-up gate.

---

## 7. What a candidate row can actually carry before heavy collection (AC-6)

Derived strictly from what the code has learned at the moment enumeration
finishes and before any configuration read.

### 7.1 Check Point

Available from the management query alone (`cp_inventory.sh:81-83`):

| Field | Origin |
|---|---|
| object name | `__name__` |
| management address | `ipaddr` |
| management connection state | `connection_state` |
| object type (`gateway` / `cluster_member`) | `type` |
| is a VSX cluster member | `vsx_cluster_member` |
| is a VS cluster member | `vs_cluster_member` |
| owning management domain | the `CMA <name>` line the loop emits (`:80`) |

Added by the same pass, from device reads: per-command outcome and error class,
`collection_outcome`, and the cluster `group_id` + VIP set
(`cp_inventory.sh:282-287`; `cp_runner.py:639-686`).

**Not known at Check Point discovery time:** serial, model, software version,
platform family, HA role. All four first appear during configuration collection,
from `cpstat os -f hw_info` and `show version all`
(`configuration/checkpoint_config_collector.py:1472-1481`) and `cphaprob stat`
(`:1489-1501`). A Check Point candidate row built only from discovery therefore
cannot show a serial or a version.

### 7.2 VSX

Only name and IP (`vsx_runner.py:23-24`), then a name-shape filter (`:166-168`),
then — after contacting the member — the VS list from `vsx stat -v` (`:175-187`).
A VS candidate does not exist until the physical member has been logged into.

### 7.3 Palo Alto

Materially richer, in one call, before anything is collected
(`panorama/pan_identity.py:48-67`): serial, hostname, connected, management IP,
model, software version, shared-policy status, template status, HA state.

### 7.4 The asymmetry, stated plainly

The minimum a candidate row needs to let an operator decide — identity, address,
reachability, what kind of thing it is, and which management domain owns it — is
**available from PAN discovery alone** but **not from CP discovery alone**: CP
supplies no serial, model or version without a device read. Any design that
requires a uniform candidate row across both vendors must either accept
`UNKNOWN` in those CP columns at discovery time, or accept that filling them is a
device contact that needs its own gate. This document states the constraint; it
does not choose between them.

---

## 8. Command and API-route gate table (AC-7)

**How to read the status column.** `AGENTS.md` is explicit that a command
appearing in source is not command approval. The repository contains **no
machine-readable `gate_registry`** — a search of `utils/` returns no
`gate_registry`, `gate_reference` or `gate_id` symbol. The only signed-off
network-device gate entries this audit found are in
`docs/design/BACKUP_RECOVERY_CONTRACTS.md` §7 (backup/attestation commands, none
of which appear in the discovery/collection paths mapped above) and the OP.0b
preflight command-gate package, whose approval is recorded by the battery modules
themselves (`checkpoint/cp_preflight_battery.py:1-6`;
`panorama/pan_preflight_battery.py:1-7`, status "APPROVED (2026-09-03) — SCOPED
PER THE PO OVERRIDES"). The gate package document itself lives under
`docs/history/` and was **not opened** in this audit, per the movement brief's
reading restriction; the status is reported as the source modules record it.

`docs/design/UI2_0_B1_05_CP_INVENTORY_EXTRACTION_CONTRACT.md:240-242` and
`docs/design/UI2_0_C6_CAPABILITY_EXTRACTION_CONTRACT.md:403,474-475` state
directly that **no `gate_registry` row exists** for `show version all` or
`cphaprob stat` under `(check_point, cp_gaia_gateway, *, ssh_exec)`, and that a
VSX `vsenv` context-switch row plus per-context read rows are still needed. That
is the repository's own answer for the CP reads, and this table does not
contradict it.

| # | Command / route | Vendor | Where issued | Class per `utils/action_taxonomy.py` | Gate status in this repository | A new implementation needs |
|---|---|---|---|---|---|---|
| 1 | `$MDSVERUTIL AllCMAs` | CP | management station, Expert | read | no gate row found | **gate entry** |
| 2 | `mdsenv <CMA>` | CP | management station, Expert | read (context switch) | no gate row found | **gate entry** |
| 3 | `cpmiquerybin attr "" network_objects <query> -a …` | CP | management station, Expert | read | no gate row found | **gate entry** (both query forms) |
| 4 | `cprid_util -server <ip> -verbose rexec -rcmd bash -c <cmd>` | CP | management station → gateway | read transport | no gate row found | **gate entry** — this is the execution channel, not just the payload |
| 5 | `ip -details -4 addr show` | CP | gateway, bash via CPRID | read | no gate row found | **gate entry** |
| 6 | `ip -4 route show table all` | CP | gateway, bash via CPRID | read | no gate row found | **gate entry** |
| 7 | `cphaprob -a -m if` | CP | gateway, bash via CPRID | read | no gate row found | **gate entry** |
| 8 | `show version all` / `show version` | CP | gateway, Clish or `clish -c` | read | **explicitly recorded as `UNKNOWN: requires gate entry`** (`UI2_0_B1_05…:240`) | **gate entry** |
| 9 | `show interfaces table` / `show interfaces` | CP | gateway, direct SSH ladder | read | no gate row found | **gate entry** (each ladder rung is its own literal) |
| 10 | `show route all` / `show route` | CP | gateway, direct SSH ladder | read | no gate row found | **gate entry** |
| 11 | `show hostname` | CP | gateway, Clish or `clish -c` | read | no gate row found | **gate entry** |
| 12 | `cpstat os -f hw_info` | CP | gateway, Clish (the one non-`show` exception, `checkpoint_config_collector.py:770-783`) | read | no gate row found; the source records only a safety finding, not an approval | **gate entry** |
| 13 | `show configuration` | CP | gateway, Clish or `clish -c` | read (secret-bearing output) | no gate row found | **gate entry** — including its redaction contract |
| 14 | `cphaprob stat` | CP | gateway, Expert | read | **`UNKNOWN: requires gate entry`** (`UI2_0_B1_05…:242`); separately approved *within* the OP.0b battery as `A3` (`cp_preflight_battery.py:84`) | **gate entry for this purpose** — the OP.0b approval is scoped to the preflight battery |
| 15 | `vsenv <VSID>` | CP/VSX | gateway, Expert | read (context switch) | named as a needed row `vsx_vsenv_context_switch`, not authored (`UI2_0_C6…:475`) | **gate entry** |
| 16 | `fw ctl set int vsid <VSID>` | CP/VSX | gateway, Expert (`vsx_runner.py:227-236`) | **not established — see §11 U-2** | **contradiction:** listed in `FORBIDDEN_COMMAND_MARKERS` as "mutating kernel-parameter set" (`cp_preflight_battery.py:114`) while the legacy VSX inventory path issues it | **gate entry and a vendor-semantic decision before any reuse** |
| 17 | `vsx stat -v` | CP/VSX | gateway, Expert | read | no gate row found | **gate entry** |
| 18 | `ifconfig` | CP/VSX | gateway, Expert inside a VS context | read | no gate row found | **gate entry** |
| 19 | `ip route` | CP/VSX | gateway, Expert inside a VS context | read | no gate row found | **gate entry** |
| 20 | `clish` + `set virtual-system <VSID>` + `show configuration` | CP/VSX | gateway, one Clish process | read (context selector + read) | no gate row found | **gate entry** for the composite; the selector is not a standalone read |
| 21 | nested `ssh <principal>@<ip>` typed into a management-station shell | CP/VSX | management station → gateway | transport | no gate row found | **gate entry and a diagnostic-path review** (`AGENTS.md`, "Diagnostic-path law") |
| 22 | `GET /api/?type=keygen&user=&password=` | PAN | Panorama | authentication | no gate row found | **gate entry**; see §11 U-4 on credentials in the URL |
| 23 | `POST /api/ type=keygen` (body) | PAN | Panorama and firewall | authentication | no gate row found | **gate entry** |
| 24 | `type=op cmd=<show><devices><all/></devices></show>` | PAN | Panorama | read | no gate row found | **gate entry** |
| 25 | `type=config action=show xpath=/config` (no target) | PAN | Panorama | read | no gate row found | **gate entry** |
| 26 | `type=config action=show xpath=/config target=<serial>` | PAN | Panorama | read | no gate row found | **gate entry** |
| 27 | `type=op cmd=<show><high-availability><state/></high-availability></show> target=<serial>` | PAN | Panorama proxy | read | approved within the OP.0b battery as `P2` (`pan_preflight_battery.py:53-56`) | **gate entry for this purpose** if used outside preflight |
| 28 | `type=op cmd=<show><system><info/></system></show>` | PAN | firewall, direct | read | approved within the OP.0b battery as `P1` (`pan_preflight_battery.py:53-55`) | **gate entry for this purpose** if used outside preflight |
| 29 | `type=config action=show xpath=/config` (direct) | PAN | firewall, direct | read | no gate row found | **gate entry** |
| 30 | `type=op cmd=<show><config><effective-running/></config></show>` | PAN | firewall, direct | read | no gate row found | **gate entry** — this is the primary evidence route |
| 31 | `type=op cmd=<show><config><merged/></config></show>` | PAN | firewall, direct | read | no gate row found | **gate entry** |
| 32 | `type=op cmd=<show><config><pushed-template/></config></show>` | PAN | firewall, direct | read | no gate row found | **gate entry** |
| 33 | `type=op cmd=<show><interface>all</interface></show> target=<serial>` | PAN | Panorama proxy | read | no gate row found | **gate entry** |
| 34 | `type=op cmd=<show><routing><route/></routing></show> target=<serial>` | PAN | Panorama proxy | read | no gate row found | **gate entry** |

**Summary: of the 34 commands and routes this map names, none carries a
repository gate row authorising it for discovery or collection.** Two CP reads
and two PAN reads carry an OP.0b approval scoped to the HA preflight battery, and
two CP reads are explicitly recorded as `UNKNOWN: requires gate entry`. A new
implementation needs a `docs/AI_DEVELOPMENT_PROTOCOL.md` ten-field entry for each
row it intends to issue, and row 16 needs a vendor-semantic decision before it
can be classified at all.

---

## 9. Session shape per vendor (AC-8)

### 9.1 Check Point — inventory

- **One** SSH client to the management station per run
  (`checkpoint/cp_runner.py:729-733`), reused for three channels: SFTP upload
  (`:738-742`), one exec channel for the collector (`:294`), SFTP download
  (`:755-822`). Closed once at the end (`:943`).
- **Zero** sessions to any gateway from the product. Per gateway, the management
  station opens 2–3 CPRID executions (interfaces, routes, and cluster interfaces
  for cluster members), each with at most one retry
  (`cp_inventory.sh:112-134`, `:220`, `:238`, `:269`).
- Completion: exit status **and** a `DONE` marker **and** a fresh
  `.collection_meta` (`cp_runner.py:346-401`).

### 9.2 Check Point — direct probe

One SSH client per candidate device, up to two connect attempts
(`direct_ssh_probe.py:255-312`), then **one new exec channel per command
attempt** — up to four attempts per family across three families
(`:178-197`, `:86-104`). Not a reused command session. Completion per command:
channel exit status, with a timeout ceiling (`:106-123`).

### 9.3 Check Point — configuration collection

One SSH client per physical host (`checkpoint_config_collector.py:1400`),
carrying:

- **one** persistent `invoke_shell` PTY session used for hostname, version,
  asset and `show configuration` (`:1408`, `:1433-1454`, `:1543-1546`);
- `cphaprob stat` on that same session **only** when the shell mode is
  `interactive_expert_explicit_clish`; otherwise a fresh exec channel
  (`:1491-1495`);
- **one fresh exec channel per VS read** — one for the configuration
  (`:1691-1695`) and one for the per-VS `cphaprob stat` (`:1776-1778`), plus
  another if the Clish-context fallback fires (`:1699`).

Completion on the interactive session is **prompt-match or idle fallback**, not
exit status: `_run_gaia_interactive_read` calls `session.run(...)` without
`frame=True` (`:803`), so the deterministic marker path
(`echo <nonce>$?<nonce>`, `:651-652`, `:680-714`) is available but unused on this
path. Completion on exec channels is the channel's exit status
(`configuration/checkpoint_config_probe.py:317-319`).

### 9.4 Check Point — the HA preflight precedent

`checkpoint/preflight_collector.py` is the repository's existing "one reused
session per device" implementation: one `InteractiveSshSession` per member
(`:338-381`), **every** read framed with `frame=True` so completion and exit
status are read explicitly (`:381`), and reads paced at
`INTER_COMMAND_DELAY_SECONDS = 0.3` (`:134`). Per-VS reads reuse the same open
session inside a verified `vsenv <VSID>` context rather than opening a second one
(`checkpoint/cp_preflight_battery.py:22-28`). It is the only CP path that already
matches the shape the Product Owner's §3.4 requires; the inventory and
configuration paths above do not.

### 9.5 VSX

One SSH client to the **management station per member**, three members
concurrently (`vsx_runner.py:196-197`, `:275`), each holding one `invoke_shell`
inside which a nested `ssh` to the member is typed (`:198-208`). So per VSX
member: one product session, one management-station shell, one nested device
login, and all VS reads multiplexed over that one nested login (`:216-249`).
Completion is Expert-prompt match, idle grace, or `max_wait` (`:75-141`) — there
is no exit status anywhere on this path.

### 9.6 Palo Alto

**No session at all.** Every read is an independent HTTPS request
(`panorama_config_collector.py:196-212`); `requests` is called per operation with
no shared `Session` object. Per run: one Panorama `keygen`. Per selected device:
one direct `keygen`, one `show system info`, one Panorama-target config read,
3–4 direct config reads, and one HA-state read only when Panorama did not
already report `ha-state` — 6–8 requests for a typical device. The runtime
inventory collector adds one `keygen` plus two requests per device
(`panorama_runtime_runner.py:245-314`). Completion is the HTTP response plus a
`status="success"` root attribute, with the vendor's own `//msg` text raised
otherwise (`:50-60`; `panorama_config_collector.py:140-154`).

---

## 10. Credentials and secret handling — mechanism only

**Resolution.** One principal and one secret for the whole process, resolved in
the order `<VAR>_FILE` → `<VAR>` → interactive prompt, and only when stdin is a
TTY; a non-interactive run with an unresolved value fails naming the missing
variables *before* any collector import or network call
(`application/services.py:185-235`). The variable names are
`SECURITYEXPERT_PRINCIPAL`, `SECURITYEXPERT_SECRET`,
`SECURITYEXPERT_CP_MDS_ENDPOINT`, `SECURITYEXPERT_PANORAMA_ENDPOINT`
(`:165-168`). Management endpoints are runtime inputs and are never embedded in
source (`config.py:15-19`).

**Per-path credential use.**

| Path | Credential | Mechanism |
|---|---|---|
| CP inventory | the runtime principal/secret, to the management station only | `checkpoint/cp_runner.py:733` |
| CP direct probe | `FBUDDY_CP_DIRECT_SSH_USERNAME` / `…_PASSWORD` if set, else the runtime credential | `direct_ssh_probe.py:422-423` |
| CP configuration | `SECURITYEXPERT_CP_CONFIG_SSH_USERNAME` / `…_PASSWORD` if set, else the runtime credential; absence raises before any connection | `checkpoint_config_collector.py:1987-1990` |
| VSX | the runtime credential, typed into the management-station shell for the nested login | `vsx_runner.py:196`, `:206-208` |
| PAN Panorama | runtime credential → one API key per run, held in memory | `panorama_config_collector.py:157-173`, `:2363` |
| PAN direct | runtime credential → one API key **per firewall**, held in memory | `:1542` |

**Redaction.** Every secret-like value is registered with a central redactor at
the moment it is resolved: principal and secret (`application/services.py:237-239`),
CP override credentials (`direct_ssh_probe.py:441-442`;
`checkpoint_config_collector.py:1991-1992`), the Panorama API key and each direct
firewall key (`panorama_runtime_runner.py:246`;
`panorama_config_collector.py:2364`, `:1543`), and PAN management IPs
(`panorama_config_collector.py:1808-1809`).

**Lifetime.** `Config.clear_credentials()` replaces the auth object, and every
collection workflow calls it in a `finally` (`config.py:27-30`;
`application/workflows/checkpoint.py:808-810`, `:100-101`, `:212-213`). The
source states plainly that Python strings cannot be reliably zeroized and that
this only drops references (`config.py:28-29`).

**Avoiding persistence of sensitive output.**

- CP `show configuration` is sanitized in memory: secret-bearing lines are
  withheld by a deliberately conservative pattern, only a redacted text and a
  canonical SHA-256 change fingerprint are stored, and
  `raw_configuration_persisted` is recorded `false`
  (`checkpoint_config_collector.py:57-59`, `:1573-1613`). Raw stdout is cleared
  from the result dict immediately afterwards (`:1625-1627`), and the same is
  done for hostname/version/asset/HA buffers once scalars are parsed
  (`:1527`, `:1534-1538`).
- The CP probe never persists raw stdout, only counts and fingerprints
  (`checkpoint_config_probe.py:52-58`, `:709-713`).
- CP collector stderr is classified into static tokens and the sample is deleted
  (`cp_runner.py:254-283`, `:357`).
- The direct-SSH probe strips command output before support-bundle
  anonymization (`direct_ssh_probe.py:508-528`).
- **Exception:** the Panorama runtime collector writes raw device XML to
  `output/panorama_raw/` (`panorama_runtime_runner.py:286-288`, `:316-318`).
  See §11 U-5.

---

## 11. UNKNOWNs (AC-9)

Each names what would settle it.

- **U-1 — the VSX `-[12]$` name filter.** `vsx_runner.py:166-168` restricts VSX
  discovery to object names ending `-1` or `-2`, commented "real cluster members
  only". Whether a VSX member can exist outside that naming convention on this
  estate — and therefore whether the filter silently drops candidates — is
  **UNKNOWN**. *Settled by:* the management object semantics for VSX cluster
  membership from official vendor documentation, or a real-environment
  enumeration comparing the filtered and unfiltered result counts.
- **U-2 — the action class of `fw ctl set int vsid <VSID>`.** Issued on the VSX
  inventory path (`vsx_runner.py:227-236`) and simultaneously listed as a
  forbidden "mutating kernel-parameter set" marker by the approved preflight
  battery (`cp_preflight_battery.py:114`). Whether it is a read-scoped context
  primitive or a kernel-parameter write is **UNKNOWN from the repository alone**,
  and the two sources disagree. *Settled by:* official vendor documentation for
  `fw ctl set int` in a VSX context, through the network-device command gate.
  Reported here as a contradiction rather than reconciled (`AGENTS.md`,
  "Authority hierarchy").
- **U-3 — the CPRID port and transport parameters.** `cp_inventory.sh:112` calls
  `cprid_util` with `-server <ip>` and no port; the port, protocol and retry
  behaviour of CPRID itself are **UNKNOWN** from this repository. *Settled by:*
  official vendor documentation for `cprid_util`.
- **U-4 — whether PAN `keygen` credentials in a URL is intended or legacy.**
  `panorama_runtime_runner.py:66-71` sends user and password as URL query
  parameters; `panorama_config_collector.py:157-168` sends them as POST body
  fields with an explicit comment that they are never URL parameters. Whether the
  runtime runner's form is a deliberate compatibility choice or unconverged legacy
  is **UNKNOWN**. *Settled by:* the owning contract, or a Product Owner decision.
  `docs/design/PAN_AUTH_TRANSPORT_CONVERGENCE_AUDIT.md` exists and was not opened
  in this audit.
- **U-5 — whether persisting raw Panorama XML is contract-authorized.**
  `panorama_runtime_runner.py:286-288` and `:316-318` write raw vendor responses
  to `output/panorama_raw/`. `AGENTS.md`'s raw-evidence law permits raw retention
  only under an explicit evidence/forensics contract with defined privacy
  handling. Whether such a contract covers these files is **UNKNOWN**. *Settled
  by:* locating the authorizing contract, or a Product Owner ruling.
- **U-6 — the semantics of `connection_state`.** The CP query requests it
  (`cp_inventory.sh:83`) and the worker compares it, lowercased, to the literal
  `communicating` to decide whether to contact a device (`:202`). The full value
  domain of that attribute is **UNKNOWN**; any value other than `communicating`
  or empty/`unknown` is treated as down. *Settled by:* official vendor
  documentation for the `network_objects` attribute.
- **U-7 — what `cpmiquerybin` returns for an object with no `ipaddr`.** The
  scheduler skips rows with an empty second field (`cp_inventory.sh:316`), and
  the config collector skips targets with no management IP
  (`checkpoint_config_collector.py:1239-1241`). Whether such objects are
  legitimate candidates that simply need a different address source is
  **UNKNOWN**. *Settled by:* a real-environment enumeration count of skipped
  rows, plus vendor documentation.
- **U-8 — whether `show configuration` is context-scoped inside a VS.** The
  Clish-context fallback's docstring states the context selector must not be
  split across `clish -c` processes because context persistence "is exactly what
  this probe is trying to validate"
  (`checkpoint_config_probe.py:400-405`), and `_pick_targets` says the probe does
  not assume the command is context-specific and compares host and VS
  fingerprints to find out (`:211-214`). The outcome of that comparison is
  **UNKNOWN** from source. *Settled by:* the probe's real-environment report, or
  official vendor documentation.
- **U-9 — the real-environment validation state of each mapped path.** This audit
  read source only; which of these paths carry `REAL_ENV_VALIDATED` evidence is
  **not established here**. *Settled by:* `CURRENT_STATE.md` and
  `project/build_history.json`, neither of which this movement's brief admitted.
- **U-10 — whether `vsx_collect.sh` is dead.** See §12.

---

## 12. Where the code and the documentation disagree

Reported, not reconciled.

1. **`checkpoint/scripts/vsx_collect.sh` is referenced by documentation but by no
   Python module.** A repository-wide search for `vsx_collect` finds it named in
   `docs/design/UI2_0_C6_CAPABILITY_EXTRACTION_CONTRACT.md:475` and
   `docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md:274` as a source of the CP VSX
   context-enumeration capability; the only other matches are the unrelated
   `"vsx_collect"` **stage name** in `utils/run_context.py:23` and
   `application/workflows/checkpoint.py:389,392`. The script's content
   (`vsx stat`, `vsenv`, `ip -4 addr show`, `ip route`, writing to
   `/var/tmp/vsx_dump_…`) differs from what `vsx_runner.py:227-236` actually
   issues (`ifconfig`, and `fw ctl set int vsid`). A capability derived from the
   script would not match the behaviour the product has. **U-10:** whether the
   script is dead code or a second, unused path is not settled by source alone.
2. **`docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md:274` records the CP VSX context
   enumeration as `REAL_ENV_VALIDATED`**, naming `vsx_collect.sh` among its
   sources. Since that script is not the code path that runs, what was validated
   is not established by this audit.
3. **`--only cp`'s inline documentation** promises "PHYSICAL NON-VSX ONLY"
   (`application/workflows/checkpoint.py:344`) and the query does exclude VSX
   objects (`cp_inventory.sh:61-62`) — but the mode still requires a pre-existing
   `vsx.json` to render (`:301`), so its output is a mixed-cycle view. The code
   labels this correctly at `:326` ("MIXED-CYCLE DEVELOPMENT VIEW / NOT A
   CHECKPOINT"); noted because a reader of the mode name alone would not expect
   it.

---

## 13. What this document deliberately does not do

- It proposes no port, wrapper, transliteration or module mapping of any Python
  file. §§3–5 describe vendor behaviour and the product's observable choices; the
  file paths are citations so a reader can verify a claim, not a build order.
- It designs no discovery feature, schema, screen or contract. §7 states a
  constraint the evidence imposes and explicitly refuses to choose between the
  options it leaves open.
- It approves no command. §8 reports gate status and marks every row that would
  need an entry.
- It changes no contract status, and cites no DRAFT as authority.

---

## 14. Defects and run shape measured in a real execution, this session

**Evidence basis, distinct from §§1–13.** Everything above this section was
read from repository source only (Status, above): no collector was run and
no endpoint was contacted in the drafting of §§1–13. This section is
different — it records three defects and one run-shape measurement observed
when the existing Check Point inventory collector was actually run against a
live management server, in the same session that produced the measurements
now recorded in `docs/design/CP_AND_VSX_DISCOVERY_CONTRACT.md` §2 and §7.
That execution was not part of this document's own audit method and does not
retroactively make any statement in §§1–13 executed; it is a second, later
evidence event, kept in its own section so the two evidence bases are never
conflated.

### 14.1 Three defects

- **D-1. A hard-coded, version-pinned path to the vendor environment
  profile.** The script's environment sourcing (`cp_inventory.sh:3`, §3.1.2)
  names a fixed, version-pinned filesystem path to the Check Point
  environment profile. On the management server measured this session, that
  path does not exist, so the `source` fails silently. The script works
  anyway only because it is invoked as a login shell (`bash -l`, §3.1.2),
  which supplies the same environment independently of the script's own
  sourcing line — the defect is latent precisely because something else
  already does the job the failing line was meant to do.
- **D-2. A positional whitespace parse that shifts columns when an object's
  own address field is empty.** The object query's return shape is one
  whitespace-separated row per object carrying the six queried attributes
  (§3.1.2). A parse that splits that row positionally on whitespace shifts
  every field after the address field by one position when the address field
  itself is empty, so a later attribute is read into the address's position
  and vice versa. Measured this session, this shift causes such an object to
  be classified as management-unreachable and skipped — the same outcome the
  management-state skip already produces for a genuinely non-communicating
  device (§3.2), but reached for a structural reason, not a management-state
  one. §11 U-7 already records that rows with an empty second field are
  skipped by design; this defect is the mechanism that would make an object
  whose row is shaped this way join that same skip path regardless of its
  actual management state. **It is latent today only because the production
  query does not select objects whose address field is empty** — the defect
  is in the parser whether or not the current query set happens to trigger
  it.
- **D-3. A bounded retry that recovered nothing in a real run.** The script's
  one hard-capped retry (retry timeout 30 s, maximum 1 retry, §3.2) was
  attempted, this session, against every device that had already failed its
  first attempt. **It recovered none of them.** The measurement does not
  settle why — a genuinely powered-off device would not be expected to
  answer on a second attempt either — but it is recorded because a retry
  budget that recovers zero devices in a real run is a cost (§14.2) with no
  observed benefit, on this run.

### 14.2 Run-shape measurement: wall-clock time spent in timeouts

This session also measured the shape of the run's wall-clock cost, derived
from the run's own recorded device counts, timeout values and parallelism —
not estimated, and not a vendor constant. The relationship: devices that do
not answer are timed out (and, per D-3, retried once) independently of the
devices that do answer, and — because the script bounds concurrent CPRID
execution to a fixed parallelism (6, §3.2) — the timeout cost the
non-responding devices add is bounded by (their count ÷ that parallelism) ×
(first timeout + retry timeout), not by their count alone, and is paid
concurrently with, not in addition to, however long the responding majority
takes.

Applied to the run measured this session, a minority of the run's total
wall-clock time — bounded by exactly that relationship — was spent timing out
against devices that never answered, and that entire cost was concentrated in
the devices this run could not reach: every device that did answer
contributed no timeout cost to the run at all. The exact share is a property
of one run's device count, which is not reproduced here as a number
(`AGENTS.md`, "Sensitive identity reporting law"); the durable measurement is
the relationship above, which any run's own recorded counts can be put
through to reproduce it.

## Cross-references

- `docs/design/PO_DECISION_RECORD_2026_09_12.md` — the decision record this
  movement serves (§1 collection gate, §2 know-how-only, §4 product sequence).
- `docs/AI_DEVELOPMENT_PROTOCOL.md` — "Network-device command gate", the ten
  fields §8 refers to.
- `docs/design/BACKUP_RECOVERY_CONTRACTS.md` §7 — the repository's existing
  signed-off gate entries, none of which cover the paths mapped here.
- `docs/design/UI2_0_B1_05_CP_INVENTORY_EXTRACTION_CONTRACT.md`,
  `docs/design/UI2_0_C6_CAPABILITY_EXTRACTION_CONTRACT.md` — the contracts that
  already record CP inventory reads as `UNKNOWN: requires gate entry`.
- `docs/design/PAN_AUTH_TRANSPORT_CONVERGENCE_AUDIT.md` — not opened here; named
  by §11 U-4.
