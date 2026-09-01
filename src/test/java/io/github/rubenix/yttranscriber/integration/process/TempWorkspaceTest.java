package io.github.rubenix.yttranscriber.integration.process;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TempWorkspaceTest {

    @Test
    void handsOutADirectoryThatExists() {
        try (TempWorkspace workspace = TempWorkspace.create("test-", "boom")) {
            assertThat(Files.isDirectory(workspace.directory())).isTrue();
        }
    }

    @Test
    void deletesTheDirectoryAndEverythingWrittenIntoIt() throws Exception {
        Path directory;
        try (TempWorkspace workspace = TempWorkspace.create("test-", "boom")) {
            directory = workspace.directory();
            Files.writeString(workspace.directory().resolve("output.json"), "{}");
            Files.createDirectory(workspace.directory().resolve("nested"));
            Files.writeString(workspace.directory().resolve("nested").resolve("more.txt"), "x");
        }

        assertThat(Files.exists(directory)).isFalse();
    }

    @Test
    void cleansUpEvenWhenTheWorkCameToAnAbruptEnd() {
        TempWorkspace workspace = TempWorkspace.create("test-", "boom");
        Path directory = workspace.directory();

        try (workspace) {
            Files.writeString(workspace.directory().resolve("half-written"), "partial");
            throw new IllegalStateException("boom");
        } catch (Exception ignored) {
            // the point is what happened to the directory, not the exception
        }

        assertThat(Files.exists(directory)).isFalse();
    }

    @Test
    void survivesADirectoryThatIsAlreadyGone() throws Exception {
        TempWorkspace workspace = TempWorkspace.create("test-", "boom");
        Files.delete(workspace.directory());

        // Cleanup is best-effort: it must never throw over the caller's own outcome.
        workspace.close();
    }
}
