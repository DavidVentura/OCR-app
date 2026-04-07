package dev.davidv.ocr

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val FILE_BASE_URL = "https://ocr.davidv.dev/files/1"
private const val DETECTION_MODEL_FILE = "PP-OCRv5_mobile_det_fp16.mnn"
private const val DEFAULT_REC_MODEL_FILE = "PP-OCRv5_mobile_rec_fp16.mnn"
private const val DEFAULT_CHARSET_FILE = "ppocr_keys_v5.txt"
private const val DOWNLOAD_LOG_TAG = "OcrDownload"
private const val OCR_LOG_TAG = "OcrTiming"
private val DEFAULT_OCR_BACKEND = OcrBackend.Cpu

enum class RootScreen {
    ManageFiles,
    UseApp,
}

data class InstalledScript(
    val id: String,
    val label: String,
    val recModelPath: String,
    val charsetPath: String,
)

data class DownloadBundleUiState(
    val id: String,
    val label: String,
    val sampleText: String,
    val sizeBytes: Long?,
    val isInstalled: Boolean,
    val isDownloading: Boolean,
    val isDownloadable: Boolean,
)

data class OcrAssetState(
    val modelsDirectoryPath: String,
    val availableScripts: List<InstalledScript>,
    val downloadBundles: List<DownloadBundleUiState>,
    val generalFilesInstalled: Boolean,
) {
    val canRunOcr: Boolean
        get() = generalFilesInstalled && availableScripts.isNotEmpty()
}

data class OcrUiState(
    val screen: RootScreen = RootScreen.ManageFiles,
    val assetState: OcrAssetState = OcrAssetState(
        modelsDirectoryPath = "",
        availableScripts = emptyList(),
        downloadBundles = emptyList(),
        generalFilesInstalled = false,
    ),
    val selectedScriptId: String? = null,
    val selectedImageUri: Uri? = null,
    val recognitionResult: OcrRecognitionResult? = null,
    val recognizedText: String = "",
    val isRunningOcr: Boolean = false,
    val errorMessage: String? = null,
    val returnToUseAppOnBack: Boolean = false,
)

class OcrAppViewModel(application: Application) : AndroidViewModel(application) {
    private val fileStore = OcrFileStore(application.applicationContext)
    private var bundleSizes: Map<String, Long?> = emptyMap()
    private var downloadingBundleIds: Set<String> = emptySet()

    var uiState by mutableStateOf(OcrUiState())
        private set

    init {
        refreshAssets(initialLoad = true)
        loadBundleSizes()
    }

    fun openManageFiles(fromSettings: Boolean = false) {
        uiState = uiState.copy(
            screen = RootScreen.ManageFiles,
            errorMessage = null,
            returnToUseAppOnBack = fromSettings && uiState.assetState.canRunOcr,
        )
    }

    fun openUseApp() {
        if (uiState.assetState.canRunOcr) {
            uiState = uiState.copy(
                screen = RootScreen.UseApp,
                errorMessage = null,
                returnToUseAppOnBack = false,
            )
        }
    }

    fun navigateBackFromManageFiles() {
        if (uiState.returnToUseAppOnBack && uiState.assetState.canRunOcr) {
            openUseApp()
        }
    }

    fun dismissError() {
        uiState = uiState.copy(errorMessage = null)
    }

    fun onImagePicked(uri: Uri?) {
        uiState = uiState.copy(
            selectedImageUri = uri,
            recognitionResult = null,
            recognizedText = "",
            errorMessage = null,
        )
        if (uri != null && selectedScript() != null) {
            runOcr()
        }
    }

    fun onSharedImageReceived(uri: Uri) {
        uiState = uiState.copy(
            selectedImageUri = uri,
            recognitionResult = null,
            recognizedText = "",
            errorMessage = null,
            returnToUseAppOnBack = false,
            screen = if (uiState.assetState.canRunOcr) RootScreen.UseApp else RootScreen.ManageFiles,
        )
        if (uiState.assetState.canRunOcr && selectedScript() != null) {
            runOcr()
        }
    }

    fun onScriptSelected(scriptId: String) {
        uiState = uiState.copy(
            selectedScriptId = scriptId,
            recognitionResult = null,
            recognizedText = "",
            errorMessage = null,
        )
        if (uiState.selectedImageUri != null) {
            runOcr()
        }
    }

    fun downloadBundle(bundleId: String) {
        val bundle = DOWNLOAD_BUNDLES.firstOrNull { it.id == bundleId } ?: return
        if (downloadingBundleIds.contains(bundleId)) {
            Log.d(DOWNLOAD_LOG_TAG, "Ignoring duplicate download request for ${bundle.label}")
            return
        }

        viewModelScope.launch {
            Log.d(DOWNLOAD_LOG_TAG, "Starting bundle download: ${bundle.label} (${bundle.files.size} files)")
            downloadingBundleIds += bundleId
            refreshAssets()
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    fileStore.downloadBundle(bundle)
                }
            }

            downloadingBundleIds -= bundleId
            refreshAssets()

            result.onSuccess {
                Log.d(DOWNLOAD_LOG_TAG, "Finished bundle download: ${bundle.label}")
                uiState = uiState.copy(errorMessage = null)
            }.onFailure { error ->
                Log.e(DOWNLOAD_LOG_TAG, "Bundle download failed: ${bundle.label}", error)
                uiState = uiState.copy(errorMessage = error.message)
            }
        }
    }

    fun deleteBundle(bundleId: String) {
        val bundle = DOWNLOAD_BUNDLES.firstOrNull { it.id == bundleId } ?: return
        if (downloadingBundleIds.contains(bundleId)) return

        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    fileStore.deleteBundle(bundle)
                }
            }

            refreshAssets()
            result.onSuccess {
                uiState = uiState.copy(errorMessage = null)
            }.onFailure { error ->
                uiState = uiState.copy(errorMessage = error.message)
            }
        }
    }

    fun runOcr() {
        val script = selectedScript() ?: return
        val imageUri = uiState.selectedImageUri ?: return

        viewModelScope.launch {
            uiState = uiState.copy(
                isRunningOcr = true,
                errorMessage = null,
                recognitionResult = null,
                recognizedText = "",
            )
            val totalStartNanos = SystemClock.elapsedRealtimeNanos()
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val imageReadStartNanos = SystemClock.elapsedRealtimeNanos()
                    val imageBytes = getApplication<Application>().contentResolver
                        .openInputStream(imageUri)
                        ?.use { it.readBytes() }
                        ?: error("Unable to open the selected image.")
                    val imageReadMs = nanosToMillis(SystemClock.elapsedRealtimeNanos() - imageReadStartNanos)

                    val nativeStartNanos = SystemClock.elapsedRealtimeNanos()
                    val recognitionResult = OcrNativeBridge.recognize(
                        imageBytes = imageBytes,
                        detModelPath = fileStore.detectionModelPath(),
                        recModelPath = script.recModelPath,
                        charsetPath = script.charsetPath,
                        backend = DEFAULT_OCR_BACKEND,
                    )
                    val nativeOcrMs = nanosToMillis(SystemClock.elapsedRealtimeNanos() - nativeStartNanos)

                    Log.d(
                        OCR_LOG_TAG,
                        "script=${script.label} imageReadMs=$imageReadMs nativeOcrMs=$nativeOcrMs imageBytes=${imageBytes.size}"
                    )

                    recognitionResult
                }
            }
            val totalMs = nanosToMillis(SystemClock.elapsedRealtimeNanos() - totalStartNanos)
            result.onSuccess { recognitionResult ->
                Log.d(
                    OCR_LOG_TAG,
                    "script=${script.label} totalOcrMs=$totalMs outputChars=${recognitionResult.text.length} blocks=${recognitionResult.blocks.size}"
                )
            }.onFailure { error ->
                Log.d(
                    OCR_LOG_TAG,
                    "script=${script.label} totalOcrMs=$totalMs failed=${error.message}"
                )
            }

            uiState = uiState.copy(
                isRunningOcr = false,
                recognitionResult = result.getOrNull(),
                recognizedText = result.getOrNull()?.text.orEmpty(),
                errorMessage = result.exceptionOrNull()?.message,
            )
        }
    }

    private fun refreshAssets(initialLoad: Boolean = false) {
        val assetState = fileStore.scanAssets(bundleSizes, downloadingBundleIds)
        val selectedScript = assetState.availableScripts.find { it.id == uiState.selectedScriptId }
            ?: assetState.availableScripts.firstOrNull()
        val screen = when {
            !assetState.canRunOcr -> RootScreen.ManageFiles
            initialLoad -> RootScreen.UseApp
            else -> uiState.screen
        }
        val returnToUseAppOnBack =
            screen == RootScreen.ManageFiles && uiState.returnToUseAppOnBack && assetState.canRunOcr

        uiState = uiState.copy(
            screen = screen,
            assetState = assetState,
            selectedScriptId = selectedScript?.id,
            returnToUseAppOnBack = returnToUseAppOnBack,
        )
    }

    private fun loadBundleSizes() {
        viewModelScope.launch {
            bundleSizes = withContext(Dispatchers.IO) {
                DOWNLOAD_BUNDLES.associate { bundle ->
                    bundle.id to fileStore.fetchBundleSize(bundle)
                }
            }
            refreshAssets()
        }
    }

    private fun selectedScript(): InstalledScript? {
        return uiState.assetState.availableScripts.firstOrNull { it.id == uiState.selectedScriptId }
            ?: uiState.assetState.availableScripts.firstOrNull()
    }
}

private class OcrFileStore(private val context: Context) {
    private val modelsDirectory = File(context.filesDir, "ocr-models")

    fun scanAssets(
        bundleSizes: Map<String, Long?>,
        downloadingBundleIds: Set<String>,
    ): OcrAssetState {
        modelsDirectory.mkdirs()
        val filesByName = modelsDirectory.listFiles().orEmpty().associateBy { it.name }
        val generalInstalled = GENERAL_FILES.files.all { filesByName[it].isUsableFile() }

        val availableScripts = buildList {
            if (generalInstalled) {
                val defaultRecModel = filesByName[DEFAULT_REC_MODEL_FILE].takeIf { it.isUsableFile() }
                val defaultCharset = filesByName[DEFAULT_CHARSET_FILE].takeIf { it.isUsableFile() }
                if (defaultRecModel != null && defaultCharset != null) {
                    add(
                        InstalledScript(
                            id = DEFAULT_SCRIPT.id,
                            label = DEFAULT_SCRIPT.label,
                            recModelPath = defaultRecModel.absolutePath,
                            charsetPath = defaultCharset.absolutePath,
                        )
                    )
                }

                SCRIPT_BUNDLES.forEach { bundle ->
                    val recModel = filesByName[bundle.files[0]].takeIf { it.isUsableFile() } ?: return@forEach
                    val charset = filesByName[bundle.files[1]].takeIf { it.isUsableFile() } ?: return@forEach
                    add(
                        InstalledScript(
                            id = bundle.id,
                            label = bundle.label,
                            recModelPath = recModel.absolutePath,
                            charsetPath = charset.absolutePath,
                        )
                    )
                }
            }
        }

        val downloadBundles = buildList {
            add(bundleUiState(GENERAL_FILES, filesByName, bundleSizes, downloadingBundleIds))
            add(bundleUiState(DEFAULT_SCRIPT_BUNDLE, filesByName, bundleSizes, downloadingBundleIds))
            SCRIPT_BUNDLES.forEach { bundle ->
                add(bundleUiState(bundle, filesByName, bundleSizes, downloadingBundleIds))
            }
        }

        return OcrAssetState(
            modelsDirectoryPath = modelsDirectory.absolutePath,
            availableScripts = availableScripts,
            downloadBundles = downloadBundles,
            generalFilesInstalled = generalInstalled,
        )
    }

    fun detectionModelPath(): String {
        val detectionFile = File(modelsDirectory, DETECTION_MODEL_FILE)
        require(detectionFile.length() > 0L) { "Detection model is missing or empty." }
        return detectionFile.absolutePath
    }

    fun downloadBundle(bundle: DownloadBundleDefinition) {
        modelsDirectory.mkdirs()
        for (fileName in bundle.files) {
            val destination = File(modelsDirectory, fileName)
            if (destination.length() > 0L) {
                Log.d(DOWNLOAD_LOG_TAG, "Skipping existing file ${destination.name} (${destination.length()} bytes)")
                continue
            }
            downloadFile(fileName, destination)
        }
    }

    fun deleteBundle(bundle: DownloadBundleDefinition) {
        modelsDirectory.mkdirs()
        bundle.files.forEach { fileName ->
            File(modelsDirectory, fileName).delete()
        }
    }

    fun fetchBundleSize(bundle: DownloadBundleDefinition): Long? {
        val sizes = bundle.files.map { fetchRemoteFileSize(it) }
        return if (sizes.all { it != null }) sizes.filterNotNull().sum() else null
    }

    private fun bundleUiState(
        bundle: DownloadBundleDefinition,
        filesByName: Map<String, File>,
        bundleSizes: Map<String, Long?>,
        downloadingBundleIds: Set<String>,
    ): DownloadBundleUiState {
        val ownFilesInstalled = bundle.files.all { filesByName[it].isUsableFile() }
        return DownloadBundleUiState(
            id = bundle.id,
            label = bundle.label,
            sampleText = bundle.sampleText,
            sizeBytes = bundleSizes[bundle.id],
            isInstalled = ownFilesInstalled,
            isDownloading = downloadingBundleIds.contains(bundle.id),
            isDownloadable = true,
        )
    }

    private fun downloadFile(fileName: String, destination: File) {
        val tempFile = File(destination.parentFile, "${destination.name}.part")
        tempFile.delete()
        destination.delete()

        val url = "$FILE_BASE_URL/$fileName"
        Log.d(DOWNLOAD_LOG_TAG, "Downloading file $fileName from $url")

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept-Encoding", "identity")
        }

        try {
            connection.connect()
            Log.d(DOWNLOAD_LOG_TAG, "Response for $fileName: ${connection.responseCode} ${connection.responseMessage}")
            if (connection.responseCode !in 200..299) {
                error("Failed to download $fileName: HTTP ${connection.responseCode}")
            }

            val bytesCopied = connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            Log.d(
                DOWNLOAD_LOG_TAG,
                "Downloaded temp file for $fileName: copied=$bytesCopied bytes, tempSize=${tempFile.length()} bytes"
            )

            if (bytesCopied <= 0L || tempFile.length() <= 0L) {
                error("Downloaded file is empty: $fileName")
            }

            tempFile.copyTo(destination, overwrite = true)
            Log.d(DOWNLOAD_LOG_TAG, "Stored file ${destination.absolutePath} (${destination.length()} bytes)")
        } finally {
            tempFile.delete()
            connection.disconnect()
        }
    }

    private fun fetchRemoteFileSize(fileName: String): Long? {
        val connection = (URL("$FILE_BASE_URL/$fileName").openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept-Encoding", "identity")
        }

        return runCatching {
            connection.connect()
            connection.contentLengthLong.takeIf { it > 0L }
        }.getOrNull().also {
            connection.disconnect()
        }
    }
}

data class DownloadBundleDefinition(
    val id: String,
    val label: String,
    val sampleText: String,
    val files: List<String>,
)

private data class ScriptDefinition(
    val id: String,
    val label: String,
)

private fun File?.isUsableFile(): Boolean = this != null && this.isFile && this.length() > 0L

private fun nanosToMillis(durationNanos: Long): String = String.format("%.1f", durationNanos / 1_000_000.0)

private val DEFAULT_SCRIPT = ScriptDefinition("multilingual", "Chinese + Japanese")

private val GENERAL_FILES = DownloadBundleDefinition(
    id = "general",
    label = "General files",
    sampleText = "",
    files = listOf(DETECTION_MODEL_FILE),
)

private val DEFAULT_SCRIPT_BUNDLE = DownloadBundleDefinition(
    id = DEFAULT_SCRIPT.id,
    label = DEFAULT_SCRIPT.label,
    sampleText = "中文 日本語",
    files = listOf(DEFAULT_REC_MODEL_FILE, DEFAULT_CHARSET_FILE),
)

private val SCRIPT_BUNDLES = listOf(
    DownloadBundleDefinition("arabic", "Arabic", "العربية", listOf("arabic_PP-OCRv5_mobile_rec_infer.mnn", "ppocr_keys_arabic.txt")),
    DownloadBundleDefinition("cyrillic", "Cyrillic", "Кириллица", listOf("cyrillic_PP-OCRv5_mobile_rec_infer.mnn", "ppocr_keys_cyrillic.txt")),
    DownloadBundleDefinition("devanagari", "Devanagari", "देवनागरी", listOf("devanagari_PP-OCRv5_mobile_rec_infer.mnn", "ppocr_keys_devanagari.txt")),
    DownloadBundleDefinition("el", "Greek", "Ελληνικά", listOf("el_PP-OCRv5_mobile_rec_infer.mnn", "ppocr_keys_el.txt")),
    DownloadBundleDefinition("en", "English", "English", listOf("en_PP-OCRv5_mobile_rec_infer.mnn", "ppocr_keys_en.txt")),
    DownloadBundleDefinition("eslav", "East Slavic", "Українська", listOf("eslav_PP-OCRv5_mobile_rec_infer.mnn", "ppocr_keys_eslav.txt")),
    DownloadBundleDefinition("korean", "Korean", "한글", listOf("korean_PP-OCRv5_mobile_rec_infer.mnn", "ppocr_keys_korean.txt")),
    DownloadBundleDefinition("latin", "Latin", "Français, Español, ...", listOf("latin_PP-OCRv5_mobile_rec_infer.mnn", "ppocr_keys_latin.txt")),
    DownloadBundleDefinition("ta", "Tamil", "தமிழ்", listOf("ta_PP-OCRv5_mobile_rec_infer.mnn", "ppocr_keys_ta.txt")),
    DownloadBundleDefinition("te", "Telugu", "తెలుగు", listOf("te_PP-OCRv5_mobile_rec_infer.mnn", "ppocr_keys_te.txt")),
    DownloadBundleDefinition("th", "Thai", "ไทย", listOf("th_PP-OCRv5_mobile_rec_infer.mnn", "ppocr_keys_th.txt")),
)

private val DOWNLOAD_BUNDLES = listOf(GENERAL_FILES, DEFAULT_SCRIPT_BUNDLE) + SCRIPT_BUNDLES
