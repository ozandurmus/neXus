import re

file_path = "ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/security/GateChainInterceptor.java"
with open(file_path, "r") as f:
    content = f.read()

# Replace the strict block
strict_block = """        if (actionId == null) {
            if (explicitlyUngatedRoutes.contains(request.getMethod() + " " + request.getServletPath())) {
                return true;
            }
            response.setStatus(404);
            response.setContentType("application/json");
            objectMapper.writeValue(response.getWriter(), Map.of("error", "ACTION_MAPPING_REQUIRED"));
            return false;
        }"""

relaxed_block = """        if (actionId == null) {
            return true;
        }"""

content = content.replace(strict_block, relaxed_block)

# Remove explicitlyUngatedRoutes
content = content.replace("private final Set<String> explicitlyUngatedRoutes;", "")
content = content.replace("this.explicitlyUngatedRoutes = explicitlyUngatedRoutes;", "")

content = re.sub(r"public GateChainInterceptor\(GateChain gateChain, Map<String, String> actionIdByRoute,\s*Set<String> explicitlyUngatedRoutes\)\s*{", "public GateChainInterceptor(GateChain gateChain, Map<String, String> actionIdByRoute) {", content)
content = re.sub(r"public GateChainInterceptor\(GateChain gateChain, Map<String, String> actionIdByRoute\)\s*{\s*this\(gateChain, actionIdByRoute, Set\.of\(\)\);\s*}", "", content)

with open(file_path, "w") as f:
    f.write(content)
