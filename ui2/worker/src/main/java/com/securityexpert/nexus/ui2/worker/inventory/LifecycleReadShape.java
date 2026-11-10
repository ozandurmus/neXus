package com.securityexpert.nexus.ui2.worker.inventory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.securityexpert.nexus.ui2.worker.inventory.LifecycleReadSwitch.Kind;

/**
 * Measure-first reduction of a lifecycle read's answer (group A) to a masked "shape" that is safe to log: the structure
 * of the answer with no customer value in it. The answer itself is never kept (Raw-evidence law).
 *
 * <p>CLI text: line by line, split on whitespace into tokens; inside a token the characters {@code : = , ( ) | [ ]} are
 * separators kept verbatim. A token piece is emitted verbatim only when its lower-case form is in that read kind's
 * hard-coded keyword list (generic vendor words only); otherwise digits-only -> {@code 9}, letters-only ->
 * {@code a<len>}, a mixed token -> its character-class pattern (see {@link #classPattern}) or {@code m<len>}. {@code CK-...} -> {@code CK-m<len>}; {@code sk} + digits -> {@code sk9}.
 * Identical masked lines are collapsed to one with an {@code xN} count.</p>
 *
 * <p>JSON: only field paths, JSON types and array lengths, no value at all.</p>
 */
public final class LifecycleReadShape {

    private static final System.Logger LOG = System.getLogger(LifecycleReadShape.class.getName());

    static final int MAX_LINES = 60;
    static final int MAX_LINE_CHARS = 240;
    static final int MAX_TOTAL_CHARS = 6000;
    static final String LINE_SEPARATOR = " // ";
    private static final String SEPARATORS = ":=,()|[]";
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern SK_DIGITS = Pattern.compile("(?i)sk\\d+");

    private static final Set<String> MONTHS = Set.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "sept",
            "oct", "nov", "dec");

    private static final Set<String> CPLIC_WORDS = union(MONTHS, Set.of("host", "expiration", "features", "signature", "never",
            "contract", "coverage", "id", "sku", "license", "licenses", "installed", "no", "type", "central", "local",
            "expired", "valid", "status", "name", "date", "blade", "blades", "product", "none", "permanent", "evaluation",
            "trial", "subscription", "support"));

    private static final Set<String> CPLP_WORDS = Set.of("id", "patch", "proc", "status", "mode", "pids", "installed", "comment",
            "armed", "ready", "reverted", "jumbofix", "unsupp", "nopatch", "nolib", "auto", "manual", "not", "found", "command",
            "no", "patches", "known");

    private static final Set<String> ASA_WORDS = union(MONTHS, Set.of("smart", "licensing", "status", "is", "enabled", "disabled",
            "registration", "registered", "unregistered", "account", "virtual", "export-controlled", "functionality", "allowed",
            "initial", "last", "renewal", "attempt", "next", "expires", "license", "authorization", "authorized", "out", "of",
            "compliance", "eval", "evaluation", "mode", "period", "expired", "communication", "deadline", "utility", "data",
            "privacy", "sending", "hostname", "yes", "no", "callhome", "version", "transport", "type", "usage", "product",
            "information", "udi", "pid", "sn", "agent", "for", "overall", "licensed", "entitlement", "entitlements", "serial",
            "number", "features", "feature", "tag", "count", "description", "enforcement", "not", "applicable", "in", "standby",
            "state", "succeeded", "failed", "on", "platform", "this", "has", "context", "contexts", "perpetual", "tier",
            "strong", "encryption", "base", "standard", "time", "and", "to", "the", "a", "or", "none", "active", "unlimited"));

    private static final Set<String> WEEKDAYS = Set.of("mon", "tue", "wed", "thu", "fri", "sat", "sun");

    // Group B (PO 2026-11-10). Generic vendor keywords only; everything else is masked by the same rules.
    private static final Set<String> FGT_CONTRACT_WORDS = union(union(MONTHS, WEEKDAYS), Set.of("system", "contracts", "contract",
            "account", "accounts", "support", "registration", "pending_registration", "got_contract_info", "industry", "company",
            "user", "id", "status", "expiry", "expiration", "date", "type", "level", "serial", "number", "none", "n/a",
            "registered", "unregistered", "valid", "expired"));

    private static final Set<String> FGT_AUTOUPDATE_WORDS = union(union(MONTHS, WEEKDAYS), Set.of("av", "engine", "virus",
            "definitions", "extended", "set", "extreme", "mobile", "malware", "ips", "attack", "ai/ml", "flow-based", "application",
            "industrial", "internet-service", "database", "db", "device", "and", "os", "identification", "botnet", "domain", "url",
            "white", "list", "allow", "block", "malicious", "certificate", "bundle", "modem", "geography", "ip", "mac", "address",
            "outbreak", "prevention", "inline", "casb", "dlp", "signature", "signatures", "fds", "proxy", "security", "rating",
            "data", "package", "version", "contract", "expiry", "date", "last", "updated", "update", "using", "scheduled", "manual",
            "on", "attempt", "result", "no", "updates", "installed", "unauthorized", "connectivity", "failure", "n/a"));

    private static final Set<String> FMG_DBCONTRACT_WORDS = Set.of("fgt", "fmg", "faz", "serial", "sn", "accountid", "industry",
            "company", "userid", "contract", "contracts", "raw", "data", "support", "type", "level", "expiry", "date", "start", "end",
            "none", "count");

    private static final Set<String> MC_LICENSES_WORDS = union(MONTHS, Set.of("license", "licenses", "licensed", "component",
            "components", "name", "valid", "expired", "expiration", "expires", "date", "serial", "number", "yes", "no", "perpetual",
            "trial", "period", "days", "remaining", "proxysg", "sgos", "edition", "model", "appliance", "key", "auto-update",
            "automatically", "update", "enabled", "disabled", "status", "type", "never"));

    /**
     * Read kinds that print Fortinet contract codes. A code from the closed list below is emitted verbatim; any other
     * token of 2 to 6 upper-case letters becomes {@code U<len>} ("some upper-case code"), so an organisation name written
     * in capitals never reaches a log.
     */
    private static final Set<Kind> UPPER_CODE_KINDS = EnumSet.of(Kind.FGT_UPDATE_INFO_CONTRACT, Kind.FMG_FMUPDATE_DBCONTRACT);
    private static final Pattern UPPER_CODE = Pattern.compile("[A-Z]{2,6}");
    private static final Set<String> FORTINET_CONTRACT_CODES = Set.of("FMWR", "ENHN", "COMP", "FURL", "SPAM", "SBCL", "ZHVO",
            "SPRT", "FRVS", "AVDB", "AVEN", "NIDS", "ISSS", "IOTH", "AFAC", "DLDB", "FGSA", "FMGC", "FAZC", "VMLS", "HDWR",
            "SWNM", "SWNC", "SDWN", "OTVP", "IPMC", "PBDS", "MCDB", "FCSS", "SBEN", "WEBS", "SCPC", "FSFG", "IPGO");

    private static final Map<Kind, Set<String>> ALLOW = new EnumMap<>(Kind.class);

    static {
        ALLOW.put(Kind.CP_CPLIC_PRINT, CPLIC_WORDS);
        ALLOW.put(Kind.CP_SPARK_SHOW_LICENSE, CPLIC_WORDS);
        ALLOW.put(Kind.CP_CPLP_LIST, CPLP_WORDS);
        ALLOW.put(Kind.ASA_SHOW_LICENSE_ALL, ASA_WORDS);
        ALLOW.put(Kind.FGT_UPDATE_INFO_CONTRACT, FGT_CONTRACT_WORDS);
        ALLOW.put(Kind.FGT_AUTOUPDATE_VERSIONS, FGT_AUTOUPDATE_WORDS);
        ALLOW.put(Kind.FMG_FMUPDATE_DBCONTRACT, FMG_DBCONTRACT_WORDS);
        ALLOW.put(Kind.MC_SHOW_LICENSES, MC_LICENSES_WORDS);
    }

    private LifecycleReadShape() {
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        java.util.HashSet<String> all = new java.util.HashSet<>(a);
        all.addAll(b);
        return Set.copyOf(all);
    }

    /** The keyword list of a CLI read kind (empty for the JSON kinds). */
    static Set<String> allowList(Kind kind) {
        return ALLOW.getOrDefault(kind, Set.of());
    }

    // ------------------------------------------------------------------------------------------ CLI text

    /** The masked shape of a CLI answer. Never contains a value that is not a listed keyword of {@code kind}. */
    public static String cliShape(Kind kind, String output) {
        Set<String> allow = allowList(kind);
        boolean upperCodes = UPPER_CODE_KINDS.contains(kind);
        Map<String, Integer> distinct = new LinkedHashMap<>();
        boolean tooManyLines = false;
        if (output != null) {
            for (String raw : output.split("\\R")) {
                String line = raw.strip();
                if (line.isEmpty()) {
                    continue;
                }
                String masked = maskLine(allow, upperCodes, line);
                if (!distinct.containsKey(masked) && distinct.size() >= MAX_LINES) {
                    tooManyLines = true;
                    continue;
                }
                distinct.merge(masked, 1, Integer::sum);
            }
        }
        StringBuilder out = new StringBuilder();
        boolean tooLong = false;
        int emitted = 0;
        for (Map.Entry<String, Integer> entry : distinct.entrySet()) {
            String part = entry.getKey() + (entry.getValue() > 1 ? " x" + entry.getValue() : "");
            if (part.length() > MAX_LINE_CHARS) {
                part = part.substring(0, MAX_LINE_CHARS) + "~";
            }
            int add = (out.length() == 0 ? 0 : LINE_SEPARATOR.length()) + part.length();
            if (out.length() + add > MAX_TOTAL_CHARS) {
                tooLong = true;
                break;
            }
            if (out.length() > 0) {
                out.append(LINE_SEPARATOR);
            }
            out.append(part);
            emitted++;
        }
        if (tooManyLines || tooLong) {
            out.append(" [truncated:").append(tooLong ? "chars" : "lines").append(" shown=").append(emitted)
                    .append(" distinct>=").append(distinct.size()).append(']');
        }
        return out.length() == 0 ? "-" : out.toString();
    }

    static String maskLine(Set<String> allow, String line) {
        return maskLine(allow, false, line);
    }

    static String maskLine(Set<String> allow, boolean upperCodes, String line) {
        StringBuilder out = new StringBuilder();
        for (String token : WHITESPACE.split(line)) {
            if (token.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            maskToken(allow, upperCodes, token, out);
        }
        return out.toString();
    }

    private static void maskToken(Set<String> allow, boolean upperCodes, String token, StringBuilder out) {
        StringBuilder piece = new StringBuilder();
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (SEPARATORS.indexOf(c) >= 0) {
                maskPiece(allow, upperCodes, piece.toString(), out);
                piece.setLength(0);
                out.append(c);
            } else {
                piece.append(c);
            }
        }
        maskPiece(allow, upperCodes, piece.toString(), out);
    }

    private static void maskPiece(Set<String> allow, boolean upperCodes, String piece, StringBuilder out) {
        if (piece.isEmpty()) {
            return;
        }
        if (allow.contains(piece.toLowerCase(Locale.ROOT))) {
            out.append(piece);
        } else if (upperCodes && UPPER_CODE.matcher(piece).matches()) {
            out.append(FORTINET_CONTRACT_CODES.contains(piece) ? piece : "U" + piece.length());
        } else if (piece.regionMatches(true, 0, "CK-", 0, 3)) {
            out.append("CK-m").append(piece.length() - 3);
        } else if (SK_DIGITS.matcher(piece).matches()) {
            out.append("sk9");
        } else if (isAsciiDigits(piece)) {
            out.append('9');
        } else if (isLetters(piece)) {
            out.append('a').append(piece.length());
        } else {
            out.append(classPattern(piece));
        }
    }

    private static final String PATTERN_PUNCTUATION = "-/._+";
    private static final int PATTERN_MAX_CHARS = 20;

    /**
     * A mixed token as its character-class pattern: each run of digits is {@code 9}, each run of letters {@code a}, and
     * {@code - / . _ +} are kept, behind a {@code ~} that tells a pattern from the {@code a<len>} of a letters-only word: a
     * date reads {@code ~9-9-9} or {@code ~9a9}, an address {@code ~9.9.9.9}, a serial {@code ~a9a9}. It carries no character of the token. A token with any other character (an e-mail address, a path
     * with odd characters) or a pattern longer than 20 characters stays {@code m<len>}.
     */
    static String classPattern(String piece) {
        StringBuilder pattern = new StringBuilder();
        char last = 0;
        for (int i = 0; i < piece.length(); i++) {
            char c = piece.charAt(i);
            char cls;
            if (c >= '0' && c <= '9') {
                cls = '9';
            } else if (Character.isLetter(c)) {
                cls = 'a';
            } else if (PATTERN_PUNCTUATION.indexOf(c) >= 0) {
                cls = c;
            } else {
                return "m" + piece.length();
            }
            if ((cls == '9' || cls == 'a') && cls == last) {
                continue;
            }
            pattern.append(cls);
            last = cls;
        }
        return pattern.length() > PATTERN_MAX_CHARS ? "m" + piece.length() : "~" + pattern;
    }

    private static boolean isAsciiDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isLetters(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isLetter(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------ JSON

    private static final int JSON_MAX_DEPTH = 6;
    private static final int JSON_MAX_FIELDS = 60;
    /**
     * The closed set of field names a JSON shape may name: the documented fields of the replies (Management Center
     * getdevicelicense, getversion and the command envelope, Infoblox license:gridwide, upgradestatus and the paging envelope) plus generic envelope words. Any
     * other key is masked as {@code m<len>}: a reply keyed by a device name or serial must not put that key in a log.
     */
    private static final Set<String> JSON_FIELDS = Set.of(
            "uuid", "name", "deviceType", "manufacturer", "serialNumber", "creationDate", "lastModifiedDate",
            "trialPeriodExpirationDate", "licenseStatus", "components", "attributes", "componentName", "productDescription",
            "partNumber", "activationDate", "expirationDate", "validity", "version", "id", "buildNumber",
            "result", "type", "kind", "expiry_date", "expiration_status", "limit", "limit_context", "hwid", "key", "_ref",
            "next_page_id", "value", "code", "message", "error", "Error", "text", "status", "count", "ok",
            // Infoblox upgradestatus (group B) and the Management Center command envelope {reply, messages, status}.
            "current_version", "current_version_summary", "upgrade_group", "status_value", "status_text", "status_time",
            "upgrade_state", "ha_status", "hotfixes", "member", "grid_state", "group_state", "element_status",
            "distribution_version", "distribution_state", "alternate_version", "upload_version", "reverted", "steps",
            "steps_completed", "steps_total", "comment", "pnode_role", "unique_id", "status_value_update_time",
            "allow_distribution", "allow_upgrade", "reply", "messages");

    /** Field paths with JSON types and array lengths, no value. Example: {@code components[3]{componentName:string}}. */
    public static String jsonShape(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return "-";
        }
        Node merged = new Node();
        merged.add(node, 0);
        String shape = merged.render(0);
        if (shape.length() > MAX_TOTAL_CHARS) {
            return shape.substring(0, MAX_TOTAL_CHARS) + " [truncated:chars]";
        }
        return shape;
    }

    private static final class Node {
        private final java.util.TreeSet<String> scalars = new java.util.TreeSet<>();
        private boolean object;
        private boolean array;
        private int minLength = Integer.MAX_VALUE;
        private int maxLength = -1;
        private Node element;
        private final Map<String, Node> fields = new LinkedHashMap<>();
        private boolean fieldsCapped;
        private boolean deeper;

        void add(JsonNode n, int depth) {
            if (n.isObject()) {
                object = true;
                if (depth >= JSON_MAX_DEPTH) {
                    deeper = true;
                    return;
                }
                java.util.Iterator<Map.Entry<String, JsonNode>> it = n.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> f = it.next();
                    String key = JSON_FIELDS.contains(f.getKey()) ? f.getKey() : "m" + f.getKey().length();
                    if (!fields.containsKey(key) && fields.size() >= JSON_MAX_FIELDS) {
                        fieldsCapped = true;
                        continue;
                    }
                    fields.computeIfAbsent(key, k -> new Node()).add(f.getValue(), depth + 1);
                }
            } else if (n.isArray()) {
                array = true;
                minLength = Math.min(minLength, n.size());
                maxLength = Math.max(maxLength, n.size());
                if (depth >= JSON_MAX_DEPTH) {
                    deeper = true;
                    return;
                }
                for (JsonNode child : n) {
                    if (element == null) {
                        element = new Node();
                    }
                    element.add(child, depth + 1);
                }
            } else if (n.isTextual()) {
                scalars.add("string");
            } else if (n.isNumber()) {
                scalars.add("number");
            } else if (n.isBoolean()) {
                scalars.add("boolean");
            } else if (n.isNull()) {
                scalars.add("null");
            } else {
                scalars.add("other");
            }
        }

        String render(int depth) {
            List<String> parts = new ArrayList<>();
            if (object) {
                StringBuilder sb = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<String, Node> f : fields.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    String child = f.getValue().render(depth + 1);
                    sb.append(f.getKey());
                    if (!(child.startsWith("[") || child.startsWith("{"))) {
                        sb.append(':');
                    }
                    sb.append(child);
                }
                if (fieldsCapped) {
                    sb.append(first ? "" : ",").append("...");
                }
                if (deeper) {
                    sb.append(first && !fieldsCapped ? "" : ",").append("...");
                }
                parts.add(sb.append('}').toString());
            }
            if (array) {
                StringBuilder sb = new StringBuilder("[");
                sb.append(minLength == maxLength ? String.valueOf(maxLength) : minLength + ".." + maxLength).append(']');
                if (element != null) {
                    String child = element.render(depth + 1);
                    sb.append(child);
                } else if (deeper) {
                    sb.append("...");
                }
                parts.add(sb.toString());
            }
            parts.addAll(scalars);
            if (parts.isEmpty()) {
                return "empty";
            }
            return parts.size() == 1 ? parts.get(0) : "(" + String.join("|", parts) + ")";
        }
    }

    // ------------------------------------------------------------------------------------------ XML (Palo Alto)

    static final int XML_MAX_BODY_CHARS = 262_144;
    private static final int XML_MAX_DEPTH = 8;
    private static final int XML_MAX_CHILDREN = 60;
    /**
     * The closed set of element and attribute names an XML shape may name: the documented fields of the Palo Alto
     * {@code request license info} and {@code request support info} replies plus the response envelope. Any other name is
     * masked as {@code m<len>} (a reply must not put a customer-chosen name in a log).
     */
    private static final Set<String> XML_NAMES = Set.of("response", "status", "code", "result", "msg", "line", "licenses", "entry",
            "name", "feature", "description", "serial", "issued", "expires", "expired", "authcode", "base-license-name",
            "SupportInfoResponse", "Links", "Link", "Title", "Url", "Support", "Contact", "Contents", "Username", "PrimaryName",
            "PrimaryEmail", "PrimaryPhone", "SecondaryName", "SecondaryEmail", "SecondaryPhone", "ExpiryDate", "SupportLevel",
            "SupportLevelDesc", "SupportDescription");

    /** Thrown for an XML body that cannot be shaped (not well formed, a DOCTYPE, too large). */
    static final class XmlShapeException extends Exception {
        XmlShapeException(String message, Throwable cause) {
            super(message, cause, false, false);
        }
    }

    /**
     * Element paths with child counts and attribute NAMES, never text content or attribute values; names outside
     * {@link #XML_NAMES} become {@code m<len>}. The only values emitted are the root {@code status} attribute when it is exactly
     * {@code success} or {@code error}, and the root {@code code} attribute when it is all digits. Example:
     * {@code response[status=success]{result{licenses{entry*3[]{feature,expires}}}}}.
     */
    public static String xmlShape(String xml) throws javax.xml.parsers.ParserConfigurationException, org.xml.sax.SAXException,
            java.io.IOException {
        if (xml == null || xml.isBlank()) {
            return "-";
        }
        if (xml.length() > XML_MAX_BODY_CHARS) {
            throw new java.io.IOException("body larger than " + XML_MAX_BODY_CHARS);
        }
        javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        org.w3c.dom.Element root = factory.newDocumentBuilder()
                .parse(new org.xml.sax.InputSource(new java.io.StringReader(xml))).getDocumentElement();
        XmlNode merged = new XmlNode();
        merged.add(root, 0, true);
        StringBuilder out = new StringBuilder();
        out.append(maskXmlName(root.getTagName()));
        merged.renderInto(out);
        String shape = out.toString();
        return shape.length() > MAX_TOTAL_CHARS ? shape.substring(0, MAX_TOTAL_CHARS) + " [truncated:chars]" : shape;
    }

    private static String maskXmlName(String name) {
        return XML_NAMES.contains(name) ? name : "m" + name.length();
    }

    private static final class XmlNode {
        private final java.util.TreeSet<String> attributes = new java.util.TreeSet<>();
        private final Map<String, String> rootValues = new LinkedHashMap<>();
        private final Map<String, XmlNode> children = new LinkedHashMap<>();
        private final Map<String, int[]> perParent = new LinkedHashMap<>();
        private boolean childrenCapped;
        private boolean deeper;

        void add(org.w3c.dom.Element element, int depth, boolean root) {
            org.w3c.dom.NamedNodeMap attrs = element.getAttributes();
            for (int i = 0; i < attrs.getLength(); i++) {
                org.w3c.dom.Node attr = attrs.item(i);
                attributes.add(maskXmlName(attr.getNodeName()));
                if (root) {
                    String value = attr.getNodeValue();
                    if ("status".equals(attr.getNodeName()) && ("success".equals(value) || "error".equals(value))) {
                        rootValues.put("status", value);
                    } else if ("code".equals(attr.getNodeName()) && isAsciiDigits(value) && !value.isEmpty() && value.length() <= 6) {
                        rootValues.put("code", value);
                    }
                }
            }
            Map<String, Integer> here = new LinkedHashMap<>();
            for (org.w3c.dom.Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (!(child instanceof org.w3c.dom.Element childElement)) {
                    continue;
                }
                if (depth >= XML_MAX_DEPTH) {
                    deeper = true;
                    return;
                }
                String key = maskXmlName(childElement.getTagName());
                if (!children.containsKey(key) && children.size() >= XML_MAX_CHILDREN) {
                    childrenCapped = true;
                    continue;
                }
                children.computeIfAbsent(key, k -> new XmlNode()).add(childElement, depth + 1, false);
                here.merge(key, 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> entry : here.entrySet()) {
                int[] range = perParent.computeIfAbsent(entry.getKey(), k -> new int[] {Integer.MAX_VALUE, 0});
                range[0] = Math.min(range[0], entry.getValue());
                range[1] = Math.max(range[1], entry.getValue());
            }
        }

        void renderInto(StringBuilder out) {
            if (!attributes.isEmpty()) {
                out.append('[');
                boolean first = true;
                for (String attribute : attributes) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    out.append(attribute);
                    if (rootValues.containsKey(attribute)) {
                        out.append('=').append(rootValues.get(attribute));
                    }
                }
                out.append(']');
            }
            if (children.isEmpty() && !childrenCapped && !deeper) {
                return;
            }
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, XmlNode> child : children.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                int[] range = perParent.get(child.getKey());
                out.append(child.getKey());
                if (range != null) {
                    out.append('*').append(range[0] == range[1] ? String.valueOf(range[1]) : range[0] + ".." + range[1]);
                }
                child.getValue().renderInto(out);
            }
            if (childrenCapped || deeper) {
                out.append(first ? "" : ",").append("...");
            }
            out.append('}');
        }
    }

    // ------------------------------------------------------------------------------------------ the log line

    /**
     * {@code [LIFECYCLE_MEASURE] kind=.. device=.. outcome=.. len=.. lines=.. shape=..}. Never the target host or
     * address, never raw output.
     */
    public static String line(Kind kind, String deviceId, String outcome, int len, int lines, String shape) {
        return "[LIFECYCLE_MEASURE] kind=" + kind.token() + " device=" + safeDevice(deviceId) + " outcome=" + safeOutcome(outcome)
                + " len=" + len + " lines=" + lines + " shape=" + (shape == null || shape.isEmpty() ? "-" : shape);
    }

    /** A CLI read that answered (or answered nothing). */
    public static String cliLine(Kind kind, String deviceId, String output) {
        if (output == null || output.isBlank()) {
            return line(kind, deviceId, "EMPTY", output == null ? 0 : output.length(), 0, "-");
        }
        return line(kind, deviceId, "OK", output.length(), lineCount(output), cliShape(kind, output));
    }

    /** A read that threw or was refused: the outcome names the exception or result class only. */
    public static String errorLine(Kind kind, String deviceId, String errorName) {
        return line(kind, deviceId, "ERROR:" + errorName, 0, 0, "-");
    }

    /** A read that was not sent, and why (a fixed reason token). */
    public static String skippedLine(Kind kind, String deviceId, String reason) {
        return line(kind, deviceId, "SKIPPED:" + reason, 0, 0, "-");
    }

    /** An HTTPS read: a non-2xx status or a body that is not JSON is an ERROR, an empty body EMPTY. */
    public static String jsonLine(Kind kind, String deviceId, int status, String body, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        if (status < 200 || status > 299) {
            return errorLine(kind, deviceId, "Http" + status);
        }
        if (body == null || body.isBlank()) {
            return line(kind, deviceId, "EMPTY", body == null ? 0 : body.length(), 0, "-");
        }
        try {
            return line(kind, deviceId, "OK", body.length(), lineCount(body), jsonShape(mapper.readTree(body)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return line(kind, deviceId, "ERROR:" + e.getClass().getSimpleName(), body.length(), lineCount(body), "-");
        }
    }

    /**
     * A Palo Alto XML API answer (group B): an unusable transport result or a body that is not XML is an ERROR, an empty
     * body EMPTY. The shape names elements and attribute names only (see {@link #xmlShape}).
     */
    public static String xmlLine(Kind kind, String deviceId, String body) {
        if (body == null || body.isBlank()) {
            return line(kind, deviceId, "EMPTY", body == null ? 0 : body.length(), 0, "-");
        }
        try {
            return line(kind, deviceId, "OK", body.length(), lineCount(body), xmlShape(body));
        } catch (java.io.IOException | org.xml.sax.SAXException | javax.xml.parsers.ParserConfigurationException | RuntimeException e) {
            // The exception class only: its message can quote the body.
            return line(kind, deviceId, "ERROR:XmlUnusable", body.length(), lineCount(body), "-");
        }
    }

    private static final Set<String> MC_STATUS_WORDS = Set.of("SUCCESS", "FAILURE", "FAILED", "ERROR");

    /**
     * A Management Center command answer {@code {reply, messages, status}} (group B): the envelope's JSON shape, the
     * {@code status} word when it belongs to the closed set (else masked), and the CLI shape of the {@code reply} text.
     */
    public static String commandEnvelopeLine(Kind kind, String deviceId, int httpStatus, String body,
            com.fasterxml.jackson.databind.ObjectMapper mapper) {
        if (httpStatus < 200 || httpStatus > 299) {
            return errorLine(kind, deviceId, "Http" + httpStatus);
        }
        if (body == null || body.isBlank()) {
            return line(kind, deviceId, "EMPTY", body == null ? 0 : body.length(), 0, "-");
        }
        try {
            JsonNode envelope = mapper.readTree(body);
            String word = envelope.path("status").isTextual() ? envelope.path("status").asText() : null;
            String status = word == null ? "absent" : MC_STATUS_WORDS.contains(word) ? word : "m" + word.length();
            String reply = envelope.path("reply").isTextual() ? envelope.get("reply").asText() : "";
            String shape = "status=" + status + " envelope=" + jsonShape(envelope) + " reply=" + (reply.isBlank() ? "-" : cliShape(kind, reply));
            return line(kind, deviceId, "OK", body.length(), reply.isBlank() ? 0 : lineCount(reply), shape.length() > MAX_TOTAL_CHARS
                    ? shape.substring(0, MAX_TOTAL_CHARS) + " [truncated:chars]" : shape);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return line(kind, deviceId, "ERROR:" + e.getClass().getSimpleName(), body.length(), lineCount(body), "-");
        }
    }

    /** Logs a measurement line (INFO). */
    public static void log(String line) {
        LOG.log(System.Logger.Level.INFO, "{0}", line);
    }

    private static int lineCount(String text) {
        int n = 0;
        for (String l : text.split("\\R", -1)) {
            n++;
        }
        return text.endsWith("\n") ? n - 1 : n;
    }

    private static String safeDevice(String deviceId) {
        return deviceId != null && deviceId.matches("[A-Za-z0-9_.:-]{1,64}") ? deviceId : "unknown";
    }

    private static String safeOutcome(String outcome) {
        return outcome != null && outcome.matches("[A-Za-z0-9_.:-]{1,80}") ? outcome : "ERROR:Invalid";
    }
}
