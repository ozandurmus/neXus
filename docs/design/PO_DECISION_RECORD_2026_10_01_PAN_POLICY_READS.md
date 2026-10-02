# Panorama management policy reads

Status: RATIFIED — Product Owner approved, 2026-10-01; hierarchy read approved 2026-10-02.

The Product Owner approved Panorama policy collection with "PAN tarafı da ok".
Device groups hold management policy. A firewall has no policy relationship until assigned;
assignment and push are separate from observed installation or runtime enforcement.
The hierarchy read was explicitly approved with "Onay geldi devam" on 2026-10-02.

Exactly four new read forms are authorized:

```text
type=config&action=show&xpath=/config/shared
type=config&action=show&xpath=/config/devices/entry[@name='localhost.localdomain']/device-group/entry[@name='<DG>']
type=op&cmd=<show><devicegroups/></show>
type=op&cmd=<show><dg-hierarchy></dg-hierarchy></show>
```

V117 and the fixture contain four SIGNED_OFF gates: palo_alto / panorama /
not_applicable / PAN_XML_API / read. Configuration action=show reads committed
running configuration; candidate/get, commit and push are excluded. DG names are
XPath literals, transported as form values with existing transport encoding.

Reuse PanXmlApiTransport, existing credential resolution and existing key generation,
with one in-memory API key per job, disposed on every exit. TLS policy remains the
ratified PO_DECISION_RECORD_2026_09_21_PAN_TLS_VERIFICATION_DISABLED.md decision.
Only registered Panorama management-server nodes with successful discovery are eligible.
The admin Policy action submits opaque intent through existing RBAC/CSRF gates. A
successful discovery queues a separate ledgered job; automatic attempts, including
failures, are admitted at most once per Panorama in six hours. Manual attempts also
record their admission time. Reads run serially, without retry or firewall contact.

Read order: devicegroups, dg-hierarchy, shared, each DG. Per-response limit 16 MiB,
aggregate input limit 64 MiB, 200 DGs, 100,000 normalized rules and 200,000 normalized
objects per job, 500,000 XML elements per response, depth 64, hierarchy
depth below 32, per-request deadline 60 seconds, job deadline 30 minutes. Hardened
StAX parsing rejects DTD/entities. Unsupported, malformed, partial, oversized or
timed-out output fails closed. All requested DG snapshots are mapped before atomic,
lease-fenced publication; previous snapshots survive failure. No raw response or
transcript is retained. Telemetry carries outcome classes and counts only.

One snapshot per DG carries its direct member firewall targets. Exact stored PAN
discovery identities provide enrolled-device references; unmatched members retain
source-scoped opaque references. No display-name/address joins or serial normalization.
Missing/unsupported synchronization values remain UNKNOWN; supported vendor values
are projected as IN_SYNC / OUT_OF_SYNC, without implying direct-device verification.

The existing p1c mapper retains shared-first, ancestor-to-child pre-rules and
child-to-ancestor, shared-last post-rules, dynamic groups and object references.
The approved reads do not expose the global object-precedence override. Objects whose
resolution differs between ancestor and descendant precedence are therefore UNKNOWN
with values withheld. Additional setting evidence would need separate approval; no
extra read is introduced.

When no Panorama node exists in the management tree, the viewer parses only
policy scopes from existing encrypted effective-running configuration artifacts
(limit 64 MiB per stored artifact).
Local vsys rulebases are badged "local firewall policy"; explicit non-local provenance
is excluded. No fresh device read, plaintext persistence or inferred Panorama link is
introduced. Missing artifact access/invalid XML is unavailable evidence, never an
empty successful policy. AIView masking applies to both projections.

Primary-source references: [PAN rule hierarchy](https://docs.paloaltonetworks.com/panorama/administration/manage-firewalls/manage-device-groups/manage-the-rule-hierarchy),
[inherited-object precedence](https://docs.paloaltonetworks.com/panorama/administration/manage-firewalls/manage-device-groups/manage-precedence-of-inherited-objects),
and [vendor SDK hierarchy/member parsing](https://github.com/PaloAltoNetworks/pan-os-python/blob/develop/panos/panorama.py).
These support parser shapes and evaluation ordering, not real-environment validation.

Authorization is implementation and local commits on this lane only. No worker device
access, HOST-A, deployment, push, PR, merge or project-state edits. Java/Gradle and live
Panorama behavior remain UNVERIFIED under the worker execution boundary.

## Local validation

- `cd ui2/frontend && npx tsc --noEmit -p .`: PASS.
- `cd ui2/frontend && npx vitest run`: PASS, 46 files / 381 tests. The first
  `--cacheDir .cache/vitest` invocation was rejected by the installed Vitest CLI;
  the existing Vite `.cache/vite` setting was then used successfully.
- `cd ui2/frontend && npm run build`: PASS.
- `python3 -m pytest -q tests/test_pan_policy_gate_contract.py tests/test_cp_policy_gate_contract.py tests/test_migration_versions_unique.py tests/test_migration_audit_context.py`:
  PASS, 4 tests.
- `python3 -m pytest -q tests/test_html_render_harness.py tests/test_architecture_convergence.py::test_project_metadata_has_no_cross_authority_contradictions`:
  6 passed, 1 skipped, 1 failed. Chromium startup for the Playwright smoke test was
  denied by the macOS sandbox (`bootstrap_check_in`, Permission denied); no browser
  inspection completed. No product/state fix is indicated by that environment failure.
- `python3 scripts/repository_privacy_check.py`: PASS, zero findings after moving
  test-created runtime logs outside the worktree. The intermediate check flagged
  only `logs` / RUNTIME_DIRECTORY_PRESENT; no contents or matched values were read.
- `git diff --check`: PASS.

Added synthetic Java tests cover request literals/escaping, approved gates, shared/
parent/child pre/post order, dynamic groups, member mapping, partial failures, opaque
unmatched members, local encrypted-artifact fallback and masking. PAN collection RBAC
uses the existing session/CSRF/persona MVC test. Java/Gradle tests were not executed:
worker sandbox execution is explicitly prohibited. Full Java regression and live
Panorama/firewall output, synchronization enums and UI acceptance remain UNVERIFIED.
Project state and handover files were intentionally left to the parent session.
