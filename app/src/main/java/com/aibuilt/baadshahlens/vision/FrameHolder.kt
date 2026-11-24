package com.aibuilt.baadshahlens.vision

/** Holds the last analysed frame so other screens can re-render the overlay locally. */
object FrameHolder {
    @Volatile
    var frame: VisionPipeline.Frame? = null
}
