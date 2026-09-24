package com.insidejoke.support;

import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Polls until a condition holds; background AI and database work completes on virtual threads. */
public final class Await {

    private Await() {}

    public static void until(String what, BooleanSupplier condition) {
        until(what, Duration.ofSeconds(10), condition);
    }

    public static void until(String what, Duration timeout, BooleanSupplier condition) {
        long end = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("Timed out waiting for " + what);
            }
            pause(10);
        }
    }

    public static <T> T value(String what, Supplier<T> supplier) {
        Object[] holder = new Object[1];
        until(what, () -> (holder[0] = supplier.get()) != null);
        @SuppressWarnings("unchecked")
        T result = (T) holder[0];
        return result;
    }

    public static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
