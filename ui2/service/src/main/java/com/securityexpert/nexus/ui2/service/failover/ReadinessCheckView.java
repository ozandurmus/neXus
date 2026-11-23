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

    /** "Traffic and ARP (information)": check 10 of Cisco ASA and FortiGate, check 8 of Palo Alto. Never blocking. */
    private static final int TRAFFIC_ARP=10,PAN_TRAFFIC_ARP=8;
    private static final String TRAFFIC_ARP_TITLE="Traffic and ARP (information)";

    public static Map<String,Object> fields(String vendor,int no,String member,String status,JsonNode d,Instant observedAt) {
        if ("cisco_asa".equals(vendor)) return Map.of("title",asaTitle(no),"member",member,"result",status,
            "blocking",no!=9 && no!=TRAFFIC_ARP,"summary",asaSummary(no,status,d));
        if ("fortinet".equals(vendor)) return Map.of("title",fgtTitle(no),"member",member,"result",status,
            "blocking",no!=6 && no!=9 && no!=TRAFFIC_ARP,"summary",fgtSummary(no,status,d));
        boolean pan="palo_alto".equals(vendor);
        // A VSX chassis has no traffic interface in VS0: each Virtual System's unit measures its own traffic.
        boolean perVirtualSystem=!pan && no==8 && "NOT_EVALUATED".equals(status)
            && "TRAFFIC_MEASURED_PER_VIRTUAL_SYSTEM".equals(d.path("reason").asText());
        // WARN is the readiness model's non-blocking warning (PO 2026-11-23: the VS0 policy row of a VSX chassis).
        var fields=new java.util.LinkedHashMap<String,Object>(Map.of("title",title(pan,no),"member",member,"result",status,
            "blocking",pan ? no!=PAN_TRAFFIC_ARP : (no!=5 && no!=13 && !perVirtualSystem && !"WARN".equals(status)),"summary",summary(pan,no,status,d,observedAt)));
        String sync=pan && no==4 && "FAIL".equals(status) ? panMemberSync(d) : null;
        if (sync!=null) fields.put("memberSync",sync);
        return Map.copyOf(fields);
    }

    /**
     * Palo Alto check 4 with config sync switched off on a member (PO 2026-11-23): this row's own member state, "off",
     * "on" or "unknown", from the per-member map the readiness run recorded; null for older evidence (no map, or no
     * marker saying which member the row is), which keeps the pair-wide wording.
     */
    private static String panMemberSync(JsonNode d) {
        if (!"CONFIG_SYNC_DISABLED".equals(d.path("reason").asText())) return null;
        JsonNode byMember=d.path("syncEnabledByMember");
        if (!byMember.isObject() || !byMember.has("first") || !byMember.has("second")) return null;
        String self=d.path("self").asText();
        if (!"first".equals(self) && !"second".equals(self)) return null;
        return switch(byMember.path(self).asText()) {
            case "no" -> "off";
            case "yes" -> "on";
            default -> "unknown";
        };
    }

    private static String fgtTitle(int no) {
        return switch(no) {
            case 1 -> "HA mode and members"; case 2 -> "Heartbeat links"; case 3 -> "Monitored interfaces";
            case 4 -> "Configuration sync"; case 5 -> "Session pickup"; case 6 -> "Sessions carried (not evaluated yet)";
            case 7 -> "Version parity"; case 8 -> "Switch preconditions"; case 9 -> "Last failover (information)";
            case TRAFFIC_ARP -> TRAFFIC_ARP_TITLE; default -> "Check";
        };
    }
    /** FortiGate: reason codes and counts only, and always "as the primary reports"; never a serial, host name, address or version. */
    private static String fgtSummary(int no,String status,JsonNode d) {
        String reason=d.path("reason").asText();
        if ("GATE_NOT_SIGNED_OFF".equals(reason)) return "Not read: the command is not approved yet";
        if ("GATE_UNAVAILABLE".equals(reason)) return "Not read: the command gate is unavailable";
        if ("UNSUPPORTED_MODE".equals(reason)) return "Only an active-passive cluster without virtual clustering is supported";
        boolean pass="PASS".equals(status);
        return switch(no) {
            case 1 -> pass ? "Active-passive, one primary and one secondary, health OK, identity matches (as the primary reports)"
                : "IDENTITY_MISMATCH".equals(reason) ? "The recorded unit is not a member of the reported cluster"
                : "RECORDED_SERIAL_MISSING".equals(reason) ? "No recorded serial for the enrolled unit yet"
                : "HA_OFF".equals(reason) ? "HA is not configured"
                : "ANSWERED_BY_SECONDARY".equals(reason) ? "The enrolled address answered from the secondary"
                : "UNKNOWN".equals(status) ? "HA status not recognised" : "Members or health are not as expected";
            case 2 -> pass ? count(d,"heartbeatUp"," heartbeat links up on both members (as the primary reports)")
                : "UNKNOWN".equals(status) ? "Heartbeat lines missing or not recognised" : "A heartbeat link is down";
            case 3 -> pass ? count(d,"monitoredUp"," monitored interfaces up on both members (as the primary reports)")
                : "UNKNOWN".equals(status) ? "Monitored interface lines missing or none configured"
                : "MONITORED_COUNT_DIFFERS".equals(reason) ? "The members monitor different numbers of interfaces" : "A monitored interface is down";
            case 4 -> pass ? "Both members in sync and the cluster checksums equal"
                : "UNKNOWN".equals(status) ? "Sync state or checksums missing or not recognised"
                : "CHECKSUM_DIFFERS".equals(reason) ? "Cluster checksums differ" : "A member is out of sync";
            case 5 -> pass ? "Session pickup enabled" : "UNKNOWN".equals(status) ? "Session pickup setting missing" : "Session pickup is disabled";
            case 6 -> "Not evaluated yet";
            case 7 -> pass ? "Same software version on both members (derived: health OK and both in sync, as the primary reports)"
                : "The primary cannot report its secondary's software version";
            case 8 -> pass ? "Override disabled, secondary uptime above the margin, no failover flag latched"
                : "UNKNOWN".equals(status) ? "Override, uptime or failover flag missing or not recognised"
                : "OVERRIDE_ENABLED".equals(reason) ? "Override is enabled" : "FAILOVER_FLAG_LATCHED".equals(reason) ? "A failover flag is latched"
                : "Secondary uptime is below the margin";
            case 9 -> "WARN".equals(status) ? "Last failover less than 6 hours ago"
                : pass ? "Last failover more than 6 hours ago" : "Last failover time not available";
            case TRAFFIC_ARP -> trafficArpSummary(d,true);
            default -> "Check result unavailable";
        };
    }

    private static String asaTitle(int no) {
        return switch(no) {
            case 1 -> "Failover state and roles"; case 2 -> "Pair identity"; case 3 -> "Failover and state links";
            case 4 -> "Monitored interfaces"; case 5 -> "Configuration sync"; case 6 -> "Stateful replication";
            case 7 -> "Sessions carried"; case 8 -> "Version parity"; case 9 -> "Last failover (information)";
            case TRAFFIC_ARP -> TRAFFIC_ARP_TITLE; default -> "Check";
        };
    }
    /** Cisco ASA: reason codes and counts only; never a serial, address, interface name or version value. */
    private static String asaSummary(int no,String status,JsonNode d) {
        String reason=d.path("reason").asText();
        if ("GATE_NOT_SIGNED_OFF".equals(reason)) return "Not read: the command is not approved yet";
        if ("GATE_UNAVAILABLE".equals(reason)) return "Not read: the command gate is unavailable";
        if ("UNSUPPORTED_MODE".equals(reason)) return "Failover groups or multiple contexts are not supported";
        boolean pass="PASS".equals(status);
        return switch(no) {
            case 1 -> pass ? "One Active and one Standby Ready; both views agree"
                : "UNKNOWN".equals(status) ? "Failover state not recognised"
                : "FAILOVER_OFF".equals(reason) ? "Failover is off" : "Roles are not one Active and one Standby Ready, or the views disagree";
            case 2 -> pass ? "Pair serials match the recorded pair"
                : "NO_RECORDED_PAIR".equals(reason) ? "No pair identity recorded yet"
                : "UNKNOWN".equals(status) ? "Pair serials missing or inconsistent" : "A reported serial is not in the recorded pair";
            case 3 -> pass ? "Failover and state links up on both members"
                : "UNKNOWN".equals(status) ? "Link line missing" : "A failover or state link is not up";
            case 4 -> pass ? count(d,"monitoredNormal"," monitored interfaces Normal")
                : "UNKNOWN".equals(status) ? "Interface lines missing or not recognised" : "A monitored interface is not Normal";
            case 5 -> pass ? "Configuration synchronised on both members"
                : "SYNC_SKIPPED_CONFIG_MATCH".equals(reason) || "SYNC_SKIPPED".equals(reason) ? "In sync: both units' configurations match, so the sync was skipped"
                : "SYNC_STATE_NOT_RECOGNISED".equals(reason) ? "Configuration state not recognised"
                : "UNKNOWN".equals(status) ? "Configuration state missing or not recognised" : "Configuration not synchronised";
            case 6 -> pass ? "No error counter increased between two samples"
                : "UNKNOWN".equals(status) ? "Stateful table missing or not recognised" : "Replication error counters increased or no traffic";
            case 7 -> pass ? count(d,"activeCount"," active connections")+", "+count(d,"standbyCount"," on the standby")
                : "UNKNOWN".equals(status) ? "Connection count missing or not recognised"
                : count(d,"activeCount"," active connections")+", "+count(d,"standbyCount"," on the standby")+", below tolerance";
            case 8 -> pass ? "Software versions match on both members"
                : "UNKNOWN".equals(status) ? "Version line missing" : "Software versions differ";
            case 9 -> "WARN".equals(status) ? "Last failover less than 6 hours ago"
                : pass ? "Last failover more than 6 hours ago" : "Last failover time not available";
            case TRAFFIC_ARP -> trafficArpSummary(d,false);
            default -> "Check result unavailable";
        };
    }

    private static String title(boolean pan,int no) {
        if (pan) return switch(no) {
            case 1 -> "HA mode and roles"; case 2 -> "Peer relationship"; case 3 -> "HA links";
            case 4 -> "Configuration sync"; case 5 -> "Session synchronization";
            case 6 -> "Sessions carried"; case 7 -> "Version parity"; case PAN_TRAFFIC_ARP -> TRAFFIC_ARP_TITLE;
            default -> "Check";
        };
        return switch(no) {
            case 1 -> "Cluster state"; case 2 -> "Cluster IP table"; case 3 -> "Cluster interfaces";
            case 5 -> "ARP"; case 6 -> "Connections"; case 8 -> "Traffic rate";
            case 9 -> "State synchronization"; case 10 -> "Installed policy parity";
            case 11 -> "Critical devices"; case 12 -> "Bond interfaces";
            case 13 -> "Last failover"; case 14 -> "Routing"; case 15 -> "Recovery mode";
            case 16 -> "Virtual systems moved"; default -> "Check";
        };
    }

    private static String summary(boolean pan,int no,String status,JsonNode d,Instant observedAt) {
        boolean pass="PASS".equals(status);
        if (pan && no==PAN_TRAFFIC_ARP) {
            String reason=d.path("reason").asText();
            if ("GATE_NOT_SIGNED_OFF".equals(reason)) return "Not read: the command is not approved yet";
            if ("GATE_UNAVAILABLE".equals(reason)) return "Not read: the command gate is unavailable";
            return trafficArpSummary(d,false);
        }
        if (!pan && no==8 && "NOT_EVALUATED".equals(status)
                && "TRAFFIC_MEASURED_PER_VIRTUAL_SYSTEM".equals(d.path("reason").asText()))
            return "Measured per Virtual System";
        if ("UNKNOWN".equals(status)) {
            if (pan) return switch(no) {
                case 1 -> "HA mode or roles not recognised";
                case 2 -> "Peer relationship could not be verified";
                case 3 -> "HA link state not recognised";
                // Live 2026-10-24: running-sync-enabled "no" means the HA pair has config sync switched off on the device.
                case 4 -> "no".equals(d.path("first").path("running-sync-enabled").asText())
                        || "no".equals(d.path("second").path("running-sync-enabled").asText())
                    ? "Config sync is disabled on this HA pair (enable it under Device > High Availability > General)"
                    : "Configuration sync field missing or not recognised";
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
                case 16 -> virtualSystemsMoved(false,d);
                default -> "Check evidence unavailable";
            };
        }
        if (pan) return switch(no) {
            case 1 -> role(d);
            case 2 -> pass ? "Reciprocal peer relationship verified" : "Peer relationship not ready";
            case 3 -> pass ? "Required HA links up on both members" : "An HA link is down";
            case 4 -> pass ? "Running configuration synchronized on both members"
                : panMemberSync(d)!=null ? switch(panMemberSync(d)) {
                    case "off" -> "Config sync is OFF on this member (Device > High Availability > General > Setup > Enable Config Sync)";
                    case "on" -> "Config sync enabled on this member; its peer has it off";
                    default -> "Config sync setting of this member is not known; its peer has it off";
                }
                : "CONFIG_SYNC_DISABLED".equals(d.path("reason").asText())
                    ? "Config sync is disabled on " + d.path("membersDisabled").asInt() + " member(s): the running configurations are not kept equal"
                    : "Configuration not synchronized";
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
            case 16 -> virtualSystemsMoved(pass,d);
            default -> "Check result unavailable";
        };
    }

    /** Check 16 of a VSX chassis failover run (PO 2026-11-19): counts and opaque VSIDs only. */
    private static String virtualSystemsMoved(boolean pass,JsonNode d) {
        if (!d.path("virtualSystems").isIntegralNumber()) return "Virtual system states unavailable";
        String counts=d.path("onExpectedMember").asLong()+" of "+d.path("virtualSystems").asLong()+" virtual systems on the expected member";
        if (pass) return counts;
        var missing=new java.util.ArrayList<String>();
        d.path("notOnExpectedMember").forEach(v -> missing.add(v.asText()));
        return counts+(missing.isEmpty() ? "" : "; not moved: VSID "+String.join(", ",missing));
    }

    /**
     * The "Traffic and ARP (information)" line: the two numbers, and against the unit's earlier readiness run the earlier
     * numbers, the two ratios and a flag when one fell below 50 %. Numbers only, never an address, MAC or interface name.
     * {@code fortigate}: the primary reports it, so the line says so, and the average session count is added.
     */
    private static String trafficArpSummary(JsonNode d,boolean fortigate) {
        String reason=d.path("reason").asText();
        if ("NOT_ACTIVE_MEMBER".equals(reason)) return "Read on the active member only";
        if ("ACTIVE_MEMBER_NOT_DETERMINED".equals(reason)) return "Not read: the active member could not be determined";
        if ("GATE_NOT_SIGNED_OFF".equals(reason)) return "Not read: the command is not approved yet";
        if ("GATE_UNAVAILABLE".equals(reason)) return "Not read: the command gate is unavailable";
        boolean traffic=measuredRate(d.path("trafficBytesPerSecond")),arp=d.path("arpEntries").isIntegralNumber();
        if (!traffic && !arp) return "Traffic rate and ARP count unavailable";
        StringBuilder text=new StringBuilder(traffic ? rate(d.path("trafficBytesPerSecond")) : "Traffic rate unavailable")
            .append(", ").append(arp ? d.path("arpEntries").asLong()+" ARP entries" : "ARP count unavailable");
        if (d.path("arpByVdom").isObject()) {
            // Per VDOM by ordinal (VDOM names are never stored); a VDOM that could not be read maps to null.
            var counts=new java.util.ArrayList<String>();
            d.path("arpByVdom").fields().forEachRemaining(e -> counts.add(e.getValue().isIntegralNumber() ? e.getValue().asText() : "unread"));
            text.append(" in ").append(counts.size()).append(counts.size()==1 ? " VDOM" : " VDOMs");
            if (!counts.isEmpty()) text.append(" (").append(String.join(", ",counts)).append(")");
        }
        if ("BOX".equals(d.path("trafficScope").asText())) text.append("; traffic is box-wide");
        if (d.path("averageSessions").isIntegralNumber()) text.append(", ").append(d.path("averageSessions").asLong()).append(" average sessions");
        if (reason.endsWith("_ZERO")) text.append(" (a zero may be a quiet window or an empty table)");
        if (d.path("previousArpEntries").isIntegralNumber() && measuredRate(d.path("previousTrafficBytesPerSecond"))) {
            text.append("; earlier run: ").append(rate(d.path("previousTrafficBytesPerSecond"))).append(", ")
                .append(d.path("previousArpEntries").asLong()).append(" ARP entries");
            if (d.path("trafficRatio").isNumber()) text.append("; traffic ").append(percent(d.path("trafficRatio").asDouble()));
            if (d.path("arpRatio").isNumber()) text.append("; ARP ").append(percent(d.path("arpRatio").asDouble()));
            if (d.path("arpDroppedVdoms").isIntegralNumber() && d.path("arpDroppedVdoms").asLong()>0)
                text.append("; ").append(d.path("arpDroppedVdoms").asLong()).append(" VDOM ARP tables below 50% of the earlier run");
            if (d.path("trafficDropped").asBoolean(false) || d.path("arpDropped").asBoolean(false)) {
                var dropped=new java.util.ArrayList<String>();
                if (d.path("trafficDropped").asBoolean(false)) dropped.add("traffic");
                if (d.path("arpDropped").asBoolean(false)) dropped.add("ARP");
                text.append("; ").append(String.join(" and ",dropped)).append(" below 50% of the earlier run");
            }
        }
        if (fortigate) text.append(" (as the primary reports)");
        return text.toString();
    }
    private static String percent(double ratio) { return Math.round(ratio*100)+"% of the earlier run"; }

    /** Check 7's evidence names each field MATCH / MISMATCH / MISSING (never a version value): the names with this outcome. */
    private static String versionFields(JsonNode d,String outcome) {
        var names=new java.util.ArrayList<String>();
        for(String field:new String[]{"software","app","threat"})
            if(outcome.equals(d.path(field).asText())) names.add("software".equals(field)?"PAN-OS":field+" content");
        return names.isEmpty()?"field not recognised":String.join(", ",names);
    }

    /** PAN check 5 WARN: an idle pair's zero session sync counters (the worker's text, repeated for the view). */
    static final String IDLE_SESSION_SYNC_WARNING="No sessions to synchronize (0 active sessions on both members); "
        +"the session sync path is not proven by traffic.";

    private static String sessionSyncSummary(String status,JsonNode d) {
        String summary="UNKNOWN".equals(status)
            ?"Session sync fields missing or not recognised: /response/result/enabled and messages/entry fields enable, sent, recv and desc"
            :"PASS".equals(status)?"Sessions synchronized on both members"
            // PO 2026-11-23 (PO_DECISION_RECORD_2026_11_23_PAN_IDLE_SESSION_SYNC.md): an idle pair warns, never passes.
            :"WARN".equals(status)?IDLE_SESSION_SYNC_WARNING:"Session synchronization failed or disabled";
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
    /**
     * The parity evidence of check 10: the worker stores it per source ({@code fwStat}, and {@code cpstat} on a failover
     * run), so the reason is read from the first source that is not a plain match; a flat legacy row is read as it is.
     */
    private static JsonNode policyParity(JsonNode d,String status) {
        if (d.has("reason") || !d.path("fwStat").isObject()) return d;
        JsonNode warning=null;
        for (String source:new String[]{"fwStat","cpstat"}) {
            JsonNode parity=d.path(source);
            String reason=parity.path("reason").asText();
            if (!parity.isObject() || "POLICY_MATCH".equals(reason)) continue;
            if ("VS0_INSTALL_TIMES_DIFFER".equals(reason)) { if (warning==null) warning=parity; continue; }
            return parity; // a FAIL/UNKNOWN reason explains the row before a WARN one
        }
        if (warning!=null) return warning;
        // Only plain matches nested: they never explain a row that is not PASS.
        return "PASS".equals(status)?d.path("fwStat"):com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }
    private static String policySummary(String status,JsonNode evidence) {
        JsonNode d=policyParity(evidence,status);
        if(!"PASS".equals(status) && "FAIL".equals(evidence.path("installContinuity").asText()) && d.path("reason").asText().isEmpty())
            return "Policy install continuity check failed";
        return switch(d.path("reason").asText()) {
            case "VS0_INSTALL_TIMES_DIFFER" -> "Same VS0 policy name; install times differ by "+installDays(d)
                +" -- reinstall the VSX gateway policy to align";
            case "POLICY_MATCH" -> "PASS".equals(status)?"Same policy on both members; installed within 10 min"
                :"Policy missing, changed or different between members";
            case "POLICY_NAMES_DIFFER" -> "Policy names differ between members";
            case "POLICY_MISSING" -> "Policy missing on a member";
            case "POLICY_CHANGED" -> "Policy changed since the pre-check";
            case "INSTALL_TIMES_DIFFER" -> "Same policy, installed "+installGap(d)+" apart (limit 10 min)";
            case "INSTALL_TIME_UNRECOGNIZED" -> "Policy install time missing or invalid";
            default -> "UNKNOWN".equals(status)?"Installed policy could not be verified"
                :"PASS".equals(status)?"Same policy on both members":"Policy missing, changed or different between members";
        };
    }
    /** The VS0 skew in whole days ("62 days", "1 day"); under a day, as {@link #installGap}. */
    private static String installDays(JsonNode d) {
        if(!d.path("installSkewSeconds").isIntegralNumber()) return "an unknown interval";
        long days=d.path("installSkewSeconds").asLong()/86_400;
        return days==0?installGap(d):days==1?"1 day":days+" days";
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
