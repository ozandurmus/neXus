package com.securityexpert.nexus.ui2.worker.failover;

import java.io.StringReader;
import java.util.Locale;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/** Derived, fail-closed facts from each firewall's direct HA-state response. */
public final class PanFailoverChecks {
    public static final int SESSION_TOLERANCE_PERCENT = 80;
    public record State(String mode, String role, String peerRole, String localSerial, String peerSerial,
            String peerConnection, String ha1, String ha1Backup, boolean backupConfigured,
            String ha2, String runningSync, String ha2Backup, boolean ha2BackupConfigured, String runningSyncEnabled,
            Versions versions) {}
    /**
     * A member's own software and content versions, from its HA state read ({@code local-info/build-rel},
     * {@code app-version}, {@code threat-version}; PO 2026-10-21, contract amendment 2026-10-21). A field the response
     * does not carry (or carries twice) is empty: UNKNOWN, never a default.
     */
    public record Versions(String software, String app, String threat) {
        public static final Versions NONE=new Versions("","","");
    }
    private PanFailoverChecks() {}

    static String unknownDerived(int check,String status,boolean fieldFound) {
        String path=fieldPath(check);
        return "UNKNOWN".equals(status) && path!=null
            ?"{\"reason\":\""+(fieldFound?"unrecognised field value":"field not found")
                +"\",\"looked_for\":\""+path+"\"}":"{}";
    }

    static boolean fieldFound(int check,String xml) {
        if(check==5) return child(result(xml),"enabled")!=null && child(result(xml),"messages")!=null;
        if(check==6) return child(result(xml),"num-active")!=null || child(result(xml),"active-sessions")!=null;
        String path=fieldPath(check);
        if (path==null) return false;
        Element element=result(xml);
        String[] elements=path.split("/");
        for (int i=3;i<elements.length;i++) element=child(element,elements[i]);
        return element!=null;
    }

    private static String fieldPath(int check) {
        return switch(check) {
            case 4 -> "/response/result/group/running-sync";
            case 5 -> "/response/result/enabled and messages/entry/{enable,sent,recv,desc}";
            case 6 -> "/response/result/num-active or active-sessions";
            default -> null;
        };
    }

    static String configurationSyncShape(String xml) {
        Element group=child(result(xml),"group");
        return "running-sync="+ReadinessShapeLog.valueShape(text(group,"running-sync"))
            +" running-sync-enabled="+ReadinessShapeLog.valueShape(text(group,"running-sync-enabled"));
    }

    private static Element child(Element parent, String name) {
        if (parent == null) return null;
        Element found=null;
        for (Node n=parent.getFirstChild(); n!=null; n=n.getNextSibling()) {
            if (n instanceof Element element && name.equals(element.getTagName())) {
                if(found!=null) return null;
                found=element;
            }
        }
        return found;
    }
    private static String text(Element parent,String name) {
        Element e=child(parent,name);
        return e==null?"":e.getTextContent().strip();
    }
    private static String value(Element parent,String name) {
        return text(parent,name).toLowerCase(Locale.ROOT);
    }
    private static Element result(String xml) {
        if (xml==null || xml.isBlank() || xml.length()>262144) return null;
        try {
            var factory=DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
            factory.setExpandEntityReferences(false);
            var response=factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getDocumentElement();
            return "response".equals(response.getTagName()) && "success".equals(response.getAttribute("status"))
                ?child(response,"result"):null;
        } catch (Exception invalid) { return null; }
    }
    public static State parse(String xml) {
        State unknown=new State("","","","","","","","",false,"","","",false,"",Versions.NONE);
        try {
            Element group=child(result(xml),"group");
            if (group==null) return unknown;
            Element local=child(group,"local-info"),peer=child(group,"peer-info");
            if (local==null || peer==null) return unknown;
            return new State(value(group,"mode"),value(local,"state"),value(peer,"state"),text(local,"serial-num"),
                text(peer,"serial-num"),value(peer,"conn-status"),value(child(peer,"conn-ha1"),"conn-status"),
                value(child(peer,"conn-ha1-backup"),"conn-status"),
                !value(local,"ha1-backup-ipaddr").isEmpty() || child(peer,"conn-ha1-backup")!=null,value(child(peer,"conn-ha2"),"conn-status"),
                value(group,"running-sync"),value(child(peer,"conn-ha2-backup"),"conn-status"),
                !value(local,"ha2-backup-ipaddr").isEmpty() || child(peer,"conn-ha2-backup")!=null,value(group,"running-sync-enabled"),
                new Versions(text(local,"build-rel"),text(local,"app-version"),text(local,"threat-version")));
        } catch (Exception invalid) { return unknown; }
    }
    public record SessionSync(String status, String derived) {}

    public static String sessionSync(String activeXml,String passiveXml) {
        return sessionSyncEvidence(activeXml,passiveXml).status();
    }

    public static SessionSync sessionSyncEvidence(String activeXml,String passiveXml) {
        ObjectNode derived=JsonNodeFactory.instance.objectNode();
        ArrayNode missing=derived.putArray("missing"),unrecognised=derived.putArray("unrecognised");
        boolean failed=false;
        String[] xml={activeXml,passiveXml};
        String[] members={"active","passive"};
        for(int i=0;i<members.length;i++) {
            Element member=result(xml[i]);
            ObjectNode facts=derived.putObject(members[i]);
            Boolean enabled=syncBoolean(member,"enabled",members[i]+".enabled",facts,missing,unrecognised);
            if(i==0 && Boolean.FALSE.equals(enabled)) failed=true;
            ObjectNode messages=facts.putObject("messages");
            if(child(member,"messages")==null) missing.add(members[i]+".messages");
            for(String description:new String[]{"session setup","session update"}) {
                String path=members[i]+".messages."+description;
                Element entry=message(member,description);
                ObjectNode counters=messages.putObject(description);
                if(entry==null) missing.add(path+".entry/desc");
                Boolean enable=syncBoolean(entry,"enable",path+".enable",counters,missing,unrecognised);
                Long sent=syncCount(entry,"sent",path+".sent",counters,missing,unrecognised);
                Long recv=syncCount(entry,"recv",path+".recv",counters,missing,unrecognised);
                if((i==0 && (Boolean.FALSE.equals(enable) || Long.valueOf(0).equals(sent)))
                        || (i==1 && Long.valueOf(0).equals(recv))) failed=true;
            }
        }
        String status=!missing.isEmpty() || !unrecognised.isEmpty()?"UNKNOWN":failed?"FAIL":"PASS";
        return new SessionSync(status,derived.toString());
    }

    private static String syncValue(Element parent,String tag,String path,ArrayNode missing) {
        if(child(parent,tag)==null) { missing.add(path); return null; }
        return text(parent,tag);
    }
    private static Boolean syncBoolean(Element parent,String tag,String path,ObjectNode facts,
            ArrayNode missing,ArrayNode unrecognised) {
        String value=syncValue(parent,tag,path,missing);
        if(value==null) return null;
        Boolean parsed=switch(value.toLowerCase(Locale.ROOT)) {
            case "yes","true","enabled" -> true;
            case "no","false","disabled","no (device not in active state)" -> false;
            default -> null;
        };
        if(parsed==null) unrecognised.add(path+":"+ReadinessShapeLog.valueShape(value));
        else facts.put(tag,parsed);
        return parsed;
    }
    private static Long syncCount(Element parent,String tag,String path,ObjectNode facts,
            ArrayNode missing,ArrayNode unrecognised) {
        String value=syncValue(parent,tag,path,missing);
        if(value==null) return null;
        Long parsed=value.matches("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)")?count(value.replace(",","")):null;
        if(parsed==null) unrecognised.add(path+":"+ReadinessShapeLog.valueShape(value));
        else facts.put(tag,parsed);
        return parsed;
    }
    private static Element message(Element result,String description) {
        Element messages=child(result,"messages"),found=null;
        if(messages==null) return null;
        for(Node n=messages.getFirstChild();n!=null;n=n.getNextSibling()) {
            if(n instanceof Element entry && "entry".equals(entry.getTagName())
                    && description.equals(value(entry,"desc"))) {
                if(found!=null) return null;
                found=entry;
            }
        }
        return found;
    }
    public static Long sessions(String xml) {
        Element r=result(xml);
        Long current=count(text(r,"num-active")),legacy=count(text(r,"active-sessions"));
        if(child(r,"num-active")!=null && child(r,"active-sessions")!=null)
            return current!=null && current.equals(legacy)?current:null;
        return child(r,"num-active")!=null?current:legacy;
    }
    /** Check 6's evidence source labels (derived JSON {@code source}; the readiness view shows them). */
    public static final String SOURCE_SESSION_INFO="session info";
    public static final String SOURCE_SESSION_METER="session meter";
    /** Where the meter parser looks, for the UNKNOWN evidence. */
    public static final String SESSION_METER_PATH="/response/result/entry/current (show session meter)";

    /**
     * Check 6 on PAN-OS 12 or later (PO 2026-10-24, GR-7): the device's current session count from
     * {@code <show><session><meter/></session></show>}, summed over every entry of the response.
     *
     * <p>Expected shape. Palo Alto documents the CLI form only ("show session meter": per vsys, Maximum per dataplane,
     * Current, Throttled; PAN-OS CLI quick start, vsys cheat sheet) and publishes no XML field reference, so the
     * element names are UNVERIFIED until the first masked shape measurement. The XML API renders such a table as
     * {@code <response status="success"><result><entry><vsys>1</vsys><maximum>0</maximum><current>N</current>
     * <throttled>0</throttled></entry>...</result></response>}, one {@code entry} per vsys (per vsys and dataplane on
     * multi-dataplane models); a single wrapping element between {@code result} and the entries is also accepted.</p>
     *
     * <p>Rules (fail closed): every entry must carry exactly one {@code current} whose text is a non-negative integer;
     * an entry without a {@code vsys} (or dataplane) leaf is still counted (tolerated); two entries naming the same
     * vsys and dataplane are ambiguous. Zero entries, an error envelope, any entry without a usable {@code current},
     * an ambiguous pair or an overflow is null -- UNKNOWN, never a guessed or partial count.</p>
     */
    public static Long sessionMeter(String xml) {
        java.util.List<Element> entries=meterEntries(result(xml));
        if (entries.isEmpty()) return null;
        long sum=0;
        java.util.Set<String> seen=new java.util.HashSet<>();
        for (Element entry:entries) {
            Element current=child(entry,"current");
            if (current==null) return null;
            Long value=count(current.getTextContent().strip());
            if (value==null) return null;
            String vsys=text(entry,"vsys"),dataplane=text(entry,"dp")+text(entry,"dataplane");
            if ((!vsys.isEmpty() || !dataplane.isEmpty()) && !seen.add(vsys+"|"+dataplane)) return null;
            try { sum=Math.addExact(sum,value); } catch (ArithmeticException overflow) { return null; }
        }
        return sum;
    }
    /** True when the meter response carries at least one entry with a {@code current} element (for the UNKNOWN reason). */
    static boolean sessionMeterFieldFound(String xml) {
        return meterEntries(result(xml)).stream().anyMatch(entry -> child(entry,"current")!=null);
    }
    private static java.util.List<Element> meterEntries(Element result) {
        if (result==null) return java.util.List.of();
        java.util.List<Element> direct=children(result,"entry");
        if (!direct.isEmpty()) return direct;
        java.util.List<Element> containers=new java.util.ArrayList<>();
        for (Node n=result.getFirstChild(); n!=null; n=n.getNextSibling()) if (n instanceof Element e) containers.add(e);
        return containers.size()==1?children(containers.get(0),"entry"):java.util.List.of();
    }
    private static java.util.List<Element> children(Element parent,String name) {
        java.util.List<Element> found=new java.util.ArrayList<>();
        for (Node n=parent.getFirstChild(); n!=null; n=n.getNextSibling())
            if (n instanceof Element e && name.equals(e.getTagName())) found.add(e);
        return found;
    }
    /** Check 6's UNKNOWN evidence when a member was read with show session meter and gave no usable count. */
    public static String sessionMeterUnknown(boolean fieldFound) {
        return "{\"reason\":\""+(fieldFound?"unrecognised field value":"field not found")
            +"\",\"looked_for\":\""+SESSION_METER_PATH+"\",\"source\":\""+SOURCE_SESSION_METER+"\"}";
    }
    /**
     * Check 6's evidence for a member's count. A show session meter count names its source; a show session info count
     * keeps the evidence it has always had ({@code {"count":N}}, PAN-OS below 12 unchanged), which the view reads as
     * session info.
     */
    public static String sessionsCounted(Long count,String source) {
        return SOURCE_SESSION_METER.equals(source)
            ?"{\"count\":"+count+",\"source\":\""+source+"\"}":"{\"count\":"+count+"}";
    }
    /** Check 6 is UNKNOWN when the two members' counts (or the two phases') came from different reads: not comparable. */
    public static String sessionsMixedSources(String own,String other) {
        return "{\"reason\":\"mixed_sources\",\"source\":\""+own+"\",\"other_source\":\""+other+"\"}";
    }
    private static Long count(String value) {
        try { return value.matches("[0-9]+")?Long.parseLong(value):null; }
        catch(NumberFormatException invalid) { return null; }
    }
    public static String carried(Long before,Long after) {
        if (before==null || after==null) return "UNKNOWN";
        return after>=Math.ceil(before*SESSION_TOLERANCE_PERCENT/100.0)?"PASS":"FAIL";
    }
    /** Check 6's derived evidence when show session info is switched off (PO 2026-10-21 scope update). */
    public static final String SESSIONS_NOT_COLLECTED_DERIVED=sessionsNotCollected("show session info retired by PO 2026-10-21");
    /** The same evidence for a read not sent for another reason (PAN-OS 12 or later, version unknown); detail is fixed text. */
    public static String sessionsNotCollected(String detail) {
        return "{\"reason\":\"not_collected\",\"detail\":\""+detail+"\"}";
    }
    /**
     * Check 7, version parity (PO 2026-10-21, contract amendment 2026-10-21): each member's own PAN-OS build and app / threat
     * content versions, read from that member's HA state ({@code local-info}) -- no new command. Every field must be
     * present on both members: a missing one is UNKNOWN (blocks), never a guess; all equal is PASS; any present pair that
     * differs is FAIL. A member's report about its peer ({@code peer-info}) is one-sided and is not used. The evidence
     * names each field MATCH / MISMATCH / MISSING and never carries a version value.
     */
    public static SessionSync versionsEvidence(State a,State b) {
        String[] names={"software","app","threat"};
        String[][] values={{a.versions().software(),b.versions().software()},{a.versions().app(),b.versions().app()},
            {a.versions().threat(),b.versions().threat()}};
        ObjectNode derived=JsonNodeFactory.instance.objectNode();
        derived.put("source","ha_state_local_info");
        boolean missing=false,mismatch=false;
        for(int i=0;i<names.length;i++) {
            String first=values[i][0],second=values[i][1];
            String outcome=first.isEmpty() || second.isEmpty()?"MISSING":first.equals(second)?"MATCH":"MISMATCH";
            derived.put(names[i],outcome);
            missing|="MISSING".equals(outcome);
            mismatch|="MISMATCH".equals(outcome);
        }
        return new SessionSync(missing?"UNKNOWN":mismatch?"FAIL":"PASS",derived.toString());
    }
    public static String versions(State a,State b) { return versionsEvidence(a,b).status(); }

    private static String status(boolean pass, boolean fail) { return pass?"PASS":fail?"FAIL":"UNKNOWN"; }
    private static boolean knownRole(String role) {
        return "active".equals(role) || "passive".equals(role) || "suspended".equals(role);
    }
    public static String roles(State a,State b,String expectedA,String expectedB) {
        return roles(a,b,expectedA,expectedB,false);
    }
    public static String roles(State a,State b,String expectedA,String expectedB,boolean afterWrite) {
        if (!"active-passive".equals(a.mode()) || !"active-passive".equals(b.mode()))
            return status(false,!a.mode().isEmpty() && !b.mode().isEmpty());
        String reciprocal=reciprocal(a,b,afterWrite);
        if(!"PASS".equals(reciprocal)) return reciprocal;
        return status(expectedA.equals(a.role()) && expectedB.equals(b.role()),
            knownRole(a.role()) && knownRole(b.role()));
    }
    public static String reciprocal(State a,State b) {
        return reciprocal(a,b,false);
    }
    private static String reciprocal(State a,State b,boolean afterWrite) {
        String identity=reciprocalIdentity(a,b);
        if(!"PASS".equals(identity)) return identity;
        String mirrored=mirroredRoles(a,b);
        if("NOT_EVALUABLE".equals(mirrored)) {
            // Only the active observer may temporarily lose the suspended peer's role.
            boolean suspendedPair="suspended".equals(a.role()) && "active".equals(b.role())
                && "active".equals(a.peerRole()) && !knownRole(b.peerRole())
                || "suspended".equals(b.role()) && "active".equals(a.role())
                && "active".equals(b.peerRole()) && !knownRole(a.peerRole());
            return afterWrite && suspendedPair?"PASS":"UNKNOWN";
        }
        return mirrored;
    }
    public static String reciprocalIdentity(State a,State b) {
        boolean ids=!a.localSerial().isEmpty() && !b.localSerial().isEmpty()
            && !a.peerSerial().isEmpty() && !b.peerSerial().isEmpty();
        if (!ids) return "UNKNOWN";
        if (a.localSerial().equals(b.localSerial())) return "FAIL";
        if (!a.peerSerial().equals(b.localSerial()) || !b.peerSerial().equals(a.localSerial())) return "FAIL";
        return "PASS";
    }
    public static String mirroredRoles(State a,State b) {
        if(knownRole(a.peerRole()) && knownRole(b.role()) && !a.peerRole().equals(b.role())
                || knownRole(b.peerRole()) && knownRole(a.role()) && !b.peerRole().equals(a.role())) return "FAIL";
        return knownRole(a.role()) && knownRole(b.role()) && knownRole(a.peerRole()) && knownRole(b.peerRole())
            ?"PASS":"NOT_EVALUABLE";
    }
    public static String relationship(State a,State b) {
        return relationship(a,b,false);
    }
    public static String relationship(State a,State b,boolean afterWrite) {
        String reciprocal=reciprocal(a,b,afterWrite);
        if(!"PASS".equals(reciprocal)) return reciprocal;
        return status("up".equals(a.peerConnection()) && "up".equals(b.peerConnection()),
            "down".equals(a.peerConnection()) || "down".equals(b.peerConnection()));
    }
    public static String stateEvidence(State state) {
        return stateEvidence(state,null);
    }
    public static String stateEvidence(State state,State peer) {
        return stateEvidence(state,peer,null);
    }
    public static String stateEvidence(State state,State peer,String switchMirroredRoles) {
        ObjectNode facts=JsonNodeFactory.instance.objectNode();
        if(switchMirroredRoles!=null) facts.put("switch_mirrored_roles",switchMirroredRoles);
        if(peer!=null) {
            facts.put("reciprocal_identity",reciprocalIdentity(state,peer));
            facts.put("mirrored_roles",mirroredRoles(state,peer));
        }
        facts.put("role",knownRole(state.role())?state.role():"unknown");
        facts.put("peer_role",knownRole(state.peerRole())?state.peerRole():"unknown");
        facts.put("mode","active-passive".equals(state.mode())?"active-passive":"unsupported");
        facts.put("reason",!"active-passive".equals(state.mode())?"UNSUPPORTED"
            :!knownRole(state.role()) || !knownRole(state.peerRole())?"MISSING_OR_UNRECOGNIZED_ROLE":"OBSERVED");
        return facts.toString();
    }
    public static String links(State a,State b) { return linksEvidence(a,b).status(); }

    public static SessionSync linksEvidence(State a,State b) {
        ObjectNode derived=JsonNodeFactory.instance.objectNode();
        ArrayNode missing=derived.putArray("missing"),unrecognised=derived.putArray("unrecognised");
        boolean failed=false;
        State[] states={a,b};
        String[] members={"first","second"};
        for(int i=0;i<states.length;i++) {
            State state=states[i];
            ObjectNode facts=derived.putObject(members[i]);
            String[] names={"ha1","ha1-backup","ha2","ha2-backup"};
            String[] values={state.ha1(),state.ha1Backup(),state.ha2(),state.ha2Backup()};
            boolean[] required={true,state.backupConfigured(),true,state.ha2BackupConfigured()};
            for(int j=0;j<names.length;j++) {
                String path=members[i]+"."+names[j],value=values[j];
                if(value.isEmpty()) {
                    if(required[j]) missing.add(path);
                    else facts.put(names[j],"not observed (optional)");
                } else if(!"up".equals(value) && !"down".equals(value)) {
                    unrecognised.add(path+":"+ReadinessShapeLog.valueShape(value));
                } else {
                    facts.put(names[j],value);
                    failed |= "down".equals(value);
                }
            }
        }
        return new SessionSync(!missing.isEmpty() || !unrecognised.isEmpty()?"UNKNOWN":failed?"FAIL":"PASS",derived.toString());
    }

    public static String sync(State a,State b) { return syncEvidence(a,b).status(); }

    public static SessionSync syncEvidence(State a,State b) {
        ObjectNode derived=JsonNodeFactory.instance.objectNode();
        ArrayNode missing=derived.putArray("missing"),unrecognised=derived.putArray("unrecognised");
        boolean failed=false,unknown=false;
        State[] states={a,b};
        String[] members={"first","second"};
        for(int i=0;i<states.length;i++) {
            State state=states[i];
            ObjectNode facts=derived.putObject(members[i]);
            String value=state.runningSync();
            if(value.isEmpty()) { missing.add(members[i]+".running-sync"); unknown=true; }
            else if(!java.util.Set.of("synchronized","not synchronized","synchronization in progress","unknown").contains(value)) {
                unrecognised.add(members[i]+".running-sync:"+ReadinessShapeLog.valueShape(value)); unknown=true;
            } else {
                facts.put("running-sync",value);
                if(!"synchronized".equals(value)) {
                    if("up".equals(state.peerConnection()) && "yes".equals(state.runningSyncEnabled())) failed=true;
                    else unknown=true;
                }
            }
            if(java.util.Set.of("up","down").contains(state.peerConnection()))
                facts.put("peer-reachable","up".equals(state.peerConnection()));
            else if(state.peerConnection().isEmpty()) missing.add(members[i]+".peer-connection");
            else unrecognised.add(members[i]+".peer-connection:"+ReadinessShapeLog.valueShape(state.peerConnection()));
            if(java.util.Set.of("yes","no").contains(state.runningSyncEnabled()))
                facts.put("running-sync-enabled",state.runningSyncEnabled());
            else if(state.runningSyncEnabled().isEmpty()) missing.add(members[i]+".running-sync-enabled");
            else unrecognised.add(members[i]+".running-sync-enabled:"+ReadinessShapeLog.valueShape(state.runningSyncEnabled()));
        }
        return new SessionSync(unknown?"UNKNOWN":failed?"FAIL":"PASS",derived.toString());
    }
}
