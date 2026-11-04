# Path analysis — A6 and G6 device command proposal

Status: RATIFIED — PO approved rows 1-5 exactly as written (chat, 2026-11-04: "1-5 onaylı devam"; AGENTS.md
2026-09-26 amendment, decision D-7). Approval covers these literals and their validated parameters only.

All commands are read-only (CLASS_0), sent through the existing controlled transports, with the diagnostic output mask,
the existing size cap, no retry, and one run per PO-chosen sample. Output is compared in memory with the neXus verdict;
only `MATCH` / `MISMATCH` / `NOT_EVALUABLE` and the masked hop is stored (raw-evidence law).

| # | Vendor / context | Exact command | Purpose | Replaces / extends |
|---|---|---|---|---|
| 1 | Check Point VSX VS (`vsenv <VSID>`) | `bash -lc 'vsenv <VSID> && ip -4 route show table all'` | G6: read every routing table of a VS, not only `main` (today `ip -4 route show` per VS) | Extends the existing per-VS read; same session, timeout and frequency |
| 2 | Check Point gateway / VS | `bash -lc 'ip -4 route get <DST>'` (VSX: `vsenv <VSID> &&` prefix) | A6: the kernel's own route decision for a sampled destination | New |
| 3 | Palo Alto firewall (XML API op) | `<test><routing><fib-lookup><virtual-router>VR</virtual-router><ip>DST</ip></fib-lookup></routing></test>` | A6: FIB decision for a sampled destination | New |
| 4 | Palo Alto firewall (XML API op) | `<test><security-policy-match><from>Z1</from><to>Z2</to><source>SRC</source><destination>DST</destination><protocol>6</protocol><destination-port>PORT</destination-port></security-policy-match></test>` | A6: the firewall's own first-match security rule | New |
| 5 | Palo Alto firewall (XML API op) | `<test><nat-policy-match><from>Z1</from><to>Z2</to><source>SRC</source><destination>DST</destination><protocol>6</protocol><destination-port>PORT</destination-port></nat-policy-match></test>` | A6: the firewall's own NAT decision | New |

`<VSID>`, `<DST>`, `VR`, `Z1`, `Z2`, `SRC`, `PORT` are filled only from a PO-chosen sample query, never from free text;
the values are validated (IPv4 literal, numeric port, existing VR/zone names from inventory) before the command is built.
Check Point access-rule matching has no read-only CLI matcher in the repository's evidence; it stays validated by
comparing neXus verdicts with the PO's knowledge of the sampled flows.

PO answer (verbatim): "1-5 onaylı devam".

## Implementation record

- Row 1 (G6) implemented on `feat/path-g6-vs-table-all-1104`: gate rows `cp_inventory_vsid_ip_route_show_table_all` (the approved
  literal) and `cp_inventory_vsid_fw_getifs` (the interface half of the old `fw getifs && ip -4 route show` composite, now its
  own call because the composite cannot carry the approved route literal), seeded by V187 and mirrored in
  `gate_registry_fixture.yaml`; `CP_INVENTORY_COMMAND_GATE_ENTRIES.md` entries 23-24. The old composite row stays SIGNED_OFF as
  history. V187 must be dry-run (BEGIN/ROLLBACK) on the live ui2 database before rollout.
- Rows 2-5 (A6) are not implemented by this change.
