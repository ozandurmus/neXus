"""UI 2.0 extraction tooling -- Private Replay tokenizer over a capture
directory, emitting a C6-layout fixture set.

Implements ``docs/design/UI2_0_C6_CAPABILITY_EXTRACTION_CONTRACT.md`` section
3 (the fixture-generation procedure) and closes its open item 1: a
shape-preserving encoding on top of ``utils.support_bundle.Tokenizer``'s
existing hex-token primitive (``Tokenizer.shaped_token``, added alongside
this script), so a serial's length/character class survives tokenization
instead of collapsing to the generic ``SERIAL_<14 hex>`` shape.

This is Line-1 Python, run by a human/agent at extraction time only -- it
never runs inside the UI 2.0 (Java) runtime, contacts no device, opens no
network connection, and reads no live credential (RUNTIME-DIRECTION P-1).
It operates on an already-captured evidence/capture directory only.

Usage (the CLI shape B1-5 actually runs)::

    python scripts/ui2_extract_fixtures.py \\
        --capture-dir /path/to/evidence_capture \\
        --output-dir ui2/fixtures/cp_gaia_inventory_show_version_ha_state \\
        --capability-id cp_gaia_inventory_show_version_ha_state \\
        --command-tuple "show version all" \\
        --vendor-version R81.20 \\
        --capture-date 2026-09-01 \\
        --capture-session-id S1

Every ``*.json`` file directly under ``--capture-dir`` becomes one fixture
file under ``--output-dir``, sanitized through the same
``FBUDDY_SUPPORT_HASH_KEY``-keyed ``Tokenizer`` session, so identifiers
repeated across files tokenize identically (cross-output identity
equality, C6 section 3.3). A capture session spanning more than one
command (e.g. FIRST-CAPABILITY's ``show version all`` + ``cphaprob stat``)
runs this tool once per command with the same ``--capture-session-id`` --
the tokenizer key is persisted (``data/.support_hmac.key`` by default, or
``FBUDDY_SUPPORT_HASH_KEY``), so identity equality holds across separate
invocations too, not only within one.

The DLP privacy gate (``utils.repository_privacy.scan_repository``, the
same offline scanner ``main.py --repository-privacy-check`` runs) is
mandatory and blocking (C6 section 3.5): fixtures are written to a
temporary staging directory first, scanned, and only copied into
``--output-dir`` on a clean scan. A finding refuses the write entirely and
exits non-zero -- there is no partial-write, no "commit anyway" path.
"""
from __future__ import annotations

import argparse
import json
import shutil
import sys
import tempfile
from pathlib import Path
from typing import Any

from utils.repository_privacy import RepositoryPrivacyError, PrivacyReport, scan_repository
from utils.support_bundle import SENSITIVE_KEYS, Tokenizer, _get_support_key

FIXTURE_KINDS = ("REAL", "SYNTHETIC", "DERIVED")

#: Capability-specific field names the general support-bundle map (SENSITIVE_
#: KEYS) does not already name (C6 section 3.1: "the extraction-tooling
#: movement extends this driver with the specific field names each
#: capability's raw output actually carries"). All route to the "serial"
#: pseudonym domain, shape-preserved (open item 1).
EXTRACTION_SENSITIVE_KEYS = {
    "appliance_serial": "serial",
    "serial_number": "serial",
    "sn": "serial",
}

#: Kinds whose real-world value has a length/character-class shape worth
#: preserving through tokenization (C6 section 3.3, "serial length/shape").
SHAPE_PRESERVED_KINDS = {"serial"}

#: Credential-shaped field names are excluded outright -- never read into a
#: fixture, tokenized or not. This is a code-level guarantee independent of
#: the DLP gate backstop below (AC-8: "never reads a credential-shaped
#: input").
CREDENTIAL_SHAPED_KEYS = {
    "password", "passwd", "secret", "credential", "credentials",
    "token", "api_key", "apikey", "private_key", "access_token",
    "session", "session_id", "psk", "community",
}


class FixtureDLPRefusalError(RuntimeError):
    """Raised instead of writing a fixture set that fails the DLP gate.

    Carries the ``PrivacyReport`` so a caller can report rule/location
    without ever surfacing a matched value (same posture as
    ``utils.repository_privacy`` itself).
    """

    def __init__(self, report: PrivacyReport):
        self.report = report
        super().__init__(f"fixture set failed the repository privacy gate: {len(report.findings)} finding(s)")


def _sanitize_value(key: str, value: Any, tok: Tokenizer) -> Any:
    if key.lower() in CREDENTIAL_SHAPED_KEYS:
        return None
    if key == "network":
        return tok.network_token(value)
    kind = SENSITIVE_KEYS.get(key) or EXTRACTION_SENSITIVE_KEYS.get(key)
    if kind:
        if kind in SHAPE_PRESERVED_KINDS:
            return tok.shaped_token(kind, value)
        return tok.token(kind, value)
    if isinstance(value, dict):
        return _sanitize_dict(value, tok)
    if isinstance(value, list):
        return [_sanitize_value("", item, tok) for item in value]
    return value


def _sanitize_dict(data: dict[str, Any], tok: Tokenizer) -> dict[str, Any]:
    return {key: _sanitize_value(key, value, tok) for key, value in data.items()}


def _sanitize_payload(data: Any, tok: Tokenizer) -> Any:
    if isinstance(data, dict):
        return _sanitize_dict(data, tok)
    if isinstance(data, list):
        return [_sanitize_value("", item, tok) for item in data]
    return data


def extract_fixtures(
    capture_dir: Path,
    output_dir: Path,
    *,
    capability_id: str,
    command_tuple: list[str],
    vendor_version: str,
    capture_date: str,
    capture_session_id: str,
    fixture_kind: str = "REAL",
    derived_from: str | None = None,
    derived_change: str | None = None,
    synthetic_reason: str | None = None,
    support_key_file: Path | None = None,
) -> list[Path]:
    """Read every ``*.json`` file under ``capture_dir``, tokenize it, and
    write one C6-layout fixture file per input into ``output_dir``.

    Returns the list of fixture files actually written. Raises
    ``FixtureDLPRefusalError`` (with the offending ``PrivacyReport``
    attached) instead of writing anything into ``output_dir`` when the
    staged fixture set fails the repository privacy gate.
    """
    if fixture_kind not in FIXTURE_KINDS:
        raise ValueError(f"fixture_kind must be one of {FIXTURE_KINDS}, got {fixture_kind!r}")
    if fixture_kind == "DERIVED" and not (derived_from and derived_change):
        raise ValueError("DERIVED fixtures require --derived-from and --derived-change")
    if fixture_kind == "SYNTHETIC" and not synthetic_reason:
        raise ValueError("SYNTHETIC fixtures require --synthetic-reason")

    capture_dir = Path(capture_dir)
    output_dir = Path(output_dir)
    source_files = sorted(capture_dir.glob("*.json"))
    if not source_files:
        raise RuntimeError(f"no *.json capture files found under {capture_dir}")

    key = _get_support_key(support_key_file) if support_key_file is not None else _get_support_key()
    tok = Tokenizer(key)

    with tempfile.TemporaryDirectory(prefix="ui2-extract-fixtures-") as tmp:
        staging = Path(tmp)
        staged_files: list[Path] = []
        for source_file in source_files:
            data = json.loads(source_file.read_text(encoding="utf-8"))
            payload = _sanitize_payload(data, tok)

            fixture_id = f"{capability_id}__{source_file.stem}__{fixture_kind.lower()}"
            metadata: dict[str, Any] = {
                "fixture_id": fixture_id,
                "capability_id": capability_id,
                "command_tuple": list(command_tuple),
                "vendor_version": vendor_version,
                # For SYNTHETIC this is the authoring date, per C6 section
                # 3.4 ("capture_date... never applicable to SYNTHETIC; the
                # authoring date is recorded instead for that case").
                "capture_date": capture_date,
                "fixture_kind": fixture_kind,
                "capture_session_id": capture_session_id,
                "source_capture_path": source_file.name,
            }
            if fixture_kind == "DERIVED":
                metadata["derived_from"] = derived_from
                metadata["derived_change"] = derived_change
            if fixture_kind == "SYNTHETIC":
                metadata["synthetic_reason"] = synthetic_reason

            fixture_doc = {**metadata, "payload": payload}
            staged_path = staging / f"{fixture_id}.json"
            staged_path.write_text(json.dumps(fixture_doc, indent=2, ensure_ascii=False), encoding="utf-8")
            staged_files.append(staged_path)

        report = scan_repository(staging)
        if report.findings:
            raise FixtureDLPRefusalError(report)

        output_dir.mkdir(parents=True, exist_ok=True)
        written: list[Path] = []
        for staged_path in staged_files:
            final_path = output_dir / staged_path.name
            shutil.copyfile(staged_path, final_path)
            written.append(final_path)
        return written


def _build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--capture-dir", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--capability-id", required=True)
    parser.add_argument("--command-tuple", action="append", required=True, dest="command_tuple")
    parser.add_argument("--vendor-version", required=True)
    parser.add_argument("--capture-date", required=True)
    parser.add_argument("--capture-session-id", required=True)
    parser.add_argument("--fixture-kind", choices=FIXTURE_KINDS, default="REAL")
    parser.add_argument("--derived-from")
    parser.add_argument("--derived-change")
    parser.add_argument("--synthetic-reason")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _build_arg_parser().parse_args(argv)
    try:
        written = extract_fixtures(
            args.capture_dir,
            args.output_dir,
            capability_id=args.capability_id,
            command_tuple=args.command_tuple,
            vendor_version=args.vendor_version,
            capture_date=args.capture_date,
            capture_session_id=args.capture_session_id,
            fixture_kind=args.fixture_kind,
            derived_from=args.derived_from,
            derived_change=args.derived_change,
            synthetic_reason=args.synthetic_reason,
        )
    except FixtureDLPRefusalError as exc:
        print("Fixture set REFUSED -- repository privacy gate failed. No files written to --output-dir.")
        print(f"Findings: {len(exc.report.findings)} (matched values intentionally withheld)")
        for finding in exc.report.findings:
            location = f"{finding.path}:{finding.line}" if finding.line else finding.path
            print(f"  {location}  {finding.rule}")
        return 2
    except (RepositoryPrivacyError, ValueError, RuntimeError) as exc:
        print(f"Fixture extraction failed: {exc}")
        return 1

    print(f"Wrote {len(written)} fixture(s) to {args.output_dir}")
    for path in written:
        print(f"  {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
