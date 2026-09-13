# Palo Alto discovery — measurement brief

## Status

**DRAFT — NOT implementation authority, and not a contract.** This is a
question list for the Product Owner to answer by running two authorized reads
against Panorama and reporting what came back. It decides nothing. The contract
it feeds does not exist yet, and must not be written until these answers do.

Authorized by `PO_DECISION_RECORD_2026_09_13B_PAN_DISCOVERY_COLLECTION_GATE.md`
§2 and bounded by it: one authenticated Panorama session, one read-only managed
device enumeration, **no firewall contacted**.

## 1. Why this exists

The Check Point contract is strong because every rule in it was measured
against a live management server and written down as a rule with a check. The
Palo Alto equivalent cannot be written from the existing Python: `AGENTS.md`
"Vendor semantics law" is explicit that a field name is not its contract and
that a command returning output does not prove the reader understood it. The
existing collector tells us which call is made; it cannot tell us what the
response means.

So this brief asks the smallest set of questions whose answers turn one API
response into a candidate model — and no question that the authorized reads
cannot answer.

## 2. How to report answers — read this before running anything

`AGENTS.md` "Sensitive identity reporting law" applies to every answer here.
Report **field names, counts, shapes and relationships. Never values.**

- A field name is safe to report. A serial, hostname, address, domain or
  certificate subject is not.
- A count is safe. "Seventeen entries carry this field" is an answer; the
  seventeen names are not.
- A comparison is safe **as its outcome**: `MATCH`, `MISMATCH`, `MISSING`,
  `NOT_EVALUABLE`, `AMBIGUOUS`. Compare locally, report the relationship.
- An enumerated field's distinct *values* are safe when the field is a state or
  a category (`yes` / `no`, an HA state word, a model string). They are not safe
  when the field is an identity.

If an answer cannot be given without reproducing an identity, the correct
answer is "cannot report under the identity law" — that is itself a usable
finding, not a failure.

## 3. The two reads

1. Authenticate to Panorama through the vendor's key generation call.
2. `type=op`, `cmd=<show><devices><all></devices></show>`.

Nothing else. No `target=` parameter on anything. No `xpath` read. No firewall.

## 4. The questions

### 4.1 Does the response carry device and address information at all — the Product Owner's stated priority

- **Q-1.** Does every entry carry an address field intended as the device's
  management address? Report: how many entries have it, how many do not.
- **Q-2.** Is that address field ever present but empty? Report the count.
  (Check Point measured exactly this case on virtual systems and it turned out
  to be the design, not a data-quality problem — HR-2. Whether Palo Alto has an
  analogue is unknown.)
- **Q-3.** Which of these are present per entry, and for how many entries:
  serial, hostname, model, software version, and any field naming the device's
  family or platform? Field names and counts only.

### 4.2 Identity — what can be a join key

- **Q-4.** Does every entry carry a serial? Report the count of entries with
  and without.
- **Q-5.** Is any serial repeated across entries? Report `MATCH` /
  `MISMATCH` / count of duplicates — never the serials.
- **Q-6.** Is the hostname ever absent or empty? The existing parser falls back
  to the serial when it is; report whether that fallback would ever fire.
- **Q-7.** Does the entry carry an identifier *other* than the serial — an
  object id, a uuid, an internal key? Field names only. This matters because
  Check Point's join key turned out to be an opaque identifier carried
  alongside a display name, and the display name was never safe to join on.

### 4.3 Candidate kinds — the Check Point §4 equivalent

This is the question the code cannot answer and the measurement can.

- **Q-8.** How many entries does the enumeration return in total?
- **Q-9.** Are all of them firewalls? Report whether the response includes
  anything that is not a firewall — a log collector, a WildFire appliance,
  Panorama itself, a virtual appliance, a cloud instance. Report what each
  *kind* is, by category, and how many of each.
- **Q-10.** If non-firewall entries exist: **which field distinguishes them?**
  Report the field name and its distinct values. This is the whole question —
  Check Point's ten kinds came from three boolean flags, and the Palo Alto
  equivalent is whatever field answers Q-10.
- **Q-11.** Does any field indicate a virtual system (`vsys`) capability, a
  multi-vsys device, or a vsys count? Field names and value shapes only.
- **Q-12.** List every field name that appears in at least one entry, as a flat
  list. This is the single most useful answer in the brief: it bounds every
  other question, and it is pure field names, so it is safe to report whole.

### 4.4 The connected field — Check Point's L-S1 all over again

Check Point measured a management-plane state reporting its **most positive
value about devices that were powered off**. Palo Alto has a field of the same
class, and the existing Python *filters the candidate list on it*.

- **Q-13.** What are the distinct values of the connected field across the
  whole response, and how many entries carry each?
- **Q-14.** **The decisive one.** For devices you independently know are
  powered off or unreachable: what does the connected field say about them?
  Report as a relationship — "of N devices known to be off, the field said
  connected for M of them" — never as a device list.
- **Q-15.** Does any *other* field in the response move with a device's power
  state? Same question Check Point asked of three planes and got three
  negatives. Field names only.
- **Q-16.** Does the response carry a last-seen, last-contact or timestamp
  field of any kind? Field name and whether it is populated.

### 4.5 High availability — where Palo Alto is currently weaker than Check Point

`PAN_HA_SERIAL_IDENTITY_HARDENING_DECISION.md` records an open blocker whose
root cause is `UNKNOWN`. These questions do not attempt to resolve it.

- **Q-17.** Does the entry carry an HA state field? Distinct values and counts.
- **Q-18.** Does the entry carry any reference to its **peer** — a peer serial,
  a peer id, a group id, a cluster id? Field names only. Report `MISSING` if
  there is none.
- **Q-19.** If a peer reference exists, is it an identifier or a name? Check
  Point's rule was that the identifier joins and the name never does; whether
  Palo Alto even offers an identifier is unknown.

### 4.6 Scope honesty

- **Q-20.** Does the response carry any Template, Template Stack or Device
  Group field? Field names only. The Product Owner placed the Panorama
  configuration read out of scope, so this asks only whether the *enumeration*
  happens to carry any of it — not to widen scope, but so the contract can say
  accurately what is absent by decision and what is absent by fact.

## 5. What happens next

With these answers, a `CP_AND_VSX_DISCOVERY_CONTRACT.md`-shaped Palo Alto
contract becomes writable: candidate kinds from Q-9 to Q-12, the join key from
Q-4 to Q-7, the address model from Q-1 to Q-3, the liveness refusal from Q-13
to Q-16, and an honest deferral of HA from Q-17 to Q-19.

Without them, the contract would be a restatement of the existing Python
wearing a contract's clothes — which is the one thing
`PO_DECISION_RECORD_2026_09_12.md` §2 rules out.

## 6. Cross-references

- `PO_DECISION_RECORD_2026_09_13B_PAN_DISCOVERY_COLLECTION_GATE.md` — the two
  methods this brief is bounded by.
- `CP_AND_VSX_DISCOVERY_CONTRACT.md` — the shape the Palo Alto contract should
  take, and the source of the questions' structure.
- `AGENTS.md` — identity law, sensitive identity reporting law, vendor
  semantics law, `UNKNOWN` / fail-closed law.
