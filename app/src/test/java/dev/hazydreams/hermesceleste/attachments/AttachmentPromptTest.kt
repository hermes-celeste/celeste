package dev.hazydreams.hermesceleste.attachments

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentPromptTest {
    @Test
    fun preservesCaptionContentWhenRemovingImageDirectives() {
        val normalized = normalizeImageReferences(
            "Describe this [screenshot]\n@image:/hermes/images/one.png",
        )

        assertEquals("Describe this [screenshot]", normalized.visibleText)
        assertEquals(listOf("/hermes/images/one.png"), normalized.references)
    }

    @Test
    fun removesOnlyStandaloneDirectives() {
        val normalized = normalizeImageReferences(
            "Inline @image:/keep-visible.png\n@image:`/hermes/images/with space.png`\n@image:\n@image:/hermes/images/one.png",
        )

        assertEquals("Inline @image:/keep-visible.png\n@image:", normalized.visibleText)
        assertEquals(
            listOf("/hermes/images/with space.png", "/hermes/images/one.png"),
            normalized.references,
        )
    }
}
