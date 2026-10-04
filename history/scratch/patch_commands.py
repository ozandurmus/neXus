import re

file_path = "ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/discovery/cp/MgmtCliCommands.java"
with open(file_path, "r") as f:
    content = f.read()

content = content.replace(
    'return "mgmt_cli -r true -f json show-domains limit 500 details-level full";',
    'return "bash -l -c \\"mgmt_cli -r true -f json show-domains limit 500 details-level full\\"";'
)

content = content.replace(
    'return "mgmt_cli -r true -d " + quote(domainIdentifier) + " -f json show-gateways-and-servers limit 500 details-level full";',
    'return "bash -l -c \\"mgmt_cli -r true -d " + quote(domainIdentifier) + " -f json show-gateways-and-servers limit 500 details-level full\\"";'
)

with open(file_path, "w") as f:
    f.write(content)

