package com.securityexpert.nexus.ui2.worker.inventory.lifecycle;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/** The XML settings of {@code LifecycleReadShape.xmlShape}: no DOCTYPE, no external entities, no XInclude. */
final class SecureXml {

    private static final int MAX_CHARS = 2_000_000;

    private SecureXml() {
    }

    /** The root element, or an exception for anything that is not a well-formed, DOCTYPE-free document. */
    static Element parse(String xml) throws ParserConfigurationException, SAXException, IOException {
        if (xml == null || xml.isBlank() || xml.length() > MAX_CHARS) {
            throw new IOException("unusable body");
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new ErrorHandler() {
            @Override
            public void warning(SAXParseException e) {
            }

            @Override
            public void error(SAXParseException e) throws SAXException {
                throw e;
            }

            @Override
            public void fatalError(SAXParseException e) throws SAXException {
                throw e;
            }
        });
        return builder.parse(new InputSource(new StringReader(xml))).getDocumentElement();
    }

    static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        if (parent == null) {
            return out;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(name)) {
                out.add(e);
            }
        }
        return out;
    }

    static Element child(Element parent, String name) {
        List<Element> all = children(parent, name);
        return all.isEmpty() ? null : all.get(0);
    }

    /** Trimmed text content of a direct child, or "" when absent. */
    static String text(Element parent, String name) {
        Element e = child(parent, name);
        return e == null || e.getTextContent() == null ? "" : e.getTextContent().strip();
    }
}
