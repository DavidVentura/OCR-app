package dev.davidv.ocr

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.davidv.ocr.ui.theme.OCRTheme

class MainActivity : ComponentActivity() {
    private var pendingSharedImageUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingSharedImageUri = extractSharedImageUri(intent)
        enableEdgeToEdge()
        setContent {
            OCRTheme {
                OcrApp(
                    sharedImageUri = pendingSharedImageUri,
                    onSharedImageConsumed = { pendingSharedImageUri = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingSharedImageUri = extractSharedImageUri(intent)
    }

    private fun extractSharedImageUri(intent: Intent?): Uri? {
        if (intent == null) return null
        if (intent.type?.startsWith("image/") != true) return null

        return when (intent.action) {
            Intent.ACTION_SEND -> extractSingleSharedImageUri(intent)
            Intent.ACTION_SEND_MULTIPLE -> extractMultipleSharedImageUris(intent).firstOrNull()
            else -> null
        }
    }

    @Suppress("DEPRECATION")
    private fun extractSingleSharedImageUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
    }

    @Suppress("DEPRECATION")
    private fun extractMultipleSharedImageUris(intent: Intent): List<Uri> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OcrApp(
    sharedImageUri: Uri? = null,
    onSharedImageConsumed: () -> Unit = {},
    viewModel: OcrAppViewModel = viewModel(),
) {
    val uiState = viewModel.uiState
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        viewModel.onImagePicked(uri)
    }

    BackHandler(
        enabled = uiState.screen == RootScreen.ManageFiles && uiState.returnToUseAppOnBack,
    ) {
        viewModel.navigateBackFromManageFiles()
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            viewModel.dismissError()
        }
    }

    LaunchedEffect(sharedImageUri) {
        sharedImageUri?.let { uri ->
            viewModel.onSharedImageReceived(uri)
            onSharedImageConsumed()
        }
    }

    when (uiState.screen) {
        RootScreen.ManageFiles -> {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Manage Files") },
                        actions = {
                            if (uiState.assetState.canRunOcr && !uiState.returnToUseAppOnBack) {
                                TextButton(onClick = viewModel::openUseApp) {
                                    Text("Use app")
                                }
                            }
                        },
                    )
                },
                modifier = Modifier.fillMaxSize(),
            ) { innerPadding ->
                ManageFilesScreen(
                    uiState = uiState,
                    modifier = Modifier.padding(innerPadding),
                    onDownloadBundle = viewModel::downloadBundle,
                    onDeleteBundle = viewModel::deleteBundle,
                )
            }
        }

        RootScreen.UseApp -> {
            Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                UseAppScreen(
                    uiState = uiState,
                    modifier = Modifier.padding(innerPadding),
                    onSelectScript = viewModel::onScriptSelected,
                    onPickImage = { imagePicker.launch("image/*") },
                    onOpenSettings = { viewModel.openManageFiles(fromSettings = true) },
                )
            }
        }
    }
}

@Composable
private fun ManageFilesScreen(
    uiState: OcrUiState,
    modifier: Modifier = Modifier,
    onDownloadBundle: (String) -> Unit,
    onDeleteBundle: (String) -> Unit,
) {
    val installedBundles = uiState.assetState.downloadBundles.filter { it.isInstalled }
    val availableBundles = uiState.assetState.downloadBundles.filterNot { it.isInstalled }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (!uiState.assetState.canRunOcr) {
            Text(
                text = "Download model files to get started",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (installedBundles.isNotEmpty()) {
            BundleSection(
                title = "Installed",
                bundles = installedBundles,
                onDownloadBundle = onDownloadBundle,
                onDeleteBundle = onDeleteBundle,
            )
        }

        if (availableBundles.isNotEmpty()) {
            BundleSection(
                title = "Available",
                bundles = availableBundles,
                onDownloadBundle = onDownloadBundle,
                onDeleteBundle = onDeleteBundle,
            )
        }
    }
}

@Composable
private fun BundleSection(
    title: String,
    bundles: List<DownloadBundleUiState>,
    onDownloadBundle: (String) -> Unit,
    onDeleteBundle: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column {
                bundles.forEachIndexed { index, bundle ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    }
                    BundleRow(
                        bundle = bundle,
                        onDownloadBundle = onDownloadBundle,
                        onDeleteBundle = onDeleteBundle,
                    )
                }
            }
        }
    }
}

@Composable
private fun BundleRow(
    bundle: DownloadBundleUiState,
    onDownloadBundle: (String) -> Unit,
    onDeleteBundle: (String) -> Unit,
) {
    val detailText = buildString {
        bundle.sizeBytes?.let { append(formatFileSize(it)) }
        if (bundle.sampleText.isNotBlank()) {
            if (isNotEmpty()) append(" • ")
            append(bundle.sampleText)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.padding(end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = bundle.label,
                style = MaterialTheme.typography.titleMedium,
            )
            if (detailText.isNotBlank()) {
                Text(
                    text = detailText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        BundleAction(
            bundle = bundle,
            onDownloadBundle = onDownloadBundle,
            onDeleteBundle = onDeleteBundle,
        )
    }
}

@Composable
private fun BundleAction(
    bundle: DownloadBundleUiState,
    onDownloadBundle: (String) -> Unit,
    onDeleteBundle: (String) -> Unit,
) {
    Box(
        modifier = Modifier.size(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bundle.isDownloading -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.5.dp,
                )
            }

            bundle.isInstalled -> {
                IconButton(onClick = { onDeleteBundle(bundle.id) }) {
                    Icon(
                        painter = painterResource(R.drawable.delete),
                        contentDescription = "Delete ${bundle.label}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> {
                IconButton(onClick = { onDownloadBundle(bundle.id) }) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.add),
                            contentDescription = "Download ${bundle.label}",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UseAppScreen(
    uiState: OcrUiState,
    modifier: Modifier = Modifier,
    onSelectScript: (String) -> Unit,
    onPickImage: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val assetState = uiState.assetState
    val selectedScript = assetState.availableScripts.firstOrNull { it.id == uiState.selectedScriptId }
        ?: assetState.availableScripts.firstOrNull()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val density = LocalDensity.current
    var scriptMenuExpanded by remember { mutableStateOf(false) }
    var isImageViewerOpen by remember(uiState.selectedImageUri, uiState.recognitionResult) {
        mutableStateOf(false)
    }
    var previewImageSize by remember(uiState.selectedImageUri) { mutableStateOf(IntSize.Zero) }
    val selectionTokens = remember(uiState.recognitionResult) {
        uiState.recognitionResult?.let(::buildSelectionTokens).orEmpty()
    }
    var selectedTokenIds by remember(uiState.recognitionResult) {
        mutableStateOf(emptySet<Int>())
    }
    val selectedText = remember(selectionTokens, selectedTokenIds) {
        buildSelectedText(selectionTokens, selectedTokenIds)
    }
    val activeText = selectedText.ifBlank { uiState.recognizedText }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val dropdownWidth = (maxWidth - 112.dp).coerceAtLeast(160.dp)
                    val dropdownEnabled = assetState.availableScripts.isNotEmpty() && !uiState.isRunningOcr
                    val dropdownBorderColor = if (dropdownEnabled) {
                        MaterialTheme.colorScheme.outlineVariant
                    } else {
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                    }
                    val dropdownContainerColor = if (dropdownEnabled) {
                        MaterialTheme.colorScheme.surface
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    }
                    val dropdownContentColor = if (dropdownEnabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(modifier = Modifier.width(dropdownWidth)) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = dropdownEnabled) {
                                        scriptMenuExpanded = true
                                    },
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, dropdownBorderColor),
                                color = dropdownContainerColor,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                ) {
                                    Text(
                                        text = selectedScript?.label ?: "Select script",
                                        modifier = Modifier.padding(end = 28.dp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = dropdownContentColor,
                                    )
                                    Icon(
                                        painter = painterResource(R.drawable.expandmore),
                                        contentDescription = null,
                                        modifier = Modifier.align(Alignment.CenterEnd),
                                        tint = dropdownContentColor,
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = scriptMenuExpanded,
                                onDismissRequest = { scriptMenuExpanded = false },
                            ) {
                                assetState.availableScripts.forEach { script ->
                                    DropdownMenuItem(
                                        text = { Text(script.label) },
                                        onClick = {
                                            scriptMenuExpanded = false
                                            onSelectScript(script.id)
                                        },
                                    )
                                }
                            }
                        }

                        IconButton(onClick = onPickImage, enabled = !uiState.isRunningOcr) {
                            Icon(
                                painter = painterResource(R.drawable.gallery),
                                contentDescription = "Pick image",
                            )
                        }

                        IconButton(onClick = onOpenSettings, enabled = !uiState.isRunningOcr) {
                            Icon(
                                painter = painterResource(R.drawable.settings),
                                contentDescription = "Settings",
                            )
                        }
                    }
                }

                if (uiState.selectedImageUri != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isImageViewerOpen = true },
                    ) {
                        AndroidView(
                            factory = { context ->
                                ImageView(context).apply {
                                    adjustViewBounds = true
                                    scaleType = ImageView.ScaleType.FIT_CENTER
                                }
                            },
                            update = { imageView ->
                                imageView.setImageURI(uiState.selectedImageUri)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onSizeChanged { previewImageSize = it },
                        )

                        if (uiState.isRunningOcr && previewImageSize != IntSize.Zero) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .requiredSize(
                                        width = with(density) { previewImageSize.width.toDp() },
                                        height = with(density) { previewImageSize.height.toDp() },
                                    )
                                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                                ) {
                                    Box(
                                        modifier = Modifier.padding(14.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(28.dp),
                                            strokeWidth = 2.5.dp,
                                        )
                                    }
                                }
                            }
                        }

                    }
                }
            }
        }

        if (uiState.selectedImageUri != null) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = {
                                shareText(context, activeText)
                            },
                            enabled = activeText.isNotBlank(),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.share),
                                contentDescription = "Share",
                            )
                        }
                        IconButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(activeText))
                            },
                            enabled = activeText.isNotBlank(),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.copy),
                                contentDescription = "Copy",
                            )
                        }
                    }
                    SelectionContainer {
                        Text(
                            text = activeText,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }

    val selectedImageUri = uiState.selectedImageUri
    if (isImageViewerOpen && selectedImageUri != null) {
        FullscreenSelectionViewer(
            imageUri = selectedImageUri,
            recognitionResult = uiState.recognitionResult,
            tokens = selectionTokens,
            selectedTokenIds = selectedTokenIds,
            onSelectionChange = { selectedTokenIds = it },
            activeText = activeText,
            onCopy = { clipboardManager.setText(AnnotatedString(activeText)) },
            onShare = { shareText(context, activeText) },
            onDismiss = { isImageViewerOpen = false },
        )
    }
}

@Composable
private fun OcrSelectionOverlay(
    recognitionResult: OcrRecognitionResult,
    tokens: List<SelectionToken>,
    selectedTokenIds: Set<Int>,
    onSelectionChange: (Set<Int>) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (recognitionResult.imageWidth <= 0 || recognitionResult.imageHeight <= 0 || tokens.isEmpty()) {
        return
    }

    val density = LocalDensity.current
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    val selectionRadiusPx = with(density) { 12.dp.toPx() }
    val touchSlopPx = with(density) { 24.dp.toPx() }
    val verticalBubbleInsetPx = with(density) { 1.dp.toPx() }
    var dragAnchorId by remember(tokens) { mutableStateOf<Int?>(null) }
    var dragTargetId by remember(tokens) { mutableStateOf<Int?>(null) }

    Canvas(
        modifier = modifier.pointerInput(tokens, recognitionResult) {
            awaitEachGesture {
                val down = awaitFirstPointerDown(pass = PointerEventPass.Initial)
                down.consume()

                val displaySize = Size(size.width.toFloat(), size.height.toFloat())
                val anchor = findClosestTokenId(
                    point = down.position,
                    displaySize = displaySize,
                    recognitionResult = recognitionResult,
                    tokens = tokens,
                    maxDistancePx = touchSlopPx,
                )
                dragAnchorId = anchor
                dragTargetId = anchor
                onSelectionChange(anchor?.let { tokenRangeSelection(it, it) }.orEmpty())

                while (true) {
                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: break

                    if (!change.pressed) {
                        change.consume()
                        dragAnchorId = null
                        dragTargetId = null
                        break
                    }

                    val activeAnchor = dragAnchorId
                    if (activeAnchor != null) {
                        val target = findClosestTokenId(
                            point = change.position,
                            displaySize = displaySize,
                            recognitionResult = recognitionResult,
                            tokens = tokens,
                            maxDistancePx = touchSlopPx,
                        ) ?: dragTargetId ?: activeAnchor
                        dragTargetId = target
                        onSelectionChange(tokenRangeSelection(activeAnchor, target))
                    }

                    change.consume()
                }
            }
        },
    ) {
        val selectedRects = tokens
            .asSequence()
            .filter { it.id in selectedTokenIds }
            .map {
                mapTokenToDisplayRect(
                    token = it,
                    recognitionResult = recognitionResult,
                    displaySize = size,
                )
            }
            .toList()

        val mergedRects = mergeAdjacentSelectionRects(selectedRects)
        val verticalInsets = computeVerticalBubbleInsets(
            rects = mergedRects,
            targetGap = verticalBubbleInsetPx * 2f,
        )
        mergedRects.forEachIndexed { index, rect ->
            val (topInset, bottomInset) = verticalInsets[index]
            val insetRect = insetSelectionRect(
                rect = rect,
                top = topInset,
                bottom = bottomInset,
            )
            drawRoundRect(
                color = highlightColor,
                topLeft = insetRect.topLeft,
                size = insetRect.size,
                cornerRadius = CornerRadius(selectionRadiusPx, selectionRadiusPx),
            )
        }
    }
}

@Composable
private fun FullscreenSelectionViewer(
    imageUri: Uri,
    recognitionResult: OcrRecognitionResult?,
    tokens: List<SelectionToken>,
    selectedTokenIds: Set<Int>,
    onSelectionChange: (Set<Int>) -> Unit,
    activeText: String,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.94f),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { context ->
                        ImageView(context).apply {
                            scaleType = ImageView.ScaleType.FIT_CENTER
                        }
                    },
                    update = { imageView ->
                        imageView.setImageURI(imageUri)
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                if (recognitionResult != null && tokens.isNotEmpty()) {
                    OcrSelectionOverlay(
                        recognitionResult = recognitionResult,
                        tokens = tokens,
                        selectedTokenIds = selectedTokenIds,
                        onSelectionChange = onSelectionChange,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                OverlayActionButton(
                    onClick = onDismiss,
                    painterRes = R.drawable.cancel,
                    contentDescription = "Close",
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(start = 16.dp, top = 16.dp),
                    enabled = true,
                )

                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(end = 16.dp, top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OverlayActionButton(
                        onClick = onShare,
                        painterRes = R.drawable.share,
                        contentDescription = "Share",
                        enabled = activeText.isNotBlank(),
                    )
                    OverlayActionButton(
                        onClick = onCopy,
                        painterRes = R.drawable.copy,
                        contentDescription = "Copy",
                        enabled = activeText.isNotBlank(),
                    )
                }
            }
        }
    }
}

@Composable
private fun OverlayActionButton(
    onClick: () -> Unit,
    painterRes: Int,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        modifier = modifier.size(52.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxSize(),
        ) {
            Icon(
                painter = painterResource(painterRes),
                contentDescription = contentDescription,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                },
            )
        }
    }
}

private data class SelectionToken(
    val id: Int,
    val blockIndex: Int,
    val tokenIndexInBlock: Int,
    val text: String,
    val separatorBefore: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

private val tokenSegmentRegex = Regex("\\S+|\\s+")

private fun buildSelectionTokens(result: OcrRecognitionResult): List<SelectionToken> {
    if (result.imageWidth <= 0 || result.imageHeight <= 0) {
        return emptyList()
    }

    val tokens = mutableListOf<SelectionToken>()

    result.blocks.forEachIndexed { blockIndex, block ->
        val segments = tokenSegmentRegex.findAll(block.text).map { it.value }.toList()
        if (segments.isEmpty()) {
            return@forEachIndexed
        }

        val totalUnits = segments.sumOf { it.length }.coerceAtLeast(1).toFloat()
        var consumedUnits = 0f
        var tokenIndexInBlock = 0
        var pendingSeparator = ""

        segments.forEach { segment ->
            val segmentWidth = block.width * (segment.length / totalUnits)
            val segmentLeft = block.left + (block.width * (consumedUnits / totalUnits))
            val segmentRight = (segmentLeft + segmentWidth).coerceAtMost(block.left + block.width)

            if (segment.isBlank()) {
                if (tokenIndexInBlock > 0) {
                    pendingSeparator = " "
                }
            } else {
                tokens += SelectionToken(
                    id = -1,
                    blockIndex = block.lineIndex,
                    tokenIndexInBlock = tokenIndexInBlock,
                    text = segment,
                    separatorBefore = pendingSeparator,
                    left = segmentLeft.coerceAtLeast(0f),
                    top = block.top.coerceAtLeast(0f),
                    right = segmentRight.coerceAtMost(result.imageWidth.toFloat()),
                    bottom = (block.top + block.height).coerceAtMost(result.imageHeight.toFloat()),
                )
                pendingSeparator = ""
                tokenIndexInBlock += 1
            }

            consumedUnits += segment.length
        }
    }

    return sortSelectionTokens(tokens).mapIndexed { index, token ->
        token.copy(id = index)
    }
}

private fun sortSelectionTokens(tokens: List<SelectionToken>): List<SelectionToken> {
    if (tokens.isEmpty()) {
        return emptyList()
    }

    data class TokenLine(
        val tokens: MutableList<SelectionToken>,
        var centerY: Float,
        var avgHeight: Float,
    )

    val lines = mutableListOf<TokenLine>()
    tokens.sortedBy { (it.top + it.bottom) * 0.5f }.forEach { token ->
        val tokenCenterY = (token.top + token.bottom) * 0.5f
        val tokenHeight = (token.bottom - token.top).coerceAtLeast(1f)
        val bestLine = lines
            .filter { kotlin.math.abs(it.centerY - tokenCenterY) <= maxOf(it.avgHeight, tokenHeight) * 0.6f }
            .minByOrNull { kotlin.math.abs(it.centerY - tokenCenterY) }

        if (bestLine == null) {
            lines += TokenLine(
                tokens = mutableListOf(token),
                centerY = tokenCenterY,
                avgHeight = tokenHeight,
            )
        } else {
            bestLine.tokens += token
            val count = bestLine.tokens.size.toFloat()
            bestLine.centerY = ((bestLine.centerY * (count - 1f)) + tokenCenterY) / count
            bestLine.avgHeight = ((bestLine.avgHeight * (count - 1f)) + tokenHeight) / count
        }
    }

    return lines
        .sortedBy { it.centerY }
        .flatMap { line -> line.tokens.sortedBy { it.left } }
}

private fun buildSelectedText(tokens: List<SelectionToken>, selectedTokenIds: Set<Int>): String {
    if (tokens.isEmpty() || selectedTokenIds.isEmpty()) {
        return ""
    }

    val selectedTokens = tokens.filter { it.id in selectedTokenIds }.sortedBy { it.id }
    if (selectedTokens.isEmpty()) {
        return ""
    }

    return buildString {
        selectedTokens.forEachIndexed { index, token ->
            if (index > 0) {
                val previous = selectedTokens[index - 1]
                if (token.blockIndex != previous.blockIndex) {
                    append('\n')
                } else {
                    append(token.separatorBefore.ifEmpty { " " })
                }
            }
            append(token.text)
        }
    }
}

private fun tokenRangeSelection(firstId: Int, secondId: Int): Set<Int> {
    val start = minOf(firstId, secondId)
    val end = maxOf(firstId, secondId)
    return (start..end).toSet()
}

private fun findClosestTokenId(
    point: Offset,
    displaySize: Size,
    recognitionResult: OcrRecognitionResult,
    tokens: List<SelectionToken>,
    maxDistancePx: Float,
): Int? {
    var bestId: Int? = null
    var bestDistance = Float.POSITIVE_INFINITY

    tokens.forEach { token ->
        val rect = mapTokenToDisplayRect(token, recognitionResult, displaySize)
        if (expandRect(rect, maxDistancePx * 0.35f).contains(point)) {
            return token.id
        }

        val distance = distanceSquaredToRect(point, rect)
        if (distance < bestDistance) {
            bestDistance = distance
            bestId = token.id
        }
    }

    return if (bestDistance <= maxDistancePx * maxDistancePx) bestId else null
}

private fun mapTokenToDisplayRect(
    token: SelectionToken,
    recognitionResult: OcrRecognitionResult,
    displaySize: Size,
): Rect {
    val imageWidth = recognitionResult.imageWidth.toFloat()
    val imageHeight = recognitionResult.imageHeight.toFloat()
    if (imageWidth <= 0f || imageHeight <= 0f || displaySize.width <= 0f || displaySize.height <= 0f) {
        return Rect.Zero
    }

    val scale = minOf(displaySize.width / imageWidth, displaySize.height / imageHeight)
    val renderedWidth = imageWidth * scale
    val renderedHeight = imageHeight * scale
    val offsetX = (displaySize.width - renderedWidth) * 0.5f
    val offsetY = (displaySize.height - renderedHeight) * 0.5f

    return Rect(
        left = offsetX + token.left * scale,
        top = offsetY + token.top * scale,
        right = offsetX + token.right * scale,
        bottom = offsetY + token.bottom * scale,
    )
}

private fun mergeAdjacentSelectionRects(rects: List<Rect>): List<Rect> {
    if (rects.isEmpty()) {
        return emptyList()
    }

    data class RectLine(
        val rects: MutableList<Rect>,
        var centerY: Float,
        var avgHeight: Float,
    )

    val lines = mutableListOf<RectLine>()
    rects.sortedBy { it.center.y }.forEach { rect ->
        val bestLine = lines
            .filter { kotlin.math.abs(it.centerY - rect.center.y) <= maxOf(it.avgHeight, rect.height) * 0.6f }
            .minByOrNull { kotlin.math.abs(it.centerY - rect.center.y) }

        if (bestLine == null) {
            lines += RectLine(
                rects = mutableListOf(rect),
                centerY = rect.center.y,
                avgHeight = rect.height,
            )
        } else {
            bestLine.rects += rect
            val count = bestLine.rects.size.toFloat()
            bestLine.centerY = ((bestLine.centerY * (count - 1f)) + rect.center.y) / count
            bestLine.avgHeight = ((bestLine.avgHeight * (count - 1f)) + rect.height) / count
        }
    }

    val sortedRects = lines
        .sortedBy { it.centerY }
        .flatMap { line -> line.rects.sortedBy { it.left } }

    val merged = mutableListOf<Rect>()
    sortedRects.forEach { rect ->
        val previous = merged.lastOrNull()
        if (previous == null) {
            merged += rect
            return@forEach
        }

        val sameLine = kotlin.math.abs(previous.center.y - rect.center.y) <=
            minOf(previous.height, rect.height) * 0.6f
        val maxGap = maxOf(previous.height, rect.height) * 0.40f
        val gap = rect.left - previous.right

        if (sameLine && gap >= 0f && gap <= maxGap) {
            merged[merged.lastIndex] = Rect(
                left = minOf(previous.left, rect.left),
                top = minOf(previous.top, rect.top),
                right = maxOf(previous.right, rect.right),
                bottom = maxOf(previous.bottom, rect.bottom),
            )
        } else {
            merged += rect
        }
    }

    return merged
}

private fun insetSelectionRect(
    rect: Rect,
    left: Float = 0f,
    top: Float = 0f,
    right: Float = 0f,
    bottom: Float = 0f,
): Rect {
    val clampedLeft = minOf(left, rect.width * 0.25f)
    val clampedRight = minOf(right, rect.width * 0.25f)
    val clampedTop = minOf(top, rect.height * 0.4f)
    val clampedBottom = minOf(bottom, rect.height * 0.4f)
    val insetLeft = rect.left + clampedLeft
    val insetTop = rect.top + clampedTop
    val insetRight = rect.right - clampedRight
    val insetBottom = rect.bottom - clampedBottom
    return if (insetRight <= insetLeft || insetBottom <= insetTop) rect else Rect(
        insetLeft,
        insetTop,
        insetRight,
        insetBottom,
    )
}

private fun computeVerticalBubbleInsets(rects: List<Rect>, targetGap: Float): List<Pair<Float, Float>> {
    if (rects.isEmpty()) {
        return emptyList()
    }

    val topInsets = MutableList(rects.size) { 0f }
    val bottomInsets = MutableList(rects.size) { 0f }

    for (i in rects.indices) {
        for (j in i + 1 until rects.size) {
            val upper = rects[i]
            val lower = rects[j]
            val horizontalOverlap = minOf(upper.right, lower.right) - maxOf(upper.left, lower.left)
            if (horizontalOverlap <= 0f) {
                continue
            }

            val upperIsAbove = upper.center.y <= lower.center.y
            val topIndex = if (upperIsAbove) i else j
            val bottomIndex = if (upperIsAbove) j else i
            val topRect = rects[topIndex]
            val bottomRect = rects[bottomIndex]
            val currentGap = bottomRect.top - topRect.bottom
            val neededGap = targetGap - currentGap
            if (neededGap <= 0f) {
                continue
            }

            val halfInset = neededGap * 0.5f
            bottomInsets[topIndex] = maxOf(bottomInsets[topIndex], halfInset)
            topInsets[bottomIndex] = maxOf(topInsets[bottomIndex], halfInset)
        }
    }

    return rects.indices.map { index -> topInsets[index] to bottomInsets[index] }
}

private fun distanceSquaredToRect(point: Offset, rect: Rect): Float {
    val dx = when {
        point.x < rect.left -> rect.left - point.x
        point.x > rect.right -> point.x - rect.right
        else -> 0f
    }
    val dy = when {
        point.y < rect.top -> rect.top - point.y
        point.y > rect.bottom -> point.y - rect.bottom
        else -> 0f
    }
    return dx * dx + dy * dy
}

private fun expandRect(rect: Rect, amount: Float): Rect {
    return Rect(
        left = rect.left - amount,
        top = rect.top - amount,
        right = rect.right + amount,
        bottom = rect.bottom + amount,
    )
}

private suspend fun AwaitPointerEventScope.awaitFirstPointerDown(
    pass: PointerEventPass,
): PointerInputChange {
    while (true) {
        val event = awaitPointerEvent(pass = pass)
        event.changes.firstOrNull { it.pressed }?.let { return it }
    }
}

private fun shareText(context: android.content.Context, text: String) {
    if (text.isBlank()) {
        return
    }

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(intent, null)
    if (context !is Activity) {
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}

private fun formatFileSize(sizeBytes: Long): String {
    if (sizeBytes < 1024) {
        return "$sizeBytes B"
    }
    val sizeKb = sizeBytes / 1024.0
    if (sizeKb < 1024) {
        return String.format("%.1f KB", sizeKb)
    }
    val sizeMb = sizeKb / 1024.0
    if (sizeMb < 1024) {
        return String.format("%.1f MB", sizeMb)
    }
    return String.format("%.1f GB", sizeMb / 1024.0)
}

@Preview(showBackground = true)
@Composable
private fun ManageFilesPreview() {
    OCRTheme {
        ManageFilesScreen(
            uiState = OcrUiState(),
            onDownloadBundle = {},
            onDeleteBundle = {},
        )
    }
}
