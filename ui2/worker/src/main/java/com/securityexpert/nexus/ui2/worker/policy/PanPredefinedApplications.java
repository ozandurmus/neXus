package com.securityexpert.nexus.ui2.worker.policy;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * The Panorama's predefined App-ID catalogue, reduced to each application's standard ports (PO approval 2026-11-14,
 * verbatim "Onaylıyorum", option A: {@code type=config&action=get&xpath=/config/predefined/application}, one call per
 * policy collection on the same Panorama session, timeout 120 s, no retry, 64 MB cap). Only these fields of an
 * application entry are kept, as snapshot value strings: {@code default/port/member} (one
 * {@code default/port: <member>} per member, exactly as PanoramaPolicyMapper stores a custom application's),
 * {@code default/ident-by-ip-protocol} and {@code default/ident-by-icmp-type} / {@code default/ident-by-icmp6-type}
 * ({@code type} and {@code code}). Everything else in the reply is skipped while streaming and never retained.
 * <p>Fail closed: a reply that is not {@code status="success"}, an application name outside the closed character set,
 * the same name twice, a value outside its closed shape (kept as {@code UNPARSED}, which the engine reads as unknown
 * ports) or more than {@link #MAX_APPLICATIONS} entries gives no catalogue at all ({@link Unreadable}).
 */
final class PanPredefinedApplications {
    private PanPredefinedApplications() {}
    static final String XPATH = "/config/predefined/application";
    static final long MAX_BYTES = 64L * 1024 * 1024;
    static final int MAX_APPLICATIONS = 50_000;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,62}");
    private static final Pattern PORT = Pattern.compile("(?:tcp|udp)/[0-9]{1,5}(?:-[0-9]{1,5})?(?:,[0-9]{1,5}(?:-[0-9]{1,5})?)*");
    private static final Pattern NUMBER = Pattern.compile("[0-9]{1,3}");

    /** A catalogue read that must not be used; the code is a fixed token for masked logs. */
    static final class Unreadable extends RuntimeException {
        Unreadable(String code) { super(code, null, false, false); }
    }

    /** Application name to its kept values; an application without any kept field maps to an empty list. */
    static Map<String, List<String>> parse(InputStream reply) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        Map<String, List<String>> out = new LinkedHashMap<>();
        boolean success = false;
        try {
            XMLStreamReader xml = factory.createXMLStreamReader(reply);
            Deque<String> path = new ArrayDeque<>();
            String current = null; List<String> values = null; Map<String, String> icmp = new HashMap<>();
            StringBuilder text = new StringBuilder();
            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = xml.getLocalName();
                    if (path.isEmpty()) {
                        if (!name.equals("response")) throw new Unreadable("NOT_A_RESPONSE");
                        success = "success".equals(xml.getAttributeValue(null, "status"));
                        if (!success) throw new Unreadable("STATUS_NOT_SUCCESS");
                    }
                    path.addLast(name);
                    text.setLength(0);
                    // response/result/application/entry: one application.
                    if (path.size() == 4 && name.equals("entry") && List.copyOf(path).subList(0, 3).equals(List.of("response", "result", "application"))) {
                        current = xml.getAttributeValue(null, "name");
                        if (current == null || !NAME.matcher(current).matches()) throw new Unreadable("APPLICATION_NAME");
                        if (out.containsKey(current)) throw new Unreadable("DUPLICATE_APPLICATION");
                        if (out.size() >= MAX_APPLICATIONS) throw new Unreadable("TOO_MANY_APPLICATIONS");
                        values = new ArrayList<>(); icmp.clear();
                    }
                } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                    if (current != null && text.length() < 512) text.append(xml.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String relative = current == null || path.size() < 5 ? null : String.join("/", List.copyOf(path).subList(4, path.size()));
                    String value = text.toString().trim();
                    if (relative != null) switch (relative) {
                        case "default/port/member" -> values.add("default/port: " + (PORT.matcher(value).matches() ? value : "UNPARSED"));
                        case "default/ident-by-ip-protocol" -> values.add("default/ident-by-ip-protocol: " + (NUMBER.matcher(value).matches() ? value : "UNPARSED"));
                        case "default/ident-by-icmp-type/type", "default/ident-by-icmp-type/code",
                             "default/ident-by-icmp6-type/type", "default/ident-by-icmp6-type/code" ->
                            icmp.put(relative, NUMBER.matcher(value).matches() ? value : "UNPARSED");
                        default -> { }
                    }
                    text.setLength(0);
                    if (path.size() == 4 && current != null) {
                        for (String family : List.of("icmp", "icmp6")) {
                            String base = "default/ident-by-" + family + "-type/";
                            if (icmp.containsKey(base + "type") || icmp.containsKey(base + "code"))
                                values.add("default/ident-by-" + family + "-type: type " + icmp.getOrDefault(base + "type", "UNPARSED")
                                    + (icmp.containsKey(base + "code") ? " code " + icmp.get(base + "code") : ""));
                        }
                        out.put(current, List.copyOf(values));
                        current = null; values = null;
                    }
                    path.removeLast();
                }
            }
            xml.close();
        } catch (XMLStreamException malformed) {
            throw new Unreadable("MALFORMED_XML");
        }
        if (!success) throw new Unreadable("EMPTY_REPLY");
        return Collections.unmodifiableMap(out);
    }

    /** Reads at most {@code limit} bytes; beyond it the whole read is unusable. */
    static InputStream capped(InputStream input, long limit) {
        return new java.io.FilterInputStream(input) {
            private long count;
            private void add(long n) throws IOException { if (n > 0 && (count += n) > limit) throw new IOException("PREDEFINED_SIZE_LIMIT"); }
            @Override public int read() throws IOException { int b = in.read(); if (b >= 0) add(1); return b; }
            @Override public int read(byte[] b, int off, int len) throws IOException { int n = in.read(b, off, len); add(n); return n; }
        };
    }
}
