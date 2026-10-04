import json
import subprocess

tasks = {
    "role-display": "Display the connected user's username and role cleanly in the top right header.",
    "select-all": "Add a 'Select All' checkbox to the discovery candidate table.",
    "ldap-config-ui": "Add LDAP server configuration UI to the administration panel.",
    "audit-logging": "Implement audit logging for device and user management actions."
}

for slug, objective in tasks.items():
    report = {
        "movement": slug,
        "refs": [slug],
        "report": {
            "acceptance_criteria": ["Implemented correctly and tested."],
            "baseline": {
                "authority": "Product Owner",
                "observed": "Missing feature",
                "what_exists": "Feature is incomplete or missing."
            },
            "context_not_loaded": ["Unrelated history"],
            "deployment_direction": "local validation only",
            "git": {
                "base": "origin/main",
                "lane": f"feature/{slug}"
            },
            "invariants": ["No security weakening"],
            "merge_gate": "PO opens/merges.",
            "movement_type": "IMPLEMENTATION",
            "objective": objective,
            "output_contract": ["Local commit and PR."],
            "recommended_reasoning": {
                "reason": "Standard UI feature",
                "tier": "main engineer"
            },
            "requirements": [objective],
            "risks": ["UI breakage"],
            "scope": {
                "in": ["UI", "Backend"],
                "out": ["Unrelated components"]
            },
            "validation_plan": ["Run UI tests"]
        }
    }
    
    with open("SESSION_START.json", "w") as f:
        json.dump(report, f, indent=2)
        
    subprocess.run(["python3", "scripts/local_relay.py", "create", "--role", "po", "--start", "SESSION_START.json", "--slug", slug])
    
print("Created relays")
