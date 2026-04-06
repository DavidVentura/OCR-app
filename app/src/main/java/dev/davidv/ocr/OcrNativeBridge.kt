package dev.davidv.ocr

enum class OcrBackend(val nativeValue: Int) {
    Cpu(0),
    Vulkan(1),
}

object OcrNativeBridge {
    init {
        System.loadLibrary("ocr_bindings")
    }

    fun recognize(
        imageBytes: ByteArray,
        detModelPath: String,
        recModelPath: String,
        charsetPath: String,
        backend: OcrBackend,
    ): String = nativeRecognize(
        imageBytes = imageBytes,
        detModelPath = detModelPath,
        recModelPath = recModelPath,
        charsetPath = charsetPath,
        backend = backend.nativeValue,
    )

    @JvmStatic
    private external fun nativeRecognize(
        imageBytes: ByteArray,
        detModelPath: String,
        recModelPath: String,
        charsetPath: String,
        backend: Int,
    ): String
}
