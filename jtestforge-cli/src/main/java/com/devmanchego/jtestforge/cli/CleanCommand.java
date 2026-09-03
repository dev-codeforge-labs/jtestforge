package com.devmanchego.jtestforge.cli;

import com.devmanchego.jtestforge.config.ConfigLoadException;
import com.devmanchego.jtestforge.config.JTestForgeConfig;
import com.devmanchego.jtestforge.state.LockFile;
import com.devmanchego.jtestforge.state.LockHeldException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.concurrent.Callable;

/**
 * {@code jtestforge clean} — jtestforge-specification.md §14: removes state, transcripts
 * and backups for the module.
 *
 * <p>Refuses to run while another JTestForge process genuinely holds the module's lock -
 * deleting state out from under a live run would corrupt it, not merely lose history. A
 * <em>stale</em> lock (holder no longer alive) does not block cleaning: it is itself one
 * of the files being removed.
 */
@Command(
        name = "clean",
        description = "Remove state, transcripts and backups for the module."
)
public final class CleanCommand implements Callable<Integer> {

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

        if (!Files.exists(stateDir)) {
            console.info("Nothing to clean: " + stateDir + " does not exist.");
            return ExitCodes.SUCCESS;
        }

        try {
            LockFile probe = LockFile.acquire(stateDir, Clock.systemUTC());
            probe.release();
        } catch (LockHeldException e) {
            if (!e.isStale()) {
                console.error("Refusing to clean while another JTestForge run (PID " + e.holderPid()
                        + ") holds the lock. Wait for it to finish, or use --force-unlock on that run first.");
                return ExitCodes.LOCK_HELD;
            }
            // A stale lock is one of the files this command is about to remove anyway.
        }

        int removed = deleteRecursively(stateDir);
        console.info("Removed " + stateDir + " (" + removed + " file(s)).");
        return ExitCodes.SUCCESS;
    }

    private int deleteRecursively(Path root) {
        try (var walk = Files.walk(root)) {
            var toDelete = walk.sorted(Comparator.reverseOrder()).toList();
            int count = 0;
            for (Path path : toDelete) {
                if (Files.deleteIfExists(path)) {
                    count++;
                }
            }
            return count;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to remove " + root, e);
        }
    }

    private ConsoleOutput console() {
        return new ConsoleOutput(spec.commandLine().getOut(), spec.commandLine().getErr());
    }
}
