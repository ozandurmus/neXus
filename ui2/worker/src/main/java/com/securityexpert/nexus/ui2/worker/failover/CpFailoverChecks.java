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
    public static final double ARP_MIN_RATIO = 0.80;
    public static final double CONNECTION_MIN_RATIO = 0.80;
    public static final double TRAFFIC_MIN_RATIO = 0.50;
    private static final Pattern LOCAL = Pattern.compile("(?i)\\(local\\)");
    private static final Pattern IP_ROW = Pattern.compile("(?m)^\\s*([^\\s()]+)\\s+(\\d+)\\s+([0-9a-fA-F:.]+)(?:[ \\t]+[0-9a-fA-F:]+)?[ \\t]*$");
    private static final Pattern INTERFACE = Pattern.compile("(?m)^\\s*([A-Za-z][A-Za-z0-9_.:-]{0,63})[ \\t]+(?:\\(((?:S|HA|LS|LM|P)(?:,[ \t]*(?:S|HA|LS|LM|P))*)\\)[ \\t]+)?(UP|DOWN|Non-Monitored)\\b(.*)$");
    private static final Pattern CONNECTION = Pattern.compile("(?im)^\\s*\\S+\\s+connections\\s+\\d+\\s+(\\d+)\\s+(\\d+)\\s+\\d+\\s*$");
    private static final Pattern DEV = Pattern.compile("(?m)^\\s*([A-Za-z0-9_.:-]{1,64}):\\s*((?:\\d+\\s+){15}\\d+)\\s*$");
    private static final Pattern SYNC_STATUS = Pattern.compile("(?m)^Sync status:\\s*(OK|Off\\s*-.*|Fullsync in progress|Problem\\s*\\(.*\\))\\s*$");
    private static final Pattern POLICY_ROW = Pattern.compile("(?m)^localhost\\s+(\\S+)\\s+(\\d{1,2}[A-Za-z]{3}\\d{4})\\s+(\\d{1,2}:\\d{2}:\\d{2})\\s+:.*$");
    private CpFailoverChecks() {}

    public record State(String mode, Map<String,String> members, String localId, String localRole) {
        public boolean pair() { return members.size()==2 && members.values().stream().filter("ACTIVE"::equals).count()==1
            && members.values().stream().filter("STANDBY"::equals).count()==1 && localId!=null; }
    }
    public static State state(String output) {
        State unknown=new State("UNKNOWN",Map.of(),null,"UNKNOWN");
        if (!CheckPointHaStateParser.clusterModeOf(output).filter(mode -> mode.equals("High Availability")
                || mode.equals("Virtual System Load Sharing")).isPresent())
            return unknown;
        Map<String,String> roles=new HashMap<>(); String local=null;
        for (String line:output.split("\\R")) {
            if (line.toLowerCase(Locale.ROOT).contains("virtual devices status on each cluster member")) break;
            Matcher marker=LOCAL.matcher(line);
            boolean isLocal=marker.find();
            String[] columns=marker.replaceAll(" ").strip().split("\\s+");
            if (columns.length<3 || !columns[1].matches("[0-9a-fA-F:.]+")) continue;
            int stateColumn=columns[2].endsWith("%")?3:2;
            if (columns.length<=stateColumn) return unknown;
            String role=CheckPointHaStateParser.memberStateOf(columns[stateColumn]).orElse(null);
            if (role==null || roles.putIfAbsent(columns[0],role)!=null) return unknown;
            if (isLocal) {
                if (local!=null) return unknown;
                local=columns[0];
            }
        }
        return roles.size()<2 || local==null?unknown:new State("HA",Map.copyOf(roles),local,roles.get(local));
    }
    public static boolean corroborated(State a,State b) {
        return a.pair() && b.pair() && a.members().equals(b.members()) && !a.localId().equals(b.localId());
    }

    /** Compare complete tables in memory; never persist addresses or interface identifiers. */
    public static Set<String> ipTable(String output) {
        if (output==null || !output.contains("Unique IP's Table")) return Set.of();
        Set<String> rows=new HashSet<>(); Map<String,String> mapping=new HashMap<>();
        for(String raw:output.split("\\R")) {
            String line=LOCAL.matcher(raw).replaceAll(" ");
            if(!line.strip().matches("[0-9].*")) continue;
            Matcher m=IP_ROW.matcher(line);
            if(!m.matches()) return Set.of();
            String key=m.group(1)+"|"+m.group(2);
            String previous=mapping.putIfAbsent(key,m.group(3));
            if(previous!=null && !previous.equals(m.group(3))) return Set.of();
            rows.add(key+"|"+m.group(3));
        }
        return Set.copyOf(rows);
    }
    public static boolean twoTableMembers(Set<String> rows) {
        return rows.stream().map(row -> row.substring(0,row.indexOf('|'))).distinct().count()==2;
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
                if(!sync) traffic.add(m.group(1));
            } else down=true;
        }
        if(!hasCount) required=monitored;
        return new Interfaces(Set.copyOf(up),Set.copyOf(traffic),required,true,
            monitored>0 && up.size()>=required && !down);
    }
    public static int arpCount(String output) {
        if(output==null) return -1;
        int count=0; for(String line:output.split("\\R")) {
            if(line.isBlank()) continue;
            if(!line.matches("^\\? \\([0-9a-fA-F:.]+\\) at .+ on [A-Za-z0-9_.:-]+.*$")) return -1;
            count++;
        }
        return count;
    }
    public record Connections(long count,long peak) {}
    public static Connections connections(String output) {
        if(output==null || !output.contains("#VALS") || !output.contains("#PEAK")) return null;
        Matcher m=CONNECTION.matcher(output);
        if(!m.find()) return null;
        try { return new Connections(Long.parseLong(m.group(1)),Long.parseLong(m.group(2))); }
        catch(NumberFormatException invalid) { return null; }
    }
    public static Map<String,Long> bytesByInterface(String output) {
        if(output==null || !output.contains("Receive") || !output.contains("Transmit")) return Map.of();
        Map<String,Long> counters=new HashMap<>(); Matcher m=DEV.matcher(output);
        while(m.find()) {
            try {
                String[] columns=m.group(2).trim().split("\\s+");
                counters.put(m.group(1),Math.addExact(Long.parseLong(columns[0]),Long.parseLong(columns[8])));
            } catch (NumberFormatException | ArithmeticException invalid) { return Map.of(); }
        }
        return Map.copyOf(counters);
    }
    public static long trafficBytesPerSecond(Map<String,Long> before,Map<String,Long> after,Set<String> interfaces) {
        if(before.isEmpty() || after.isEmpty() || interfaces.isEmpty()) return -1;
        long delta=0;
        for(String name:interfaces) {
            Long a=before.get(name),b=after.get(name);
            if(a==null || b==null || b<a) return -1;
            try { delta=Math.addExact(delta,b-a); }
            catch(ArithmeticException overflow) { return -1; }
        }
        return delta/5;
    }
    public static boolean ratio(long value,long baseline,double minimum) {
        return baseline>=0 && value>=0 && value>=baseline*minimum;
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
    public static Map<String,Long> syncInformation(String output) {
        Map<String,Long> result=new HashMap<>();
        if(output==null) return Map.of();
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
    public static Pnotes pnotes(String output) {
        if(output==null) return new Pnotes("UNKNOWN",List.of());
        List<String> names=new ArrayList<>();
        Matcher name=Pattern.compile("(?m)^[ \\t]*Device Name:[ \\t]*([^\\r\\n]+)$").matcher(output);
        while(name.find()) {
            String value=name.group(1).strip();
            names.add(BUILTIN_PNOTES.contains(value)?value:"CUSTOM_PNOTE_"+(names.size()+1));
        }
        if(!names.isEmpty()) return new Pnotes("FAIL",List.copyOf(names));
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
        if(output==null || !output.matches("(?s)^HOST POLICY DATE\\s*.*")) return new Policy("UNKNOWN",null,null);
        Matcher row=POLICY_ROW.matcher(output);
        if(!row.find()) return output.trim().equals("HOST POLICY DATE")
            ?new Policy("FAIL",null,null):new Policy("UNKNOWN",null,null);
        String name=row.group(1), installedAt=row.group(2)+" "+row.group(3);
        if(row.find()) return new Policy("UNKNOWN",null,null);
        return new Policy("PASS",name,installedAt);
    }
}
