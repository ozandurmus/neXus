import json
from pathlib import Path
from typing import Any

from utils.logger import info
from utils.runtime_paths import default_output_root


BASE_DIR = Path(__file__).resolve().parent.parent
OUTPUT_DIR = default_output_root(repository_root=BASE_DIR)

CP_FILE = OUTPUT_DIR / "cp.json"
VSX_FILE = OUTPUT_DIR / "vsx.json"
PAN_FILE = OUTPUT_DIR / "panorama_runtime.json"
UNIFIED_FILE = OUTPUT_DIR / "unified.json"


def load_json(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        info(f">>> SKIP: {path.name} not found")
        return []

    with path.open("r", encoding="utf-8") as file:
        data = json.load(file)

    if isinstance(data, list):
        return data

    if isinstance(data, dict):
        for key in ("devices", "items", "results", "data"):
            value = data.get(key)
            if isinstance(value, list):
                return value

        return [data]

    return []


def normalize_source(value: Any, fallback: str) -> str:
    source = str(value or fallback).strip().lower()

    aliases = {
        "checkpoint": "cp",
        "check point": "cp",
        "pan": "panorama",
        "panorama-runtime": "panorama",
        "panorama_runtime": "panorama",
        "paloalto": "panorama",
        "palo alto": "panorama",
    }

    return aliases.get(source, source)


def normalize_cp(item: dict[str, Any]) -> dict[str, Any]:
    result = dict(item)

    result["source"] = "cp"
    result["device"] = (
        item.get("device")
        or item.get("name")
        or item.get("hostname")
        or ""
    )
    result["vsys"] = item.get("vsys") or "default"

    # CP parser commonly uses routes.
    if "routes" not in result and isinstance(item.get("routing"), list):
        result["routes"] = item["routing"]

    result.setdefault("interfaces", [])
    result.setdefault("routes", [])

    return result


def normalize_vsx(item: dict[str, Any]) -> dict[str, Any]:
    result = dict(item)

    result["source"] = "vsx"
    result["device"] = (
        item.get("device")
        or item.get("name")
        or item.get("hostname")
        or ""
    )
    result["vsys"] = (
        item.get("vsys")
        or item.get("vs_name")
        or item.get("virtual_system")
        or ""
    )

    if "cluster" not in result:
        device = str(result["device"])

        if device.endswith("-1") or device.endswith("-2"):
            result["cluster"] = device[:-2]
        else:
            result["cluster"] = item.get("parent") or ""

    # VSX parser commonly uses routing.
    if "routes" not in result and isinstance(item.get("routing"), list):
        result["routes"] = item["routing"]

    result.setdefault("interfaces", [])
    result.setdefault("routes", [])

    return result


def normalize_panorama(item: dict[str, Any]) -> dict[str, Any]:
    """
    Preserve the real Panorama runtime schema.

    Expected runtime record:
    {
        "source": "panorama",
        "device": "...",
        "serial": "...",
        "interfaces": [...],
        "routes": [...]
    }

    Older records with vr_data are also preserved.
    """
    result = dict(item)

    result["source"] = "panorama"
    result["device"] = (
        item.get("device")
        or item.get("name")
        or item.get("hostname")
        or item.get("serial")
        or ""
    )

    interfaces = item.get("interfaces")
    routes = item.get("routes")

    # Alternative field names, if a runner version used them.
    if not isinstance(interfaces, list):
        interfaces = item.get("interface_data")

    if not isinstance(routes, list):
        routes = item.get("routing")

    result["interfaces"] = (
        interfaces if isinstance(interfaces, list) else []
    )
    result["routes"] = (
        routes if isinstance(routes, list) else []
    )

    # Do not remove vr_data: old PAN output may still depend on it.
    if isinstance(item.get("vr_data"), dict):
        result["vr_data"] = item["vr_data"]

    return result


def run_merge(cp_file=None, vsx_file=None, pan_file=None, unified_file=None) -> None:
    info(">>> MERGE ENGINE START")

    cp_file = Path(cp_file or CP_FILE)
    vsx_file = Path(vsx_file or VSX_FILE)
    pan_file = Path(pan_file or PAN_FILE)
    unified_file = Path(unified_file or UNIFIED_FILE)

    cp_data = load_json(cp_file)
    vsx_data = load_json(vsx_file)
    pan_data = load_json(pan_file)

    merged: list[dict[str, Any]] = []

    merged.extend(normalize_cp(item) for item in cp_data)
    merged.extend(normalize_vsx(item) for item in vsx_data)
    merged.extend(normalize_panorama(item) for item in pan_data)

    unified_file.parent.mkdir(parents=True, exist_ok=True)

    with unified_file.open("w", encoding="utf-8") as file:
        json.dump(
            merged,
            file,
            indent=2,
            ensure_ascii=False,
        )

    cp_interfaces = sum(
        len(item.get("interfaces", []))
        for item in merged
        if item.get("source") == "cp"
    )
    vsx_interfaces = sum(
        len(item.get("interfaces", []))
        for item in merged
        if item.get("source") == "vsx"
    )
    pan_interfaces = sum(
        len(item.get("interfaces", []))
        for item in merged
        if item.get("source") == "panorama"
    )
    pan_routes = sum(
        len(item.get("routes", []))
        for item in merged
        if item.get("source") == "panorama"
    )

    info(
        ">>> MERGE DONE "
        f"({len(merged)} objects | "
        f"CP interfaces: {cp_interfaces} | "
        f"VSX interfaces: {vsx_interfaces} | "
        f"PAN interfaces: {pan_interfaces} | "
        f"PAN routes: {pan_routes})"
    )