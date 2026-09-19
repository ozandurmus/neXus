# Architectural review verdict

**[REJECTED] — as an implementation/freeze candidate in its current form.**

The dedicated-service direction is permitted, but the draft is not yet contract-complete, privacy-safe, schema-compatible, or operationally deployable. This rejects the present specification—not the Product Owner’s goal.

## Priority findings

| Severity | Finding | Required resolution |
|---|---|---|
| Critical | The deployable-service contract leaves authentication placement, data ownership, inter-service protocol, and build/release unresolved. | Freeze a successor contract resolving those decisions for `ui2-configuration`. |
| Critical | Configuration is not an approved `aiview` surface under frozen C9. | Perform a field-classification movement and adversarial leak testing before exposing any configuration endpoint. |
| Critical | `rawConfigContent` as a JSON `String` contradicts PAN’s mandatory streaming semantics and creates a new raw-evidence transport boundary. | Use a bounded, authenticated streaming protocol; never expose `/parse` to browsers or ingress. |
| Critical | Existing Java worker code already owns CP parsing, PAN streaming, deviation calculation, artefact writing, and persistence. | Define an explicit ownership migration; do not create parallel implementations. |
| High | The proposed models cannot be stored in the existing schema. | Add a governed migration or remove persistent AST/highlight claims. |
| High | The claimed Python “1:1” behavior contains concrete mismatches. | Establish golden differential fixtures and resolve the mismatches below. |
| High | The proposed K3s manifest violates existing deployment requirements. | Replace `:latest`, add complete security/resource/probe/network configuration, and define the image build. |

## 1. Service and SPI design

A dedicated capability service is architecturally permitted by DS-1, but adding one must be a contract decision. The governing decision explicitly leaves `AUTH-PLACEMENT`, `DATA-OWNERSHIP`, `BUILD-AND-RELEASE`, and `INTER-SERVICE-BOUNDARY` unresolved and prohibits a second authenticated surface until authentication placement is settled ([decision record](/Users/OzanDur/Codo/nexus/docs/design/PO_DECISION_RECORD_2026_09_13D_INDEPENDENTLY_DEPLOYABLE_SERVICES.md:35)).

The smallest viable boundary is:

- `ui2-configuration` is an internal, stateless parser service.
- It has no browser route, ingress, device credentials, SSH/API transport, artefact-store mount, or database credentials.
- `ui2-worker` remains the sole device-contact, artefact-writing, change-detection, and persistence owner.
- `ui2-service` continues to be the only authenticated operator surface.
- The worker streams the one collected response simultaneously to the encrypted artefact sink and the internal parser—never performing a second device read.

The proposed SPI should be reduced:

```java
interface VendorConfigParser {
    ConfigParseResult parse(ConfigFormat format, InputStream content);
}
```

Recommended changes:

- Replace arbitrary `vendor`, `format`, and `entityType` strings with closed enums, reusing existing `ConfigurationVendor` and read-kind vocabulary.
- Remove `supports(...)`; register parsers by an enum key and fail on missing or duplicate registration.
- Remove `computeDeviation(...)`; the existing [`IndexDeviationComputer`](/Users/OzanDur/Codo/nexus/ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/configuration/IndexDeviationComputer.java:29) already computes deviation from persisted history.
- Remove `deviceId` and `contextRef` from the pure parser input unless parsing genuinely depends on them.
- Remove `Map<String,Object> metadata`; it is an unclassified side channel for raw or identity-bearing content.
- Keep `rawLineKey` internal. Python removes `_key` before presentation ([collector](/Users/OzanDur/Codo/nexus/configuration/checkpoint_config_collector.py:1901)).
- Move `memberSpecific` out of the parser. Python establishes it only after comparing independently collected cluster members ([collector](/Users/OzanDur/Codo/nexus/configuration/checkpoint_config_collector.py:1864)).
- Add explicit `schemaVersion`, `parserVersion`, outcome/status, and incomplete/unsupported/error states.

`pan_os_set` must be removed. Frozen CG-4 authorizes PAN XML requests, not a CLI-set configuration source ([14G contract](/Users/OzanDur/Codo/nexus/docs/design/PO_DECISION_RECORD_2026_09_14G_CONFIGURATION_COLLECTION_MEASURED_FORMS.md:35)).

## 2. Check Point 1:1 fidelity

The regex constants in the draft match the current Python implementation closely, but the overall claim of byte-for-byte semantic equivalence is presently false.

Specific differences:

- Python calls `.strip()`, not merely trailing-whitespace removal ([collector](/Users/OzanDur/Codo/nexus/configuration/checkpoint_config_collector.py:198)).
- Python returns no canonical hash when there are no canonical `set` lines; the proposed non-null `String` model does not define this failure state.
- Python emits an exact four-line sanitized header containing schema, canonical hash, and withheld count ([collector](/Users/OzanDur/Codo/nexus/configuration/checkpoint_config_collector.py:212)).
- Withheld lines do not enter Python’s safe AST/sections. The specification does not state whether `index.entryCount` counts raw, safe, or displayed lines.
- The existing Java implementation currently counts sections before withholding secrets and simply omits secret lines from sanitized text ([current Java processor](/Users/OzanDur/Codo/nexus/ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/configuration/cp/CheckPointGaiaConfigProcessor.java:44)). Migrating to Python semantics is therefore an observable behavior change.
- The proposed `services` mapping is not present in Python’s `_section_for`; `snmptrap` currently falls into `other` ([collector](/Users/OzanDur/Codo/nexus/configuration/checkpoint_config_collector.py:267)). The Python acceptance test explicitly records that CP has no services section ([test](/Users/OzanDur/Codo/nexus/tests/test_phase0_7_2_compliance_followups.py:244)).
- Check Point configuration is host-level under CG-1. A CP `vsx_context` parse mode would contradict the frozen “one read per host, no per-VS repetition” rule ([14G contract](/Users/OzanDur/Codo/nexus/docs/design/PO_DECISION_RECORD_2026_09_14G_CONFIGURATION_COLLECTION_MEASURED_FORMS.md:15)).

Required acceptance evidence is a shared golden corpus executed against Python and Java, comparing exact hashes, sanitized text, counts, sections, settings, highlights, and error outcomes. It should cover:

- CRLF and mixed newline forms;
- selector echoes and mixed-case selector commands;
- empty or header-only output;
- duplicate and reordered lines;
- quoted values and malformed shell quoting;
- every safe password-control knob;
- banner/MOTD variants;
- generic-key and expanded secret patterns;
- secret-only changes;
- member comparison as a separate reconciliation step.

## 3. Worker, service, persistence, and privacy

### Existing implementation ownership

The worker already contains:

- Check Point processing;
- streaming PAN XML parsing with DTD and external entities disabled ([PAN processor](/Users/OzanDur/Codo/nexus/ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/configuration/pan/PaloAltoConfigStreamProcessor.java:19));
- one-read execution and encrypted artefact handling;
- change-state and deviation calculation;
- audited persistence.

The draft must describe a staged extraction from those classes, including rollback and version-skew compatibility, rather than introducing a second active parsing path.

### Schema compatibility

The present database stores:

- run metadata and sanitized text;
- index counts;
- override paths;
- deviation summaries.

It does not store settings, sections, highlights, total-setting counts, or arbitrary metadata ([V16 migration](/Users/OzanDur/Codo/nexus/ui2/service/src/main/resources/db/migration/V16__device_configuration.sql:48), [V22 migration](/Users/OzanDur/Codo/nexus/ui2/service/src/main/resources/db/migration/V22__configuration_deviation_summary.sql:20)).

Therefore, the draft is not schema-compatible as claimed. The minimal extension would be one versioned, sanitized projection column or child record associated with the run. It must:

- be written in the same audited transaction as the run/index rows;
- contain no internal `rawLineKey`;
- carry an explicit projection schema version;
- be classified for audit and `aiview`;
- never replace the encrypted raw artefact or its independent byte hash.

The worker should remain the only database writer. Allowing both worker and configuration service to write the same run would split transaction ownership and create duplicate/partial-run failure modes.

### `aiview`

Frozen C9 originally approved only two device routes and requires each later surface—including `ConfigurationController`—to receive its own field-classification pass ([C9 contract](/Users/OzanDur/Codo/nexus/docs/design/UI2_0_C9_PRODUCTION_REPLAY_ROLE_CONTRACT.md:124), [expansion sequence](/Users/OzanDur/Codo/nexus/docs/design/UI2_0_C9_PRODUCTION_REPLAY_ROLE_CONTRACT.md:283)).

The current generic response advice is not sufficient proof:

- It marks every controller response as supported.
- Free-form hostname masking only replaces identities previously registered in in-memory caches ([pseudonymizer](/Users/OzanDur/Codo/nexus/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/privacy/TopologyNamePseudonymizer.java:99)).
- It deliberately preserves `device_id`, contrary to C9’s frozen pseudonymization rule ([advice](/Users/OzanDur/Codo/nexus/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/privacy/PrivacyMaskingResponseBodyAdvice.java:195)).
- No privacy test covers configuration settings, contexts, override paths, `panorama_source`, sanitized text, or canonical hashes.
- The plain-text configuration route cannot safely depend on another response having previously populated hostname caches.

Before configuration is enabled for `aiview`, classify every output field, including:

- device/context identifiers;
- setting values;
- override element paths and Panorama source labels;
- canonical hashes embedded in JSON and sanitized text;
- sanitized free-form text;
- errors and parser diagnostics.

Unknown fields must cause whole-surface refusal, not best-effort recursive masking.

## 4. K3s feasibility

The service is feasible on K3s, but the proposed deployment is not deployable yet. The named [`54-configuration-deployment.yaml`](/Users/OzanDur/Codo/nexus/deploy/ui2) does not exist.

Required corrections:

- Never use `nexus/ui2-configuration:latest`; immutable digest or commit-derived tags are mandatory.
- Define a reproducible in-cluster/CI image build rather than “Kaniko or Gradle.”
- Add CPU and memory limits as well as requests.
- Treat the proposed 100m/256Mi values as unverified until representative parsing measurements exist.
- Reuse the simple `/healthz` approach unless Actuator provides a demonstrated need; Actuator is not currently an installed dependency.
- Add startup, readiness, and liveness behavior with truthful failure states.
- Use `runAsNonRoot`, read-only root filesystem, dropped capabilities, `RuntimeDefault` seccomp, and declared writable `emptyDir` paths.
- Use ClusterIP only, with no ingress.
- Disable service-account token mounting unless needed.
- Add service-to-service authentication and a NetworkPolicy allowing only the worker to reach port 8084.
- Enforce body-size, parse-time, concurrency, and response-size bounds.
- Start with one replica and no autoscaling until measured behavior supports otherwise.

These requirements follow the established deployment construction rules ([deployment contract](/Users/OzanDur/Codo/nexus/docs/design/UI2_0_B1_01C_CONTAINER_IMAGE_AND_KUBERNETES_DEPLOYMENT_CONTRACT.md:340)).

## Recommended refinement set

A revised contract should freeze these decisions:

1. Internal stateless parsing service; worker remains device, artefact, history, and DB owner.
2. Streaming binary input, typed enums, bounded request, authenticated worker-only access.
3. No `/deviation` service endpoint; reuse existing deviation computation.
4. Characterization-based Check Point port with explicitly resolved `services` and empty-output semantics.
5. Reuse the existing StAX PAN processor in Phase 2; no `pan_os_set`.
6. Versioned sanitized projection persistence through one additive migration.
7. Configuration-specific C9 field classification and fail-closed adversarial tests.
8. Immutable, restricted-v2-compatible image and manifest with no external route.
9. Explicit compatibility and rollback policy during worker-to-service ownership migration.

## SESSION CLOSE

Read-only architectural review completed. No source, configuration, project state, Git history, deployment, device, credential, or production data was changed. No tests were run; conclusions are based on frozen contracts, current source, migrations, tests, and manifests. The worktree remains on `main` with the already-present untracked architecture draft and consultation script.

Next movement: `CONTRACT`, high reasoning, preferably in a fresh session after the refinement decisions are accepted. Recommended lane: `feature/ui2-configuration-contract`. Merge to `main` is blocked until the successor contract resolves the critical findings; no Git dispatch commands are recommended at this stage. There is no `main.py`, UI, or runtime effect from this review.

