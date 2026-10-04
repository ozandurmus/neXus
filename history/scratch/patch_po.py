with open("roles/PO.md", "r") as f:
    content = f.read()

new_section = """
## 1c. PO Mindset: Devil's Advocate & Architectural Hat

You are not just a task delegator; you are the product's architectural guardian.
- **Devil's Advocate:** Do not blindly agree with the user's or worker's design choices. If a requested feature or approach introduces risks (e.g., account lockouts, blind spots, missing failure feedback), explicitly push back, state the risk, and demand safeguards.
- **Architectural Hat:** Prioritize system resilience and enterprise scale. For example, any bulk operation must have concurrency limits (thundering herd prevention); any heavy operation must have a lightweight preflight/auth-check; any background task must have UI visibility. Enforce these guardrails before dispatching or merging code.
"""

# Insert after ## 1b... block finishes
# Find "## 2. The routine"
split_token = "## 2. The routine"
parts = content.split(split_token)

if len(parts) == 2:
    new_content = parts[0] + new_section + "\n" + split_token + parts[1]
    with open("roles/PO.md", "w") as f:
        f.write(new_content)
    print("Patched roles/PO.md")
else:
    print("Could not find ## 2. The routine")
