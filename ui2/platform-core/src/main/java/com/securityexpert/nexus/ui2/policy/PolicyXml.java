package com.securityexpert.nexus.ui2.policy;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.*;
import org.w3c.dom.*;

/** Bounded, entity-free streaming XML; raw responses never become persisted artifacts. */
public final class PolicyXml {
    private PolicyXml() {}
    public static final int MAX_BYTES = 64 * 1024 * 1024;
    public static Document parse(String xml) {
        return parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
    public static Document parse(InputStream input) { return parse(input, false); }
    public static Document parse(InputStream input, boolean policyOnly) {
        try {
            var factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            var bounded = new FilterInputStream(input) {
                private long count;
                private void count(int n) throws IOException {
                    if (n > 0 && (count += n) > MAX_BYTES) throw new IOException("POLICY_RESPONSE_LIMIT");
                }
                @Override public int read() throws IOException { int n = in.read(); count(n < 0 ? 0 : 1); return n; }
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    int n = in.read(b, off, len); count(n); return n;
                }
            };
            var reader = factory.createXMLStreamReader(bounded);
            Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            Deque<Element> stack = new ArrayDeque<>();
            int elements = 0;
            List<String> path = new ArrayList<>();
            int skippedDepth = 0;
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE)
                        throw new IllegalArgumentException("POLICY_XML_ENTITY_REFUSED");
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        if (++elements > 500_000 || path.size() >= 64) throw new IllegalArgumentException("POLICY_XML_LIMIT");
                        path.add(reader.getLocalName());
                        if (skippedDepth > 0 || (policyOnly && !policyPath(path))) { skippedDepth++; continue; }
                        Element element = document.createElement(reader.getLocalName());
                        for (int i = 0; i < reader.getAttributeCount(); i++)
                            element.setAttribute(reader.getAttributeLocalName(i), reader.getAttributeValue(i));
                        if (stack.isEmpty()) document.appendChild(element); else stack.peek().appendChild(element);
                        stack.push(element);
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        path.remove(path.size() - 1);
                        if (skippedDepth > 0) skippedDepth--; else stack.pop();
                    }
                    else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) && !stack.isEmpty() && skippedDepth == 0)
                        stack.peek().appendChild(document.createTextNode(reader.getText()));
                }
            } finally { reader.close(); }
            if (document.getDocumentElement() == null) throw new IllegalArgumentException("POLICY_XML_EMPTY");
            return document;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("POLICY_XML_INVALID");
        }
    }
    private static boolean policyPath(List<String> original) {
        int start = original.indexOf("config");
        if (start < 0) return true;
        var path = original.subList(start + 1, original.size());
        if (path.isEmpty()) return true;
        int scope = path.indexOf("shared");
        if (scope < 0 && path.size() >= 4 && path.get(0).equals("devices") && path.get(1).equals("entry")
                && Set.of("vsys", "device-group").contains(path.get(2)) && path.get(3).equals("entry")) scope = 3;
        if (scope >= 0) return path.size() <= scope + 1 || Set.of("address", "address-group", "service", "service-group",
                "application-group", "tag", "rulebase", "pre-rulebase", "post-rulebase").contains(path.get(scope + 1));
        return path.get(0).equals("devices") && (path.size() == 1 || path.get(1).equals("entry"))
                && (path.size() <= 2 || Set.of("vsys", "device-group").contains(path.get(2)));
    }
    public static List<Element> selectRelative(Element root, String path) {
        List<Element> current = List.of(root);
        for (String name : path.split("/")) {
            List<Element> next = new ArrayList<>();
            for (Element parent : current) for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling())
                if (child instanceof Element element && element.getTagName().equals(name)) next.add(element);
            current = next;
        }
        return current;
    }
    public static Optional<String> firstRelativeText(Element root, String path) {
        return selectRelative(root, path).stream().findFirst().map(e -> e.getTextContent().trim());
    }
}
