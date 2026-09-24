package com.insidejoke.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalLong;
import java.util.PriorityQueue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A scheduler on a {@link ManualClock}: nothing runs by itself. {@link #runDue()} runs, earliest first, every task
 * whose time has come, on the calling thread. Unit tests of the game drive phase timers with it.
 */
public final class ManualScheduler extends AbstractExecutorService implements ScheduledExecutorService {

    private final ManualClock clock;
    private final PriorityQueue<Task<?>> queue =
            new PriorityQueue<>(Comparator.comparingLong((Task<?> t) -> t.at).thenComparingLong(t -> t.seq));
    private long seq;
    private boolean shutdown;

    public ManualScheduler(ManualClock clock) {
        this.clock = clock;
    }

    /** Runs every task due by now, earliest first, including tasks that those schedule for no later than now. */
    public void runDue() {
        while (true) {
            Task<?> next = queue.peek();
            if (next == null || next.at > clock.millis()) {
                return;
            }
            queue.poll();
            if (!next.isCancelled()) {
                next.run();
            }
        }
    }

    /** When the earliest task still waiting is due. */
    public OptionalLong nextAt() {
        queue.removeIf(Task::isCancelled);
        Task<?> next = queue.peek();
        return next == null ? OptionalLong.empty() : OptionalLong.of(next.at);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return add(Executors.callable(command, null), delay, unit);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        return add(callable, delay, unit);
    }

    private <V> Task<V> add(Callable<V> callable, long delay, TimeUnit unit) {
        Task<V> task = new Task<>(callable, clock.millis() + unit.toMillis(Math.max(0, delay)), seq++);
        queue.add(task);
        return task;
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        throw new UnsupportedOperationException("The game schedules one-off timers only");
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        throw new UnsupportedOperationException("The game schedules one-off timers only");
    }

    @Override
    public void execute(Runnable command) {
        schedule(command, 0, TimeUnit.MILLISECONDS);
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

    private final class Task<V> extends FutureTask<V> implements ScheduledFuture<V> {
        private final long at;
        private final long seq;

        private Task(Callable<V> callable, long at, long seq) {
            super(callable);
            this.at = at;
            this.seq = seq;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(at - clock.millis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(getDelay(TimeUnit.MILLISECONDS), other.getDelay(TimeUnit.MILLISECONDS));
        }
    }
}
