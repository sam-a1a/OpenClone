package com.sam.openclone.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sam.openclone.R
import com.sam.openclone.clone.AppRepository
import com.sam.openclone.clone.CloneCoordinator
import com.sam.openclone.clone.CloneService
import com.sam.openclone.clone.InstalledApp
import androidx.compose.runtime.LaunchedEffect

private val ICON_SIZE = 44.dp

@Composable
internal fun AppListScreen() {
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }
    val cloneState by CloneCoordinator.state.collectAsStateWithLifecycle()

    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var query by remember { mutableStateOf("") }
    var reloadToken by remember { mutableIntStateOf(0) }
    var needsInstallPermission by remember { mutableStateOf(false) }

    // Returning from the settings screen: nothing to read back, the permission
    // is re-checked on the next tap.
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    LaunchedEffect(reloadToken) {
        apps = AppRepository.load(context, refresh = reloadToken > 0)
    }

    // A finished clone both reports itself and, on success, adds a row.
    LaunchedEffect(Unit) {
        CloneCoordinator.state.collect { state ->
            val result = state.result ?: return@collect
            CloneCoordinator.consumeResult()
            if (result.success) reloadToken++
            snackbarHost.showSnackbar(result.message)
        }
    }

    // Reading `query` inside derivedStateOf keeps the filter off the
    // recomposition path for everything that is not the list itself.
    val filtered by remember(apps) {
        derivedStateOf {
            val all = apps.orEmpty()
            val needle = query.trim().lowercase()
            if (needle.isEmpty()) all
            else all.filter {
                it.searchLabel.contains(needle) || it.searchPackage.contains(needle)
            }
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            SearchField(
                query = query,
                onQueryChange = { query = it },
                subtitle = apps?.let { stringResource(R.string.app_count, it.size) },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                apps == null -> Centered { Text(stringResource(R.string.loading_apps)) }

                filtered.isEmpty() -> Centered {
                    Text(
                        stringResource(R.string.no_apps, query),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    // Keyed so a reload after a clone reuses rows instead of
                    // rebuilding and re-fetching every icon.
                    items(filtered, key = { it.packageName }) { app ->
                        AppRow(
                            app = app,
                            busy = cloneState.busyPackage == app.packageName,
                            progress = cloneState.progress,
                            awaiting = cloneState.awaitingConfirmation,
                            enabled = cloneState.busyPackage == null,
                            onClick = {
                                // Checked before the work, not after: without
                                // this the user rewrites and signs the whole
                                // APK only to be sent to Settings at the end.
                                if (context.packageManager.canRequestPackageInstalls()) {
                                    CloneService.start(context, app)
                                } else {
                                    needsInstallPermission = true
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (needsInstallPermission) {
        AlertDialog(
            onDismissRequest = { needsInstallPermission = false },
            title = { Text(stringResource(R.string.permission_title)) },
            text = { Text(stringResource(R.string.permission_body)) },
            confirmButton = {
                TextButton(onClick = {
                    needsInstallPermission = false
                    settingsLauncher.launch(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            "package:${context.packageName}".toUri(),
                        )
                    )
                }) { Text(stringResource(R.string.permission_open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { needsInstallPermission = false }) {
                    Text(stringResource(R.string.permission_not_now))
                }
            },
        )
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, subtitle: String?) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        // The bar is drawn edge to edge, so it owns the status bar inset.
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (subtitle != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
            TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
            Spacer(Modifier.size(8.dp))
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    busy: Boolean,
    progress: Float,
    awaiting: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled && !busy, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(app.packageName)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (app.isClone) {
                        Spacer(Modifier.width(6.dp))
                        CloneBadge()
                    }
                }
                Text(
                    text = if (busy && awaiting) {
                        stringResource(R.string.waiting_for_install)
                    } else if (busy) {
                        stringResource(R.string.cloning)
                    } else {
                        app.packageName
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            if (busy) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_clone),
                    contentDescription = stringResource(R.string.clone_action),
                    tint = if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        if (busy && !awaiting) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { ICON_SIZE.roundToPx() }
    // Seeded from cache so scrolling back to a row never flickers.
    var icon by remember(packageName) {
        mutableStateOf(com.sam.openclone.clone.IconLoader.cached(packageName))
    }
    LaunchedEffect(packageName) {
        if (icon == null) icon = com.sam.openclone.clone.IconLoader.load(context, packageName, sizePx)
    }

    val bitmap = icon
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.size(ICON_SIZE),
        )
    } else {
        Box(
            Modifier
                .size(ICON_SIZE)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}

@Composable
private fun CloneBadge() {
    Text(
        text = stringResource(R.string.clone_badge),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            content()
        }
    }
}
