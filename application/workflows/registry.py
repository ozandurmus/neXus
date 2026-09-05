"""PCP.1 Device Registry maintenance-class CLI workflows.

Thin dispatch only: parses CLI intent (tag KEY=VALUE parsing, output
formatting) and calls the actual registry logic in
``utils/device_registry.py``. No device contact, no vendor/collector
import, no credential resolution (AC-6) -- docs/design/
PRODUCT_CONTROL_PLANE_ARCHITECTURE.md section 21 is the contract.
"""
from __future__ import annotations


def _parse_tags(parser, raw_tags):
    tags = {}
    for item in raw_tags or []:
        if "=" not in item:
            parser.error(f"invalid --registry-tag {item!r}: expected KEY=VALUE")
        key, _, value = item.partition("=")
        tags[key.strip()] = value.strip()
    return tags


def registry_enroll(ctx):
    from utils.device_registry import DeviceRegistry, DeviceRegistryError, DeviceRegistryLockError

    args = ctx.args
    print("=== SECURITYEXPERT DEVICE REGISTRY — MANUAL ENROLLMENT (PCP.1) ===\n")
    registry = DeviceRegistry(ctx.runtime_paths.data_root)
    tags = _parse_tags(ctx.parser, args.registry_tag)
    try:
        record = registry.enroll(
            endpoint=args.registry_endpoint,
            vendor_hint=args.registry_vendor_hint,
            credential_ref=args.registry_credential_profile,
            tags=tags,
        )
    except DeviceRegistryLockError as exc:
        print(f"Refused (lock contention): {exc}")
        raise SystemExit(2)
    except DeviceRegistryError as exc:
        print(f"Refused: {exc}")
        raise SystemExit(1)
    print(f"Enrolled device_id: {record.device_id}")
    print(f"State:              {record.state}")
    print(f"Vendor:             {record.vendor} (basis={record.classification_basis})")
    print(f"Tag keys:           {sorted(record.tags.keys())}")
    print("\nNo device contact was performed. No credential was resolved.")
    return None


def registry_list(ctx):
    from utils.device_registry import DeviceRegistry, DeviceRegistryError

    args = ctx.args
    print("=== SECURITYEXPERT DEVICE REGISTRY — LIST (PCP.1) ===\n")
    registry = DeviceRegistry(ctx.runtime_paths.data_root)
    try:
        records = registry.list()
    except DeviceRegistryError as exc:
        print(f"Refused: {exc}")
        raise SystemExit(1)
    if not records:
        print("No enrolled devices.")
        return None
    for record in records:
        line = (
            f"{record.device_id}  vendor={record.vendor}  state={record.state}  "
            f"tags={sorted(record.tags.keys())}"
        )
        if args.show_endpoints:
            endpoint = record.endpoint if record.port is None else f"{record.endpoint}:{record.port}"
            line += f"  endpoint={endpoint}"
        print(line)
    return None


def registry_disable(ctx):
    from utils.device_registry import DeviceRegistry, DeviceRegistryError, DeviceRegistryLockError

    args = ctx.args
    print("=== SECURITYEXPERT DEVICE REGISTRY — DISABLE (PCP.1) ===\n")
    registry = DeviceRegistry(ctx.runtime_paths.data_root)
    try:
        record, already_disabled = registry.disable(args.registry_disable)
    except DeviceRegistryLockError as exc:
        print(f"Refused (lock contention): {exc}")
        raise SystemExit(2)
    except DeviceRegistryError as exc:
        print(f"Refused: {exc}")
        raise SystemExit(1)
    if already_disabled:
        print(f"device_id {record.device_id} was already DISABLED (no change).")
    else:
        print(f"device_id {record.device_id} is now DISABLED.")
    return None
