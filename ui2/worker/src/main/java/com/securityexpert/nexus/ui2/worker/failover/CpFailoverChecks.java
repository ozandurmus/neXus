package com.securityexpert.nexus.ui2.worker.failover;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.worker.inventory.cp.CheckPointHaStateParser;

/** Strict, in-memory projections of the approved Check Point checks. */
public final class CpFailoverChecks {
    public static final long POLICY_MAX_SKEW_SECONDS = 600;
    public static final double TRAFFIC_MIN_RATIO = 0.50;
    private static final Pattern MODE = Pattern.compile("(?im)^\\s*Cluster mode:[ \t]*([^\\r\\n]+)$");
    private static final Pattern LOCAL = Pattern.compile("(?i)\\(local\\)");
    private static final Pattern IP_ROW = Pattern.compile("(?m)^\\s*([^\\s()]+)\\s+(\\d+|[A-Za-z][A-Za-z0-9_.:-]{0,63})\\s+([0-9a-fA-F:.]+)(?:[ \\t]+[0-9a-fA-F:]+)?[ \\t]*$");
    private static final Pattern INTERFACE = Pattern.compile("(?m)^\\s*([A-Za-z][A-Za-z0-9_.:-]{0,63})[ \\t]+(?:\\(((?:S|HA|LS|LM|P)(?:,[ \t]*(?:S|HA|LS|LM|P))*)\\)[ \\t]+)?(UP|DOWN|Non-Monitored)\\b(.*)$");
    private static final Pattern DEV = Pattern.compile("(?m)^\\s*([A-Za-z0-9_.:-]{1,64}):\\s*((?:\\d+\\s+){15}\\d+)\\s*$");
    private static final Pattern SYNC_STATUS = Pattern.compile("(?m)^Sync status:\\s*(OK|Off\\s*-.*|Fullsync in progress|Problem\\s*\\(.*\\))\\s*$");
    private static final Pattern POLICY_ROW = Pattern.compile("(?m)^localhost\\s+(\\S+)\\s+(\\d{1,2}[A-Za-z]{3}\\d{4})\\s+(\\d{1,2}:\\d{2}:\\d{2})\\s+:.*$");
    private CpFailoverChecks() {}

    public record State(String mode, Map<String,String> members, String localId, String localRole, String vsId) {
        public boolean supportedMode() { return "HA".equals(mode) || "VSLS".equals(mode) && vsId!=null; }
        public boolean pair() { return supportedMode() && members.size()==2 && members.values().stream().filter("ACTIVE"::equals).count()==1
            && members.values().stream().filter("STANDBY"::equals).count()==1 && localId!=null; }
    }
    public static State state(String output) {
        State unknown=new State("UNKNOWN",Map.of(),null,"UNKNOWN",null);
        String mode=CheckPointHaStateParser.clusterModeOf(output).orElse("UNKNOWN");
        Matcher reported=MODE.matcher(output==null?"":output);
        if(!reported.find()) return unknown;
        String label=reported.group(1).strip();
        if(reported.find()) return unknown;
        if("High Availability".equals(mode) && !label.matches(
                "(?i)High Availability(?: \\((?:Active|Primary) Up\\))?(?: with IGMP Membership)?")) return unknown;
        if (!mode.equals("High Availability") && !mode.equals("Virtual System Load Sharing"))
            return new State("UNSUPPORTED",Map.of(),null,"UNKNOWN",null);
        Map<String,String> roles=new HashMap<>(); String local=null;
        for (String line:output.split("\\R")) {
            if (line.toLowerCase(Locale.ROOT).contains("virtual devices status on each cluster member")) break;
            Matcher marker=LOCAL.matcher(line);
            boolean isLocal=marker.find();
            String[] columns=marker.replaceAll(" ").strip().split("\\s+");
            if (columns.length<3 || !columns[1].matches("[0-9a-fA-F:.]+")) continue;
            int stateColumn=columns[2].endsWith("%")?3:2;
            if (columns.length<=stateColumn) return unknown;
            String role=columns[stateColumn].toUpperCase(Locale.ROOT);
            if ("ACTIVE".equals(role) && columns.length>stateColumn+1
                    && "ATTENTION".equalsIgnoreCase(columns[stateColumn+1])) role="ACTIVE ATTENTION";
            if (!Set.of("ACTIVE(!)","ACTIVE","STANDBY","BACKUP","DOWN","READY","INIT","LOST","ACTIVE ATTENTION").contains(role)) role=null;
            if (role==null || roles.putIfAbsent(columns[0],role)!=null) return unknown;
            if (isLocal) {
                if (local!=null) return unknown;
                local=columns[0];
            }
        }
        return roles.size()<2 || local==null?unknown:new State(mode.equals("Virtual System Load Sharing")?"VSLS":"HA",Map.copyOf(roles),local,
            roles.get(local),null);
    }
    public static boolean corroborated(State a,State b) {
        return a.pair() && b.pair() && "PASS".equals(reciprocal(a,b));
    }

    /** Tables must be from distinct, verified member sessions in one collection pass. */
    public static String reciprocal(State a,State b) {
        if(!java.util.Objects.equals(a.vsId(),b.vsId())) return "FAIL";
        if(a.localId()==null || b.localId()==null || a.members().size()!=2 || b.members().size()!=2)
            return "UNKNOWN";
        return !a.localId().equals(b.localId()) && a.members().equals(b.members())
            && a.localRole().equals(b.members().get(a.localId()))
            && b.localRole().equals(a.members().get(b.localId())) ? "PASS" : "FAIL";
    }

    /** The executor supplies the opaque VSID used by the approved vsenv wrapper. */
    public static State state(String output,String vsId) {
        if(vsId!=null && !vsId.matches("[0-9]{1,10}")) return state(null);
        if(output!=null) {
            Matcher context=Pattern.compile("(?im)^(?:Virtual System[ \t]+([^\r\n]+)|Context is set to Virtual Device [^\r\n]+ \\(ID ([^()]+)\\)\\.)[ \t]*$").matcher(output);
            while(context.find()) {
                String observed=context.group(1)!=null?context.group(1).strip():context.group(2);
                if(!java.util.Objects.equals(vsId,observed)) return state(null);
            }
            if(vsId!=null && output.toLowerCase(Locale.ROOT).contains("virtual devices status on each cluster member"))
                return state(null);
        }
        State parsed=state(output);
        return new State(parsed.mode(),parsed.members(),parsed.localId(),parsed.localRole(),vsId);
    }

    public static Map<String,Object> stateEvidence(State state) {
        return Map.of("role",state.localRole(),"mode",state.mode(),
            "reason",state.supportedMode()?"OBSERVED":"UNKNOWN".equals(state.mode())?"UNRECOGNIZED_STATE":"UNSUPPORTED_MODE",
            "local_state",state.localId()==null?"UNKNOWN":state.members().get(state.localId()),
            "peer_state",state.members().entrySet().stream().filter(e -> !e.getKey().equals(state.localId()))
                .map(Map.Entry::getValue).findFirst().orElse("UNKNOWN"));
    }

    /** Compare complete tables in memory; never persist addresses or interface identifiers. */
    public static Set<String> ipTable(String output) {
        if (output==null || !output.contains("Unique IP's Table")) return Set.of();
        Set<String> rows=new HashSet<>();
        for(String raw:output.split("\\R")) {
            String line=LOCAL.matcher(raw).replaceAll(" ");
            if(!line.strip().matches("[0-9].*")) continue;
            Matcher m=IP_ROW.matcher(line);
            if(!m.matches()) return Set.of();
            // Numeric indices are observer-local; only explicit interface names are comparable.
            String key=m.group(1)+"|"+(m.group(2).matches("[0-9]+")?"":m.group(2));
            rows.add(key+"|"+m.group(3));
        }
        return Set.copyOf(rows);
    }
    public static boolean twoTableMembers(Set<String> rows) {
        return rows.stream().map(row -> row.substring(0,row.indexOf('|'))).distinct().count()==2;
    }
    /** Compare per-member address sets (and explicit names), retaining only safe address aliases. */
    public static Map<String,Object> tableDifference(Set<String> a,Set<String> b) {
        var rows=new java.util.TreeSet<>(a); rows.addAll(b);
        var addresses=new HashMap<String,Integer>();
        var differences=new ArrayList<Map<String,Object>>();
        for(String row:rows) {
            String address=row.substring(row.lastIndexOf('|')+1);
            addresses.computeIfAbsent(address,ignored -> addresses.size()+1);
            boolean first=a.contains(row),second=b.contains(row);
            if(first && second) continue;
            differences.add(Map.of("member",row.substring(0,row.indexOf('|')),
                "reason",first?"MISSING_ON_SECOND":"MISSING_ON_FIRST",
                "firstAddress",first?addresses.get(address):0,
                "secondAddress",second?addresses.get(address):0));
        }
        return Map.of("firstEntries",a.size(),"secondEntries",b.size(),"differences",differences);
    }

    public record Assessment(String status,Map<String,Object> derived) {}
    public static Assessment sessionContinuity() {
        return new Assessment("NOT_EVALUATED",Map.of("reason","SESSION_CONTINUITY_NOT_EVALUATED"));
    }

    public static String trafficStatus(double baseline,double current) {
        if(!Double.isFinite(baseline) || !Double.isFinite(current) || baseline<=0 || current<0)
            return "UNKNOWN";
        return current/baseline>=TRAFFIC_MIN_RATIO?"PASS":"FAIL";
    }

    /** Reuse the inventory parser; retain policy identities only in memory. */
    public static Policy cpstatPolicy(String output) {
        var read=com.securityexpert.nexus.ui2.worker.inventory.policy.CheckPointPolicyParser.parse(output);
        String time=read.installedAt().map(t -> DateTimeFormatter.ofPattern("dMMMuuuu H:mm:ss",Locale.ENGLISH)
            .withZone(ZoneId.of("Europe/Istanbul")).format(t)).orElse(null);
        return new Policy(read.policyName().isPresent()?"PASS":"UNKNOWN",read.policyName().orElse(null),time);
    }

    public static Assessment policyParity(Policy a,Policy b,String previousA,String previousB) {
        var d=new HashMap<String,Object>();
        if(a.installedAt()!=null) d.put("firstInstalledAt",a.installedAt());
        if(b.installedAt()!=null) d.put("secondInstalledAt",b.installedAt());
        String reason,status;
        if("FAIL".equals(a.status()) || "FAIL".equals(b.status())) { status="FAIL"; reason="POLICY_MISSING"; }
        else if(!"PASS".equals(a.status()) || !"PASS".equals(b.status())) { status="UNKNOWN"; reason="POLICY_UNRECOGNIZED"; }
        else {
            boolean same=a.name().equals(b.name());
            d.put("policyNamesMatch",same);
            if(!same) { status="FAIL"; reason="POLICY_NAMES_DIFFER"; }
            else if(previousA!=null && !previousA.equals(a.name()) || previousB!=null && !previousB.equals(b.name())) {
                status="FAIL"; reason="POLICY_CHANGED";
            } else if(a.installedAt()==null || b.installedAt()==null) {
                status="UNKNOWN"; reason="INSTALL_TIME_UNRECOGNIZED";
            } else try {
                var format=DateTimeFormatter.ofPattern("dMMMuuuu H:mm:ss",Locale.ENGLISH)
                    .withResolverStyle(java.time.format.ResolverStyle.STRICT);
                long seconds=Duration.between(LocalDateTime.parse(a.installedAt(),format),
                    LocalDateTime.parse(b.installedAt(),format)).abs().getSeconds();
                d.put("installSkewSeconds",seconds);
                status=seconds<=POLICY_MAX_SKEW_SECONDS?"PASS":"FAIL";
                reason=seconds<=POLICY_MAX_SKEW_SECONDS?"POLICY_MATCH":"INSTALL_TIMES_DIFFER";
            } catch(DateTimeParseException invalid) { status="UNKNOWN"; reason="INSTALL_TIME_UNRECOGNIZED"; }
        }
        d.put("reason",reason);
        return new Assessment(status,Map.copyOf(d));
    }

    public record Interfaces(Set<String> names, Set<String> trafficNames, int required,
            boolean ccpPresent, boolean healthy) {}
    public static Interfaces interfaces(String output) {
        if(output==null) return new Interfaces(Set.of(),Set.of(),0,false,false);
        Matcher count=Pattern.compile("(?im)^\\s*Required interfaces:\\s*(\\d+)\\s*$").matcher(output);
        Matcher ccp=Pattern.compile("(?im)^\\s*CCP mode:\\s*(Automatic|Manual[^\\r\\n]*)[ \t]*$").matcher(output);
        boolean hasCount=count.find(), hasCcp=ccp.find();
        boolean framed=output.matches("(?s).*Virtual cluster interfaces:[ \t]*[0-9]+.*");
        if((output.contains("CCP mode:") && !hasCcp) || (output.contains("Required interfaces:") && !hasCount))
            return new Interfaces(Set.of(),Set.of(),0,false,false);
        if((!hasCount || !hasCcp) && !framed) return new Interfaces(Set.of(),Set.of(),0,false,false);
        int required=0;
        try { if(hasCount) required=Integer.parseInt(count.group(1)); }
        catch(NumberFormatException invalid) { return new Interfaces(Set.of(),Set.of(),0,false,false); }
        Set<String> up=new HashSet<>(), traffic=new HashSet<>(); boolean down=false;
        int monitored=0;
        for(String line:output.split("(?i)Virtual cluster interfaces:",2)[0].split("\\R")) {
            String text=line.strip();
            if(text.isEmpty() || text.matches("-+") || text.startsWith("vsid ") || text.startsWith("Required ")
                || text.startsWith("CCP mode:") || text.startsWith("Interface Name:")
                || text.equals("S - sync, HA/LS - bond type, LM - link monitor, P - probing")) continue;
            Matcher m=INTERFACE.matcher(line);
            if(!m.matches()) return new Interfaces(Set.of(),Set.of(),required,false,false);
            if("Non-Monitored".equals(m.group(3))) continue;
            monitored++;
            if("UP".equals(m.group(3))) {
                up.add(m.group(1));
                boolean sync=(m.group(2)!=null && java.util.Arrays.asList(m.group(2).split(",[ \t]*")).contains("S")) || "Sync".equalsIgnoreCase(m.group(1))
                    || (m.group(4).toLowerCase(Locale.ROOT).contains("sync")
                        && !m.group(4).toLowerCase(Locale.ROOT).contains("non sync"));
                if(!sync && !m.group(1).matches("(?i)(?:lo|mgmt|management)(?:[0-9.].*)?")) traffic.add(m.group(1));
            } else down=true;
        }
        // Prefer scoped subinterfaces over an overlapping parent aggregate.
        Set<String> selected=Set.copyOf(traffic);
        traffic.removeIf(parent -> selected.stream().anyMatch(child -> child.startsWith(parent+".")));
        if(!hasCount) required=monitored;
        return new Interfaces(Set.copyOf(up),Set.copyOf(traffic),required,true,
            monitored>0 && up.size()>=required && !down);
    }
    public static int arpCount(String output) {
        if(output==null) return -1;
        int count=0; for(String line:output.split("\\R")) {
            if(line.isBlank() || count==0 && line.matches("(?i)^Context is set to Virtual Device [^\\r\\n]+ \\(ID [0-9]+\\)\\.$")) continue;
            if(!line.matches("^\\? \\([0-9a-fA-F:.]+\\) at .+ on [A-Za-z0-9_.:-]+.*$")) return -1;
            count++;
        }
        return count;
    }
    public record TrafficBytes(long received,long transmitted) {}
    public static Map<String,TrafficBytes> bytesByInterface(String output) {
        if(output==null || !output.contains("Receive") || !output.contains("Transmit")) return Map.of();
        Map<String,TrafficBytes> counters=new HashMap<>(); Matcher m=DEV.matcher(output);
        while(m.find()) {
            try {
                String[] columns=m.group(2).trim().split("\\s+");
                if(counters.put(m.group(1),new TrafficBytes(Long.parseLong(columns[0]),Long.parseLong(columns[8])))!=null)
                    return Map.of();
            } catch (NumberFormatException | ArithmeticException invalid) { return Map.of(); }
        }
        return Map.copyOf(counters);
    }
    public static double trafficBytesPerSecond(Map<String,TrafficBytes> before,Map<String,TrafficBytes> after,
            Set<String> interfaces,long elapsedNanos) {
        if(before.isEmpty() || after.isEmpty() || interfaces.isEmpty() || elapsedNanos<=0) return -1;
        // The interface list has no slave mapping: mixed bond/physical selection cannot prove non-overlap.
        if(interfaces.stream().anyMatch(n -> n.startsWith("bond"))
                && interfaces.stream().anyMatch(n -> !n.startsWith("bond"))) return -1;
        long delta=0;
        for(String name:interfaces) {
            TrafficBytes a=before.get(name),b=after.get(name);
            if(a==null || b==null || a.received()<0 || a.transmitted()<0
                    || b.received()<a.received() || b.transmitted()<a.transmitted()) return -1;
            try { delta=Math.addExact(delta,Math.addExact(b.received()-a.received(),b.transmitted()-a.transmitted())); }
            catch(ArithmeticException overflow) { return -1; }
        }
        return delta/(elapsedNanos/1_000_000_000.0);
    }
    public static String syncStatus(String output) {
        if(output==null) return "UNKNOWN";
        Matcher status=SYNC_STATUS.matcher(output);
        if(!status.find()) return "UNKNOWN";
        String state=status.group(1);
        if(status.find()) return "UNKNOWN";
        if(!"OK".equals(state)) return "FAIL";
        boolean lost=false;
        String[] labels={"Lost updates", "Lost bulk update events", "Unsynchronized updates"};
        for(int i=0;i<labels.length;i++) {
            Matcher counter=Pattern.compile("(?m)^"+labels[i]+"\\.{2,}\\s*(\\d+)\\s*$").matcher(output);
            boolean found=counter.find();
            String value=found?counter.group(1):null;
            if((!found && i<2) || (found && counter.find())) return "UNKNOWN";
            if(found && !value.matches("0+")) lost=true;
        }
        return lost?"FAIL":"PASS";
    }
    public record SyncAssessment(String status,Map<String,Object> derived) {}
    /** Readiness compares cumulative counters only within the same member and VS. */
    public static SyncAssessment readinessSync(String output,Map<String,Object> previous,Instant observedAt) {
        var derived=new HashMap<>(syncInformation(output));
        String state=(String)derived.get("syncStatus");
        if(state==null || "UNKNOWN".equals(syncStatus(output))) return new SyncAssessment("UNKNOWN",Map.copyOf(derived));
        if(!"OK".equals(state)) return new SyncAssessment("FAIL",Map.copyOf(derived));
        if(!derived.containsKey("lostUpdates") || !derived.containsKey("lostBulkUpdateEvents"))
            return new SyncAssessment("UNKNOWN",Map.copyOf(derived));
        boolean baseline=observedAt!=null && previous.containsKey("lostUpdates")
            && previous.containsKey("lostBulkUpdateEvents");
        boolean increased=false,reset=false;
        for(String key:List.of("lostUpdates","lostBulkUpdateEvents","unsynchronizedUpdates")) {
            if(!derived.containsKey(key)) continue;
            if(!baseline || !previous.containsKey(key)) continue;
            try {
                var delta=new java.math.BigInteger(derived.get(key).toString())
                    .subtract(new java.math.BigInteger(previous.get(key).toString()));
                derived.put(key+"Increase",delta.max(java.math.BigInteger.ZERO));
                increased |= delta.signum()>0;
                reset |= delta.signum()<0;
            } catch(NumberFormatException invalid) {
                return new SyncAssessment("UNKNOWN",Map.copyOf(derived));
            }
        }
        derived.put("baselineRecorded",!baseline);
        derived.put("counterReset",reset);
        if(baseline) derived.put("baselineAt",observedAt.toString());
        return new SyncAssessment(increased?"FAIL":"PASS",Map.copyOf(derived));
    }

    public static Map<String,Object> syncInformation(String output) {
        Map<String,Object> result=new HashMap<>();
        if(output==null) return Map.of();
        Matcher status=SYNC_STATUS.matcher(output);
        if(status.find()) {
            String state=status.group(1).strip();
            if(!status.find()) result.put("syncStatus",state);
        }
        String[] labels={"Lost updates", "Lost bulk update events", "Unsynchronized updates"};
        String[] keys={"lostUpdates", "lostBulkUpdateEvents", "unsynchronizedUpdates"};
        for(int i=0;i<labels.length;i++) {
            Matcher counter=Pattern.compile("(?m)^"+labels[i]+"\\.{2,}\\s*(\\d+)\\s*$").matcher(output);
            if(counter.find()) {
                var value=new java.math.BigInteger(counter.group(1));
                if(!counter.find()) result.put(keys[i],value);
            }
        }
        for(String direction:List.of("Sent", "Received")) {
            Matcher counter=Pattern.compile("(?m)^[ \\t]*"+direction
                +" reject notifications\\.{2,}[ \\t]*(\\d+)[ \\t]*$").matcher(output);
            if(counter.find()) try {
                long value=Long.parseLong(counter.group(1));
                if(!counter.find()) result.put(direction.toLowerCase(Locale.ROOT)+"RejectNotifications",value);
            } catch(NumberFormatException invalid) { /* Unavailable informational counter. */ }
        }
        return Map.copyOf(result);
    }

    public record Pnotes(String status,List<String> names) {}
    private static final Set<String> BUILTIN_PNOTES=Set.of("Problem Notification", "Interface Active Check",
        "Load Balancing Configuration", "Recovery Delay", "CoreXL Configuration", "Fullsync", "Policy", "fwd",
        "cphad", "routed", "cvpnd", "ted", "VSX", "VSX Config", "Instances", "Hibernating", "Init", "ADMIN_DOWN");
    public static Pnotes pnotes(String output) { return pnotes(output,false); }
    public static Pnotes pnotes(String output,boolean expectedAdminDown) {
        if(output==null) return new Pnotes("UNKNOWN",List.of());
        List<String> names=new ArrayList<>();
        Matcher name=Pattern.compile("(?m)^[ \\t]*Device Name:[ \\t]*([^\\r\\n]+)$").matcher(output);
        while(name.find()) {
            String value=name.group(1).strip();
            if(value.equals("admin_down")) value="ADMIN_DOWN";
            names.add(BUILTIN_PNOTES.contains(value)?value:"CUSTOM_PNOTE_"+(names.size()+1));
        }
        if(!names.isEmpty()) return new Pnotes(expectedAdminDown && names.equals(List.of("ADMIN_DOWN"))?"PASS":"FAIL",List.copyOf(names));
        return new Pnotes(output.strip().equals("There are no pnotes in problem state")?"PASS":"UNKNOWN",List.of());
    }

    public static String bonds(String output) {
        if(output==null) return "UNKNOWN";
        if(output.strip().equals("No bond interfaces are configured.")) return "PASS";
        boolean header=false, row=false, failed=false;
        for(String line:output.split("\\R")) {
            if(line.strip().startsWith("Legend:")) break;
            if(!line.contains("|")) continue;
            String[] c=line.strip().split("\\|",-1);
            if(!header && !line.contains("Bond name")) continue;
            if(c.length!=6) return "UNKNOWN";
            for(int i=0;i<c.length;i++) c[i]=c[i].strip();
            if(c[2].equals("State") && c[5].equals("required")) { header=true; continue; }
            if(!header) continue;
            try {
                long configured=Long.parseLong(c[3]), up=Long.parseLong(c[4]), required=Long.parseLong(c[5]);
                if(configured<0 || up<0 || required<0 || up>configured) return "UNKNOWN";
                row=true;
                failed |= !c[2].equals("UP") || up<required;
            } catch(NumberFormatException invalid) { return "UNKNOWN"; }
        }
        return !row?"UNKNOWN":failed?"FAIL":"PASS";
    }

    public record Failover(String status,String lastFailoverAt) {}
    public static Failover lastFailover(String output,Instant now) {
        if(output==null) return new Failover("UNKNOWN",null);
        Matcher time=Pattern.compile("(?m)^[ \\t]*Event time:[ \\t]*([^\\r\\n]+)$").matcher(output);
        if(!time.find()) return new Failover("UNKNOWN",null);
        String text=time.group(1).strip().replaceAll("[ \\t]+"," ");
        if(time.find()) return new Failover("UNKNOWN",null);
        try {
            // Same estate timezone assumption as CheckPointPolicyParser; unverified per device.
            Instant event=LocalDateTime.parse(text,DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss uuuu",Locale.ENGLISH)
                .withResolverStyle(java.time.format.ResolverStyle.STRICT)).atZone(ZoneId.of("Europe/Istanbul")).toInstant();
            Duration age=Duration.between(event,now);
            return new Failover(age.isNegative()?"UNKNOWN":age.compareTo(Duration.ofHours(6))<0?"WARN":"PASS",event.toString());
        } catch(DateTimeParseException invalid) { return new Failover("UNKNOWN",null); }
    }

    public record Routing(int count,boolean defaultRoute) {}
    public static Routing routing(String output) {
        if(output==null) return null;
        boolean header=false, defaultRoute=false; int count=0;
        for(String line:output.split("\\R")) {
            if(line.isBlank() || line.strip().matches("[-+]+")) continue;
            if(!line.contains("|")) { if(header) return null; else continue; }
            String[] c=line.strip().split("\\|",-1);
            if(c.length!=6 || !c[0].isBlank() || !c[5].isBlank()) return null;
            for(int i=1;i<5;i++) c[i]=c[i].strip();
            if(c[1].equals("Destination") && c[2].equals("Mask") && c[3].equals("GateWay") && c[4].equals("Interface")) {
                if(header) return null;
                header=true; continue;
            }
            if(!header) return null;
            for(int i=1;i<=3;i++) {
                if(!c[i].matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) return null;
                for(String octet:c[i].split("\\.")) if(Integer.parseInt(octet)>255) return null;
            }
            if(c[4].isBlank()) return null;
            count++;
            defaultRoute |= c[1].equals("0.0.0.0") && c[2].equals("0.0.0.0");
        }
        return header?new Routing(count,defaultRoute):null;
    }
    public record Policy(String status,String name,String installedAt) {}
    public static Policy policy(String output) {
        if(output==null || !output.stripLeading().matches("(?s)^HOST[ \t]+POLICY[ \t]+DATE[ \t]*(?:\\R.*|$)")) return new Policy("UNKNOWN",null,null);
        Matcher row=POLICY_ROW.matcher(output);
        if(!row.find()) return output.strip().matches("HOST[ \t]+POLICY[ \t]+DATE")
            ?new Policy("FAIL",null,null):new Policy("UNKNOWN",null,null);
        String name=row.group(1), installedAt=row.group(2)+" "+row.group(3);
        if(row.find()) return new Policy("UNKNOWN",null,null);
        return new Policy("PASS",name,installedAt);
    }
}
