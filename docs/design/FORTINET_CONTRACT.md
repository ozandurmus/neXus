# Fortinet — FortiGate over SSH, FortiManager over JSON-RPC

**Status:** FROZEN for implementation (PO 2026-09-25: "Fortimanager ve GW'ler"; "Sen metodları hazırla, eklemeyi ben
yaparım"; one fwadm account everywhere via LDAP/RADIUS). Commands gated in V80. Delivery status IMPLEMENTED until the
first real-environment run below.

## FortiGate (vendor `fortinet`, role gateway, SSH port 22)
One interactive shell per run. Reads only. Context moves (`config global`, `config vdom`, `edit <vdom>`, `end`) change
no setting; VDOM names come from the device and are checked against `[A-Za-z0-9_.-]{1,31}` before use.
**Paging:** answered in the session (`--More--` → space). Backbox instead runs `config system console` /
`set output standard`, which is a persistent configuration change; neXus does not.

| Use | Command |
|---|---|
| every run | `get system status` (at the top level; inside `config global` when refused there) |
| inventory | `config global` → `show system interface` → `end`; per VDOM `config vdom` → `edit <vdom>` → `get router info routing-table all` → `end` (single-VDOM boxes: the two reads directly) |
| backup | `show` at the top level — the whole configuration, every VDOM, headed `#config-version=` (the same read Backbox takes) |

Stored: confirm → hostname, model (`FortiGate-1101E`), version (`v7.0.12 build0523`); inventory → one context per
VDOM with its interfaces (configured address, type, VLAN, parent, configured up/down) and routes; backup → encrypted
tgz with `fortigate.conf`, `system-status.txt`, `manifest.txt`; refused without the `#config-version=` header.

## FortiManager (vendor `fortinet`, role management server, HTTPS 443)
JSON-RPC at `POST /jsonrpc`: `exec /sys/login/user` (password only in the body, never logged), `get /sys/status`,
`get /dvmdb/device` (name, address, model, FortiOS version, connection, HA), `exec /sys/logout`. Confirm → hostname,
platform, version; inventory → the managed FortiGates, shown under "Managed devices" like the Management Center.
**Not yet:** FortiManager's own backup. Its CLI pushes `execute backup all-settings` to a server; the HOST-A SFTP
receiver used for the Radware Cyber Controller fits, and is the next step after the first confirm/inventory run.

## FortiManager interfaces, routes and discovery (V81, PO 2026-09-25)
- Inventory also reads its own `/cli/global/system/interface` and `/cli/global/system/route` (static routes); a refusal
  stores the inventory without them and is logged.
- Discovery (Add device › Discover from a management server › Fortinet): `/dvmdb/adom`, then per ADOM
  `/dvmdb/adom/<adom>/device` with HA members. Candidates: a standalone FortiGate (importable at the address FortiManager
  manages it by); an HA cluster as ONE importable candidate at that address, its members listed under it and not
  importable (they have no separate management address in FortiManager). The serial is the stable identifier; the ADOM
  is the domain. Imported devices run the FortiGate onboarding (SSH).

## Configuration plane and FortiManager link states (V85, PO 2026-09-26)
- **FortiGate configuration read** (`fgt_configuration_collect`, the onboarding's third step): the same top-level
  `show` the backup takes, parsed by the worker into the configuration plane -- one section per outermost
  `config …` block, per VDOM (`root · System Interface`), one setting per `set` line under its `edit` path;
  lines carrying a password, key, PSK, certificate or `ENC` value are withheld and counted. The canonical hash leaves
  out the per-run `#` header, so an unchanged configuration hashes the same. Device screen: the Configuration tab.
- **FortiManager interface states:** the JSON API's `status` is a number without a documented meaning (16 on every
  interface, measured 2026-09-25), so the inventory logs in over SSH too and reads `get system interface`. Measured
  2026-09-26: its `status:` is the configured state (`enable`), shown as up/down exactly as a FortiGate's configured
  `set status` is. V87 gates `diagnose hardware info nic <port>` once per named port in the same SSH session.
  The first real run on FortiManager 7.4.11 rejected every per-port invocation as a CLI error. The worker no longer
  issues that form. Physical link remains `UNSUPPORTED` for this appliance and `UNKNOWN` in inventory until an
  appliance-specific read is documented, gated, and measured; configured state remains a separate observation.
  V88 gates the FortiManager-documented `diagnose system print interface <interface>` as a bounded candidate. Its
  first neXus run probes one interface and records only a masked output shape; it does not assert link semantics.
  The V88 output had interface statistics but no explicit physical-link field. V89 gates the vendor-documented
  `diagnose fmnetwork interface detail <interface>`; its first run measures one port's masked shape and a bounded
  status enum before any interface state is changed.
  The FortiManager 7.4.11 run returned an interface-information shape but no `Status:` field. The older documented
  example therefore does not establish this estate's semantics. V89's diagnostic was removed from subsequent reads.
  Neither `UP` nor `RUNNING` from the interface-information output is promoted to physical link; Linux documents
  `IFF_RUNNING` as operational UP or UNKNOWN. Physical link stays `UNKNOWN` until stronger evidence exists.

## Real-environment measurement (to do, after the PO adds the devices)
One FortiGate with VDOMs and one FortiManager, fwadm credential: confirm facts present; VDOM, interface and route
counts; backup size and time; that `--More--` never timed a read out; that the FortiManager accepted the JSON-RPC
login with the basic-auth header the HTTPS client also sends.
