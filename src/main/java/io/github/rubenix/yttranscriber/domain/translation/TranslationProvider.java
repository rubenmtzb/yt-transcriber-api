package io.github.rubenix.yttranscriber.domain.translation;

import java.util.List;

public interface TranslationProvider {

    List<TranslatedSegment> translate(TranslationRequest request);
}
