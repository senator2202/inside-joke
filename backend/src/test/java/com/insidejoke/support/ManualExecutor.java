package com.insidejoke.support;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NullMarked;

/**
 * Background work (AI calls, database writes) that runs only when a test says so, on the test's thread. Holding it
 * back is how a unit test makes the AI "late"; {@link #runAll()} is the moment its answer arrives.
 */
@NullMarked
public final class ManualExecutor extends AbstractExecutorService {

    private final Queue<Runnable> queue = new ArrayDeque<>();
    private boolean shutdown;

    /** Runs everything queued, including work queued meanwhile, until nothing is left. */
    public void runAll() {
        Runnable next;
        while ((next = queue.poll()) != null) {
            next.run();
        }
    }

    @Override
    public void execute(Runnable command) {
        queue.add(command);
    }

    @Override
    public void shutdown() {
        shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown = true;
        List<Runnable> left = new ArrayList<>(queue);
        queue.clear();
        return left;
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public boolean isTerminated() {
        return shutdown && queue.isEmpty();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return isTerminated();
    }
}
