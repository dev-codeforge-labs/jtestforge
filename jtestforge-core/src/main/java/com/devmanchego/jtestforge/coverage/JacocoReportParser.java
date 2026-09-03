package com.devmanchego.jtestforge.coverage;

import com.devmanchego.jtestforge.model.ClassCoverage;
import com.devmanchego.jtestforge.model.LineStatus;
import com.devmanchego.jtestforge.model.MethodCoverage;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a JaCoCo {@code jacoco.xml} report into {@link ClassCoverage} —
 * jtestforge-specification.md §9.2, §9.4: {@code mvn test jacoco:report}, then
 * {@code target/site/jacoco/jacoco.xml}.
 *
 * <p><b>A counter type JaCoCo omits is zero, not absent-and-unknown.</b> A method with no
 * branches has no {@code <counter type="BRANCH">} child at all - the element is left out
 * entirely rather than written as {@code missed="0" covered="0"}. Every counter this
 * parser reads defaults to zero for exactly that reason.
 *
 * <p>Class binary names use {@code $} for nested classes ({@code Calculator$Formatter});
 * {@link ClassCoverage#fqn()} is normalised to dots throughout, including the nesting
 * separator, to match {@code ProductionClass.fqn()} as JavaParser's symbol resolver
 * produces it.
 *
 * <p>DTD resolution is disabled: the report declares
 * {@code <!DOCTYPE report SYSTEM "report.dtd">}, and a validating or entity-resolving
 * parser would attempt to fetch that relative path from the filesystem or network for no
 * benefit - this parser only ever reads element and attribute values.
 */
public final class JacocoReportParser {

    private static final String COUNTER = "counter";
    private static final String INSTRUCTION = "INSTRUCTION";
    private static final String LINE = "LINE";
    private static final String BRANCH = "BRANCH";

    public List<ClassCoverage> parse(Path xmlFile) {
        try (InputStream in = Files.newInputStream(xmlFile)) {
            return parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + xmlFile, e);
        } catch (XMLStreamException e) {
            throw new JacocoReportParseException(xmlFile, e);
        }
    }

    public List<ClassCoverage> parse(InputStream in) throws XMLStreamException {
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

    private List<ClassCoverage> read(XMLStreamReader reader) throws XMLStreamException {
        // Ordered so the result's class order matches the report's - useful for
        // deterministic test assertions and stable diffs of any derived report.
        Map<String, PendingClass> classesByBinaryName = new LinkedHashMap<>();
        Map<String, Map<Integer, LineStatus>> linesBySourcefileKey = new LinkedHashMap<>();

        String currentPackage = null;
        PendingClass currentClass = null;
        PendingMethod currentMethod = null;
        String currentSourcefileKey = null;
        Map<Integer, LineStatus> currentSourcefileLines = null;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String name = reader.getLocalName();
                switch (name) {
                    case "package" -> currentPackage = attr(reader, "name");
                    case "class" -> {
                        currentClass = new PendingClass(
                                attr(reader, "name"), attr(reader, "sourcefilename"));
                        classesByBinaryName.put(currentClass.binaryName(), currentClass);
                    }
                    case "method" -> currentMethod = new PendingMethod(
                            attr(reader, "name"), attr(reader, "desc"), intAttr(reader, "line"));
                    case "sourcefile" -> {
                        currentSourcefileKey = currentPackage + "/" + attr(reader, "name");
                        currentSourcefileLines = new LinkedHashMap<>();
                        linesBySourcefileKey.put(currentSourcefileKey, currentSourcefileLines);
                    }
                    case "line" -> {
                        if (currentSourcefileLines != null) {
                            currentSourcefileLines.put(intAttr(reader, "nr"), new LineStatus(
                                    intAttr(reader, "mi"), intAttr(reader, "ci"),
                                    intAttr(reader, "mb"), intAttr(reader, "cb")));
                        }
                    }
                    case COUNTER -> {
                        String type = attr(reader, "type");
                        int missed = intAttr(reader, "missed");
                        int covered = intAttr(reader, "covered");
                        if (currentMethod != null) {
                            currentMethod.applyCounter(type, missed, covered);
                        } else if (currentClass != null) {
                            // A <counter> that is a direct child of <class> (i.e. we are
                            // inside a class but not inside one of its methods) is the
                            // class-level aggregate - see the DTD: it always trails the
                            // <method> children.
                            currentClass.applyCounter(type, missed, covered);
                        }
                        // Counters inside <sourcefile> (its own aggregate) and <package>/
                        // <report> (rollups) are not needed: ClassCoverage's aggregate
                        // comes from the class element's own counters, which JaCoCo
                        // already scopes correctly per class.
                    }
                    default -> { /* sessioninfo, report, group - not modelled */ }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                switch (reader.getLocalName()) {
                    case "method" -> {
                        if (currentClass != null && currentMethod != null) {
                            currentClass.methods.add(currentMethod.toMethodCoverage());
                        }
                        currentMethod = null;
                    }
                    case "class" -> currentClass = null;
                    case "sourcefile" -> {
                        currentSourcefileKey = null;
                        currentSourcefileLines = null;
                    }
                    default -> { /* no-op */ }
                }
            }
        }

        List<ClassCoverage> result = new ArrayList<>();
        for (PendingClass pending : classesByBinaryName.values()) {
            Map<Integer, LineStatus> lines = linesBySourcefileKey.getOrDefault(
                    pending.sourcefileKey(), Map.of());
            result.add(pending.toClassCoverage(lines));
        }
        return List.copyOf(result);
    }

    private String attr(XMLStreamReader reader, String name) {
        return reader.getAttributeValue(null, name);
    }

    private int intAttr(XMLStreamReader reader, String name) {
        String value = attr(reader, name);
        return value == null ? 0 : Integer.parseInt(value);
    }

    /** Accumulates one {@code <class>} element's methods and its own aggregate counters. */
    private static final class PendingClass {
        private final String binaryName;
        private final String sourcefilename;
        private final List<MethodCoverage> methods = new ArrayList<>();
        private int instructionsMissed;
        private int instructionsCovered;
        private int linesMissed;
        private int linesCovered;
        private int branchesMissed;
        private int branchesCovered;

        PendingClass(String binaryName, String sourcefilename) {
            this.binaryName = binaryName;
            this.sourcefilename = sourcefilename;
        }

        String binaryName() {
            return binaryName;
        }

        /** Package is embedded in the binary name itself, e.g. {@code com/acme/Calculator$Formatter}. */
        String sourcefileKey() {
            int lastSlash = binaryName.lastIndexOf('/');
            String packagePath = lastSlash < 0 ? "" : binaryName.substring(0, lastSlash);
            return packagePath + "/" + sourcefilename;
        }

        void applyCounter(String type, int missed, int covered) {
            switch (type) {
                case INSTRUCTION -> {
                    instructionsMissed = missed;
                    instructionsCovered = covered;
                }
                case LINE -> {
                    linesMissed = missed;
                    linesCovered = covered;
                }
                case BRANCH -> {
                    branchesMissed = missed;
                    branchesCovered = covered;
                }
                default -> { /* COMPLEXITY, METHOD, CLASS - not modelled */ }
            }
        }

        ClassCoverage toClassCoverage(Map<Integer, LineStatus> lines) {
            String fqn = binaryName.replace('/', '.').replace('$', '.');
            return new ClassCoverage(fqn, instructionsMissed, instructionsCovered,
                    linesMissed, linesCovered, branchesMissed, branchesCovered, methods, lines);
        }
    }

    /** Accumulates one {@code <method>} element's own counters. */
    private static final class PendingMethod {
        private final String name;
        private final String descriptor;
        private final int startLine;
        private int instructionsMissed;
        private int instructionsCovered;
        private int linesMissed;
        private int linesCovered;
        private int branchesMissed;
        private int branchesCovered;

        PendingMethod(String name, String descriptor, int startLine) {
            this.name = name;
            this.descriptor = descriptor;
            this.startLine = startLine;
        }

        void applyCounter(String type, int missed, int covered) {
            switch (type) {
                case INSTRUCTION -> {
                    instructionsMissed = missed;
                    instructionsCovered = covered;
                }
                case LINE -> {
                    linesMissed = missed;
                    linesCovered = covered;
                }
                case BRANCH -> {
                    branchesMissed = missed;
                    branchesCovered = covered;
                }
                default -> { /* COMPLEXITY, METHOD - not modelled */ }
            }
        }

        MethodCoverage toMethodCoverage() {
            return new MethodCoverage(name, descriptor, startLine, instructionsMissed,
                    instructionsCovered, linesMissed, linesCovered, branchesMissed, branchesCovered);
        }
    }

    /** Thrown when a jacoco.xml file exists but is not well-formed XML. */
    public static final class JacocoReportParseException extends RuntimeException {
        public JacocoReportParseException(Path file, XMLStreamException cause) {
            super("Failed to parse JaCoCo report: " + file, cause);
        }
    }
}
