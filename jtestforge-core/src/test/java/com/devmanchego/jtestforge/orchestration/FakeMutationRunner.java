package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.model.MutationReport;
import com.devmanchego.jtestforge.mutation.MutationException;
import com.devmanchego.jtestforge.mutation.MutationRequest;
import com.devmanchego.jtestforge.mutation.MutationRunner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** A scripted {@link MutationRunner} for {@link HardenEngine}'s tests. */
final class FakeMutationRunner implements MutationRunner {

    private final Deque<Object> scripted = new ArrayDeque<>();
    private final List<MutationRequest> requests = new ArrayList<>();

    FakeMutationRunner thenReturns(MutationReport report) {
        scripted.add(report);
        return this;
    }

    FakeMutationRunner thenFails(String message) {
        scripted.add(new MutationException(message));
        return this;
    }

    @Override
    public MutationReport run(MutationRequest request) throws MutationException {
        requests.add(request);
        Object next = scripted.poll();
        if (next instanceof MutationException exception) {
            throw exception;
        }
        return next instanceof MutationReport report ? report : new MutationReport(List.of());
    }

    List<MutationRequest> requests() {
        return List.copyOf(requests);
    }
}
