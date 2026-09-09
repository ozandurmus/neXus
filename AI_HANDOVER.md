# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If this disagrees with `CURRENT_STATE.md` or `project/roadmap.json`, those
> sources win.

## 1. Snapshot

- Date: 2026-09-10. `ui2_b1_01_skeleton_ci_docker`, contract-only movement
  on `feature/ui2-b1-01-skeleton-contract`.
- FROZEN: `docs/design/UI2_0_B1_01_SKELETON_CI_DOCKER_CONTRACT.md` (Product Owner approved 2026-09-10 after portability and cold-cache CI corrections).
- No `ui2/` implementation, Line-1 source, `validation.yml`, or device contact.
- Branch was fast-forwarded to `origin/main` `04b99c0` before drafting,
  preserving the later UI2 logo and portfolio decisions.

## 2. What changed

- The contract gives every C1/C2/C3/C4 component a module/package home and
  fixes ten dependency-direction rules mirrored one-to-one by named tests.
- It specifies exact Gradle/unit/integration/architecture/image commands,
  Flyway-before-test lifecycle, C1 `audit_context_missing` coverage, isolated
  UI2 CI coexistence, image contents/exclusions, component-scoped secret
  injection, and 15 runnable implementation checks.
- Technology choices and PO veto points are explicit, including React/MUI
  for the Material Design 3 frontend and patch-version pinning at implementation.

## 3. Exact next action

1. Dispatch the B1-1 IMPLEMENTATION movement to create `ui2/`
   and the isolated UI2 workflow exactly against its 15 checks.
2. Keep the taxonomy decision movement independent; this contract authorizes
   no device execution or restore-write semantics.

## 4. Test delta

- Documentation/project-state tests, repository privacy comparison, relay
  validation, and `git diff --check` are the required close evidence.
- No Java/Gradle/Docker implementation exists yet, so the contract's runnable
  implementation checks are intentionally pending the later movement.

## 5. New risks

- The contract is frozen; implementation still requires its own dependency approvals and movement gate.
- Patch versions are selected and pinned during implementation; dependency
  additions remain separately approval-gated.
- React/MUI and the distroless image choice remain explicit PO veto points.
