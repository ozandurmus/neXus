package com.securityexpert.nexus.ui2.worker.failover;

import java.util.HashMap;
import java.util.HashSet;
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
    private static final Pattern IP_ROW = Pattern.compile("(?m)^\\s*([^\\s()]+)\\s+(\\d+)\\s+([0-9a-fA-F:.]+)\\s*$");
    private static final Pattern INTERFACE = Pattern.compile("(?m)^\\s*([A-Za-z][A-Za-z0-9_.:-]{0,63})\\s+(UP|DOWN|Non-Monitored)\\b(.*)$");
    private static final Pattern CONNECTION = Pattern.compile("(?im)^\\s*\\S+\\s+connections\\s+\\d+\\s+(\\d+)\\s+(\\d+)\\s+\\d+\\s*$");
    private static final Pattern DEV = Pattern.compile("(?m)^\\s*([A-Za-z0-9_.:-]{1,64}):\\s*((?:\\d+\\s+){15}\\d+)\\s*$");
    private static final Pattern SYNC_STATUS = Pattern.compile("(?m)^Sync status:\\s*(OK|Off\\s*-.*|Fullsync in progress|Problem\\s*\\(.*\\))\\s*$");
    private static final Pattern POLICY_ROW = Pattern.compile("(?m)^localhost\\s+(\\S+)\\s+(\\d{1,2}[A-Za-z]{3}\\d{4})\\s+(\\d{2}:\\d{2}:\\d{2})\\s+:.*$");
    private CpFailoverChecks() {}

    public record State(String mode, Map<String,String> members, String localId, String localRole) {
        public boolean pair() { return members.size()==2 && members.values().stream().filter("ACTIVE"::equals).count()==1
            && members.values().stream().filter("STANDBY"::equals).count()==1 && localId!=null; }
    }
    public static State state(String output) {
        State unknown=new State("UNKNOWN",Map.of(),null,"UNKNOWN");
        if (!CheckPointHaStateParser.clusterModeOf(output).filter("High Availability"::equals).isPresent())
            return unknown;
        Map<String,String> roles=new HashMap<>(); String local=null;
        for (String line:output.split("\\R")) {
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
        Set<String> rows=new HashSet<>(); Matcher m=IP_ROW.matcher(output);
        while(m.find()) rows.add(m.group(1)+"|"+m.group(2)+"|"+m.group(3));
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
        Matcher ccp=Pattern.compile("(?im)^\\s*CCP mode:\\s*(Automatic|Manual[^\\r\\n]*)$").matcher(output);
        if(!count.find() || !ccp.find()) return new Interfaces(Set.of(),Set.of(),0,false,false);
        int required;
        try { required=Integer.parseInt(count.group(1)); }
        catch(NumberFormatException invalid) { return new Interfaces(Set.of(),Set.of(),0,false,false); }
        Set<String> up=new HashSet<>(), traffic=new HashSet<>(); boolean down=false; Matcher m=INTERFACE.matcher(output);
        while(m.find()) {
            if("UP".equals(m.group(2))) {
                up.add(m.group(1));
                if(m.group(3).toLowerCase(java.util.Locale.ROOT).matches(".*\\bnon\\s+sync\\b.*"))
                    traffic.add(m.group(1));
            }
            else if("DOWN".equals(m.group(2))) down=true;
        }
        return new Interfaces(Set.copyOf(up),Set.copyOf(traffic),required,true,
            required>0 && up.size()>=required && !down);
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
