package dev.hazydreams.hermesceleste

internal object AttachmentLimits {
    const val MAX_COUNT = 10
    const val MAX_ITEM_MEGABYTES = 10
    const val MAX_ITEM_BYTES = MAX_ITEM_MEGABYTES * 1024L * 1024L
    const val MAX_TOTAL_BYTES = 50L * 1024L * 1024L
}
