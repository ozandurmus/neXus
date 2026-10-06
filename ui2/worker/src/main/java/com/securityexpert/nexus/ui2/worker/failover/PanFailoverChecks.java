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
            String ha2, String runningSync, String ha2Backup, boolean ha2BackupConfigured, String runningSyncEnabled) {}
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
        State unknown=new State("","","","","","","","",false,"","","",false,"");
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
                !value(local,"ha2-backup-ipaddr").isEmpty() || child(peer,"conn-ha2-backup")!=null,value(group,"running-sync-enabled"));
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
    private static Long count(String value) {
        try { return value.matches("[0-9]+")?Long.parseLong(value):null; }
        catch(NumberFormatException invalid) { return null; }
    }
    public static String carried(Long before,Long after) {
        if (before==null || after==null) return "UNKNOWN";
        return after>=Math.ceil(before*SESSION_TOLERANCE_PERCENT/100.0)?"PASS":"FAIL";
    }
    public static String versions(String first,String second) {
        Element a=child(result(first),"system"),b=child(result(second),"system");
        boolean mismatch=false;
        for (String field:new String[]{"sw-version","app-version","threat-version"}) {
            if (text(a,field).isEmpty() || text(b,field).isEmpty()) return "UNKNOWN";
            if (!text(a,field).equals(text(b,field))) mismatch=true;
        }
        return mismatch?"FAIL":"PASS";
    }
    private static String status(boolean pass, boolean fail) { return pass?"PASS":fail?"FAIL":"UNKNOWN"; }
    private static boolean knownRole(String role) {
        return "active".equals(role) || "passive".equals(role) || "suspended".equals(role);
    }
    public static String roles(State a,State b,String expectedA,String expectedB) {
        if (!"active-passive".equals(a.mode()) || !"active-passive".equals(b.mode()))
            return status(false,!a.mode().isEmpty() && !b.mode().isEmpty());
        String reciprocal=reciprocal(a,b);
        if(!"PASS".equals(reciprocal)) return reciprocal;
        return status(expectedA.equals(a.role()) && expectedB.equals(b.role()),
            knownRole(a.role()) && knownRole(b.role()));
    }
    public static String reciprocal(State a,State b) {
        boolean ids=!a.localSerial().isEmpty() && !b.localSerial().isEmpty()
            && !a.peerSerial().isEmpty() && !b.peerSerial().isEmpty();
        if (!ids) return "UNKNOWN";
        if (a.localSerial().equals(b.localSerial())) return "FAIL";
        if (!a.peerSerial().equals(b.localSerial()) || !b.peerSerial().equals(a.localSerial())) return "FAIL";
        if(!knownRole(a.role()) || !knownRole(b.role()) || !knownRole(a.peerRole()) || !knownRole(b.peerRole()))
            return "UNKNOWN";
        return a.peerRole().equals(b.role()) && b.peerRole().equals(a.role())?"PASS":"FAIL";
    }
    public static String relationship(State a,State b) {
        String reciprocal=reciprocal(a,b);
        if(!"PASS".equals(reciprocal)) return reciprocal;
        return status("up".equals(a.peerConnection()) && "up".equals(b.peerConnection()),
            "down".equals(a.peerConnection()) || "down".equals(b.peerConnection()));
    }
    public static String stateEvidence(State state) {
        ObjectNode facts=JsonNodeFactory.instance.objectNode();
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
