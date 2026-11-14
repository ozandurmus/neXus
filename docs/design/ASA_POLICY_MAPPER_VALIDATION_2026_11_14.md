# Cisco ASA policy parser and mapper: validation over stored configurations, 2026-11-14

## Status

MEASUREMENT RECORD (evidence, not a contract) - 2026-11-14. Engineering-session review of the Argon lane
`feat/asa-policy-parser-1114` (steps 1-2 of `ASA_FORTIGATE_POLICY_DESIGN_2026_11_14.md`), run under
`PO_DECISION_RECORD_2026_11_14_ASA_FORTIGATE_POLICY_MODEL.md` items 1-2 (stored artefacts only, counts only, no device
contact). Shapes it is compared with: `ASA_FGT_POLICY_SHAPE_MEASUREMENT_2026_11_14.md`.

## Method

`scripts/measurement/asa_policy_mapper_validation/`: the branch's parser and mapper compiled against the pod's
`service.jar`, run in `ui2-service` over the latest `show_configuration` artefact of each of the 12 enrolled ASAs,
counters only. One diagnostic run (v7) emitted the first two words of configuration lines, which included ACL names;
the output file was deleted, the key was replaced by a closed keyword vocabulary, and no name was copied anywhere.

## Results (final run, after the review fixes below)

| Check | Result |
|---|---|
| Unique ACEs in access-group sections | 80,431 = the shape measurement's bound-ACE count |
| Access rules incl. ACLs bound to several interfaces | 107,628 in 70 sections (67 `in` + 3 `global`, as measured) |
| ACEs parsed / unparsed | 114,395 / 3 (the 3 are `standard` ACEs, kept unparsed by design) |
| NAT rules parsed / unparsed | 5,778 / 0 (5,471 manual + 307 object NAT, as measured) |
| Object-group children parsed / unparsed | 42,042 / 0 |
| Raw `object network` blocks vs parsed objects | 6,727 = 6,727 |
| Access rules with every cell RESOLVED and no time/user/TrustSec/unparsed constraint | 92,090 of 107,628 (85.6 %) |
| Rules carrying `user` (identity firewall, UNKNOWN by A0-ASA-16) / `time` | 12,739 / 1,927 (incl. multi-bound copies) |
| Service cells UNSUPPORTED (`lt`/`gt`/`neq`, protocols other than tcp/udp/icmp/ip, literals outside Table 7) | 413 |
| Address cells UNSUPPORTED (non-contiguous mask, IPv6) / UNKNOWN (`interface`) | 18 / 21 objects |
| Parse + map time, all 12 units | 0.26 s + 0.62 s |
| Every artefact ends with `: end` | 12 of 12 |

**Open finding:** 118 ACE operands (6 units) and 246 object-group members (8 units) name a network object that has
no `object network` definition anywhere in the stored text (no case-only difference, not a group, service object or
`names` alias); 33 more references carry an IPv4 literal where an object name was expected. They map to UNRESOLVED
(fail closed). The cause is UNKNOWN: the configuration text was captured complete to `: end`, but a loss inside the
capture or an ASA printing rule is not excluded. Next step: compare the same counts on `startup-config.txt` from the
backup bundle (same counting method, no device contact) before deciding.

## Review fixes made by the engineering session

1. Multi-word identity names: the running configuration prints `user-group DOMAIN\\group name with spaces` unquoted;
   the parser read one word and marked about 6,365 ACEs unparsed. The name now runs to the next address or criterion
   keyword (or the closing quote); a continuation word that is an IPv4/IPv6 literal or a `names` alias leaves the ACE
   unparsed (test `multiWordIdentityNamesParseAndAmbiguousNamesStayUnparsed`).
2. `ASA not evaluated` sections (ACLs used by crypto maps, VPN filters, split tunnel, control plane) are now outside
   security evaluation (`PolicySnapshot.ASA_NON_SECURITY_SOURCES`), so their UNKNOWN rules cannot reach hygiene or
   lookup.
