package com.securityexpert.nexus.ui2.worker.compliance.evaluator;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import com.securityexpert.nexus.ui2.worker.compliance.model.Verdict;

/**
 * Facts read from the sanitized ACTIVE PAN-OS configuration for the three controls decided by structure rather than by
 * one value: SNMPv3, unused interfaces and DDNS. One streaming pass; the document is never held as a tree and DTDs and
 * external entities are off. Malformed text yields {@link #parsed()} false and every verdict UNKNOWN. Results carry
 * counts and relationships only, never a name, an address or a secret.
 */
final class PanOsXmlFacts {

    /** A verdict with the sentence and the observed value shown for it. */
    record Outcome(Verdict verdict, String message, String observed) {
    }

    private static final Set<String> INTERFACE_ROLES = Set.of(
            "layer3", "layer2", "virtual-wire", "tap", "ha", "aggregate-group", "decrypt-mirror", "log-card");

    private boolean parsed;
    private boolean snmpBlock;
    private boolean snmpV2c;
    private boolean snmpV3;
    private int v3Users;
    private int v3StrongUsers;
    private boolean networkInterfaces;
    private int roleLessInterfaces;
    private int roleLessInterfacesNotDown;
    private boolean ddnsEnabled;

    private PanOsXmlFacts() {
    }

    boolean parsed() {
        return parsed;
    }

    static PanOsXmlFacts read(String xml) {
        PanOsXmlFacts f = new PanOsXmlFacts();
        if (xml == null || xml.isBlank()) {
            return f;
        }
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        List<String> path = new ArrayList<>();
        // per-entry scratch
        boolean userAuth = false;
        boolean userPriv = false;
        boolean ifaceRole = false;
        String ifaceLinkState = null;
        StringBuilder text = new StringBuilder();
        try {
            XMLStreamReader r = factory.createXMLStreamReader(new StringReader(xml));
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String name = r.getLocalName();
                    path.add(name);
                    text.setLength(0);
                    int n = path.size();
                    if (name.equals("snmp-setting")) {
                        f.snmpBlock = true;
                    } else if (inPath(path, "snmp-setting", "access-setting", "version") && n >= 1) {
                        if (name.equals("v2c") && parentIs(path, "version")) {
                            f.snmpV2c = true;
                        } else if (name.equals("v3") && parentIs(path, "version")) {
                            f.snmpV3 = true;
                        }
                    }
                    if (isUserEntry(path)) {
                        userAuth = false;
                        userPriv = false;
                    } else if (isUserEntryChild(path)) {
                        if (name.equals("authpwd")) {
                            userAuth = true;
                        } else if (name.equals("privpwd")) {
                            userPriv = true;
                        }
                    }
                    if (n >= 2 && path.get(n - 2).equals("network") && name.equals("interface")) {
                        f.networkInterfaces = true;
                    }
                    if (isEthernetEntry(path)) {
                        ifaceRole = false;
                        ifaceLinkState = null;
                    } else if (isEthernetEntryChild(path) && INTERFACE_ROLES.contains(name)) {
                        ifaceRole = true;
                    }
                } else if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) {
                    if (text.length() < 64) {
                        text.append(r.getText());
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String name = r.getLocalName();
                    String value = text.toString().strip();
                    if (name.equals("ddns-enabled") && value.equalsIgnoreCase("yes")) {
                        f.ddnsEnabled = true;
                    }
                    if (isEthernetEntryChild(path) && name.equals("link-state")) {
                        ifaceLinkState = value;
                    }
                    if (isUserEntry(path)) {
                        f.v3Users++;
                        if (userAuth && userPriv) {
                            f.v3StrongUsers++;
                        }
                    }
                    if (isEthernetEntry(path) && !ifaceRole) {
                        f.roleLessInterfaces++;
                        if (!"down".equalsIgnoreCase(ifaceLinkState)) {
                            f.roleLessInterfacesNotDown++;
                        }
                    }
                    path.removeLast();
                    text.setLength(0);
                }
            }
            f.parsed = true;
        } catch (Exception e) {
            f.parsed = false;
        }
        return f;
    }

    private static boolean inPath(List<String> path, String... names) {
        int at = 0;
        for (String p : path) {
            if (p.equals(names[at]) && ++at == names.length) {
                return true;
            }
        }
        return false;
    }

    private static boolean parentIs(List<String> path, String parent) {
        int n = path.size();
        return n >= 2 && path.get(n - 2).equals(parent);
    }

    /** .../snmp-setting/access-setting/version/v3/users/entry */
    private static boolean isUserEntry(List<String> path) {
        int n = path.size();
        return n >= 6 && path.get(n - 1).equals("entry") && path.get(n - 2).equals("users") && path.get(n - 3).equals("v3")
                && path.get(n - 4).equals("version") && path.get(n - 5).equals("access-setting");
    }

    private static boolean isUserEntryChild(List<String> path) {
        return isUserEntry(path.subList(0, path.size() - 1));
    }

    /** .../network/interface/ethernet/entry */
    private static boolean isEthernetEntry(List<String> path) {
        int n = path.size();
        return n >= 4 && path.get(n - 1).equals("entry") && path.get(n - 2).equals("ethernet")
                && path.get(n - 3).equals("interface") && path.get(n - 4).equals("network");
    }

    private static boolean isEthernetEntryChild(List<String> path) {
        return path.size() > 1 && isEthernetEntry(path.subList(0, path.size() - 1));
    }

    Outcome snmpV3() {
        String missing = "No SNMP setting is present in the active configuration; the control cannot be proven from it.";
        if (!parsed || !snmpBlock) {
            return new Outcome(Verdict.UNKNOWN, missing, null);
        }
        if (snmpV2c) {
            return new Outcome(Verdict.FAIL, "SNMP v2c is configured; only v3 is allowed.", "v2c configured");
        }
        if (!snmpV3) {
            return new Outcome(Verdict.UNKNOWN, "The SNMP setting names no version; the control cannot be proven from it.", null);
        }
        if (v3Users == 0) {
            return new Outcome(Verdict.UNKNOWN, "SNMP v3 is selected but no v3 user is present.", null);
        }
        if (v3StrongUsers < v3Users) {
            return new Outcome(Verdict.FAIL, (v3Users - v3StrongUsers) + " of " + v3Users
                    + " SNMP v3 users lack an authentication or privacy password.", "weaker v3 user present");
        }
        return new Outcome(Verdict.PASS, "SNMP is v3-only and all " + v3Users + " users have authentication and privacy.",
                "v3-only, authenticated and encrypted users");
    }

    /**
     * An interface entry is unused when it has no role (layer3, layer2, virtual-wire, tap, ha, aggregate-group,
     * decrypt-mirror, log-card). Ports with no entry at all are not visible in a configuration, so no such entry means UNKNOWN.
     */
    Outcome unusedInterfaces() {
        if (!parsed || !networkInterfaces || roleLessInterfaces == 0) {
            return new Outcome(Verdict.UNKNOWN,
                    "No interface entry without a role is present; unconfigured ports are not visible in the configuration.", null);
        }
        if (roleLessInterfacesNotDown > 0) {
            return new Outcome(Verdict.FAIL, roleLessInterfacesNotDown + " of " + roleLessInterfaces
                    + " interface entries without a role are not set link-state down.", "unused interface not down");
        }
        return new Outcome(Verdict.PASS, "All " + roleLessInterfaces + " interface entries without a role are set link-state down.",
                "all unused interfaces down");
    }

    Outcome ddns() {
        if (!parsed || !networkInterfaces) {
            return new Outcome(Verdict.UNKNOWN, "No network interface configuration is present; DDNS cannot be proven off.", null);
        }
        if (ddnsEnabled) {
            return new Outcome(Verdict.FAIL, "Dynamic DNS is enabled on an interface.", "ddns enabled");
        }
        return new Outcome(Verdict.PASS, "No interface enables dynamic DNS.", "ddns not enabled");
    }
}
