"""
Helper: Write R02/R03/R04 test policies for real-env validation.
Usage:
  py -B _write_r0x_policy.py r02  <runtime_root>   # disabled policy
  py -B _write_r0x_policy.py r03  <runtime_root>   # malformed policy
  py -B _write_r0x_policy.py r04  <runtime_root>   # minimal enabled cp policy
"""
import json
import pathlib
import sys

POLICIES = {
    "r02": {"version": 1, "enabled": False, "schedule": []},
    "r03": "NOT_VALID_JSON_{{{",  # raw string — malformed
    "r04": {"version": 1, "enabled": True, "schedule": [{"workflow": "cp", "interval_minutes": 60}]},
}

if len(sys.argv) != 3 or sys.argv[1] not in POLICIES:
    print("Usage: py -B _write_r0x_policy.py <r02|r03|r04> <runtime_root>")
    sys.exit(1)

tag = sys.argv[1]
runtime_root = pathlib.Path(sys.argv[2])
policy_path = runtime_root / "data" / "state" / "scheduler_policy.json"
policy_path.parent.mkdir(parents=True, exist_ok=True)

content = POLICIES[tag]
if isinstance(content, str):
    policy_path.write_text(content, encoding="utf-8")
else:
    policy_path.write_text(json.dumps(content, indent=2), encoding="utf-8")

print(f"Wrote {tag} policy to: {policy_path}")
