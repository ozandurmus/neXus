import re

file_path = "ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/security/SecurityWebMvcConfig.java"
with open(file_path, "r") as f:
    content = f.read()

content = content.replace("registry.addInterceptor(new GateChainInterceptor(gateChain, ACTION_ID_BY_ROUTE, EXPLICITLY_UNGATED_ROUTES));", "registry.addInterceptor(new GateChainInterceptor(gateChain, ACTION_ID_BY_ROUTE));")

# Delete the EXPLICITLY_UNGATED_ROUTES block
pattern = r"static final Set<String> EXPLICITLY_UNGATED_ROUTES = Set\.of\([\s\S]*?\);"
content = re.sub(pattern, "", content)
content = content.replace("import java.util.Set;", "")

with open(file_path, "w") as f:
    f.write(content)
