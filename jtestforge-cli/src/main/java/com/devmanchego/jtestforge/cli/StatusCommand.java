package com.devmanchego.jtestforge.cli;

import com.devmanchego.jtestforge.config.ConfigLoadException;
import com.devmanchego.jtestforge.config.JTestForgeConfig;
import com.devmanchego.jtestforge.model.RunState;
import com.devmanchego.jtestforge.model.UnitStatus;
import com.devmanchego.jtestforge.model.WorkUnit;
import com.devmanchego.jtestforge.state.StateStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code jtestforge status} — jtestforge-specification.md §14: prints the current state
 * file as a table. Read-only, and the same source the run report reads from (§15) - there
 * is no second bookkeeping artifact this could disagree with.
 */
@Command(
        name = "status",
        description = "Print the current state file as a table."
)
public final class StatusCommand implements Callable<Integer> {

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
        Path stateDir = ConfigResolver.stateDirOf(config, modulePath);
        StateStore stateStore = new StateStore(stateDir, Clock.systemUTC());

        Optional<RunState> loaded = stateStore.load();
        if (loaded.isEmpty()) {
            console.info("No run state found at " + stateStore.stateFile() + ". Nothing has been run yet.");
            return ExitCodes.SUCCESS;
        }

        printStatus(console, loaded.get());
        return ExitCodes.SUCCESS;
    }

    private void printStatus(ConsoleOutput console, RunState state) {
        console.info("Run " + state.runId() + " (" + state.phase() + ")");
        console.info("Module: " + state.modulePath());
        console.info("Started: " + state.startedAt() + "  Updated: " + state.updatedAt());
        console.info("");

        Map<UnitStatus, Integer> counts = state.statusCounts();
        if (counts.isEmpty()) {
            console.info("No work units discovered.");
            return;
        }
        console.info(String.format("%-28s %6s", "Status", "Count"));
        for (UnitStatus status : UnitStatus.values()) {
            Integer count = counts.get(status);
            if (count != null && count > 0) {
                console.info(String.format("%-28s %6d", status, count));
            }
        }
        console.info("");
        console.info("Total: " + state.units().size() + " unit(s)");

        long inProgress = state.units().stream().filter(unit -> unit.status() == UnitStatus.IN_PROGRESS).count();
        if (inProgress > 0) {
            console.warn(inProgress + " unit(s) are still marked IN_PROGRESS - a previous run may have been "
                    + "interrupted. Resuming will reconcile them against the current source.");
        }
        for (WorkUnit unit : state.units()) {
            if (unit.status() == UnitStatus.FAILED_COMPILE || unit.status() == UnitStatus.FAILED_ASSERTION
                    || unit.status() == UnitStatus.PROVIDER_ERROR) {
                console.info("  " + unit.id() + ": " + unit.status()
                        + (unit.lastError() == null ? "" : " (" + unit.lastError() + ")"));
            }
        }
    }

    private ConsoleOutput console() {
        return new ConsoleOutput(spec.commandLine().getOut(), spec.commandLine().getErr());
    }
}
