package com.devmanchego.jtestforge.build;

import com.devmanchego.jtestforge.model.SurefireTestResult;
import com.devmanchego.jtestforge.model.TestOutcome;

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
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads test outcomes from {@code target/surefire-reports/*.xml} —
 * jtestforge-specification.md §13.1.
 *
 * <p>Read from the XML, never from stdout: the schema is stable across Surefire versions
 * and console locales, the console text is not.
 *
 * <p>A {@code &lt;error&gt;} whose exception type or message names a Spring context-loading
 * failure is reported as {@link TestOutcome#CONTEXT_LOAD_FAILURE}, not
 * {@link TestOutcome#ERROR} - see {@link TestOutcome} for why that distinction exists.
 */
public final class SurefireReportParser {

    private static final int STACK_TRACE_HEAD_LINES = 10;

    private static final Set<String> CONTEXT_FAILURE_TYPE_MARKERS = Set.of(
            "ApplicationContextException", "BeanCreationException", "BeanDefinitionStoreException",
            "BeanInstantiationException", "NoSuchBeanDefinitionException",
            "UnsatisfiedDependencyException", "BeanCurrentlyInCreationException");
    private static final String CONTEXT_FAILURE_MESSAGE_MARKER = "Failed to load ApplicationContext";

    private final XMLInputFactory xmlInputFactory = XMLInputFactory.newDefaultFactory();

    /** Every {@code TEST-*.xml} in {@code surefireReportsDir}, in a stable, sorted order. */
    public List<SurefireTestResult> parseDirectory(Path surefireReportsDir) {
        if (!Files.isDirectory(surefireReportsDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(surefireReportsDir)) {
            List<Path> reportFiles = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith("TEST-"))
                    .filter(p -> p.getFileName().toString().endsWith(".xml"))
                    .sorted()
                    .toList();
            List<SurefireTestResult> all = new ArrayList<>();
            for (Path reportFile : reportFiles) {
                all.addAll(parseFile(reportFile));
            }
            return List.copyOf(all);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list " + surefireReportsDir, e);
        }
    }

    public List<SurefireTestResult> parseFile(Path xmlFile) {
        try (InputStream in = Files.newInputStream(xmlFile)) {
            return parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + xmlFile, e);
        } catch (XMLStreamException e) {
            throw new SurefireReportParseException(xmlFile, e);
        }
    }

    private List<SurefireTestResult> parse(InputStream in) throws XMLStreamException {
        List<SurefireTestResult> results = new ArrayList<>();
        XMLStreamReader reader = xmlInputFactory.createXMLStreamReader(in);
        try {
            String defaultClassName = null;
            String currentTestName = null;
            String currentClassName = null;
            double currentTime = 0;
            TestOutcome currentOutcome = TestOutcome.PASSED;
            String currentFailureType = null;
            String currentFailureMessage = null;
            StringBuilder currentFailureText = null;
            String capturingElement = null;

            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String localName = reader.getLocalName();
                    switch (localName) {
                        case "testsuite" -> defaultClassName = attribute(reader, "name");
                        case "testcase" -> {
                            currentTestName = attribute(reader, "name");
                            currentClassName = attributeOrDefault(reader, "classname", defaultClassName);
                            currentTime = parseTime(attribute(reader, "time"));
                            currentOutcome = TestOutcome.PASSED;
                            currentFailureType = null;
                            currentFailureMessage = null;
                            currentFailureText = null;
                        }
                        case "failure" -> {
                            currentOutcome = TestOutcome.ASSERTION_FAILURE;
                            currentFailureType = attribute(reader, "type");
                            currentFailureMessage = attribute(reader, "message");
                            currentFailureText = new StringBuilder();
                            capturingElement = "failure";
                        }
                        case "error" -> {
                            String type = attribute(reader, "type");
                            String message = attribute(reader, "message");
                            currentOutcome = isContextLoadFailure(type, message)
                                    ? TestOutcome.CONTEXT_LOAD_FAILURE
                                    : TestOutcome.ERROR;
                            currentFailureType = type;
                            currentFailureMessage = message;
                            currentFailureText = new StringBuilder();
                            capturingElement = "error";
                        }
                        case "skipped" -> currentOutcome = TestOutcome.SKIPPED;
                        default -> { /* rerunFailure, system-out, etc. - not modelled */ }
                    }
                } else if (event == XMLStreamConstants.CHARACTERS && currentFailureText != null
                        && capturingElement != null) {
                    currentFailureText.append(reader.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String localName = reader.getLocalName();
                    if (localName.equals("failure") || localName.equals("error")) {
                        capturingElement = null;
                    } else if (localName.equals("testcase")) {
                        results.add(new SurefireTestResult(
                                currentClassName, currentTestName, currentOutcome,
                                currentFailureType, currentFailureMessage,
                                headOf(currentFailureText), currentTime));
                    }
                }
            }
        } finally {
            reader.close();
        }
        return results;
    }

    private boolean isContextLoadFailure(String type, String message) {
        if (type != null && CONTEXT_FAILURE_TYPE_MARKERS.stream().anyMatch(type::contains)) {
            return true;
        }
        return message != null && message.contains(CONTEXT_FAILURE_MESSAGE_MARKER);
    }

    private String headOf(StringBuilder failureText) {
        if (failureText == null || failureText.isEmpty()) {
            return null;
        }
        return failureText.toString().lines().limit(STACK_TRACE_HEAD_LINES)
                .reduce((a, b) -> a + "\n" + b).orElse(null);
    }

    private double parseTime(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String attribute(XMLStreamReader reader, String name) {
        return reader.getAttributeValue(null, name);
    }

    private String attributeOrDefault(XMLStreamReader reader, String name, String fallback) {
        String value = attribute(reader, name);
        return value != null ? value : fallback;
    }

    /** Thrown when a surefire report file exists but is not well-formed XML. */
    public static final class SurefireReportParseException extends RuntimeException {
        public SurefireReportParseException(Path file, XMLStreamException cause) {
            super("Failed to parse surefire report: " + file, cause);
        }
    }
}
