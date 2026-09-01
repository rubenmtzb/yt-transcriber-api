package io.github.rubenix.yttranscriber.application;

@FunctionalInterface
public interface ProgressListener {

    ProgressListener NOOP = stage -> {
    };

    void onStage(ProcessingStage stage);
}
