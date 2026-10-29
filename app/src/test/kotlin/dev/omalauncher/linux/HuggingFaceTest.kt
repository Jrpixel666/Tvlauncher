package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Test

class HuggingFaceTest {
    @Test fun codeWinsOverChatWhenBothTagsArePresent() {
        assertEquals(ModelCategory.Code, categoryOf("text-generation", listOf("code")))
    }

    @Test fun plainTextGenerationFallsBackToChat() {
        assertEquals(ModelCategory.Chat, categoryOf("text-generation", listOf("llama")))
    }

    @Test fun conversationalPipelineTagIsChat() {
        assertEquals(ModelCategory.Chat, categoryOf("conversational", emptyList()))
    }

    @Test fun visionTagsAreVision() {
        assertEquals(ModelCategory.Vision, categoryOf(null, listOf("image-text-to-text")))
    }

    @Test fun whisperIsAudio() {
        assertEquals(ModelCategory.Audio, categoryOf("automatic-speech-recognition", listOf("whisper")))
    }

    @Test fun featureExtractionIsEmbedding() {
        assertEquals(ModelCategory.Embedding, categoryOf("feature-extraction", emptyList()))
    }

    @Test fun nothingRecognizableFallsBackToOther() {
        assertEquals(ModelCategory.Other, categoryOf(null, listOf("unrelated-tag")))
    }
}
