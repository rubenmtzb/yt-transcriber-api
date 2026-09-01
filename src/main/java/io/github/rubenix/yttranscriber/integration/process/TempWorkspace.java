package io.github.rubenix.yttranscriber.integration.process;

import io.github.rubenix.yttranscriber.exception.ProviderUnavailableException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * A scratch directory for one external-tool run, deleted when the run finishes.
 *
 * Both providers shell out to tools that write their output to disk (yt-dlp its subtitle file,
 * whisper-cli its transcript) and each had its own copy of the same create-and-clean-up code.
 * Being {@link AutoCloseable} also means the cleanup rides on try-with-resources instead of a
 * finally block that a future early return could slip past.
 */
public final class TempWorkspace implements AutoCloseable {

    private final Path directory;

    private TempWorkspace(Path directory) {
        this.directory = directory;
    }

    public static TempWorkspace create(String prefix) {
        try {
            return new TempWorkspace(Files.createTempDirectory(prefix));
        } catch (IOException e) {
            throw new ProviderUnavailableException("Could not create a temporary working directory.");
        }
    }

    public Path directory() {
        return directory;
    }

    public Path resolve(String fileName) {
        return directory.resolve(fileName);
    }

    @Override
    public void close() {
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(TempWorkspace::deleteQuietly);
        } catch (IOException ignored) {
            // Best-effort: the OS reclaims its temp directory regardless, and failing to tidy up
            // must never mask whatever the caller was actually doing.
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.delete(path);
        } catch (IOException ignored) {
            // see close()
        }
    }
}
