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
        return Map.of("title",title(pan,no),"member",member,"result",status,
            "blocking",pan || (no!=5 && no!=13),"summary",summary(pan,no,status,d,observedAt));
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
            case 13 -> "Last failover"; case 14 -> "Routing"; default -> "Check";
        };
    }

    private static String summary(boolean pan,int no,String status,JsonNode d,Instant observedAt) {
        boolean pass="PASS".equals(status);
        if ("UNKNOWN".equals(status)) {
            if (pan) return switch(no) {
                case 1 -> "HA mode or roles not recognised";
                case 2 -> "Peer relationship could not be verified";
                case 3 -> "HA link state not recognised";
                case 4 -> "Configuration sync field missing or not recognised";
                case 5 -> "Session sync fields missing or not recognised: /response/result/enabled and messages/entry fields enable, sent, recv and desc";
                case 6 -> "Session count missing or not recognised: /response/result/num-active or active-sessions";
                case 7 -> "Version fields missing or not recognised";
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
                default -> "Check evidence unavailable";
            };
        }
        if (pan) return switch(no) {
            case 1 -> role(d);
            case 2 -> pass ? "Reciprocal peer relationship verified" : "Peer relationship not ready";
            case 3 -> pass ? "Required HA links up on both members" : "An HA link is down";
            case 4 -> pass ? "Running configuration synchronized on both members" : "Configuration not synchronized";
            case 5 -> pass ? "Sessions synchronized on both members" : "Session synchronization failed or disabled";
            case 6 -> count(d,"count"," active sessions")+(pass ? "" : ", below carry tolerance");
            case 7 -> pass ? "Software and content versions match on both members" : "Software or content versions differ";
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
            case 8 -> count(d,"bytesPerSecond"," bytes/s")+(pass ? "" : ", below tolerance");
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
            default -> "Check result unavailable";
        };
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
