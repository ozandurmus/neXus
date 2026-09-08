"""The typed PRIVATE_REPLAY privacy/fidelity policy.

Realizes ``docs/design/PRIVATE_REPLAY_ARCHITECTURE.md``'s "Privacy compiler"
table as executable schema: every field gets an explicit, versioned
treatment, and an unclassified field fails closed rather than passing
through unchanged. This module classifies and transforms in-memory data
only -- it reads nothing from disk and knows nothing about run directories,
support-bundle file layout or the package format; slice 2 (the offline
typed exporter) wires this policy to real payload trees.

Guarantees this module is required to hold (frozen document, "Identity and
topology" and acceptance gate 2):

- **Irreversibility** -- the only pseudonymization mechanism is
  ``utils.support_bundle.Tokenizer``, an HMAC-SHA256 one-way function over
  ``key`` (never included in a replay package) and ``value``. This module
  introduces no second, weaker pseudonymization primitive.
- **Consistency across a dataset** -- the same ``(domain, value)`` pair
  always produces the same token for the lifetime of one export key
  (``Tokenizer.token`` is a pure HMAC of its inputs), so relationships that
  depend on identity equality survive transformation.
- **No raw-value leakage** -- ``PrivacyFidelityPolicy.transform`` never
  returns a raw value for anything it does not explicitly classify as
  ``RETAIN``. A field this policy has no rule for is treated as
  ``EXCLUDE``, not as "pass through" -- an unclassified field blocks
  publication rather than leaking (acceptance gate 1).
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Any, Mapping

from utils.support_bundle import Tokenizer

SCHEMA_VERSION = "private-replay-privacy-policy-v1"


class FieldTreatment(Enum):
    """One row of the frozen document's "Privacy compiler" table."""

    #: Preserve the approved semantic value unchanged (e.g. an evidence
    #: state, a capability state, a relationship that is itself the safe
    #: output -- never an identity, credential or free-text field).
    RETAIN = "retain"
    #: Replace with a consistent pseudonymous ID via the existing Tokenizer.
    #: Used for device/cluster/context/manager/object/principal identities
    #: and for artifact IDs/references/filenames.
    PSEUDONYMIZE = "pseudonymize"
    #: Network-specific typed replacement (IPs, networks, MACs, domains,
    #: serials, fingerprints) -- a distinct algorithm from identity HMAC
    #: because it must preserve relationships (same/different address,
    #: subnet membership, prefix containment), not just equality.
    NETWORK_SYNTHESIZE = "network_synthesize"
    #: Fabricate a typed replacement value from scratch (e.g. a shifted
    #: replay clock). Requires a registered synthesizer; see
    #: ``PolicyCoverageError`` below for the fail-closed default.
    SYNTHESIZE = "synthesize"
    #: Derive a safe summary/count instead of the raw value (e.g. free
    #: text, paths, raw command/configuration text).
    DERIVE_SUMMARY = "derive_summary"
    #: Never leaves the trusted boundary (credentials, tokens, private
    #: keys, session material) -- and the fail-closed default for any
    #: field this policy does not name.
    EXCLUDE = "exclude"


class PolicyCoverageError(RuntimeError):
    """Raised instead of leaking a value this policy cannot safely treat.

    Two cases collapse to this one error, both fail-closed by design: a
    field with no ``FieldPolicy`` at all, and a ``SYNTHESIZE`` field with no
    registered synthesizer (concrete vendor-aware synthesis is slice 2/3
    work; slice 1 must not invent an ad hoc synthetic value in its place).
    The message carries the schema path and the treatment name only --
    never the matched value (acceptance gate 1).
    """

    def __init__(self, path: str, treatment: FieldTreatment | None):
        self.path = path
        self.treatment = treatment
        label = treatment.value if treatment is not None else "unclassified"
        super().__init__(f"privacy policy coverage gap at {path!r}: {label}, publication blocked")


@dataclass(frozen=True)
class FieldPolicy:
    key: str
    treatment: FieldTreatment
    #: HMAC domain-separation namespace for PSEUDONYMIZE fields. The domain
    #: represents the identity namespace (e.g. "device", "artifact"), never
    #: whichever field name happens to carry it -- two different field
    #: names sharing a domain pseudonymize to the same identity space.
    pseudonym_domain: str | None = None
    notes: str = ""

    def __post_init__(self) -> None:
        if self.treatment is FieldTreatment.PSEUDONYMIZE and not self.pseudonym_domain:
            raise ValueError(f"field policy {self.key!r} is PSEUDONYMIZE but declares no pseudonym_domain")


#: The frozen document's "Privacy compiler" table, field by field. Keys are
#: dictionary keys as they appear in already-existing payload/evidence
#: trees (the same key vocabulary ``utils.support_bundle.SENSITIVE_KEYS``
#: partially covers) -- this table is broader because PRIVATE_REPLAY's
#: fidelity/coverage bar is stricter than the shareable support bundle's.
PRIVATE_REPLAY_FIELD_POLICIES: tuple[FieldPolicy, ...] = (
    # Device, cluster, context, manager, object and principal identities.
    FieldPolicy("device", FieldTreatment.PSEUDONYMIZE, "device"),
    FieldPolicy("device_id", FieldTreatment.PSEUDONYMIZE, "device"),
    FieldPolicy("hostname", FieldTreatment.PSEUDONYMIZE, "device"),
    FieldPolicy("cluster", FieldTreatment.PSEUDONYMIZE, "cluster"),
    FieldPolicy("vsys", FieldTreatment.PSEUDONYMIZE, "context"),
    FieldPolicy("context", FieldTreatment.PSEUDONYMIZE, "context"),
    FieldPolicy("vs_id", FieldTreatment.PSEUDONYMIZE, "context"),
    FieldPolicy("manager", FieldTreatment.PSEUDONYMIZE, "manager"),
    FieldPolicy("name", FieldTreatment.PSEUDONYMIZE, "name"),
    FieldPolicy("username", FieldTreatment.PSEUDONYMIZE, "principal"),
    FieldPolicy("principal", FieldTreatment.PSEUDONYMIZE, "principal"),
    # Credentials, tokens, private keys, session material.
    FieldPolicy("password", FieldTreatment.EXCLUDE),
    FieldPolicy("credential", FieldTreatment.EXCLUDE),
    FieldPolicy("token", FieldTreatment.EXCLUDE),
    FieldPolicy("secret", FieldTreatment.EXCLUDE),
    FieldPolicy("private_key", FieldTreatment.EXCLUDE),
    FieldPolicy("session", FieldTreatment.EXCLUDE),
    FieldPolicy("api_key", FieldTreatment.EXCLUDE),
    # IPs, networks, MACs, domains, serials and fingerprints.
    FieldPolicy("ip", FieldTreatment.NETWORK_SYNTHESIZE),
    FieldPolicy("management_ip", FieldTreatment.NETWORK_SYNTHESIZE),
    FieldPolicy("next_hop", FieldTreatment.NETWORK_SYNTHESIZE),
    FieldPolicy("network", FieldTreatment.NETWORK_SYNTHESIZE),
    FieldPolicy("mac", FieldTreatment.NETWORK_SYNTHESIZE),
    FieldPolicy("domain", FieldTreatment.NETWORK_SYNTHESIZE),
    FieldPolicy("serial", FieldTreatment.PSEUDONYMIZE, "serial"),
    FieldPolicy("fingerprint", FieldTreatment.PSEUDONYMIZE, "fingerprint"),
    # Free text, descriptions, paths, tags, errors, raw command/config text.
    FieldPolicy("description", FieldTreatment.EXCLUDE),
    FieldPolicy("path", FieldTreatment.EXCLUDE),
    FieldPolicy("tags", FieldTreatment.EXCLUDE),
    FieldPolicy("error", FieldTreatment.EXCLUDE),
    FieldPolicy("message", FieldTreatment.EXCLUDE),
    FieldPolicy("raw_command", FieldTreatment.EXCLUDE),
    FieldPolicy("raw_config", FieldTreatment.EXCLUDE),
    # Roles, evidence states, capability states, findings, relationships.
    FieldPolicy("state", FieldTreatment.RETAIN),
    FieldPolicy("data_state", FieldTreatment.RETAIN),
    FieldPolicy("capability_state", FieldTreatment.RETAIN),
    FieldPolicy("role", FieldTreatment.RETAIN),
    FieldPolicy("status", FieldTreatment.RETAIN),
    FieldPolicy("type", FieldTreatment.RETAIN),
    # Times and histories.
    FieldPolicy("timestamp", FieldTreatment.SYNTHESIZE),
    FieldPolicy("collected_at", FieldTreatment.SYNTHESIZE),
    FieldPolicy("expiry", FieldTreatment.SYNTHESIZE),
    # Counts, versions, capacities and topology.
    FieldPolicy("version", FieldTreatment.RETAIN),
    FieldPolicy("count", FieldTreatment.RETAIN),
    FieldPolicy("interfaces", FieldTreatment.RETAIN),
    FieldPolicy("routes", FieldTreatment.RETAIN),
    # Artifact IDs, references and filenames.
    FieldPolicy("artifact_id", FieldTreatment.PSEUDONYMIZE, "artifact"),
    FieldPolicy("filename", FieldTreatment.PSEUDONYMIZE, "artifact"),
    FieldPolicy("reference", FieldTreatment.PSEUDONYMIZE, "artifact"),
)


class PrivacyFidelityPolicy:
    """A versioned, fail-closed policy: classify a field, then apply its
    treatment. Owns no I/O and no run-directory knowledge (slice 2's job)."""

    def __init__(
        self,
        field_policies: tuple[FieldPolicy, ...] = PRIVATE_REPLAY_FIELD_POLICIES,
        *,
        schema_version: str = SCHEMA_VERSION,
        synthesizers: Mapping[str, Any] | None = None,
    ) -> None:
        self.schema_version = schema_version
        self._by_key: dict[str, FieldPolicy] = {fp.key: fp for fp in field_policies}
        #: Optional, explicitly registered per-key synthesis callables for
        #: SYNTHESIZE fields. Unregistered SYNTHESIZE fields raise
        #: ``PolicyCoverageError`` rather than leaking the raw value or
        #: inventing an unreviewed synthetic one (slice 2/3 scope).
        self._synthesizers: dict[str, Any] = dict(synthesizers or {})

    def classify(self, key: str) -> FieldPolicy | None:
        """The policy for ``key``, or ``None`` if this policy does not name
        it -- ``None`` is itself the fail-closed classification, never an
        implicit RETAIN."""
        return self._by_key.get(key)

    def apply(self, key: str, value: Any, tokenizer: Tokenizer, *, path: str = "") -> Any:
        """Transform the value under ``key`` per its classified treatment.

        Raises ``PolicyCoverageError`` -- never returns the raw value --
        for anything unclassified or unimplemented. A container value
        (dict/list) under a non-``RETAIN``, non-``EXCLUDE`` treatment is
        also a coverage error rather than a guess: the frozen document
        warns that sensitive strings occur nested in dictionary keys,
        arrays and embedded structures, "not just leaf values", so this
        policy never silently decides how to reach into an unexpected
        shape -- ``EXCLUDE`` drops the whole subtree (safe for any shape),
        ``RETAIN`` recurses into it (children still get their own
        classification), everything else must already know it is scalar.
        """
        full_path = path or key
        policy = self.classify(key)
        if policy is None:
            raise PolicyCoverageError(full_path, None)
        treatment = policy.treatment

        if isinstance(value, dict):
            if treatment is FieldTreatment.RETAIN:
                return self.transform(value, tokenizer, path=full_path)
            if treatment is FieldTreatment.EXCLUDE:
                return None
            raise PolicyCoverageError(full_path, treatment)

        if isinstance(value, list):
            if treatment is FieldTreatment.RETAIN:
                return [
                    self.transform(item, tokenizer, path=f"{full_path}[]")
                    if isinstance(item, (dict, list))
                    else item
                    for item in value
                ]
            if treatment is FieldTreatment.EXCLUDE:
                return None
            return [
                self._apply_scalar(policy, item, tokenizer, path=f"{full_path}[]")
                for item in value
            ]

        return self._apply_scalar(policy, value, tokenizer, path=full_path)

    def _apply_scalar(self, policy: FieldPolicy, value: Any, tokenizer: Tokenizer, *, path: str) -> Any:
        treatment = policy.treatment
        if treatment is FieldTreatment.RETAIN:
            return value
        if treatment is FieldTreatment.EXCLUDE:
            return None
        if treatment is FieldTreatment.PSEUDONYMIZE:
            return tokenizer.token(policy.pseudonym_domain, value)
        if treatment is FieldTreatment.NETWORK_SYNTHESIZE:
            return tokenizer.network_token(value)
        if treatment is FieldTreatment.DERIVE_SUMMARY:
            return _safe_summary(value)
        if treatment is FieldTreatment.SYNTHESIZE:
            synthesizer = self._synthesizers.get(policy.key)
            if synthesizer is None:
                raise PolicyCoverageError(path, treatment)
            return synthesizer(value)
        raise PolicyCoverageError(path, treatment)  # pragma: no cover -- exhaustive above

    def transform(self, tree: Any, tokenizer: Tokenizer, *, path: str = "$") -> Any:
        """Recursively apply this policy to an arbitrary JSON-shaped tree.

        Every dict key must classify or the whole call fails closed
        (``PolicyCoverageError``) -- this is deliberately stricter than
        ``utils.support_bundle._sanitize_dict``, which retains unrecognized
        keys by default. PRIVATE_REPLAY's fidelity bar requires every field
        to have an explicit, reviewed treatment (acceptance gate 1);
        support_bundle's narrower shareable-diagnostics contract is not
        reused here, only its ``Tokenizer``.
        """
        if isinstance(tree, dict):
            return {
                key: self.apply(key, value, tokenizer, path=f"{path}.{key}")
                for key, value in tree.items()
            }
        if isinstance(tree, list):
            return [self.transform(item, tokenizer, path=f"{path}[]") for item in tree]
        return tree


def _safe_summary(value: Any) -> dict[str, Any]:
    if isinstance(value, str):
        return {"type": "string", "length": len(value)}
    if isinstance(value, (list, tuple)):
        return {"type": "list", "length": len(value)}
    if isinstance(value, dict):
        return {"type": "object", "keys": len(value)}
    return {"type": type(value).__name__}
