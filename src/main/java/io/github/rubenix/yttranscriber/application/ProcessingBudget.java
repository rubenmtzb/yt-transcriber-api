package io.github.rubenix.yttranscriber.application;

import io.github.rubenix.yttranscriber.exception.ProcessingTimeoutException;

import java.time.Duration;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * One monotonic deadline for a synchronous pipeline, including all its provider calls.
 * ScopedValue keeps concurrent requests isolated and always removes the binding on exit.
 */
public final class ProcessingBudget {

    private static final ScopedValue<ProcessingBudget> CURRENT = ScopedValue.newInstance();

    private final long started;
    private final long durationNanos;
    private final LongSupplier nanoTime;

    ProcessingBudget(Duration duration, LongSupplier nanoTime) {
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("Processing timeout must be positive.");
        }
        this.nanoTime = nanoTime;
        this.durationNanos = duration.toNanos();
        this.started = nanoTime.getAsLong();
    }

    public static <T> T run(Duration duration, Supplier<T> action) {
        // A nested pipeline must not buy another full budget.
        if (CURRENT.isBound()) {
            return checked(action);
        }
        return new ProcessingBudget(duration, System::nanoTime).run(action);
    }

    <T> T run(Supplier<T> action) {
        return ScopedValue.where(CURRENT, this).call(() -> checked(action));
    }

    private static <T> T checked(Supplier<T> action) {
        check();
        try {
            T result = action.get();
            check();
            return result;
        } catch (RuntimeException e) {
            check();
            throw e;
        }
    }

    public static void check() {
        if (CURRENT.isBound()) {
            CURRENT.get().remaining();
        }
    }

    public static Duration cap(Duration callTimeout) {
        if (!CURRENT.isBound()) {
            return callTimeout;
        }
        Duration remaining = CURRENT.get().remaining();
        return remaining.compareTo(callTimeout) < 0 ? remaining : callTimeout;
    }

    private Duration remaining() {
        long remaining = durationNanos - (nanoTime.getAsLong() - started);
        if (remaining <= 0) {
            throw new ProcessingTimeoutException();
        }
        return Duration.ofNanos(remaining);
    }
}
