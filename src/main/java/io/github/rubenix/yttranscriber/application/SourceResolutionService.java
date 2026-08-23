package io.github.rubenix.yttranscriber.application;

import io.github.rubenix.yttranscriber.domain.source.SourceProvider;
import io.github.rubenix.yttranscriber.domain.source.SourceRequest;
import io.github.rubenix.yttranscriber.domain.source.SourceResolution;
import org.springframework.stereotype.Service;

@Service
public class SourceResolutionService {

    private final SourceProvider sourceProvider;

    public SourceResolutionService(SourceProvider sourceProvider) {
        this.sourceProvider = sourceProvider;
    }

    public SourceResolution resolve(String youtubeUrl) {
        return sourceProvider.resolve(new SourceRequest(youtubeUrl));
    }
}
