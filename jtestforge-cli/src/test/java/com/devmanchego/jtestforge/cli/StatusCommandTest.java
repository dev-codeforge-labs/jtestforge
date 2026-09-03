package com.devmanchego.jtestforge.cli;

import com.devmanchego.jtestforge.model.Phase;
import com.devmanchego.jtestforge.model.RunState;
import com.devmanchego.jtestforge.model.SpringTierState;
import com.devmanchego.jtestforge.model.Tier;
import com.devmanchego.jtestforge.model.UnitStatus;
import com.devmanchego.jtestforge.model.WorkUnit;
import com.devmanchego.jtestforge.model.WorkUnitId;
import com.devmanchego.jtestforge.state.StateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code jtestforge status} — jtestforge-implementation-plan.md phase 17. */
class StatusCommandTest {

    private static final Instant NOW = Instant.parse("2026-08-31T10:00:00Z");

    @Test
    void withNoStateFileItSaysSoRatherThanFailing(@TempDir Path dir) throws IOException {
        writeMinimalConfig(dir);

        CommandOutcome outcome = run(dir);

        assertThat(outcome.exitCode).isEqualTo(0);
        assertThat(outcome.out).contains("No run state found");
    }

    @Test
    void withARealStateFileItPrintsTheStatusTable(@TempDir Path dir) throws IOException {
        writeMinimalConfig(dir);
        StateStore store = new StateStore(dir.resolve(".jtestforge"), Clock.fixed(NOW, ZoneOffset.UTC));
        WorkUnit done = unit("applyFee", UnitStatus.DONE);
        WorkUnit failed = unit("settle", UnitStatus.FAILED_COMPILE).withStatusAndError(
                UnitStatus.FAILED_COMPILE, "cannot find symbol: OrderStatus.PARTIAL");
        store.save(RunState.startNew("run-1", NOW, Phase.GENERATE, dir.toString(), "sha256:cfg", "claude",
                SpringTierState.springDisabled(40), List.of(done, failed)));

        CommandOutcome outcome = run(dir);

        assertThat(outcome.exitCode).isEqualTo(0);
        assertThat(outcome.out).contains("run-1").contains("GENERATE");
        assertThat(outcome.out).contains("DONE").contains("FAILED_COMPILE");
        assertThat(outcome.out).contains("Total: 2 unit(s)");
        assertThat(outcome.out).contains("cannot find symbol: OrderStatus.PARTIAL");
    }

    @Test
    void anInProgressUnitIsCalledOutAsAPossibleInterruptedRun(@TempDir Path dir) throws IOException {
        writeMinimalConfig(dir);
        StateStore store = new StateStore(dir.resolve(".jtestforge"), Clock.fixed(NOW, ZoneOffset.UTC));
        store.save(RunState.startNew("run-1", NOW, Phase.GENERATE, dir.toString(), "sha256:cfg", "claude",
                SpringTierState.springDisabled(40), List.of(unit("applyFee", UnitStatus.IN_PROGRESS))));

        CommandOutcome outcome = run(dir);

        assertThat(outcome.out).contains("WARNING").contains("interrupted");
    }

    @Test
    void aMissingConfigFailsWithConfigurationErrorRatherThanAStackTrace(@TempDir Path dir) {
        CommandOutcome outcome = run(dir);

        assertThat(outcome.exitCode).isEqualTo(1);
    }

    private WorkUnit unit(String method, UnitStatus status) {
        WorkUnitId id = WorkUnitId.of("com.acme.PaymentService", method + "()", Tier.PLAIN_UNIT);
        return WorkUnit.pending(id, "PaymentServiceTest.java", "PaymentService.java", "sha256:src")
                .withStatus(status);
    }

    private void writeMinimalConfig(Path dir) throws IOException {
        Files.writeString(dir.resolve("jtestforge.yaml"), "project:\n  modulePath: .\n", StandardCharsets.UTF_8);
    }

    private CommandOutcome run(Path dir) {
        CommandLine commandLine = new CommandLine(new StatusCommand());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        commandLine.setOut(new PrintWriter(out, true, StandardCharsets.UTF_8));
        int exitCode = commandLine.execute("--module", dir.toString());
        return new CommandOutcome(exitCode, out.toString(StandardCharsets.UTF_8));
    }

    private record CommandOutcome(int exitCode, String out) {
    }
}
