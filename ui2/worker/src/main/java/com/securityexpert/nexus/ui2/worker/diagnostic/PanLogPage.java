package com.securityexpert.nexus.ui2.worker.diagnostic;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/**
 * One finished PAN-OS log job ({@code type=log&action=get}) read into entries for storage (system-log programme phase B,
 * DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md section 6, "Storage"): every {@code <entry>} child element name maps to its
 * text as received, entry attributes keep an {@code @} prefix. The entry's {@code receive_time} (the cursor) and
 * {@code seqno} (its identity at a boundary second, never an ordering or contiguity assumption) are lifted out and
 * shape-checked; an entry without them cannot be placed against the cursor, so the page is unreadable.
 */
record PanLogPage(List<Entry> entries) {
    private static final Pattern RECEIVE_TIME = Pattern.compile("[0-9]{4}/[0-9]{2}/[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}");
    private static final Pattern SEQNO = Pattern.compile("[0-9]{1,20}");

    /** @param fields the entry as stored (insertion order = document order); redaction rewrites a copy of it. */
    record Entry(String receiveTime, String seqno, Map<String, String> fields) {
    }

    /** Why a page could not be read; only these fixed codes ever leave this class. */
    static final class Unreadable extends Exception {
        final String code;
        Unreadable(String code) { super(code, null, false, false); this.code = code; }
    }

    static PanLogPage parse(String body) throws Unreadable {
        Element logs;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            factory.setXIncludeAware(false);
            var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(body)));
            Element result = child(document.getDocumentElement(), "result");
            Element log = result == null ? null : child(result, "log");
            logs = log == null ? null : child(log, "logs");
        } catch (Exception unparseable) {
            // The parser's message can quote the document; it is dropped here.
            throw new Unreadable("LOG_RESULT_UNREADABLE");
        }
        if (logs == null) throw new Unreadable("LOG_RESULT_UNREADABLE");
        List<Entry> entries = new ArrayList<>();
        for (Node node = logs.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element entry) || !"entry".equals(entry.getTagName())) continue;
            Map<String, String> fields = new LinkedHashMap<>();
            var attributes = entry.getAttributes();
            for (int i = 0; i < attributes.getLength(); i++)
                fields.put("@" + attributes.item(i).getNodeName(), attributes.item(i).getNodeValue());
            for (Node field = entry.getFirstChild(); field != null; field = field.getNextSibling()) {
                if (!(field instanceof Element element)) continue;
                String name = element.getTagName();
                String text = element.getTextContent() == null ? "" : element.getTextContent();
                int duplicate = 1;
                String key = name;
                while (fields.containsKey(key)) key = name + "#" + (++duplicate);
                fields.put(key, text);
            }
            String time = fields.get("receive_time") == null ? "" : fields.get("receive_time").strip();
            if (!RECEIVE_TIME.matcher(time).matches()) throw new Unreadable("LOG_ENTRY_TIME_UNREADABLE");
            String seqno = fields.get("seqno") == null ? "" : fields.get("seqno").strip();
            if (!SEQNO.matcher(seqno).matches()) throw new Unreadable("LOG_ENTRY_SEQNO_UNREADABLE");
            entries.add(new Entry(time, seqno, fields));
        }
        return new PanLogPage(entries);
    }

    private static Element child(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getTagName())) return element;
        }
        return null;
    }
}
