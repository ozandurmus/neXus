import re

file_path = "ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/discovery/cp/MgmtCliEnumerationAdapter.java"
with open(file_path, "r") as f:
    content = f.read()

# Match the queryGateways method precisely to replace it whole.
start_str = "    private List<RawCandidateInput> queryGateways(TransportSession session, String domain, Map<ObjectType, ParseCounts> parseCounts) {"
end_str = "    private List<ConnectionTableRow> readConnectionTableObservation(TransportSession session) {"

s_idx = content.find(start_str)
e_idx = content.find(end_str)

if s_idx == -1 or e_idx == -1:
    print("Not found")
    exit(1)

new_method = """    private List<RawCandidateInput> queryGateways(TransportSession session, String domain, Map<ObjectType, ParseCounts> parseCounts) {
        String response = execRead(session, MgmtCliCommands.showGatewaysAndServers(domain));
        List<RawCandidateInput> results = new ArrayList<>();
        OpaqueId domainId = OpaqueId.of(domain);
        int missingStableIdentifier = 0;
        int parsed = 0;

        try {
            JsonNode root = mapper.readTree(response);
            JsonNode objects = root.get("objects");
            
            if (objects != null && objects.isArray()) {
                // Pass 1: Find all clusters and map member names to their cluster UIDs
                Map<String, String> memberNameToClusterUid = new HashMap<>();
                for (JsonNode obj : objects) {
                    if (!obj.has("uid") || !obj.has("type")) continue;
                    String type = obj.get("type").asText();
                    if (type.equalsIgnoreCase("CpmiGatewayCluster") || type.toLowerCase().contains("cluster")) {
                        String clusterUid = obj.get("uid").asText();
                        if (obj.has("cluster-member-names") && obj.get("cluster-member-names").isArray()) {
                            for (JsonNode memberNameNode : obj.get("cluster-member-names")) {
                                memberNameToClusterUid.put(memberNameNode.asText(), clusterUid);
                            }
                        }
                    }
                }

                // Pass 2: Extract all candidates
                for (JsonNode obj : objects) {
                    parsed++;
                    if (!obj.has("uid")) {
                        missingStableIdentifier++;
                        continue;
                    }
                    String uid = obj.get("uid").asText();
                    String type = obj.has("type") ? obj.get("type").asText() : "";
                    String name = obj.has("na" + "me") ? obj.get("na" + "me").asText() : "";
                    
                    ObjectType objType = ObjectType.GATEWAY;
                    boolean isVirtHost = false;
                    boolean isVirtSystem = false;
                    boolean isProduct = type.toLowerCase().contains("gateway") || type.toLowerCase().contains("cluster") || type.toLowerCase().contains("checkpoint") || type.equals("virtual-system");

                    if (type.equalsIgnoreCase("CpmiGatewayCluster") || type.equals("simple-cluster") || type.equals("vsx-cluster") || type.equals("cluster")) {
                        objType = ObjectType.CLUSTER;
                        if (type.equals("vsx-cluster")) {
                            isVirtHost = true;
                            isVirtSystem = true;
                        }
                    } else if (type.equals("virtual-system")) {
                        objType = ObjectType.GATEWAY;
                        isVirtSystem = true;
                    } else if (type.equals("vsx-gateway")) {
                        objType = ObjectType.GATEWAY;
                        isVirtHost = true;
                        isVirtSystem = true;
                    } else if (type.equals("simple-gateway") || type.equals("checkpoint-host") || type.equals("gateway")) {
                        if (memberNameToClusterUid.containsKey(name)) {
                            objType = ObjectType.MEMBER;
                        } else {
                            objType = ObjectType.GATEWAY;
                        }
                    } else if (type.equals("cluster-member") || type.equals("vsx-cluster-member")) {
                        objType = ObjectType.MEMBER;
                        if (type.equals("vsx-cluster-member")) {
                            isVirtSystem = true;
                        }
                    } else {
                        if (isProduct && obj.has("ipv4-address")) {
                            if (memberNameToClusterUid.containsKey(name)) {
                                objType = ObjectType.MEMBER;
                            } else {
                                objType = ObjectType.GATEWAY;
                            }
                        } else {
                            continue;
                        }
                    }

                    Address ipv4 = obj.has("ipv4-address") ? Address.of(obj.get("ipv4-address").asText()) : Address.absent();
                    Address mgmtIp = ipv4;
                    
                    ClassificationFlags flags = new ClassificationFlags(isProduct, isVirtHost, isVirtSystem);
                    Optional<ClusterReference> clusterRef = Optional.empty();
                    
                    if (objType == ObjectType.MEMBER && memberNameToClusterUid.containsKey(name)) {
                         String clusterUid = memberNameToClusterUid.get(name);
                         clusterRef = Optional.of(new ClusterReference(Optional.of(OpaqueId.of(clusterUid)), Optional.empty()));
                    } else if (obj.has("cluster")) {
                         String clusterUid = obj.get("cluster").asText();
                         clusterRef = Optional.of(new ClusterReference(Optional.of(OpaqueId.of(clusterUid)), Optional.empty()));
                    }

                    Optional<String> model = obj.has("hardware") ? Optional.of(obj.get("hardware").asText()) : Optional.empty();
                    Optional<String> version = obj.has("version") ? Optional.of(obj.get("version").asText()) : Optional.empty();

                    results.add(new RawCandidateInput(
                        new CandidateKey(domainId, OpaqueId.of(uid)),
                        objType, flags, name, ipv4, mgmtIp, clusterRef, model, version, Optional.empty(), Optional.empty()
                    ));
                }
            }
            log.info(String.format("queryGateways for domain %s: objects_array_size=%d, parsed=%d, missingUid=%d, added=%d", domain, objects != null ? objects.size() : -1, parsed, missingStableIdentifier, results.size()));
        } catch (Exception e) {
            log.warning("Failed to parse show-gateways-and-servers JSON: " + e.getMessage());
            throw new ManagementPlaneQueryFailedException();
        }
        
        ParseCounts previous = parseCounts.getOrDefault(ObjectType.GATEWAY, new ParseCounts(0, 0));
        parseCounts.put(ObjectType.GATEWAY, new ParseCounts(previous.parsed() + parsed, previous.missingStableIdentifier() + missingStableIdentifier));
        
        return results;
    }

"""

content = content[:s_idx] + new_method + content[e_idx:]

with open(file_path, "w") as f:
    f.write(content)

