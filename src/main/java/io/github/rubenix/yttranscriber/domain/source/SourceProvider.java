package io.github.rubenix.yttranscriber.domain.source;

public interface SourceProvider {

    SourceResolution resolve(SourceRequest request);
}
