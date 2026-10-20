# PO decision record — 2026-10-20: policy permissiveness scoring

**Status: RATIFIED — PO APPROVED (relayed by the coordinator session, 2026-10-20).** Recorded by the engineering
session (lane `lane/policy-hits-perm`) from the Product Owner's decisions as relayed; it amends the scoring paragraph of
[POLICY_HYGIENE_W1_A_NOTES.md](POLICY_HYGIENE_W1_A_NOTES.md), whose authority is
[PO_DECISION_RECORD_2026_10_09_NSPM_WAVE1.md](PO_DECISION_RECORD_2026_10_09_NSPM_WAVE1.md).

## 1. Context

PO report 2026-10-19: permissiveness did not look right. Under the W1-A model every Any selector, application included,
scored 2 points, so a Palo Alto rule with application `any` but a specific service, source and destination was Medium,
and every rule naming a predefined App-ID or service (not in the collected configuration) was Unknown.

## 2. Decisions

1. **Scoring table.**

   | Selector | Points |
   |---|---|
   | Any source | 3 |
   | Any destination | 3 |
   | Any service | 3 |
   | Any application | 1, but 0 when the service is specific (anything but Any service, including `application-default`) |
   | Large CIDR (IPv4 prefix <= 16), per field | 1 |
   | Broad service range (>= 1024 ports) | 1 |

   Levels: score 0 Low, 1–3 Medium, >= 4 High. An unassessable selector still makes the verdict Unknown irrespective of
   the score, and the reasons stay specific (fixed vocabulary, `PolicyRuleMetrics.FIXED_REASONS`).

2. **Palo Alto predefined objects.** A named, non-`any` Palo Alto application or service that is not in the collected
   configuration (a predefined App-ID or predefined service) counts as a specific selection, 0 breadth points, with the
   reason `Predefined App-ID/service, ports not collected`, instead of Unknown. This is a Product Owner scoring decision,
   not a vendor-semantic proof: the ports of predefined objects remain uncollected. Unresolved address objects and
   Check Point dictionary misses are not covered and stay Unknown with their own reasons.

## 3. Confirmed alongside (no change needed)

Palo Alto `lastHit` nulls are exactly the never-hit rules (live aggregate, aiview, first pages of 54 policies: hits = 0
for 4,326 rules, all with `lastHit` null; hits > 0 with `lastHit` set for 3,998; 2 rules with hits unknown). There is no
Palo Alto parse gap; the UI now shows "Never" for a collected zero counter without dates.

## 4. Implementation

`ui2/service/.../policy/PolicyRuleMetrics.java` (`permissiveness`, vendor-aware for decision 2), tests in
`PolicyPermissivenessCausesTest` (`scoringTableFollowsThePoDecision`, `paloAltoPredefinedApplicationsAndServicesAreSpecific`).
Verdicts are computed when a policy is read, so the change shows after the service is deployed; no re-collection.

## 5. Addendum — 2026-10-20: access roles and security zones (Check Point)

**Status: RATIFIED — PO APPROVED (relayed by the coordinator session, 2026-10-20).** Recorded by the engineering
session (lane `lane/policy-perm-cp`). Live aggregate before the change (aiview, first page of every policy, counts
only): of 2,874 Check Point Unknown verdicts, 842 named `Unresolved: access role` and 358 `Unresolved: security zone`.

3. **Access role.** A Check Point access role (identity-based selection) in a rule's source or destination counts as a
   specific selection, 0 breadth points, reason `Access role (identity-based)`.
4. **Security zone.** A Check Point security zone in a rule's source or destination counts as 1 point, reason
   `Zone-based selection`. The point is added once per rule (one fixed reason, like `Broad service range`), not per
   field or per zone object.

Like decision 2 these are scoring decisions, not vendor-semantic proofs: membership of an access role or zone is
still decided at enforcement time, so shadowing keeps treating both as unevaluable (`unresolved object`). Other
enforcement-time classes (dynamic object, DNS domain, updatable object) stay Unknown with their own reasons.

**Palo Alto unchanged.** Palo Alto zones are the rule's `from`/`to` fields, not source/destination selectors; they
never entered the score and still do not. Palo Alto has no access-role object class.

### Engineering findings recorded alongside (no PO decision needed)

- `Unresolved: object without values` (1,754): the package mapper read address ranges only under the generic
  `ip-address-first`/`-last` names, which are the add-address-range request parameters; the Check Point object
  representation carries `ipv4-address-first`/`-last` and `ipv6-address-first`/`-last` (Check Point Management API
  reference, show-address-range; the vendor's own Terraform provider reads exactly these keys,
  `CheckPointSW/terraform-provider-checkpoint` `data_source_checkpoint_management_address_range.go`; the repository's
  domain-inventory parser already read them). Every dictionary address range was therefore stored without values.
  The mapper now records both spellings, and shadowing and rule search read `ipv4-address-first`/`-last`. Snapshots
  already stored take the values of the same object (same uid) from the domain inventory for the permissiveness
  verdict only (reason `Object values resolved from the domain object inventory`); an object that is empty in both
  stays Unknown with the same reason as before.
- Services without ports: a Check Point ICMP, ICMPv6, other, DCE-RPC or RPC service is a specific protocol selection.
  The scoring table scores only Any service and broad port ranges, so such a service scores 0 points. A domain-
  inventory service row without captured fields keeps its protocol class (`protocol: other`), as the mapper records it.
- A multicast address range has the address-range shape (vendor Terraform provider,
  `data_source_checkpoint_management_multicast_address_range.go`) and is now an address object.
- A wildcard object (address plus a possibly non-contiguous wildcard mask, `data_source_checkpoint_management_wildcard.go`)
  is not an interval and stays Unknown, now with the reason `Unresolved: wildcard object`.

Implementation: `PolicyRuleMetrics` (decisions 3-4, wildcard reason), `CheckPointPolicyMapper`, `CpObjectTypes`,
`PolicyNormalization`, `PolicyRuleQuery`, `PolicyQueryService.breadthObjects`, `PolicyHygiene`; tests in
`PolicyPermissivenessCheckPointShapesTest` and `PolicyMappersTest.checkpointAddressRangesCarryTheirFirstAndLastAddresses`.
