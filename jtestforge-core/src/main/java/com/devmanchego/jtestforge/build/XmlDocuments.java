package com.devmanchego.jtestforge.build;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal DOM access for {@code pom.xml} and {@code settings.xml}: direct children only,
 * so a {@code <groupId>} inside a {@code <dependency>} is never mistaken for the
 * project's own. DOCTYPE declarations are refused outright (XXE).
 */
final class XmlDocuments {

    private XmlDocuments() {
    }

    static Element rootOf(Path file) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        } catch (ParserConfigurationException | SAXException e) {
            throw new IllegalArgumentException("Not well-formed XML: " + file + " - " + e.getMessage(), e);
        }
    }

    static Element child(Element parent, String name) {
        for (Element element : elements(parent)) {
            if (name.equals(name(element))) {
                return element;
            }
        }
        return null;
    }

    static List<Element> children(Element parent, String name) {
        return elements(parent).stream().filter(element -> name.equals(name(element))).toList();
    }

    static List<Element> elements(Element parent) {
        List<Element> result = new ArrayList<>();
        if (parent == null) {
            return result;
        }
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element) {
                result.add(element);
            }
        }
        return result;
    }

    static String text(Element parent, String name) {
        Element element = child(parent, name);
        return element == null ? null : element.getTextContent().strip();
    }

    static String name(Element element) {
        String tag = element.getTagName();
        int colon = tag.indexOf(':');
        return colon < 0 ? tag : tag.substring(colon + 1);
    }
}
