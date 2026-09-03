package com.devmanchego.jtestforge.cli;

import com.devmanchego.jtestforge.analysis.ProductionClassScanner;
import com.devmanchego.jtestforge.analysis.ProductionTypeSolvers;
import com.devmanchego.jtestforge.analysis.SelectionFilter;
import com.devmanchego.jtestforge.build.MavenClasspathResolver;
import com.devmanchego.jtestforge.config.ConfigLoadException;
import com.devmanchego.jtestforge.config.JTestForgeConfig;
import com.devmanchego.jtestforge.model.ProductionClass;
import com.devmanchego.jtestforge.model.ProductionMethod;
import com.devmanchego.jtestforge.model.SemanticGap;
import com.devmanchego.jtestforge.model.Tier;
import com.devmanchego.jtestforge.spring.FrameworkSemanticGapScanner;
import com.devmanchego.jtestforge.spring.TierClassifier;
import com.devmanchego.jtestforge.util.ProcessRunner;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code jtestforge scan} — jtestforge-specification.md §14: the cheap dry run. Analysis,
 * tier classification (§7.4) and framework-semantic gap detection (§7.5) only - no
 * coverage measurement, no AI call, no file write, no Spring context load.
 *
 * <p>Real Maven classpath resolution is not skipped, though: an accurate scan needs the
 * same {@code TypeSolver} a real {@code generate} run would use, or annotations and
 * collaborator types that only resolve via an external dependency would silently read as
 * unresolved. That one {@code mvn dependency:build-classpath} invocation is the only
 * process this command starts.
 */
@Command(
        name = "scan",
        description = "Analyse the module and print the work units that WOULD be "
                + "attempted, with current coverage. No AI calls, no writes."
)
public final class ScanCommand implements Callable<Integer> {

    private static final Duration CLASSPATH_TIMEOUT = Duration.ofMinutes(2);

    @Mixin
    private CommonModuleOptions options;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        ConsoleOutput console = console();
        JTestForgeConfig config;
        try {
            config = ConfigResolver.load(options).config();
        } catch (ConfigLoadException e) {
            console.error(e.getMessage());
            return ExitCodes.CONFIGURATION_OR_PREFLIGHT_ERROR;
        }

        Path modulePath = ConfigResolver.modulePathOf(config);
        if (!Files.isRegularFile(modulePath.resolve("pom.xml"))) {
            console.error("Not a Maven module (no pom.xml found): " + modulePath);
            return ExitCodes.CONFIGURATION_OR_PREFLIGHT_ERROR;
        }
        Path mainSourceRoot = modulePath.resolve(config.project().mainSourceRoot());
        if (!Files.isDirectory(mainSourceRoot)) {
            console.error("No main source root at " + mainSourceRoot);
            return ExitCodes.CONFIGURATION_OR_PREFLIGHT_ERROR;
        }

        List<ProductionClass> productionClasses;
        try {
            List<Path> classpath = resolveClasspath(config, modulePath);
            var typeSolver = ProductionTypeSolvers.forModule(mainSourceRoot, classpath);
            productionClasses = new ProductionClassScanner(typeSolver, mainSourceRoot).scan();
        } catch (RuntimeException e) {
            console.error("Failed to analyse " + modulePath + ": " + e.getMessage());
            return ExitCodes.CONFIGURATION_OR_PREFLIGHT_ERROR;
        }

        printScanResult(console, config, productionClasses);
        return ExitCodes.SUCCESS;
    }

    private List<Path> resolveClasspath(JTestForgeConfig config, Path modulePath) {
        var resolver = new MavenClasspathResolver(new ProcessRunner(),
                config.project().mavenExecutable(), config.project().mavenArgs());
        return resolver.resolveCompileClasspath(modulePath, CLASSPATH_TIMEOUT);
    }

    private void printScanResult(ConsoleOutput console, JTestForgeConfig config, List<ProductionClass> classes) {
        SelectionFilter selectionFilter = new SelectionFilter(config.selection());
        TierClassifier tierClassifier = new TierClassifier();
        FrameworkSemanticGapScanner gapScanner = new FrameworkSemanticGapScanner();
        boolean detectGaps = config.spring().detectFrameworkSemanticGaps();

        Map<Tier, Integer> methodsPerTier = new EnumMap<>(Tier.class);
        int classesConsidered = 0;
        int classesExcluded = 0;
        int methodsConsidered = 0;
        int methodsExcluded = 0;
        int openGaps = 0;

        for (ProductionClass productionClass : classes) {
            if (!selectionFilter.isClassIncluded(productionClass)) {
                classesExcluded++;
                continue;
            }
            classesConsidered++;
            Tier tier = tierClassifier.primaryTier(productionClass, config.spring().preferLowestTier());

            for (ProductionMethod method : productionClass.methods()) {
                if (!selectionFilter.isMethodIncluded(method)) {
                    methodsExcluded++;
                    continue;
                }
                methodsConsidered++;
                methodsPerTier.merge(tier, 1, Integer::sum);
            }

            if (detectGaps) {
                List<SemanticGap> gaps = gapScanner.scan(productionClass);
                openGaps += gaps.size();
            }
        }

        console.info("Scanned " + classes.size() + " production class(es) under "
                + config.project().mainSourceRoot());
        console.info(classesConsidered + " class(es) selected, " + classesExcluded + " excluded by `selection`");
        console.info(methodsConsidered + " method(s) would be attempted, " + methodsExcluded
                + " excluded by `selection`");
        console.info("");
        console.info(String.format("%-16s %10s", "Tier", "Methods"));
        for (Tier tier : Tier.values()) {
            Integer count = methodsPerTier.get(tier);
            if (count != null && count > 0) {
                console.info(String.format("%-16s %10d", tier, count));
            }
        }
        console.info("");
        console.info(openGaps + " framework-semantic gap(s) currently open (§7.5)"
                + (detectGaps ? "" : " - detection disabled by spring.detectFrameworkSemanticGaps"));
    }

    private ConsoleOutput console() {
        return new ConsoleOutput(spec.commandLine().getOut(), spec.commandLine().getErr());
    }
}
