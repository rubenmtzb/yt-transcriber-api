package io.github.rubenix.yttranscriber.domain.transcription;

/**
 * How a transcript's text was produced. The three differ in ways a reader can feel, so the result
 * says which one it was instead of presenting them as interchangeable:
 *
 * <ul>
 *   <li>{@link #MANUAL_CAPTIONS} — written by the uploader. The most trustworthy: real spelling,
 *       real punctuation, names as intended.</li>
 *   <li>{@link #AUTOMATIC_CAPTIONS} — YouTube's own speech recognition. Usually unpunctuated and
 *       prone to mangling names and uncommon words.</li>
 *   <li>{@link #SPEECH_TO_TEXT} — our own Whisper run, used only when the video has no captions
 *       at all. Similar failure modes to automatic captions.</li>
 * </ul>
 */
public enum TranscriptSource {
    MANUAL_CAPTIONS,
    AUTOMATIC_CAPTIONS,
    SPEECH_TO_TEXT
}
