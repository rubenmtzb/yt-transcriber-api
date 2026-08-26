package io.github.rubenix.yttranscriber.integration.process;

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
        Process process = start(command);

        StreamGobbler stdout = new StreamGobbler(process.getInputStream());
        StreamGobbler stderr = new StreamGobbler(process.getErrorStream());
        Thread stdoutThread = Thread.ofVirtual().start(stdout);
        Thread stderrThread = Thread.ofVirtual().start(stderr);

        if (!waitFor(process, timeout, command)) {
            process.destroyForcibly();
            throw new ProviderUnavailableException(
                    "Process timed out after %s: %s".formatted(timeout, command.getFirst()));
        }

        joinQuietly(stdoutThread);
        joinQuietly(stderrThread);

        return new ProcessResult(process.exitValue(), stdout.output(), stderr.output());
    }

    private Process start(List<String> command) {
        try {
            return new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new ProviderUnavailableException("Could not start process: " + command.getFirst());
        }
    }

    private boolean waitFor(Process process, Duration timeout, List<String> command) {
        try {
            return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new ProviderUnavailableException("Interrupted while waiting for process: " + command.getFirst());
        }
    }

    private void joinQuietly(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
