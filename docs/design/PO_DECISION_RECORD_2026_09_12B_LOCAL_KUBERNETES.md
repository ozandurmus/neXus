# PO Decision Record — 2026-09-12B — local Kubernetes runtime

## Status

**FROZEN — PRODUCT OWNER DIRECTIVES, 2026-09-12.** This document records
Product Owner decisions given in the 2026-09-12 local PO+O session that
existed only in session chat. `AGENTS.md` "Authority hierarchy" item 7 makes
chat non-authoritative, so an unrecorded verbal decision is lost at the
session boundary. This file is the durable record; it creates no new
authority, it preserves authority the Product Owner already exercised.

It does not amend `PO_DECISION_RECORD_2026_09_12.md`; it stands beside it.
Where the two touch the same subject, the section below says so explicitly.

## 1. One image, three runtimes, no host container tooling

**PO directive.** The product runs on plain Kubernetes driven with `kubectl`.
The local single-node OpenShift instance is removed. No host container tool is
part of any path — neither the one this session briefly installed nor the more
common one. The Product Owner's words: the corporate platform is OpenShift,
they cannot move to it directly, and when an Ubuntu server arrives they prefer
to continue on Kubernetes there; they asked whether the middle step forces a
host build tool, and whether that would make the eventual move to the
corporate platform incoherent.

**The answer this record fixes as the product's path.** It does not, and it is
not incoherent, because the artifact is an **OCI image**, and every runtime in
the chain consumes the same one:

| Stage | Runtime | Where the image is produced |
| --- | --- | --- |
| This machine | `minikube`, containerd | built inside the cluster (`minikube image build`, BuildKit) |
| Ubuntu server, later | Kubernetes, containerd or CRI-O | built in CI, or in-cluster; pulled from a registry |
| Corporate platform, production | OpenShift, CRI-O | the same image, pulled from a registry |

One image and one manifest set carry across all three. The only production
difference is that a `Route` replaces the `Ingress`. No host build daemon
exists at any stage, so no stage introduces a tool the next stage must undo.

**What this replaces.** The session brief and `AI_HANDOVER.md` §3 item 2 said
images would be built with a host container tool. That instruction is
**withdrawn**. The OpenShift-safety half of the same instruction is retained —
see §3.

**What it does not change.** `PO_DECISION_RECORD_2026_09_12.md` §4's required
first state stands: a clean, empty database, the UI and its menus visible,
nothing pre-populated.

## 2. The runtime chosen, and the evidence that it works

The Product Owner selected `minikube` with the `vfkit` driver, with the stated
caveat that this is a corporate machine and the installation might be refused.
It was not refused. Measured in this session, on this machine:

| Fact | Evidence |
| --- | --- |
| `minikube` v1.39.0 installs without `sudo` | written to `~/.local/bin`, already on `PATH`; `/usr/local/bin` is root-owned and was not touched |
| `vfkit` v0.6.4 runs under the corporate profile | `codesign` shows `com.apple.security.virtualization`; the VM booted |
| The cluster is up | `kubectl get nodes` → `minikube Ready control-plane v1.37.0`, `containerd://2.3.4` |
| No host container tooling is present or needed | none of the common host build tools is installed, and none is required by the path above |
| No host JDK is needed | `java` is absent from this host; the builder stage carries the JDK |
| Images build in-cluster | `minikube image build` built a `registry.access.redhat.com/ubi9/openjdk-21-runtime` derivative and exported it; the test image was then removed |
| Registry egress works from inside the VM | `registry.access.redhat.com/v2/` → 200; the two public registry endpoints probed alongside it → 401, the normal unauthenticated `v2` answer, i.e. reachable. `minikube start` printed a contrary warning; that warning is its own probe, not a reachability fact |

**State of the machine after this session's environment work.** The local
OpenShift Local instance was deleted with its own `delete` and `cleanup`
commands and its 74 GB cache removed; `kubectl config get-contexts` now lists
`minikube` alone. The transient machine this session created before the
directive was given was removed. Neither is part of any product path.

## 3. OpenShift safety is kept, as a construction rule

The Product Owner's instruction that the manifests stay OpenShift-safe **by
construction** is retained, and §1 is why it matters more now rather than
less: it is the single property that lets one image cross all three stages.

- no root, and no fixed numeric UID — the image must run under an arbitrary
  assigned UID, with group 0 owning the writable paths,
- no `hostPath`, no privileged container, no host networking,
- resource requests and limits set on every container,
- `Service` plus `Ingress`, with a `Route` replacing the `Ingress` on the
  corporate platform,
- no credential value in any tracked file; secrets arrive as a `Secret`.

This is exactly the constraint `UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` §6
names as the gate on the container image — the `restricted-v2` arbitrary-UID
constraint, which defeats a fixed numeric UID and an `fsGroup`.

## 4. Sequencing consequence: the contract gate still holds

`UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` §6 (FROZEN) states the container
image is NOT BUILT and may not be implemented until its own successor contract
freezes. Changing the local runtime does not lift that gate — the gate is on
the image and its UID model, not on the tool that produces it. The refusal
recorded in `relay/NXS-LOCAL-0109-ui2-b1-12-deployment-slice.json` was
correct and remains correct.

`UI2_0_B1_01B_CI_WORKFLOW_CONTRACT.md` is the precedent for the shape of the
answer: the same §6 gate, resolved by a frozen successor contract that creates
no artifact itself.

## 5. Removal of the superseded runtime equipment

**PO directive.** The superseded host-tooling equipment is to be removed from
the repository, not merely left unused. The Product Owner authorized removal
across the live product and the UI 2.0 test carrier, and authorized revising
the frozen contracts that carry the superseded assumption.

**Boundary, as reported to and accepted by the Product Owner.** Historical
records are not rewritten: `docs/history/**`, `relay/*.json` and
`project/*.json` are an audit trail that the repository's own
`build_history_index.py --check` and queue generator validate, and the frozen
contracts' references to the superseded tooling are in several cases the very
clauses that withdraw it. Frozen contracts are therefore revised through their
own amendment mechanism, appended and dated, never by silent deletion — which
is also what `AGENTS.md` requires.

## 6. Cross-references

- `PO_DECISION_RECORD_2026_09_12.md` §4 — required first state, unchanged.
- `UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` §6 — the gate.
- `UI2_0_B1_01B_CI_WORKFLOW_CONTRACT.md` — successor-contract precedent.
- `AI_HANDOVER.md` §3 — the host-tooling instruction this record withdraws.
