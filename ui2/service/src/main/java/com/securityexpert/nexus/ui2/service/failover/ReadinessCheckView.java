package com.securityexpert.nexus.ui2.service.failover;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/** Display only safe counts, enums and relationships; never echo topology or policy names. */
public final class ReadinessCheckView {
    private ReadinessCheckView() {}

    public static Map<String,Object> fields(String vendor,int no,String member,String status,JsonNode d,Instant observedAt) {
        boolean pan="palo_alto".equals(vendor);
        // A VSX chassis has no traffic interface in VS0: each Virtual System's unit measures its own traffic.
        boolean perVirtualSystem=!pan && no==8 && "NOT_EVALUATED".equals(status)
            && "TRAFFIC_MEASURED_PER_VIRTUAL_SYSTEM".equals(d.path("reason").asText());
        return Map.of("title",title(pan,no),"member",member,"result",status,
            "blocking",pan || (no!=5 && no!=13 && !perVirtualSystem),"summary",summary(pan,no,status,d,observedAt));
    }

    private static String title(boolean pan,int no) {
        if (pan) return switch(no) {
            case 1 -> "HA mode and roles"; case 2 -> "Peer relationship"; case 3 -> "HA links";
            case 4 -> "Configuration sync"; case 5 -> "Session synchronization";
            case 6 -> "Sessions carried"; case 7 -> "Version parity"; default -> "Check";
        };
        return switch(no) {
            case 1 -> "Cluster state"; case 2 -> "Cluster IP table"; case 3 -> "Cluster interfaces";
            case 5 -> "ARP"; case 6 -> "Connections"; case 8 -> "Traffic rate";
            case 9 -> "State synchronization"; case 10 -> "Installed policy parity";
            case 11 -> "Critical devices"; case 12 -> "Bond interfaces";
            case 13 -> "Last failover"; case 14 -> "Routing"; case 15 -> "Recovery mode"; default -> "Check";
        };
    }

    private static String summary(boolean pan,int no,String status,JsonNode d,Instant observedAt) {
        boolean pass="PASS".equals(status);
        if (!pan && no==8 && "NOT_EVALUATED".equals(status)
                && "TRAFFIC_MEASURED_PER_VIRTUAL_SYSTEM".equals(d.path("reason").asText()))
            return "Measured per Virtual System";
        if ("UNKNOWN".equals(status)) {
            if (pan) return switch(no) {
                case 1 -> "HA mode or roles not recognised";
                case 2 -> "Peer relationship could not be verified";
                case 3 -> "HA link state not recognised";
                case 4 -> "Configuration sync field missing or not recognised";
                case 5 -> sessionSyncSummary(status,d);
                case 6 -> "not_collected".equals(d.path("reason").asText())
                    ? "Not collected: " + d.path("detail").asText("show session info retired by PO 2026-10-21")
                    // PO 2026-10-24: counts from show session info and show session meter are never compared.
                    : "mixed_sources".equals(d.path("reason").asText())
                    ? "Session counts not comparable: read with " + sessionSource(d.path("source").asText())
                        + " and " + sessionSource(d.path("other_source").asText())
                    : "session meter".equals(d.path("source").asText())
                    ? "Session count missing or not recognised (session meter): /response/result/entry/current"
                    : "Session count missing or not recognised: /response/result/num-active or active-sessions";
                case 7 -> "not_collected".equals(d.path("reason").asText())
                    // A row recorded between the system info retirement and the HA-state version check (2026-10-21).
                    ? "Not collected: show system info retired by PO 2026-10-21 (this check now reads the HA state)"
                    : "Version fields missing from the HA state read: " + versionFields(d,"MISSING");
                default -> "Check evidence unavailable";
            };
            return switch(no) {
                case 1 -> "Cluster state not recognised";
                case 2 -> "tables differ".equals(d.path("reason").asText())
                    ? tableSummary(false,d)+"; equivalence could not be verified" : "Cluster IP table not recognised";
                case 3 -> "Interface table not recognised";
                case 5 -> "ARP table or active member not recognised";
                case 6 -> "Connection table or active member not recognised";
                case 8 -> "Traffic counters or monitored interfaces unavailable";
                case 9 -> "Sync state or counters not recognised";
                case 10 -> policySummary(status,d);
                case 11 -> "Pnotes output not recognised";
                case 12 -> "Bond table not recognised";
                case 13 -> "Last failover time unavailable or invalid";
                case 14 -> "Routing table not recognised";
                case 15 -> "Recovery mode not recognised; failover is refused unless both members report Active Up";
                default -> "Check evidence unavailable";
            };
        }
        if (pan) return switch(no) {
            case 1 -> role(d);
            case 2 -> pass ? "Reciprocal peer relationship verified" : "Peer relationship not ready";
            case 3 -> pass ? "Required HA links up on both members" : "An HA link is down";
            case 4 -> pass ? "Running configuration synchronized on both members" : "Configuration not synchronized";
            case 5 -> sessionSyncSummary(status,d);
            case 6 -> count(d,"count"," active sessions")+(pass ? "" : ", below carry tolerance")
                // PO 2026-10-24: on PAN-OS 12 or later the count is the sum of show session meter's current sessions.
                +("session meter".equals(d.path("source").asText()) ? " (source: session meter)" : "");
            case 7 -> pass ? "Software and content versions match on both members"
                : "Software or content versions differ: " + versionFields(d,"MISMATCH");
            default -> "Check result unavailable";
        };
        return switch(no) {
            case 1 -> role(d)+(pass ? "" : "; cluster roles not ready");
            case 2 -> tableSummary(pass,d);
            case 3 -> d.path("up").isIntegralNumber() && d.path("required").isIntegralNumber()
                ? d.path("up").asLong()+" of "+d.path("required").asLong()+" required interfaces up"
                : "Interface counts unavailable";
            case 5 -> count(d,"count"," ARP entries");
            case 6 -> connectionSummary(pass,d);
            case 8 -> rate(d.path("bytesPerSecond"))+(pass || !measuredRate(d.path("bytesPerSecond")) ? "" : ", below tolerance");
            case 9 -> (pass ? d.path("baselineRecorded").asBoolean(false) ? "Sync OK, baseline recorded"
                : d.has("baselineAt") ? "Sync OK, no lost-counter increase"
                    +(d.path("counterReset").asBoolean(false) ? "; counter reset, baseline recorded" : "")
                : "Sync OK, 0 lost updates" : syncFailure(d))
                +(d.path("sentRejectNotifications").isIntegralNumber()
                    ? " ("+d.path("sentRejectNotifications").asLong()+" sent rejects)" : "");
            case 10 -> policySummary(status,d);
            case 11 -> pass ? "No pnotes in problem state" : "Pnotes in problem state";
            case 12 -> pass ? d.path("noneConfigured").asBoolean(false) ? "No bonds configured" : "Required bond links up" : "Bond links not ready";
            case 13 -> lastFailover(d,observedAt);
            case 14 -> (d.path("defaultRoute").isBoolean()
                ? d.path("defaultRoute").asBoolean() ? "Default route present" : "No default route" : "Default route unknown")
                +", "+count(d,"routeCount"," routes")+(pass ? "" : "; routing not ready");
            case 15 -> pass ? "Active Up: the former active returns as standby"
                : "PRIMARY_UP".equals(d.path("recoveryMode").asText())
                    ? "Primary Up: the return step could preempt; failover is refused"
                    : "Recovery mode not Active Up; failover is refused";
            default -> "Check result unavailable";
        };
    }

    /** Check 7's evidence names each field MATCH / MISMATCH / MISSING (never a version value): the names with this outcome. */
    private static String versionFields(JsonNode d,String outcome) {
        var names=new java.util.ArrayList<String>();
        for(String field:new String[]{"software","app","threat"})
            if(outcome.equals(d.path(field).asText())) names.add("software".equals(field)?"PAN-OS":field+" content");
        return names.isEmpty()?"field not recognised":String.join(", ",names);
    }

    private static String sessionSyncSummary(String status,JsonNode d) {
        String summary="UNKNOWN".equals(status)
            ?"Session sync fields missing or not recognised: /response/result/enabled and messages/entry fields enable, sent, recv and desc"
            :"PASS".equals(status)?"Sessions synchronized on both members":"Session synchronization failed or disabled";
        if(d.path("missing").isArray() && !d.path("missing").isEmpty())
            summary+="; missing fields: "+d.path("missing").size();
        if(d.path("unrecognised").isArray() && !d.path("unrecognised").isEmpty())
            summary+="; unrecognised values: "+d.path("unrecognised").size();
        for(String member:new String[]{"active","passive"}) {
            JsonNode facts=d.path(member);
            if(facts.path("enabled").isBoolean())
                summary+="; "+member+" sync "+(facts.path("enabled").asBoolean()?"enabled":"disabled");
            for(String message:new String[]{"session setup","session update"}) {
                JsonNode counters=facts.path("messages").path(message);
                if(counters.path("enable").isBoolean())
                    summary+="; "+member+" "+message+" "+(counters.path("enable").asBoolean()?"enabled":"disabled");
                for(String field:new String[]{"sent","recv"})
                    if(counters.path(field).isIntegralNumber() && counters.path(field).asLong()>=0)
                        summary+=", "+field+" "+counters.path(field).asLong();
            }
        }
        return summary;
    }

    private static String tableSummary(boolean pass,JsonNode d) {
        String summary=count(d,"entries"," cluster IP entries")+(pass?", same on both members":", tables differ");
        if(pass) return summary;
        var details=new java.util.ArrayList<String>();
        for(JsonNode row:d.path("differences")) {
            // Table coordinates are opaque strings; never render arbitrary derived text or raw addresses.
            String member=row.path("member").asText(),iface=row.path("interface").asText();
            if(!member.matches("[0-9]+") || !iface.isEmpty() && !iface.matches("[0-9]+")) continue;
            String reason=switch(row.path("reason").asText()) {
                case "ADDRESS_MISMATCH" -> "address differs ("+addressAlias(row,"firstAddress")
                    +" / "+addressAlias(row,"secondAddress")+")";
                case "MISSING_ON_FIRST" -> "missing on first observer";
                case "MISSING_ON_SECOND" -> "missing on second observer";
                default -> "difference unrecognised";
            };
            details.add("table member "+member+(iface.isEmpty()?"":", interface "+iface)+": "+reason);
        }
        return summary+(details.isEmpty()?"":"; "+String.join("; ",details));
    }
    private static String addressAlias(JsonNode d,String key) {
        return d.path(key).isIntegralNumber() && d.path(key).asLong()>0
            ? "masked address "+d.path(key).asLong():"address unavailable";
    }
    private static String connectionSummary(boolean pass,JsonNode d) {
        String summary=count(d,"count"," connections")+", "+count(d,"peak"," peak")+(pass?"":", below tolerance");
        String rule=switch(d.path("rule").asText()) {
            case "LOW_VOLUME_ABSOLUTE_OR_RATIO" -> "difference < 2000 or ratio >= 50% (active < 10000)";
            case "RATIO_80" -> "standby/active >= 80% (active >= 10000)";
            case "POST_RATIO_80" -> "new active/pre-switch active >= 80%";
            default -> "";
        };
        if(rule.isEmpty()) return summary;
        return summary+"; active baseline "+count(d,"activeCount","")+", compared "+count(d,"comparedCount","")
            +", ratio "+(d.path("ratio").isNumber()?String.format(Locale.ROOT,"%.1f%%",d.path("ratio").asDouble()*100)
                :"not applicable (zero baseline)")+"; rule: "+rule;
    }
    private static String policySummary(String status,JsonNode d) {
        return switch(d.path("reason").asText()) {
            case "POLICY_MATCH" -> "Same policy on both members; installed within 10 min";
            case "POLICY_NAMES_DIFFER" -> "Policy names differ between members";
            case "POLICY_MISSING" -> "Policy missing on a member";
            case "POLICY_CHANGED" -> "Policy changed since the pre-check";
            case "INSTALL_TIMES_DIFFER" -> "Same policy, installed "+installGap(d)+" apart (limit 10 min)";
            case "INSTALL_TIME_UNRECOGNIZED" -> "Policy install time missing or invalid";
            default -> "UNKNOWN".equals(status)?"Installed policy could not be verified"
                :"PASS".equals(status)?"Same policy on both members":"Policy missing, changed or different between members";
        };
    }
    private static String installGap(JsonNode d) {
        if(!d.path("installSkewSeconds").isIntegralNumber()) return "an unknown interval";
        long seconds=d.path("installSkewSeconds").asLong();
        return seconds%3600==0?seconds/3600+" h":seconds%60==0?seconds/60+" min":seconds+" s";
    }

    private static String syncFailure(JsonNode d) {
        String state=d.path("syncStatus").asText();
        var reasons=new java.util.ArrayList<String>();
        if(!state.isEmpty() && !"OK".equals(state)) reasons.add("Sync status: "+state);
        String[] keys={"lostUpdates", "lostBulkUpdateEvents", "unsynchronizedUpdates"};
        String[] labels={"lost updates", "lost bulk update events", "unsynchronized updates"};
        for(int i=0;i<keys.length;i++) {
            JsonNode increase=d.path(keys[i]+"Increase");
            if(increase.isIntegralNumber() && increase.bigIntegerValue().signum()>0) {
                try {
                    reasons.add("+"+increase.bigIntegerValue()+" "+labels[i]+" since "+Instant.parse(d.path("baselineAt").asText()));
                } catch(java.time.DateTimeException invalid) { reasons.add("Lost-counter increase; baseline time unavailable"); }
                continue;
            }
            JsonNode value=d.path(keys[i]);
            if(!d.has("baselineAt") && value.isIntegralNumber() && value.bigIntegerValue().signum()>0)
                reasons.add(value.bigIntegerValue()+" "+labels[i]+" (counter since boot)");
        }
        if(reasons.isEmpty()) return "Sync not ready";
        return ("OK".equals(state) ? "Sync OK but " : "")+String.join("; ",reasons);
    }

    /** Pilot 2026-10-21/22: the worker stores the rate as a double (1.4E9), which the integral count read as unavailable. */
    private static boolean measuredRate(JsonNode value) {
        return value.isNumber() && Double.isFinite(value.asDouble()) && value.asDouble()>=0;
    }
    /** A measured byte rate in decimal units: "400 bytes/s", "1.4 GB/s"; a negative (-1, not measured) rate is unavailable. */
    static String rate(JsonNode value) {
        if(!measuredRate(value)) return "Rate unavailable";
        double rate=value.asDouble();
        if(rate<1000) return Math.round(rate)+" bytes/s";
        String[] units={"KB/s","MB/s","GB/s","TB/s"};
        int unit=-1;
        while(rate>=1000 && unit<units.length-1) { rate/=1000; unit++; }
        if(Math.round(rate*10)>=10000 && unit<units.length-1) { rate/=1000; unit++; }
        return String.format(Locale.ROOT,"%.1f %s",rate,units[unit]).replace(".0 "," ");
    }
    private static String sessionSource(String source) {
        return "session meter".equals(source) ? "show session meter" : "show session info";
    }
    private static String count(JsonNode d,String key,String suffix) {
        return d.path(key).isIntegralNumber() ? d.path(key).asLong()+suffix : "Count unavailable";
    }
    private static String role(JsonNode d) {
        return switch(d.path("role").asText().toUpperCase(Locale.ROOT)) {
            case "ACTIVE" -> "Active"; case "STANDBY" -> "Standby"; case "PASSIVE" -> "Passive";
            case "DOWN" -> "Down"; case "SUSPENDED" -> "Suspended"; case "READY" -> "Ready";
            default -> "Role not recognised";
        };
    }
    private static String lastFailover(JsonNode d,Instant observedAt) {
        try {
            Instant event=Instant.parse(d.path("lastFailoverAt").asText());
            long days=Duration.between(event,observedAt).toDays();
            if (event.isAfter(observedAt)) return "Last failover time invalid";
            return "Last failover "+DateTimeFormatter.ofPattern("d MMM uuuu",Locale.ENGLISH)
                .withZone(ZoneOffset.UTC).format(event)+" ("+days+" days before this check)";
        } catch (java.time.DateTimeException invalid) { return "Last failover time unavailable"; }
    }
}
