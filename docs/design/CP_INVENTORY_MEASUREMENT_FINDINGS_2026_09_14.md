# Check Point inventory — measurement findings (14C section 5, M-1)

## Status

**DRAFT — NOT implementation authority.** Records what the Product Owner
observed on 2026-09-14 running `scripts/measurement/cp_inventory_shape.sh`
on two Check Point hosts: one **ClusterXL member** (non-VSX, HA mode) and
one **VSX host** (VSLS cluster member, physical context VS0). It decides
nothing; it exists so the observation survives the session
(`AGENTS.md` item 7). Shapes, field names and vocabulary only; no address,
hostname, serial or cluster name is recorded. M-2 (virtual-system context
on a new channel) is still owed.

## 1. Session

- The SSH session lands in **Expert** on both hosts (prompt of the form
  `[Expert@<name>:<vsid>]#`, VSID `0` on the VSX host). Every command below
  ran **as typed**, exit code 0, no `clish -c`, no `expert` step.
- Unsolicited kernel/syslog lines can be interleaved into any command's
  output (observed inside `cphaprob stat` on the VSX host: a
  `Message from syslogd@…` line followed by a kernel line). Parsers must
  skip lines they do not recognise rather than fail.

## 2. `ip -details -4 addr show`

- Shape exactly as the Linux `iproute2` form: header
  `<index>: <name>: <FLAGS> mtu … state UP|UNKNOWN qlen …`, then
  `link/ether` or `link/loopback`, then `inet <addr>/<prefix> [brd …] scope
  global|host <name>`, then `valid_lft …`.
- VLAN subinterface header carries `@parent` (e.g. `bond1.3841@bond1`) and a
  detail line `vlan protocol 802.1Q id <id> <REORDER_HDR>` — the VLAN id is
  available from that line as well as from the name.
- Interface kinds seen: loopback, `Mgmt`, `Sync`, `ethN-NN` physical ports,
  bond VLAN subinterface. Bond parents themselves did not appear with an
  address in either output.
- Flags lists contain `UP` and `LOWER_UP` as separate tokens; `state` is a
  separate field (`UP`, `UNKNOWN` on loopback).
- **VSX host, VS0 context: only `lo`, `Mgmt`, `Sync` appear.** Virtual
  systems' interfaces are not visible from the physical context, which
  confirms the per-context read (14C §3) is required.
- `ip -6 addr show` returned empty on both hosts (no IPv6 configured); the
  command itself is accepted.

## 3. `ip -4 route show table all`

- Main-table rows: `default via <gw> dev <if> proto 7`;
  `<net>/<len> dev <if> proto kernel scope link src <addr>` (connected);
  `<net>/<len> via <gw> dev <if> proto 7` (static / routed-installed);
  on the VSX host also `<net>/<len> dev <if> proto 7 scope link` (no `src`).
- **`proto` is numeric (`7`) for routes installed by Gaia's routing
  daemon** — not the textual `static`. The parser must accept numeric
  protocol tokens and map `7` → `static` (Gaia routed) while keeping the raw
  token; `kernel` → `connected`.
- Rows containing `table local` (`broadcast`/`local` first token) are not
  routes and are to be dropped, as the earlier product did.
- Counts: 22 lines on the cluster member (6 real routes), 13 on the VSX
  host VS0 (3 real routes).

## 4. `cphaprob stat`

- `Cluster Mode:` line vocabulary observed:
  `High Availability (Active Up) with IGMP Membership` (cluster member) and
  `Virtual System Load Sharing (Active Up)` (VSX host).
- Member table header `ID  Unique Address  Assigned Load  State  Name`;
  local row marked `1 (local)`; states `STANDBY` / `ACTIVE`; load as a
  percentage.
- Then `Active PNOTEs:`, `Last member state change event:`, `Last cluster
  failover event:`, `Cluster failover count:` blocks (informational).
- **VSLS adds** a `Cluster name:` line and a `Virtual Devices Status on
  each Cluster Member` table: rows `<VSID> | <weight> | <state on member 1>
  | <state on member 2>`, the local member column marked `[local]`, then
  `Active` / `Weight` / `Weight (%)` totals and a `Legend:` block. **This
  gives the per-virtual-system HA role of every member from the physical
  context, without `vsenv`.**

## 5. `cphaprob -a -m if`

- VSX host output is prefixed by a `vsid 0:` section header (underlined).
- `CCP mode:` line, `Required interfaces:` / `Required secured interfaces:`
  counts, then a table `Interface Name:  Status:` with rows
  `<name> [(S)|(LS)|(HA)]  UP|Non-Monitored`; markers explained by a legend
  line `S - sync, HA/LS - bond type, LM - link monitor, P - probing`.
- Then `Virtual cluster interfaces: <n>` followed by `<n>` rows of the form
  `<name>  <address>` optionally followed by `VMAC address: <mac>`. This is
  the cluster-virtual-address block (role `cluster_virtual`). The VSX host
  VS0 listed one (on `Mgmt`); the cluster member listed two.
- Then either `No VLANs are monitored on the member` or a
  `ClusterXL VLAN monitoring per interface:` table (`Interface | Low VLAN |
  High VLAN`) followed by a `Security GW:` sentence.

## 6. `vsx stat -v`

- VSX host: `VSX Gateway Status` block (name, policy, install time, SIC),
  `Number of Virtual Systems allowed by license`, `Virtual Systems [active /
  configured]: 2 / 2`, then `Virtual Devices Status` table whose rows begin
  `<ID> | <Type letter> <name> | …`; type legend `S` virtual system, `B`
  bridge mode, `R` virtual router, `W` virtual switch. VSIDs observed: two,
  both type `S`.
- Non-VSX member: prints `VSX is not supported on this platform` with exit
  code 0 — **VSX detection must be by that text, not by the exit code.**

## 7. M-2, first pass (VSX host)

- `vsenv <id>` for a VSID that does not exist prints
  `vsenv: operation failed, specified Virtual System ID does not exist.`
  (the host's virtual systems are the two IDs `vsx stat -v` listed, not 1).
- **`vsenv` is a function of the interactive Expert shell, not an
  executable:** inside `sh -c` the composite line printed
  `sh: vsenv: command not found` and the reads that followed ran in the
  physical context unchanged. A non-interactive exec channel therefore
  cannot call `vsenv` bare. Candidates to measure next: `bash -lc 'vsenv N
  && …'` (login profile), sourcing the Check Point profile
  (`$CPDIR/tmp/.CPprofile.sh`) before `vsenv`, or a persistent interactive
  shell session per device (the pattern the earlier product's preflight
  battery settled on). The shape script's VS section now tries the first
  two and prints `type vsenv`.

## 8. M-2, second pass (VSX host, one virtual system)

- **`bash -lc 'vsenv <VSID> && <reads>'` works on a non-interactive
  channel** (exit 0, first line `Context is set to Virtual Device <name>
  (ID <n>).`, then the reads answer for that virtual system). Sourcing the
  Check Point profile inside `bash -c` does **not** define `vsenv` (exit
  127). `type vsenv` under `bash -i` reports a shell function. The Java
  transport therefore issues every per-VS composite as
  `bash -lc 'vsenv <VSID> && …'`.
- Inside the VS context, `ip -4 addr show` lists the virtual system's own
  interfaces (physical port, VLAN subinterfaces `name.ID@ifNN` with
  `link-netnsid`), and `ip -4 route show` its own routes (`proto 7` default
  and statics, `proto kernel` connected, `proto 7 scope link`). These are
  the **member's** addresses on that virtual system.
- `cphaprob -a -m if` inside the context is headed `vsid <N>:` and lists
  the virtual system's monitored interfaces and its own `Virtual cluster
  interfaces: <n>` block — the cluster addresses for that virtual system
  (five on the measured one). Its VLAN table has the VSX shape
  `Interface | VS ID | Monitored VLANs` followed by a `VSX load sharing:`
  sentence.
- `cphaprob stat` inside the context reports that virtual system's own
  member roles (the local member was ACTIVE there while STANDBY for the
  physical view), consistent with the VSLS table in §4.
- **A fresh SSH session may land directly in a non-zero context** (prompt
  `:3` observed on a new login after the measurement). The collector must
  never assume the physical context on connect: physical reads run in the
  session as opened only after verifying `:0`, or, simpler, are themselves
  issued as `bash -lc 'vsenv 0 && …'`.
- Virtual-system ids differ per host (one host had 2 and 3, another 1 and
  3); the id set always comes from `vsx stat -v`.

## 9. Still owed

On the VSX host: `sh cp_inventory_shape.sh <VSID>` for one of the two
virtual systems (composite `vsenv <VSID>; ip -4 addr show; ip -4 route
show`), then a **new** SSH session running only `ip -4 addr show` to prove
the context does not survive a fresh channel.
