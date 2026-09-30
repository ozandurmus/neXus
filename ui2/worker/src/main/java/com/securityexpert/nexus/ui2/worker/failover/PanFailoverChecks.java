package com.securityexpert.nexus.ui2.worker.failover;

import java.io.StringReader;
import java.util.Locale;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/** Derived, fail-closed facts from each firewall's direct HA-state response. */
public final class PanFailoverChecks {
    public static final int SESSION_TOLERANCE_PERCENT = 80;
    public record State(String mode, String role, String localSerial, String peerSerial,
            String peerConnection, String ha1, String ha1Backup, boolean backupConfigured,
            String ha2, String runningSync) {}
    private PanFailoverChecks() {}

    static String unknownDerived(int check,String status,boolean fieldFound) {
        String path=fieldPath(check);
        return "UNKNOWN".equals(status) && path!=null
            ?"{\"reason\":\""+(fieldFound?"unrecognised field value":"field not found")
                +"\",\"looked_for\":\""+path+"\"}":"{}";
    }

    static boolean fieldFound(int check,String xml) {
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
            case 5 -> "/response/result/session-sync";
            case 6 -> "/response/result/active-sessions";
            default -> null;
        };
    }

    private static Element child(Element parent, String name) {
        if (parent == null) return null;
        for (Node n=parent.getFirstChild(); n!=null; n=n.getNextSibling())
            if (n instanceof Element element && name.equals(element.getTagName())) return element;
        return null;
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
        State unknown=new State("","","","","","","",false,"","");
        try {
            Element group=child(result(xml),"group");
            if (group==null) return unknown;
            Element local=child(group,"local-info"),peer=child(group,"peer-info");
            if (local==null || peer==null) return unknown;
            return new State(value(group,"mode"),value(local,"state"),text(local,"serial-num"),
                text(peer,"serial-num"),value(peer,"conn-status"),value(child(peer,"conn-ha1"),"conn-status"),
                value(child(peer,"conn-ha1-backup"),"conn-status"),
                !value(local,"ha1-backup-ipaddr").isEmpty(),value(child(peer,"conn-ha2"),"conn-status"),
                value(group,"running-sync"));
        } catch (Exception invalid) { return unknown; }
    }
    public static String sessionSync(String xml) {
        String state=value(result(xml),"session-sync");
        return status("current".equals(state) || "in-sync".equals(state),
            "failed".equals(state) || "disabled".equals(state));
    }
    public static Long sessions(String xml) {
        String count=text(result(xml),"active-sessions");
        try { return count.matches("[0-9]+")?Long.parseLong(count):null; }
        catch (NumberFormatException invalid) { return null; }
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
        return status(expectedA.equals(a.role()) && expectedB.equals(b.role()),
            knownRole(a.role()) && knownRole(b.role()));
    }
    public static String relationship(State a,State b) {
        boolean ids=!a.localSerial().isEmpty() && !b.localSerial().isEmpty()
            && !a.peerSerial().isEmpty() && !b.peerSerial().isEmpty();
        if (!ids) return "UNKNOWN";
        if (a.localSerial().equals(b.localSerial())) return "FAIL";
        if (!a.peerSerial().equals(b.localSerial()) || !b.peerSerial().equals(a.localSerial())) return "FAIL";
        return status("up".equals(a.peerConnection()) && "up".equals(b.peerConnection()),
            "down".equals(a.peerConnection()) || "down".equals(b.peerConnection()));
    }
    public static String links(State a,State b) {
        String[] links={a.ha1(),a.ha2(),b.ha1(),b.ha2(),
            a.backupConfigured()?a.ha1Backup():"up",b.backupConfigured()?b.ha1Backup():"up"};
        for (String link:links) if ("down".equals(link)) return "FAIL";
        for (String link:links) if (!"up".equals(link)) return "UNKNOWN";
        return "PASS";
    }
    public static String sync(State a,State b) {
        return "synchronized".equals(a.runningSync()) && "synchronized".equals(b.runningSync())
            ?"PASS":"UNKNOWN";
    }
}
