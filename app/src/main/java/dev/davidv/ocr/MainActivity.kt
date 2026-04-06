package dev.davidv.ocr

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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
    val clipboardManager = LocalClipboardManager.current
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
                    onCopyResult = {
                        clipboardManager.setText(AnnotatedString(uiState.recognizedText))
                    },
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
    onCopyResult: () -> Unit,
) {
    val assetState = uiState.assetState
    val selectedScript = assetState.availableScripts.firstOrNull { it.id == uiState.selectedScriptId }
        ?: assetState.availableScripts.firstOrNull()
    var scriptMenuExpanded by remember { mutableStateOf(false) }

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
                    Box(modifier = Modifier.fillMaxWidth()) {
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
                            modifier = Modifier.fillMaxWidth(),
                        )

                        if (uiState.isRunningOcr) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
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
                            onClick = onCopyResult,
                            enabled = uiState.recognizedText.isNotBlank(),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.copy),
                                contentDescription = "Copy",
                            )
                        }
                    }
                    SelectionContainer {
                        Text(
                            text = uiState.recognizedText,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
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
