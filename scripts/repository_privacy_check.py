#!/usr/bin/env python3
"""Run the local repository privacy gate without importing product surfaces."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

_REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_REPO_ROOT))

from utils.repository_privacy import (
    RepositoryPrivacyError, baseline_finding_keys, finding_key, scan_repository,
)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--privacy-baseline-ref", help="Compare against the merge base with this local Git ref")
    args = parser.parse_args(argv)
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
    new_findings = report.findings
    if report.findings:
        print("\nFindings (matched values intentionally withheld):")
        for finding in report.findings:
            location = f"{finding.path}:{finding.line}" if finding.line else finding.path
            print(f"  {location}  {finding.rule}")
        baseline_keys, baseline_note = baseline_finding_keys(_REPO_ROOT, args.privacy_baseline_ref)
        new_findings = tuple(
            f for f in report.findings if finding_key(_REPO_ROOT, f) not in baseline_keys
        )
        print(f"\nBaseline:              {baseline_note}")
        print(f"New findings:          {len(new_findings)}")
    gate_pass = not new_findings
    print(f"\nGate:                 {'PASS' if gate_pass else 'FAIL'}")
    print("No network access performed. No matched values were printed.")
    return 0 if gate_pass else 1


if __name__ == "__main__":
    sys.exit(main())
