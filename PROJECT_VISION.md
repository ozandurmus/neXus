# SecurityExpert — Product Vision & Architectural North Star

## Product identity

SecurityExpert is not only an inventory script. It is a network-security state,
evidence and assurance platform built incrementally from proven read-only
collection.

Product progression:

`SEE → VERIFY → TRACE → RECOVER → OPERATE`

The first four stages must become trustworthy before controlled write/change
operations are considered.

## Product planes

Target product capabilities include:

- Overview / executive operational posture
- Network Inventory
- Configuration Intelligence
- Alignment / expected-vs-actual
- Policy & Objects
- Compliance / Findings
- History / Diff
- Backup / Recovery
- controlled Operations later

Inventory describes runtime/operational state. Configuration describes current
configured state. Alignment compares expected intent with actual/effective
state. These concepts must not be collapsed merely because they share data.

## Multi-vendor direction

The architecture must remain vendor-neutral while preserving vendor-native
semantics. Current mature planes include Check Point/VSX and Palo Alto/Panorama;
future adapters may include Fortinet, Cisco firewall/routing/switching and other
network/security platforms.

Do not force different vendors into a false common model. Normalize product
concepts while retaining provenance and vendor-native evidence.

## Evidence model

Prefer observed evidence over inferred state.

- Management systems: discovery, topology, assignment, intent, provenance.
- Direct devices: actual/effective runtime and configuration evidence.
- Identity verification is required before accepting direct evidence where the
  product contract defines an identity gate.
- `UNKNOWN` is a valid result when evidence is insufficient.

## Safety model

Product behavior is governed by `utils/action_taxonomy.py` (see `AGENTS.md`
"Network action taxonomy" and `CURRENT_STATE.md` "Safety status"): class 0
read is the great majority of the product, and class 1 controlled recovery
writes exist only under their own explicit `RB.x` contracts, CLI-only, never
console-submittable. No automatic configuration change, policy install,
commit, reboot, failover, interface/routing change, credential change or
remediation is permitted — classes 2 through 4 have no permitted member at
the current product maturity.

Device interaction must be stable and conservative. New commands, higher
frequency, recurring schedules or increased concurrency require explicit safety
review.

## Data/privacy model

Repository source is separate from operational runtime state. Real operational
identities, raw evidence and secrets are not repository content. Shareable
artifacts are sanitized by design; local operator artifacts may remain
sensitive.

Raw secret-bearing configuration must not be casually persisted or surfaced.

## Platform direction

Development path:

`local Windows POC → Corporate Git development → managed server runtime → containerized platform → controlled production`

Deployment work must not change device interaction semantics merely to fit the
platform. Runtime, storage, secrets, scheduler and CI/CD foundations are
introduced through explicit engineering checkpoints.

## Product decision lenses

For important architecture/UI decisions consider the perspectives of:

- Senior Python Architect
- Network Security Engineer
- Check Point / VSX Engineer
- Palo Alto Engineer
- Multi-vendor Automation Engineer
- Configuration Management Specialist
- Data / Inventory Architect
- DevSecOps / Platform Engineer
- Security Reviewer
- Test Automation Engineer
- UI/UX Product Designer
- Technical Product Owner
- Network/Security Manager
- Business/Executive stakeholder

These are reasoning lenses, not claims about actual team members.
