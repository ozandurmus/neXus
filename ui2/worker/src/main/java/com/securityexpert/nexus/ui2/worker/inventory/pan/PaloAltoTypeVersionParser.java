package com.securityexpert.nexus.ui2.worker.inventory.pan;

import java.io.StringReader;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;
import com.securityexpert.nexus.ui2.persistence.device.PanTypeVersion;

/**
 * The firewall's own answer to the documented XML API request {@code type=version} (PO 2026-10-21,
 * {@link PanTypeVersion}): {@code response/result/{sw-version, multi-vsys, model, serial}}. Nothing else is mapped --
 * the response carries no hostname, family, uptime or content version, and a field it does not carry stays empty
 * (UNKNOWN), never a default and never a guess.
 *
 * <p>Fail closed: a body that is not a well-formed {@code <response status="success">} with a {@code <result>}, is
 * larger than {@link #MAX_BODY_CHARS}, carries a DOCTYPE, or repeats a field is {@link Info#UNUSABLE} (every field
 * empty). A value with a control character or longer than {@link #MAX_VALUE_CHARS} is dropped, not truncated. The
 * serial is kept exactly as received after the whitespace strip (identity law: opaque identifier).</p>
 */
public final class PaloAltoTypeVersionParser {

    /** The same output bound the failover reads apply to a PAN XML answer. */
    public static final int MAX_BODY_CHARS = 262_144;
    static final int MAX_VALUE_CHARS = 128;
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}]");

    private PaloAltoTypeVersionParser() {
    }

    /**
     * The one request neXus sends for this read: {@code POST /api/} with the form body {@code type=version}, the API
     * key in the {@code X-PAN-KEY} header (never the URL), no {@code cmd}. Same transport, timeouts and key as every
     * other PAN request.
     */
    public static XmlApiSpec request(Map<String, String> headers) {
        return new XmlApiSpec("POST", PanTypeVersion.TYPE, "", "direct_firewall", Map.of(), headers);
    }

    public static Info parse(String body) {
        if (body == null || body.isBlank() || body.length() > MAX_BODY_CHARS) {
            return Info.UNUSABLE;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            Element response = factory.newDocumentBuilder().parse(new InputSource(new StringReader(body))).getDocumentElement();
            if (!"response".equals(response.getTagName()) || !"success".equals(response.getAttribute("status"))) {
                return Info.UNUSABLE;
            }
            Element result = child(response, "result");
            if (result == null) {
                return Info.UNUSABLE;
            }
            return new Info(true, text(result, "serial"), text(result, "model"), text(result, "sw-version"),
                    multiVsys(text(result, "multi-vsys")));
        } catch (Exception invalid) {
            return Info.UNUSABLE;
        }
    }

    private static Optional<String> multiVsys(Optional<String> value) {
        return value.map(v -> v.toLowerCase(java.util.Locale.ROOT)).filter(v -> v.equals("on") || v.equals("off"));
    }

    /** The first and only direct child with this name; none, or a repeated one, is null. */
    private static Element child(Element parent, String name) {
        Element found = null;
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element element && name.equals(element.getTagName())) {
                if (found != null) {
                    return null;
                }
                found = element;
            }
        }
        return found;
    }

    private static Optional<String> text(Element parent, String name) {
        Element element = child(parent, name);
        if (element == null) {
            return Optional.empty();
        }
        String value = element.getTextContent() == null ? "" : element.getTextContent().strip();
        if (value.isEmpty() || value.length() > MAX_VALUE_CHARS || CONTROL.matcher(value).find()) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    /**
     * @param envelopeOk the answer was a well-formed success envelope with a result (even if some fields are missing)
     * @param serial the firewall serial exactly as received (whitespace stripped), or empty
     * @param multiVsys {@code on} / {@code off} as reported, or empty; parsed for the log line only, nothing stores it
     */
    public record Info(boolean envelopeOk, Optional<String> serial, Optional<String> model, Optional<String> swVersion,
            Optional<String> multiVsys) {

        public static final Info UNUSABLE =
                new Info(false, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        public Info {
            serial = serial == null ? Optional.empty() : serial;
            model = model == null ? Optional.empty() : model;
            swVersion = swVersion == null ? Optional.empty() : swVersion;
            multiVsys = multiVsys == null ? Optional.empty() : multiVsys;
        }

        /** True when the read gave a serial: the only thing the identity check can compare. */
        public boolean hasSerial() {
            return serial.isPresent();
        }
    }
}
