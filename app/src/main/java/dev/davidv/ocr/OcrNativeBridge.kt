package dev.davidv.ocr

import org.json.JSONObject

enum class OcrBackend(val nativeValue: Int) {
    Cpu(0),
    Vulkan(1),
    OpenCl(2),
}

data class OcrTextBlock(
    val lineIndex: Int,
    val text: String,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

data class OcrRecognitionResult(
    val imageWidth: Int,
    val imageHeight: Int,
    val blocks: List<OcrTextBlock>,
) {
    val text: String
        get() = blocks
            .sortedWith(compareBy<OcrTextBlock> { it.lineIndex }.thenBy { it.left })
            .groupBy { it.lineIndex }
            .values
            .joinToString(separator = "\n") { line -> line.joinToString(separator = " ") { it.text } }
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
    ): OcrRecognitionResult {
        val payload = nativeRecognize(
            imageBytes = imageBytes,
            detModelPath = detModelPath,
            recModelPath = recModelPath,
            charsetPath = charsetPath,
            backend = backend.nativeValue,
        )
        return parseRecognitionResult(payload)
    }

    @JvmStatic
    private external fun nativeRecognize(
        imageBytes: ByteArray,
        detModelPath: String,
        recModelPath: String,
        charsetPath: String,
        backend: Int,
    ): String

    private fun parseRecognitionResult(payload: String): OcrRecognitionResult {
        val root = JSONObject(payload)
        val blocksJson = root.optJSONArray("blocks")
        val blocks = buildList {
            if (blocksJson != null) {
                for (index in 0 until blocksJson.length()) {
                    val block = blocksJson.getJSONObject(index)
                    add(
                        OcrTextBlock(
                            lineIndex = block.optInt("lineIndex"),
                            text = block.optString("text"),
                            confidence = block.optDouble("confidence").toFloat(),
                            left = block.optDouble("left").toFloat(),
                            top = block.optDouble("top").toFloat(),
                            width = block.optDouble("width").toFloat(),
                            height = block.optDouble("height").toFloat(),
                        )
                    )
                }
            }
        }

        return OcrRecognitionResult(
            imageWidth = root.optInt("imageWidth"),
            imageHeight = root.optInt("imageHeight"),
            blocks = blocks,
        )
    }
}
