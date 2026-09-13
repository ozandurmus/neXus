package com.securityexpert.nexus.ui2.worker.discovery.pan;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Hardened XML parsing and slash-path navigation for the enumeration and
 * key-generation responses. "XML parsing with the JDK parser configured
 * against external entities and DTDs" (WORKER.md): DOCTYPE declarations are
 * disallowed outright, so a response carrying one fails closed without ever
 * reaching entity resolution (AC-4) -- the external-entity/XInclude
 * settings below are defense in depth on top of that.
 */
final class PanoramaXmlSupport {

    private PanoramaXmlSupport() {
    }

    static Document parse(String body) {
        try {
            DocumentBuilder builder = hardenedBuilder();
            return builder.parse(new InputSource(new StringReader(body)));
        } catch (SAXException | IOException e) {
            throw new PanoramaQueryFailedException();
        }
    }

    private static DocumentBuilder hardenedBuilder() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new PanoramaQueryFailedException();
        }
    }

    /** Absolute path from the document root: the first segment must name the root element itself. */
    static List<Element> select(Document doc, String path) {
        Element root = doc.getDocumentElement();
        if (root == null) {
            return List.of();
        }
        String[] segments = path.split("/");
        if (segments.length == 0 || !root.getTagName().equals(segments[0])) {
            return List.of();
        }
        List<Element> current = new ArrayList<>();
        current.add(root);
        for (int i = 1; i < segments.length; i++) {
            current = childrenNamed(current, segments[i]);
        }
        return current;
    }

    /** Path relative to one element: every segment is one level of child-name traversal. */
    static List<Element> selectRelative(Element context, String path) {
        List<Element> current = new ArrayList<>();
        current.add(context);
        for (String segment : path.split("/")) {
            current = childrenNamed(current, segment);
        }
        return current;
    }

    static Optional<String> firstText(Document doc, String path) {
        List<Element> matches = select(doc, path);
        return matches.isEmpty() ? Optional.empty() : Optional.of(textOf(matches.get(0)));
    }

    static Optional<String> firstRelativeText(Element context, String path) {
        List<Element> matches = selectRelative(context, path);
        return matches.isEmpty() ? Optional.empty() : Optional.of(textOf(matches.get(0)));
    }

    private static List<Element> childrenNamed(List<Element> parents, String name) {
        List<Element> result = new ArrayList<>();
        for (Element parent : parents) {
            NodeList children = parent.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node instanceof Element el && el.getTagName().equals(name)) {
                    result.add(el);
                }
            }
        }
        return result;
    }

    private static String textOf(Element element) {
        return element.getTextContent() == null ? "" : element.getTextContent().trim();
    }
}
