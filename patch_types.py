import re

file_path = "ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/discovery/cp/MgmtCliEnumerationAdapter.java"
with open(file_path, "r") as f:
    content = f.read()

# Replace Pass 1 cluster detection
old_pass1 = 'if (type.equalsIgnoreCase("CpmiGatewayCluster") || type.toLowerCase().contains("cluster")) {'
new_pass1 = 'if (type.equalsIgnoreCase("CpmiGatewayCluster") || type.equalsIgnoreCase("CpmiVsClusterNetobj") || type.equalsIgnoreCase("CpmiVsxClusterNetobj") || type.toLowerCase().contains("cluster")) {'
content = content.replace(old_pass1, new_pass1)

# Replace Pass 2 isProduct
old_isProduct = 'boolean isProduct = type.toLowerCase().contains("gateway") || type.toLowerCase().contains("cluster") || type.toLowerCase().contains("checkpoint") || type.equals("virtual-system");'
new_isProduct = 'boolean isProduct = type.toLowerCase().contains("gateway") || type.toLowerCase().contains("cluster") || type.toLowerCase().contains("checkpoint") || type.toLowerCase().contains("vs") || type.toLowerCase().contains("virtual");'
content = content.replace(old_isProduct, new_isProduct)

# We will completely replace the long if-else chain for ObjectType mapping.
old_chain_start = '                    if (type.equalsIgnoreCase("CpmiGatewayCluster")'
old_chain_end = '                    Address ipv4 = obj.has("ipv4-address") ? Address.of(obj.get("ipv4-address").asText()) : Address.absent();'

s_idx = content.find(old_chain_start)
e_idx = content.find(old_chain_end)

if s_idx == -1 or e_idx == -1:
    print("Could not find the if-else chain to replace.")
    exit(1)

new_chain = """                    if (type.equalsIgnoreCase("CpmiGatewayCluster") || type.equals("simple-cluster") || type.equals("cluster")) {
                        objType = ObjectType.CLUSTER;
                    } else if (type.equalsIgnoreCase("CpmiVsxClusterNetobj") || type.equals("vsx-cluster")) {
                        objType = ObjectType.CLUSTER;
                        isVirtHost = true;
                    } else if (type.equalsIgnoreCase("CpmiVsClusterNetobj")) {
                        objType = ObjectType.CLUSTER;
                        isVirtSystem = true;
                    } else if (type.equalsIgnoreCase("CpmiVsNetobj") || type.equals("virtual-system")) {
                        objType = ObjectType.GATEWAY;
                        isVirtSystem = true;
                    } else if (type.equalsIgnoreCase("CpmiVsxNetobj") || type.equals("vsx-gateway")) {
                        objType = ObjectType.GATEWAY;
                        isVirtHost = true;
                    } else if (type.equalsIgnoreCase("CpmiVsxClusterMember") || type.equals("vsx-cluster-member")) {
                        objType = ObjectType.MEMBER;
                        isVirtHost = true;
                    } else if (type.equals("cluster-member")) {
                        objType = ObjectType.MEMBER;
                    } else if (type.equals("simple-gateway") || type.equals("checkpoint-host") || type.equals("gateway")) {
                        if (memberNameToClusterUid.containsKey(name)) {
                            objType = ObjectType.MEMBER;
                        } else {
                            objType = ObjectType.GATEWAY;
                        }
                    } else {
                        if (isProduct && obj.has("ipv4-address")) {
                            if (memberNameToClusterUid.containsKey(name)) {
                                objType = ObjectType.MEMBER;
                            } else if (type.toLowerCase().contains("cluster")) {
                                objType = ObjectType.CLUSTER;
                            } else {
                                objType = ObjectType.GATEWAY;
                            }
                        } else {
                            continue;
                        }
                    }

"""

content = content[:s_idx] + new_chain + content[e_idx:]

with open(file_path, "w") as f:
    f.write(content)

