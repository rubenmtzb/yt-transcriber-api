package io.github.rubenix.yttranscriber.integration.process;

import io.github.rubenix.yttranscriber.exception.ProviderUnavailableException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * A throwaway directory handed to an external tool as its output location, deleted when the run
 * finishes. Shared by every integration that shells out to a binary that writes files (yt-dlp's
 * subtitle and audio downloads, whisper-cli's JSON transcript) rather than just printing to
 * stdout. Implements {@link AutoCloseable} so call sites express the lifetime as
 * try-with-resources and cannot forget the cleanup.
 */
public final class TempWorkspace implements AutoCloseable {

    private final Path directory;

    private TempWorkspace(Path directory) {
        this.directory = directory;
    }

    /**
     * @param prefix         name prefix for the created directory, to make stray leftovers
     *                       traceable to the integration that made them
     * @param failureMessage user-facing message for the {@link ProviderUnavailableException}
     *                       raised when the directory cannot be created
     */
    public static TempWorkspace create(String prefix, String failureMessage) {
        try {
            return new TempWorkspace(Files.createTempDirectory(prefix));
        } catch (IOException e) {
            throw new ProviderUnavailableException(failureMessage, e);
        }
    }

    public Path directory() {
        return directory;
    }

    @Override
    public void close() {
        deleteRecursively(directory);
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(TempWorkspace::deleteQuietly);
        } catch (IOException ignored) {
            // best-effort cleanup; the OS will reclaim the temp dir eventually regardless
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.delete(path);
        } catch (IOException ignored) {
            // best-effort cleanup of an ephemeral temp file
        }
    }
}
