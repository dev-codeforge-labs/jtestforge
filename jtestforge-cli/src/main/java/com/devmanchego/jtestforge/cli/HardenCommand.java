package com.devmanchego.jtestforge.cli;

import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Command(
        name = "harden",
        description = "Pass 2 - mutation-driven test hardening."
)
public final class HardenCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        throw new NotYetImplementedException("harden", 20);
    }
}
