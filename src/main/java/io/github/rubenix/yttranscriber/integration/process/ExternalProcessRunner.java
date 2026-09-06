package io.github.rubenix.yttranscriber.integration.process;

import io.github.rubenix.yttranscriber.application.ProcessingBudget;
import io.github.rubenix.yttranscriber.exception.ProviderUnavailableException;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs an external command with a bounded timeout, capturing stdout and stderr on separate
 * threads to avoid the classic ProcessBuilder deadlock where an unread stream fills its pipe
 * buffer and blocks the child process. Knows nothing about any specific binary -- shared by every
 * integration that shells out to an external tool (yt-dlp, whisper-cli, ...).
 */
@Component
public class ExternalProcessRunner {

    public record ProcessResult(int exitCode, String stdout, String stderr) {
    }

    public ProcessResult run(List<String> command, Duration timeout) {
        Duration effectiveTimeout = ProcessingBudget.cap(timeout);
        long deadline = System.nanoTime() + effectiveTimeout.toNanos();
        Process process = start(command);

        StreamGobbler stdout = new StreamGobbler(process.getInputStream());
        StreamGobbler stderr = new StreamGobbler(process.getErrorStream());
        Thread stdoutThread = Thread.ofVirtual().start(stdout);
        Thread stderrThread = Thread.ofVirtual().start(stderr);

        try {
            if (!process.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
                    || !joinUntil(stdoutThread, deadline) || !joinUntil(stderrThread, deadline)) {
                ProcessingBudget.check();
                throw new ProviderUnavailableException(
                        "Process timed out after %s: %s".formatted(effectiveTimeout, command.getFirst()));
            }
            ProcessingBudget.check();
            return new ProcessResult(process.exitValue(), stdout.output(), stderr.output());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ProcessingBudget.check();
            throw new ProviderUnavailableException("Interrupted while waiting for process: " + command.getFirst());
        } finally {
            // yt-dlp can be waiting on ffmpeg; stopping only the parent leaves CPU work behind.
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            stdoutThread.interrupt();
            stderrThread.interrupt();
        }
    }

    private Process start(List<String> command) {
        try {
            return new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new ProviderUnavailableException("Could not start process: " + command.getFirst(), e);
        }
    }

    private boolean joinUntil(Thread thread, long deadline) throws InterruptedException {
        if (!thread.isAlive()) {
            return true;
        }
        long remaining = deadline - System.nanoTime();
        return remaining > 0 && thread.join(Duration.ofNanos(remaining));
    }

    private static final class StreamGobbler implements Runnable {

        private final InputStream input;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        StreamGobbler(InputStream input) {
            this.input = input;
        }

        @Override
        public void run() {
            try {
                input.transferTo(buffer);
            } catch (IOException ignored) {
                // the stream closes once the process ends; whatever was captured up to now stands
            }
        }

        String output() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
}
