import json
import subprocess

slug = "cp-inventory-ssh"
objective = "Draft UI2_0_INVENTORY_TOPOLOGY_CONTRACT.md stipulating 100% SSH for Check Point Inventory collection, and implement the collection using existing CheckPointIpRouteParser and CheckPointVsxStatParser."

report = {
    "movement": slug,
    "refs": [slug],
    "report": {
        "acceptance_criteria": ["Contract written and Java inventory implemented via SSH."],
        "baseline": {
            "authority": "docs/design/NEXUS_AGENT_RELAY_PROTOCOL.md (FROZEN)",
            "observed": "Missing CP inventory",
            "what_exists": "Parsers exist but capability executor not fully wired to UI."
        },
        "context_not_loaded": ["Unrelated history"],
        "deployment_direction": "local validation only",
        "git": {
            "base": "origin/main",
            "lane": f"feature/{slug}"
        },
        "invariants": ["No security weakening"],
        "merge_gate": "PO opens/merges.",
        "movement_type": "ARCHITECTURE",
        "objective": objective,
        "output_contract": ["Local commit and PR."],
        "recommended_reasoning": {
            "reason": "Contract drafting and Java implementation",
            "tier": "main engineer"
        },
        "requirements": [objective],
        "risks": ["Parsing errors"],
        "scope": {
            "in": ["UI", "Backend"],
            "out": ["Unrelated components"]
        },
        "validation_plan": ["Run Java tests"]
    }
}

with open("SESSION_START_INVENTORY_SSH.json", "w") as f:
    json.dump(report, f, indent=2)

subprocess.run(["python3", "scripts/local_relay.py", "create", "--role", "po", "--start", "SESSION_START_INVENTORY_SSH.json", "--slug", slug])
print("Created relay for pure SSH inventory worker")
