"""Finalize 0.6.5 real-environment report from value-free operator evidence.

Usage example:
  py -B _realenv_0_6_5_finalize_report.py --result PASS --success 5 --partial 0 --failed 0 --management-down 0 --attested

This script never reads runtime logs and only writes the repository report
template with sanitized, value-free fields.
"""

from __future__ import annotations

import argparse
import json
from datetime import date
from pathlib import Path
from typing import Any


DEFAULT_REPORT_PATH = Path("project/validation/0_6_5_real_env_report_template.json")


def _to_bool(value: str) -> bool:
    if value.lower() in {"1", "true", "yes", "y"}:
        return True
    if value.lower() in {"0", "false", "no", "n"}:
        return False
    raise argparse.ArgumentTypeError("expected boolean: true/false")


def _required_gate_pass(report: dict[str, Any]) -> bool:
    real_checks = report["real_environment"]["checks"]
    privacy_checks = report["privacy"]["checks"]
    return all(real_checks.values()) and all(privacy_checks.values())


def _score(report: dict[str, Any]) -> tuple[int, int, int]:
    real_checks = report["real_environment"]["checks"]
    privacy_checks = report["privacy"]["checks"]
    required = len(real_checks) + len(privacy_checks)
    passed = sum(1 for flag in real_checks.values() if flag) + sum(
        1 for flag in privacy_checks.values() if flag
    )
    failed = required - passed
    return required, passed, failed


def _set_status(report: dict[str, Any], result: str) -> None:
    required, passed, failed = _score(report)
    pending = 0
    pass_rate = round((passed / required) * 100.0, 2) if required else 0.0

    gate_pass = _required_gate_pass(report)
    if result == "PASS" and gate_pass:
        status = "real_env_validated"
        promotion = True
    elif result == "FAIL":
        status = "real_env_failed"
        promotion = False
    else:
        status = "real_env_partial"
        promotion = False

    report["status"] = status
    report["validation_date"] = date.today().isoformat()
    report["summary"]["required_checks"] = required
    report["summary"]["passed_checks"] = passed
    report["summary"]["failed_checks"] = failed
    report["summary"]["pending_checks"] = pending
    report["summary"]["pass_rate"] = pass_rate
    report["summary"]["promotion_allowed"] = promotion
    report["summary"]["deployment_gate"] = (
        "real_env_validated" if promotion else "real_env_evidence_incomplete"
    )


def finalize_report(
    report_path: Path,
    result: str,
    success: int,
    partial: int,
    failed: int,
    management_down: int,
    attested: bool,
) -> dict[str, Any]:
    report = json.loads(report_path.read_text(encoding="utf-8"))

    # Real-environment checks are derived from operator-declared outcome.
    if result == "PASS":
        for key in report["real_environment"]["checks"]:
            report["real_environment"]["checks"][key] = True
        for key in report["privacy"]["checks"]:
            report["privacy"]["checks"][key] = True
    elif result == "PARTIAL":
        report["real_environment"]["checks"]["R01_strict_mode_enabled_with_ca_bundle"] = True
        report["real_environment"]["checks"]["R04_value_free_shareable_evidence"] = True
        report["real_environment"]["checks"]["R05_no_scope_drift_scheduler_polling_concurrency"] = True
        report["privacy"]["checks"]["D01_no_credentials_in_shared_artifacts"] = True
        report["privacy"]["checks"]["D02_no_real_identity_literals_in_report"] = True
        report["privacy"]["checks"]["D03_no_raw_secret_bearing_config_shared"] = True
    else:  # FAIL
        report["real_environment"]["checks"]["R04_value_free_shareable_evidence"] = True
        report["real_environment"]["checks"]["R05_no_scope_drift_scheduler_polling_concurrency"] = True

    report["real_environment"]["safe_summary"]["result"] = result
    report["real_environment"]["safe_summary"]["success_count"] = success
    report["real_environment"]["safe_summary"]["partial_count"] = partial
    report["real_environment"]["safe_summary"]["failed_count"] = failed
    report["real_environment"]["safe_summary"]["management_down_count"] = management_down

    report["human_attestation"]["approved"] = attested
    report["human_attestation"]["safe_summary_reviewed"] = attested

    _set_status(report, result)

    report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return report


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Finalize 0.6.5 real-env validation report")
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT_PATH)
    parser.add_argument("--result", choices=["PASS", "PARTIAL", "FAIL"], required=True)
    parser.add_argument("--success", type=int, default=0)
    parser.add_argument("--partial", type=int, default=0)
    parser.add_argument("--failed", type=int, default=0)
    parser.add_argument("--management-down", type=int, default=0)
    parser.add_argument("--attested", type=_to_bool, nargs="?", const=True, default=False)
    return parser


def main() -> int:
    args = build_parser().parse_args()

    if min(args.success, args.partial, args.failed, args.management_down) < 0:
        raise SystemExit("counts must be >= 0")

    report = finalize_report(
        report_path=args.report,
        result=args.result,
        success=args.success,
        partial=args.partial,
        failed=args.failed,
        management_down=args.management_down,
        attested=args.attested,
    )

    safe = {
        "build": report["build"],
        "status": report["status"],
        "validation_date": report["validation_date"],
        "pass_rate": report["summary"]["pass_rate"],
        "promotion_allowed": report["summary"]["promotion_allowed"],
        "deployment_gate": report["summary"]["deployment_gate"],
        "safe_summary": report["real_environment"]["safe_summary"],
    }
    print(json.dumps(safe, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
