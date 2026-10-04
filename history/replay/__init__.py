"""PRIVATE_REPLAY / SYNTHETIC_SCENARIO -- Phase A, delivery slice 1.

Implementation authority: ``docs/design/PRIVATE_REPLAY_ARCHITECTURE.md``
(FROZEN -- PRODUCT OWNER APPROVED, 2026-09-08), Phase A only. This package
is offline-first by construction: nothing here opens a network socket,
resolves a device credential, or imports a vendor/collector module.

Slice 1 scope (this package, as of this build):

- ``privacy_policy.py`` -- the typed privacy/fidelity policy schema (the
  "Privacy compiler" table realized as code) and a generic, fail-closed
  tree-transform engine built on the existing
  ``utils.support_bundle.Tokenizer`` (HMAC-SHA256). No new pseudonymization
  primitive is introduced -- the invariant that reuses the existing
  Tokenizer is enforced by import, not restated as prose.
- ``isolation.py`` -- ``ConfinedPackageRoot``, the path-confinement
  primitive every replay provider is built on, plus the four explicit
  replay-provider interfaces the frozen document names for the replay
  runtime (evidence, registry, clock, job execution). Only the evidence
  provider has a concrete slice-1 implementation, used to prove the
  isolation boundary in code; registry/clock/job concrete implementations
  are delivery slice 3's "isolated console providers" work.

Explicitly out of slice 1 and out of Phase A: the offline typed exporter
itself (slice 2), console/UI wiring (slice 3), and anything under the
frozen document's "Phase B: LIVE_PRIVATE execution model" section --
credential entry, session handles, live mediation. None of that is
imported, stubbed, or hooked from this package.
"""
