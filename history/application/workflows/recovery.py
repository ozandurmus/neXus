"""Recovery / attestation modes.

§5 "recovery/attestation modes" — ``--restore-readiness-check``,
``--recovery-store-check``, ``--recovery-validate``, ``--recovery-collect`` and
``--recovery-attest``. Each entrypoint keeps its own lazy
``from utils.recovery_* import …`` / ``from checkpoint.checkpoint_recovery_* import …``
inside the function body; nothing vendor-bound is imported at module scope
(AC-3). Blocks are moved verbatim from ``main()`` apart from reading shared
state off the passed :class:`~application.context.ApplicationContext` and
rebuilding the ``_admitted`` / ``_runtime_config`` helpers from it.
"""
from __future__ import annotations

import json
from pathlib import Path

from application.services import _require_bootstrap, make_admitted, make_runtime_config


def _load_recovery_attestations(data_root):
    """Read `data/state/recovery_attestations.json` (RB.3a) into an
    `entity_id -> [records]` map for `compute_restore_readiness(attestations=)`.

    A missing, corrupt or malformed file degrades to `{}` ("no attestations"),
    never to an error -- the same fail-safe posture as
    `utils/compliance_history.py` (RB.3a correctness contract item 5)."""
    path = Path(data_root) / "state" / "recovery_attestations.json"
    try:
        doc = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    if not isinstance(doc, dict):
        return {}
    raw = doc.get("attestations")
    if not isinstance(raw, dict):
        return {}
    clean = {}
    for entity_id, records in raw.items():
        if isinstance(records, list):
            clean[str(entity_id)] = [r for r in records if isinstance(r, dict)]
    return clean


def restore_readiness_check(ctx):
    runtime_paths = ctx.runtime_paths
    _require_bootstrap("restore-readiness-check", runtime_paths.output_root)
    from utils.restore_readiness import compute_restore_readiness

    print("=== SECURITYEXPERT RESTORE READINESS — RB.0 ===\n")
    unified_path = runtime_paths.output_root / "unified.json"
    unified_devices = json.loads(unified_path.read_text(encoding="utf-8"))

    # RB.1 (the encrypted recovery store) still does not exist, so
    # recovery_manifests stays empty -- no held artifact is ever reported.
    # RB.3a adds the other §5 input: device-reported attestations from a
    # prior `--recovery-attest` run (data/state/recovery_attestations.json).
    # Absent or corrupt -> {} ("no attestations"), never an error.
    # docs/design/BACKUP_RECOVERY_CONTRACTS.md §5.
    attestations = _load_recovery_attestations(runtime_paths.data_root)
    report = compute_restore_readiness(unified_devices, attestations=attestations)

    state_dir = runtime_paths.data_root / "state"
    state_dir.mkdir(parents=True, exist_ok=True)
    state_path = state_dir / "restore_readiness.json"
    state_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    summary = report["summary"]
    total = sum(summary.values())
    attested_devices = sum(1 for d in report["devices"] if d.get("attested_not_held"))
    print(f"Devices assessed:      {total}")
    for state in ("READY", "STALE", "PARTIAL", "UNPROTECTED", "UNKNOWN"):
        print(f"  {state:<12}       {summary.get(state, 0)}")
    if attestations:
        print(f"  (device-attested)  {attested_devices}")
    if not any(d.get("held_artifacts") for d in report["devices"]):
        print(
            "\nNote: no recovery artifact is held for this fleet (RB.1 recovery "
            "store not yet built) -- UNPROTECTED means 'no held vendor-native "
            "backup', not a fault in this check."
            + (
                " PARTIAL rows here are backed only by a device attestation "
                "(RB.3a): the endpoint says it holds a snapshot/backup we have "
                "not counted; that is weaker than a held, validated artifact and "
                "never reaches READY."
                if attestations else ""
            )
            + " See docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md."
        )
    print(f"\nWritten:                {state_path}")
    print("No network access performed. No recovery artifact was collected.")
    return None


def recovery_store_check(ctx):
    runtime_paths = ctx.runtime_paths
    parser = ctx.parser
    args = ctx.args
    from utils.recovery_store import RecoveryStoreError, get_or_create_vault_key, list_artifact_dirs
    from utils.recovery_retention import read_ledger
    from utils.runtime_paths import RuntimePathError, resolve_recovery_root

    print("=== SECURITYEXPERT RECOVERY-PLANE STORE CHECK — RB.1 ===\n")
    try:
        recovery_paths = resolve_recovery_root(
            args.recovery_root, runtime_root=runtime_paths.runtime_root
        )
    except RuntimePathError as exc:
        parser.error(str(exc))
    try:
        _, vault_key_id = get_or_create_vault_key(
            runtime_paths.data_root, recovery_paths.recovery_root
        )
    except RecoveryStoreError as exc:
        print("Gate:                    ERROR")
        print(f"Reason:                  {exc}")
        raise SystemExit(2)

    artifact_dirs = list_artifact_dirs(recovery_paths)
    ledger = read_ledger(recovery_paths)

    print(f"Recovery root:           {recovery_paths.recovery_root}")
    print(f"Vault key id:            {vault_key_id}")
    print(f"Artifacts held:          {len(artifact_dirs)}")
    print(f"Retention deletions:     {len(ledger)}")
    if not artifact_dirs:
        print(
            "\nNote: the store is initialized but empty -- run --recovery-collect "
            "--recovery-vendor panorama to collect a PAN device-state artifact "
            "(CP Gaia backup remains blocked; see "
            "docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md)."
        )
    print("\nGate:                    PASS")
    print("No network access performed. No artifact bytes, key material or vault key path was printed.")
    return None


def recovery_validate(ctx):
    runtime_paths = ctx.runtime_paths
    parser = ctx.parser
    args = ctx.args
    from utils.recovery_store import (
        RecoveryStoreError, get_or_create_vault_key, list_artifact_dirs,
        read_manifest, revalidate_artifact,
    )
    from utils.runtime_paths import RuntimePathError, resolve_recovery_root

    print("=== SECURITYEXPERT RECOVERY ARTIFACT VALIDATION — RB.4 ===\n")
    try:
        recovery_paths = resolve_recovery_root(
            args.recovery_root, runtime_root=runtime_paths.runtime_root
        )
    except RuntimePathError as exc:
        parser.error(str(exc))
    try:
        vault_key, _ = get_or_create_vault_key(runtime_paths.data_root, recovery_paths.recovery_root)
    except RecoveryStoreError as exc:
        print("Gate:                    ERROR")
        print(f"Reason:                  {exc}")
        raise SystemExit(2)

    unified_path = runtime_paths.output_root / "unified.json"
    if unified_path.is_file():
        unified_devices = json.loads(unified_path.read_text(encoding="utf-8"))
    else:
        unified_devices = []
        print(
            "Note: no local unified.json -- every V3 semantic check will report "
            "NOT_APPLICABLE rather than PASS (frozen rule 3, contract §4).\n"
        )

    artifact_dirs = list_artifact_dirs(recovery_paths)
    verdict_counts: dict[str, int] = {}
    artifacts_with_a_failed_check = 0
    for artifact_dir in artifact_dirs:
        manifest = read_manifest(artifact_dir)
        updated = revalidate_artifact(
            artifact_dir, manifest, vault_key=vault_key, unified_devices=unified_devices
        )
        validation = updated["validation"]
        verdict_counts[validation["verdict"]] = verdict_counts.get(validation["verdict"], 0) + 1
        # The gate must not rely on `verdict` alone: verdict reflects the
        # highest level fully passed (e.g. a V2-only failure still
        # reports INTACT, since V1 passed) -- an operator needs to know
        # about ANY failed check, not only a total V1 failure.
        if any(c["result"] == "FAIL" for c in validation["checks"]):
            artifacts_with_a_failed_check += 1

    print(f"Artifacts validated:     {len(artifact_dirs)}")
    for verdict in ("RESTORE_PROVEN", "CONSISTENT", "WELL_FORMED", "INTACT", "FAILED"):
        if verdict_counts.get(verdict):
            print(f"  {verdict:<16}     {verdict_counts[verdict]}")
    print(f"Artifacts with a finding: {artifacts_with_a_failed_check}")
    if not artifact_dirs:
        print("\nNote: the store holds no artifacts yet -- nothing to validate.")

    print(f"\nGate:                    {'FAIL' if artifacts_with_a_failed_check else 'PASS'}")
    print("No network access performed. No artifact bytes or key material was printed.")
    raise SystemExit(1 if artifacts_with_a_failed_check else 0)


def recovery_collect(ctx):
    runtime_paths = ctx.runtime_paths
    parser = ctx.parser
    args = ctx.args
    provenance = ctx.provenance
    admission_run_context = ctx.admission_run_context
    _admitted = make_admitted(ctx)
    _runtime_config = make_runtime_config(ctx)

    _require_bootstrap("recovery-collect", runtime_paths.output_root)
    from utils.recovery_collect import (
        RecoveryCollectionError,
        RecoveryCollectionRequest,
        run_recovery_collection,
    )
    from utils.recovery_store import (
        RecoveryStoreError,
        get_or_create_vault_key,
        list_artifact_dirs,
        read_manifest,
    )
    from utils.runtime_paths import RuntimePathError, resolve_recovery_root

    print(f"=== SECURITYEXPERT RECOVERY COLLECTION — {args.recovery_vendor} ===\n")
    try:
        recovery_paths = resolve_recovery_root(
            args.recovery_root, runtime_root=runtime_paths.runtime_root
        )
    except RuntimePathError as exc:
        parser.error(str(exc))
    try:
        vault_key, vault_key_id = get_or_create_vault_key(
            runtime_paths.data_root, recovery_paths.recovery_root
        )
    except RecoveryStoreError as exc:
        print("Gate:                    ERROR")
        print(f"Reason:                  {exc}")
        raise SystemExit(2)

    unified_devices = json.loads(
        (runtime_paths.output_root / "unified.json").read_text(encoding="utf-8")
    )

    if args.recovery_gateways:
        entity_ids = [g.strip() for g in args.recovery_gateways.split(",") if g.strip()]
        selector = {"mode": "targets", "entity_ids": entity_ids}
    else:
        selector = {"mode": "all"}
    request = RecoveryCollectionRequest(
        vendor=args.recovery_vendor, selector=selector, provenance=provenance,
    )

    def _run_under_admission(entity_id, operation):
        return _admitted(budget_vendor, f"recovery-{args.recovery_vendor}", entity_id, operation)

    try:
        if args.recovery_vendor == "panorama":
            from panorama.panorama_recovery_collector import PanDeviceStateCollector
            from panorama.panorama_runtime_runner import _tls_verify_setting

            cfg = _runtime_config(require_cp=False, require_panorama=True)
            collector = PanDeviceStateCollector(cfg, verify=_tls_verify_setting())
            budget_vendor = "paloalto"
        else:
            # RB.3b step 6: the distinct backup credential (D4 / B11) is
            # resolved in the constructor and fails closed if absent --
            # refusing the whole CP request before target selection. Ledger
            # + store binding, the platform gate and the prior-backup-size
            # lookup are wired here.
            from checkpoint.checkpoint_recovery_collector import (
                ARTIFACT_CLASS as _CP_BACKUP_ARTIFACT_CLASS,
                CheckpointGaiaBackupCollector,
                is_vsx_virtual_system,
            )
            from utils.recovery_manifest import RecoveryManifestError
            from utils.recovery_operational_ledger import RecoveryOperationalLedger

            # B7 -- a VSX virtual system is never a backup target; refused
            # here as a clean parser.error before admission, in addition to
            # (not instead of) the collector's own precheck() refusal.
            if args.recovery_gateways:
                vsx_targets = sorted(e for e in entity_ids if is_vsx_virtual_system(e))
                if vsx_targets:
                    parser.error(
                        "--recovery-gateways: a VSX virtual system is never a "
                        "backup target (contract §7.3 point 3): "
                        + ", ".join(vsx_targets)
                    )

            # AC-9 platform gate input -- the same discovery-lifecycle
            # platform-family classification --recovery-attest uses above,
            # propagated into cp_config_telemetry.json by a prior
            # --cp-config-collect run. Absent -> no entity is excluded here
            # (the collector treats an unknown platform as supported).
            platform_by_entity: dict[str, str] = {}
            ct_path = runtime_paths.output_root / "cp_config_telemetry.json"
            if ct_path.exists():
                try:
                    ct_doc = json.loads(ct_path.read_text(encoding="utf-8"))
                    for dev in ct_doc.get("devices", []) or []:
                        eid = dev.get("entity_id")
                        fam = (dev.get("platform") or {}).get("family")
                        if eid and fam:
                            platform_by_entity[str(eid)] = str(fam)
                except (OSError, ValueError):
                    platform_by_entity = {}

            # B8 / §7.7 -- the largest prior cp_gaia_backup per entity, read
            # from the recovery store's own manifests (the ledger records
            # execution outcomes, not artifact size).
            prior_backup_sizes_by_entity: dict[str, list[int]] = {}
            for artifact_dir in list_artifact_dirs(recovery_paths, vendor="checkpoint"):
                try:
                    manifest = read_manifest(artifact_dir)
                except (OSError, ValueError, RecoveryStoreError, RecoveryManifestError):
                    continue
                if (manifest.get("artifact") or {}).get("class") != _CP_BACKUP_ARTIFACT_CLASS:
                    continue
                eid = (manifest.get("device") or {}).get("entity_id")
                size = (manifest.get("artifact") or {}).get("plaintext_bytes")
                if eid and isinstance(size, int) and size > 0:
                    prior_backup_sizes_by_entity.setdefault(str(eid), []).append(size)

            collector = CheckpointGaiaBackupCollector(
                ledger=RecoveryOperationalLedger.from_data_root(runtime_paths.data_root),
                recovery_paths=recovery_paths,
                vault_key=vault_key,
                vault_key_id=vault_key_id,
                run_id=admission_run_context.run_id if admission_run_context else None,
                platform_by_entity=platform_by_entity,
                prior_backup_sizes_by_entity=prior_backup_sizes_by_entity,
            )
            budget_vendor = "checkpoint"

        result = run_recovery_collection(
            request,
            unified_devices=unified_devices, collector=collector,
            recovery_paths=recovery_paths, vault_key=vault_key, vault_key_id=vault_key_id,
            run_under_admission=_run_under_admission,
        )
    except RecoveryCollectionError as exc:
        parser.error(str(exc))

    print(f"Targets:                 {len(result.outcomes)}")
    print(f"Collected:               {result.collected_count}")
    print(f"Skipped (already fresh): {result.skipped_count}")
    print(f"Failed/blocked:          {result.failed_count}")
    for outcome in result.outcomes:
        if outcome.status != "collected":
            print(f"  {outcome.entity_id}: {outcome.status} -- {outcome.error}")
    print(f"\nGate:                    {'PASS' if result.failed_count == 0 else 'FAIL'}")
    raise SystemExit(0 if result.failed_count == 0 else 1)


def recovery_attest(ctx):
    runtime_paths = ctx.runtime_paths
    parser = ctx.parser
    args = ctx.args
    provenance = ctx.provenance
    _admitted = make_admitted(ctx)
    _runtime_config = make_runtime_config(ctx)

    _require_bootstrap("recovery-attest", runtime_paths.output_root)
    from datetime import datetime, timezone

    from checkpoint.checkpoint_recovery_attestation import CheckpointRecoveryAttester
    from utils.recovery_collect import (
        RecoveryCollectionError,
        RecoveryCollectionRequest,
        run_recovery_attestation,
    )

    print("=== SECURITYEXPERT RECOVERY ATTESTATION — checkpoint ===\n")
    unified_devices = json.loads(
        (runtime_paths.output_root / "unified.json").read_text(encoding="utf-8")
    )

    if args.recovery_gateways:
        entity_ids = [g.strip() for g in args.recovery_gateways.split(",") if g.strip()]
        selector = {"mode": "targets", "entity_ids": entity_ids}
    else:
        selector = {"mode": "all"}
    request = RecoveryCollectionRequest(
        vendor="checkpoint", selector=selector, provenance=provenance,
    )

    # A8 platform gate input: the discovery-lifecycle platform-family
    # classification, as propagated into cp_config_telemetry.json by a
    # prior --cp-config-collect run. Absent -> every endpoint is treated
    # as a supported/unknown platform and attested normally (A8: an
    # unknown platform is not a reason to skip a read-class command).
    platform_by_entity: dict[str, str] = {}
    ct_path = runtime_paths.output_root / "cp_config_telemetry.json"
    if ct_path.exists():
        try:
            ct_doc = json.loads(ct_path.read_text(encoding="utf-8"))
            for dev in ct_doc.get("devices", []) or []:
                eid = dev.get("entity_id")
                fam = (dev.get("platform") or {}).get("family")
                if eid and fam:
                    platform_by_entity[str(eid)] = str(fam)
        except (OSError, ValueError):
            platform_by_entity = {}

    cfg = _runtime_config(require_cp=True, require_panorama=False)
    try:
        attester = CheckpointRecoveryAttester(cfg, platform_by_entity=platform_by_entity)

        def _attest_under_admission(entity_id, operation):
            return _admitted("checkpoint", "recovery-attest-cp", entity_id, operation)

        try:
            result = run_recovery_attestation(
                request,
                unified_devices=unified_devices,
                attester=attester,
                run_under_admission=_attest_under_admission,
            )
        except RecoveryCollectionError as exc:
            parser.error(str(exc))
    finally:
        cfg.clear_credentials()

    state_dir = runtime_paths.data_root / "state"
    state_dir.mkdir(parents=True, exist_ok=True)
    state_path = state_dir / "recovery_attestations.json"
    state_path.write_text(
        json.dumps(
            {
                "schema": "securityexpert-recovery-attestations-v1",
                "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                "attestations": result.as_attestation_map(),
            },
            indent=2, ensure_ascii=False,
        ) + "\n",
        encoding="utf-8",
    )

    print(f"Physical endpoints:      {len(result.outcomes)}")
    print(f"Attested:                {result.attested_count}")
    print(f"Failed:                  {result.failed_count}")
    for outcome in result.outcomes:
        if outcome.status != "attested":
            detail = f" -- {outcome.error}" if outcome.error else ""
            print(f"  {outcome.entity_id}: {outcome.status}{detail}")
    print(f"\nWritten:                 {state_path}")
    print(f"Gate:                    {'PASS' if result.failed_count == 0 else 'FAIL'}")
    print(
        "No recovery artifact was collected. No backup or snapshot name was "
        "recorded; records carry {class, age_days, source} only."
    )
    raise SystemExit(0 if result.failed_count == 0 else 1)
