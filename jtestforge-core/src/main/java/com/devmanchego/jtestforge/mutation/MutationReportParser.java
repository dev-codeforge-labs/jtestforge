package com.devmanchego.jtestforge.mutation;

import com.devmanchego.jtestforge.model.Mutant;
import com.devmanchego.jtestforge.model.MutationReport;
import com.devmanchego.jtestforge.model.MutationStatus;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a PIT {@code mutations.xml} report into {@link MutationReport} —
 * jtestforge-specification.md §10.1, §13.2.
 *
 * <p>DTD resolution is disabled, matching {@code JacocoReportParser}'s reasoning: this
 * parser only ever reads element and attribute text, so there is nothing to gain from a
 * validating or entity-resolving parser and no reason to let it fetch anything.
 *
 * <p>PIT's own {@code <killingTest>} text sometimes carries a trailing
 * {@code (n/m)} run-index suffix (e.g. {@code com.acme.CalculatorTest.addsTwoNumbers(3/5)}
 * for a parameterised test); this parser strips it, since everything downstream
 * (the report, the unkillable list) wants the test's identity, not which invocation of it
 * happened to kill the mutant.
 */
public final class MutationReportParser {

    public MutationReport parse(Path xmlFile) {
        try (InputStream in = Files.newInputStream(xmlFile)) {
            return parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + xmlFile, e);
        } catch (XMLStreamException e) {
            throw new MutationReportParseException(xmlFile, e);
        }
    }

    public MutationReport parse(InputStream in) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XMLStreamReader reader = factory.createXMLStreamReader(in);
        try {
            return read(reader);
        } finally {
            reader.close();
        }
    }

    private MutationReport read(XMLStreamReader reader) throws XMLStreamException {
        List<Mutant> mutants = new ArrayList<>();
        PendingMutant current = null;
        String currentText = null;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String name = reader.getLocalName();
                if (name.equals("mutation")) {
                    current = new PendingMutant(attr(reader, "status"));
                }
                currentText = null;
            } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                if (current != null) {
                    currentText = currentText == null ? reader.getText() : currentText + reader.getText();
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                String name = reader.getLocalName();
                if (current != null) {
                    applyEndElement(current, name, currentText);
                }
                currentText = null;
                if (name.equals("mutation") && current != null) {
                    mutants.add(current.toMutant());
                    current = null;
                }
            }
        }
        return new MutationReport(mutants);
    }

    private void applyEndElement(PendingMutant pending, String elementName, String text) {
        String value = text == null ? "" : text.trim();
        switch (elementName) {
            case "mutatedClass" -> pending.mutatedClass = value;
            case "mutatedMethod" -> pending.mutatedMethod = value;
            case "methodDescription" -> pending.methodDescription = value;
            case "lineNumber" -> pending.lineNumber = value.isEmpty() ? 0 : Integer.parseInt(value);
            case "mutator" -> pending.mutator = value;
            case "index" -> {
                if (!value.isEmpty()) {
                    pending.indexes.add(Integer.parseInt(value));
                }
            }
            case "killingTest" -> pending.killingTest = value.isEmpty() ? null : stripRunIndexSuffix(value);
            case "description" -> pending.description = value;
            default -> { /* sourceFile, indexes, blocks, block - not modelled */ }
        }
    }

    private String stripRunIndexSuffix(String killingTest) {
        int parenIndex = killingTest.lastIndexOf('(');
        return parenIndex > 0 && killingTest.endsWith(")") ? killingTest.substring(0, parenIndex) : killingTest;
    }

    private String attr(XMLStreamReader reader, String name) {
        return reader.getAttributeValue(null, name);
    }

    /** Accumulates one {@code <mutation>} element's children as they stream past. */
    private static final class PendingMutant {
        private final String status;
        private String mutatedClass = "";
        private String mutatedMethod = "";
        private String methodDescription = "";
        private int lineNumber;
        private String mutator = "";
        private final List<Integer> indexes = new ArrayList<>();
        private String killingTest;
        private String description = "";

        PendingMutant(String status) {
            this.status = status;
        }

        Mutant toMutant() {
            return new Mutant(mutatedClass, mutatedMethod, methodDescription, lineNumber, mutator,
                    indexes, MutationStatus.valueOf(status), killingTest, description);
        }
    }

    /** Thrown when a mutations.xml file exists but is not well-formed XML. */
    public static final class MutationReportParseException extends RuntimeException {
        public MutationReportParseException(Path file, XMLStreamException cause) {
            super("Failed to parse PIT report: " + file, cause);
        }
    }
}
