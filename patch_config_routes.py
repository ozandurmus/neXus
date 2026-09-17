import re

file_path = "ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/security/SecurityWebMvcConfig.java"
with open(file_path, "r") as f:
    content = f.read()

missing_routes = """            Map.entry("GET /clusters/*/inventory", ActionRegistry.DEVICE_READ),
            // NXS-LOCAL-0165 "Routes": same wildcard shapes as the inventory
            // routes above; /devices/*/configuration/text is a four-segment
            // route (GateChainInterceptor tries a single-segment wildcard at
            // every position, so the id at position 2 resolves the same way
            // /discovery/runs/*/import's own position-3 wildcard already does).
            Map.entry("GET /configuration", ActionRegistry.DEVICE_READ),
            Map.entry("GET /devices/*/configuration", ActionRegistry.DEVICE_READ),
            Map.entry("GET /devices/*/configuration/text", ActionRegistry.DEVICE_CONFIGURATION_TEXT_READ),
            Map.entry("POST /devices/*/configuration/collect", ActionRegistry.DEVICE_CONFIGURATION_COLLECT),"""

content = content.replace("            Map.entry(\"GET /clusters/*/inventory\", ActionRegistry.DEVICE_READ),", missing_routes)

with open(file_path, "w") as f:
    f.write(content)
