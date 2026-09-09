#!/usr/bin/env python3
"""Run the local repository privacy gate without importing product surfaces."""
from __future__ import annotations

import sys
from pathlib import Path

_REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_REPO_ROOT))

from utils.repository_privacy import RepositoryPrivacyError, scan_repository


def main() -> int:
    print("=== SECURITYEXPERT STANDALONE REPOSITORY PRIVACY GATE — GOV.PO.2 ===\n")
    try:
        report = scan_repository(_REPO_ROOT)
    except RepositoryPrivacyError as exc:
        print("Gate:                 ERROR")
        print(f"Reason:               {exc}")
        print("No network access performed. No matched values were printed.")
        return 2

    print(f"Files scanned:        {report.files_scanned}")
    print(f"Files skipped:        {report.files_skipped}")
    print(f"Findings:             {len(report.findings)}")
    if report.findings:
        print("\nFindings (matched values intentionally withheld):")
        for finding in report.findings:
            location = f"{finding.path}:{finding.line}" if finding.line else finding.path
            print(f"  {location}  {finding.rule}")
        print("\nBaseline:              baseline unavailable (no baseline ref provided)")
        print(f"New findings:          {len(report.findings)}")
    gate_pass = not report.findings
    print(f"\nGate:                 {'PASS' if gate_pass else 'FAIL'}")
    print("No network access performed. No matched values were printed.")
    return 0 if gate_pass else 1


if __name__ == "__main__":
    sys.exit(main())
