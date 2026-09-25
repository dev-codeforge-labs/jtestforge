package com.devmanchego.jtestforge.build;

import org.w3c.dom.Element;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads a single {@code pom.xml} into a {@link PomInfo}; no inheritance, no interpolation. */
public final class PomReader {

    private static final String COMPILER_PLUGIN = "maven-compiler-plugin";
    private static final List<String> COMPILER_KEYS = List.of("release", "source", "target");

    private PomReader() {
    }

    /**
     * @throws java.io.UncheckedIOException if the file cannot be read
     * @throws IllegalArgumentException     if it is not well-formed XML
     */
    public static PomInfo read(Path pomFile) {
        Element project = XmlDocuments.rootOf(pomFile);
        PomInfo.ParentReference parent = parentOf(project);
        return new PomInfo(
                pomFile.toAbsolutePath().normalize(),
                firstNonBlank(XmlDocuments.text(project, "groupId"), parent == null ? null : parent.groupId()),
                XmlDocuments.text(project, "artifactId"),
                firstNonBlank(XmlDocuments.text(project, "version"), parent == null ? null : parent.version()),
                firstNonBlank(XmlDocuments.text(project, "packaging"), "jar"),
                parent,
                properties(project),
                modules(project),
                compilerConfiguration(project));
    }

    private static PomInfo.ParentReference parentOf(Element project) {
        Element parent = XmlDocuments.child(project, "parent");
        if (parent == null) {
            return null;
        }
        Element relativePath = XmlDocuments.child(parent, "relativePath");
        return new PomInfo.ParentReference(
                XmlDocuments.text(parent, "groupId"),
                XmlDocuments.text(parent, "artifactId"),
                XmlDocuments.text(parent, "version"),
                relativePath == null ? "../pom.xml" : relativePath.getTextContent().strip());
    }

    private static Map<String, String> properties(Element project) {
        Map<String, String> properties = new LinkedHashMap<>();
        for (Element property : XmlDocuments.elements(XmlDocuments.child(project, "properties"))) {
            properties.put(XmlDocuments.name(property), property.getTextContent().strip());
        }
        return properties;
    }

    private static List<String> modules(Element project) {
        return XmlDocuments.children(XmlDocuments.child(project, "modules"), "module").stream()
                .map(module -> module.getTextContent().strip())
                .filter(module -> !module.isEmpty())
                .toList();
    }

    /** {@code <plugins>} is read after {@code <pluginManagement>} so it wins, as in Maven. */
    private static Map<String, String> compilerConfiguration(Element project) {
        Map<String, String> configuration = new LinkedHashMap<>();
        Element build = XmlDocuments.child(project, "build");
        collectCompilerConfiguration(
                XmlDocuments.child(XmlDocuments.child(build, "pluginManagement"), "plugins"), configuration);
        collectCompilerConfiguration(XmlDocuments.child(build, "plugins"), configuration);
        return configuration;
    }

    private static void collectCompilerConfiguration(Element plugins, Map<String, String> into) {
        for (Element plugin : XmlDocuments.children(plugins, "plugin")) {
            if (!COMPILER_PLUGIN.equals(XmlDocuments.text(plugin, "artifactId"))) {
                continue;
            }
            Element configuration = XmlDocuments.child(plugin, "configuration");
            for (String key : COMPILER_KEYS) {
                String value = XmlDocuments.text(configuration, key);
                if (value != null && !value.isBlank()) {
                    into.put(key, value);
                }
            }
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
