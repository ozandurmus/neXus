# PO decision record - 2026-11-06: VSX VIP prefixes from management data

**Status: RATIFIED - PO DECISION (chat, 2026-11-06).** Direction only; staged implementation below.

## Context
`TOPOLOGY_CONFLICTS_RCA_2026_11_06.md`: 310 VSX Virtual System VIPs of 16 VSX members are recorded as
`cluster_virtual`. Each is reported `ADDRESS_PREFIX_UNKNOWN`, because no collected runtime evidence carries their
mask:
- `cphaprob -a if` prints no mask;
- the connected routes on those interfaces are unrelated /28 networks.

The PO was offered three options:
- management data, already collected;
- a new device command;
- keeping them UNKNOWN.

## PO answer (verbatim)
"Yönetim verisi" ("Management data").

## Decision
VSX VIP prefixes may be taken from Check Point management data that neXus already collects with an approved
command (`show-gateways-and-servers ... details-level full`). No new device command. Conditions:

1. **Evidence grade.** A prefix taken from management data is configuration intent, not runtime truth (AGENTS.md
   evidence laws). An attachment that uses it carries a management-derived evidence grade, is never `corroborated`,
   and the topology shows where the prefix came from.
2. **Identity binding.**
   - A management object binds to an inventory context only through an identity the repository proves: the object
     UID, through the discovery record (`discovery_candidate.stable_identifier` and the device's
     `discovery_match_key`), and for a Virtual System a VS identifier carried by the management object itself.
   - A name or display label is never a join key. Without a proven binding the address stays
     `ADDRESS_PREFIX_UNKNOWN`.
3. **Value match.** Within a bound object, the mask applies only to the interface whose management address equals
   the recorded VIP. If none matches, or more than one does, the address stays UNKNOWN.
4. **Fail closed.** If the management data is missing, stale, partial or ambiguous, the result is UNKNOWN. A
   management prefix never overrides a runtime prefix: a VIP that has a mask or a single containing connected route
   keeps the runtime value.

## Staging
- **Stage 1 (measure).** A parse-scope extension of the same listing logs field-name presence counters per object
  type (`[CP_GATEWAY_FIELDS]`, key names and counts only) to learn whether VS objects carry interfaces, mask lengths
  and a VS identifier. No value is read or stored.
- **Stage 2 (bind and use).** Retain the needed interface fields in the object inventory and use them in the
  topology builder under conditions 1-4. Stage 2 is only built if stage 1 shows the fields exist; if they do not,
  the VIPs stay UNKNOWN and a new decision is needed.

## Cross-references
- `docs/design/TOPOLOGY_CONFLICTS_RCA_2026_11_06.md`
- `docs/design/CP_GATEWAY_OBJECT_BOUND_REVIEW_2026_11_05.md` (the same interface retention question for policy shadowing)

## Amendment 2026-11-07 - condition 2 for Virtual Systems (PO, chat)
Stage 1 (`CP_MEASUREMENTS_2026_11_06_AFTERNOON.md` §3) found that VS objects carry `interfaces[].ipv4-address` and
`interfaces[].ipv4-mask-length`, but no VS identifier and no reference to their VSX cluster. The official API has
none either. The PO was offered two options: keep the VIPs UNKNOWN, or a stricter value binding.

**PO answer (verbatim):** "Sıkı eşleştirme" ("Strict binding").

**Amended condition 2, for a Virtual System only.** A VS management object binds to an inventory VS context when all
of these hold:
- its UID is a discovery `stable_identifier` of kind `VIRTUAL_SYSTEM_CLUSTER` or `STANDALONE_VIRTUAL_SYSTEM`;
- the set of IPv4 addresses on its interfaces equals the set of `cluster_virtual` IPv4 addresses recorded for that
  context (same unit, same context) exactly;
- exactly one such object matches.

A subset, a superset, two matching objects, or an object from an incomplete or stale inventory leaves every address
of that context `ADDRESS_PREFIX_UNKNOWN`. Conditions 1, 3 and 4 are unchanged. The match proves the binding by the
whole address set, never by a name.
