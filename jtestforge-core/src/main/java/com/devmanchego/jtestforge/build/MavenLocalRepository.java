package com.devmanchego.jtestforge.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The local Maven repository, located the way Maven itself would - but without starting
 * Maven: {@code project.localRepository}, then {@code -Dmaven.repo.local} in
 * {@code mavenArgs}, then {@code ~/.m2/settings.xml}, then the Maven installation's
 * {@code conf/settings.xml}, then {@code ~/.m2/repository}.
 *
 * @param source where the path came from, for the run's diagnostic output
 */
public record MavenLocalRepository(Path path, String source) {

    private static final String REPO_LOCAL_ARG = "-Dmaven.repo.local=";
    private static final Pattern ENV_REFERENCE = Pattern.compile("\\$\\{env\\.([A-Za-z0-9_]+)}");

    /**
     * @param mavenExecutable resolves the {@code mvn} launcher, if any - its installation's
     *                        {@code conf/settings.xml} is the global settings file. Lazy:
     *                        searching {@code PATH} for it is skipped entirely when
     *                        {@code configured}, a {@code -Dmaven.repo.local} arg, or the
     *                        user's own {@code ~/.m2/settings.xml} already answers.
     */
    public static MavenLocalRepository detect(String configured, List<String> mavenArgs, Path userHome,
                                              Map<String, String> environment, Supplier<Optional<Path>> mavenExecutable) {
        if (configured != null && !configured.isBlank()) {
            return of(configured, userHome, environment, "project.localRepository");
        }
        for (String arg : mavenArgs) {
            if (arg.startsWith(REPO_LOCAL_ARG)) {
                return of(arg.substring(REPO_LOCAL_ARG.length()), userHome, environment, "mavenArgs " + REPO_LOCAL_ARG);
            }
        }
        Optional<MavenLocalRepository> fromUserSettings =
                fromSettings(userHome.resolve(".m2").resolve("settings.xml"), userHome, environment);
        if (fromUserSettings.isPresent()) {
            return fromUserSettings.get();
        }
        // Only reached without a user settings.xml answer, so the PATH search a bare "mvn"
        // needs is skipped in the (common) case that already resolved the repository.
        for (Path settings : globalSettingsCandidates(environment, mavenExecutable)) {
            Optional<MavenLocalRepository> fromSettings = fromSettings(settings, userHome, environment);
            if (fromSettings.isPresent()) {
                return fromSettings.get();
            }
        }
        return new MavenLocalRepository(userHome.resolve(".m2").resolve("repository"), "Maven default (~/.m2/repository)");
    }

    /** Standard repository layout: {@code g/r/o/u/p/artifactId/version/artifactId-version[-classifier].ext}. */
    public Path artifact(String groupId, String artifactId, String version, String classifier, String extension) {
        Path directory = path;
        for (String segment : groupId.split("\\.")) {
            directory = directory.resolve(segment);
        }
        String suffix = classifier == null || classifier.isEmpty() ? "" : "-" + classifier;
        return directory.resolve(artifactId).resolve(version)
                .resolve(artifactId + "-" + version + suffix + "." + extension);
    }

    private static List<Path> globalSettingsCandidates(Map<String, String> environment, Supplier<Optional<Path>> mavenExecutable) {
        List<Path> candidates = new ArrayList<>();
        for (String variable : List.of("MAVEN_HOME", "M2_HOME")) {
            String home = environment.get(variable);
            if (home != null && !home.isBlank()) {
                candidates.add(Path.of(home, "conf", "settings.xml"));
            }
        }
        // <maven home>/bin/mvn(.cmd) -> <maven home>/conf/settings.xml
        mavenExecutable.get().map(Path::toAbsolutePath).map(Path::getParent).map(Path::getParent)
                .ifPresent(home -> candidates.add(home.resolve("conf").resolve("settings.xml")));
        return candidates;
    }

    private static Optional<MavenLocalRepository> fromSettings(
            Path settings, Path userHome, Map<String, String> environment) {
        if (!Files.isRegularFile(settings)) {
            return Optional.empty();
        }
        String value;
        try {
            value = XmlDocuments.text(XmlDocuments.rootOf(settings), "localRepository");
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(of(value, userHome, environment, settings.toString()));
    }

    private static MavenLocalRepository of(String raw, Path userHome, Map<String, String> environment, String source) {
        String expanded = raw.strip().replace("${user.home}", userHome.toString());
        Matcher matcher = ENV_REFERENCE.matcher(expanded);
        expanded = matcher.replaceAll(match -> Matcher.quoteReplacement(environment.getOrDefault(match.group(1), "")));
        return new MavenLocalRepository(Path.of(expanded).toAbsolutePath().normalize(), source);
    }
}
