package net.rpcs3.ui.games

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import java.io.File
import kotlin.concurrent.thread
import kotlin.text.substringAfterLast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rpcs3.EmulatorState
import net.rpcs3.FirmwareRepository
import net.rpcs3.Game
import net.rpcs3.GameFlag
import net.rpcs3.GameInfo
import net.rpcs3.GameProgress
import net.rpcs3.GameProgressType
import net.rpcs3.GameRepository
import net.rpcs3.ProgressRepository
import net.rpcs3.R
import net.rpcs3.RPCS3
import net.rpcs3.RPCS3Activity
import net.rpcs3.dialogs.AlertDialogQueue
import net.rpcs3.gameTitleId
import net.rpcs3.ui.components.chasingBorder
import net.rpcs3.ui.library.GameSort
import net.rpcs3.ui.theme.Rpcs
import net.rpcs3.ui.theme.SettingsStyle
import net.rpcs3.utils.FileUtil
import net.rpcs3.utils.FolderGames
import net.rpcs3.utils.GameDetails
import net.rpcs3.utils.GameDetailsReader
import net.rpcs3.utils.RecommendedConfigs

internal fun isInternalGame(path: String): Boolean {
    val root = RPCS3.rootDirectory
    return root.isNotEmpty() && File(path).absolutePath.startsWith(File(root).absolutePath)
}

internal fun isManagedGame(path: String) = isInternalGame(path) && !FolderGames.isDirectBoot(path)

internal data class UninstallPrompt(val title: String, val message: String, val action: String)

internal fun uninstallPrompt(context: Context, path: String, name: String): UninstallPrompt {
    val directBoot = FolderGames.isDirectBoot(path)
    val internal = isInternalGame(path) && !directBoot

    return UninstallPrompt(
        title = context.getString(
            if (internal) R.string.games_uninstall_title else R.string.games_remove_title, name
        ),
        message = when {
            internal -> context.getString(R.string.games_uninstall_message)
            directBoot -> context.getString(R.string.games_remove_direct_boot_message, path)
            else -> context.getString(R.string.games_remove_message, path)
        },
        action = context.getString(
            if (internal) R.string.action_uninstall else R.string.action_remove
        )
    )
}

private fun withAlpha(color: Color, alpha: Float): Color {
    return Color(
        red = color.red, green = color.green, blue = color.blue, alpha = alpha
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameItem(
    game: Game,
    navigateToGameSettings: (titleId: String) -> Unit = {},
    navigateToGamePatches: (titleId: String) -> Unit = {},
    navigateToGameUpdates: (titleId: String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val deleteScope = rememberCoroutineScope()
    val context = LocalContext.current

    val menuExpanded = remember { mutableStateOf(false) }
    val iconExists = remember { mutableStateOf(false) }
    var emulatorState by remember { RPCS3.state }
    val emulatorActiveGame by remember { RPCS3.activeGame }

    var licenseDialogVisible by remember { mutableStateOf(false) }
    var bootAfterLicenseInstall by remember { mutableStateOf(false) }

    fun bootGame() {
        if (FirmwareRepository.version.value == null) {
            AlertDialogQueue.showDialog(
                title = context.getString(R.string.games_firmware_missing_title),
                message = context.getString(R.string.games_firmware_missing_message)
            )
            return
        }

        if (FirmwareRepository.progressChannel.value != null) {
            AlertDialogQueue.showDialog(
                title = context.getString(R.string.games_firmware_missing_title),
                message = context.getString(R.string.games_firmware_installing_message)
            )
            return
        }

        if (game.info.path == "$" || game.findProgress(
                arrayOf(GameProgressType.Install, GameProgressType.Remove)
            ) != null
        ) {
            return
        }

        if (game.findProgress(GameProgressType.Compile) != null) {
            AlertDialogQueue.showDialog(
                title = context.getString(R.string.games_compiling_title),
                message = context.getString(R.string.games_compiling_message)
            )
            return
        }

        GameRepository.onBoot(game)
        val emulatorWindow = Intent(context, RPCS3Activity::class.java)
        emulatorWindow.putExtra("path", game.info.path)
        emulatorWindow.putExtra("bootPath", FolderGames.bootPath(game.info.path))
        context.startActivity(emulatorWindow)
    }

    val installKeyLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri: Uri? ->
            val bootWhenInstalled = bootAfterLicenseInstall
            bootAfterLicenseInstall = false

            if (uri != null) {
                val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r")
                val fd = descriptor?.parcelFileDescriptor?.fd

                if (fd != null) {
                    val installProgress = ProgressRepository.create(
                        context,
                        context.getString(R.string.progress_license_installation)
                    )

                    game.addProgress(GameProgress(installProgress, GameProgressType.Compile))

                    if (bootWhenInstalled) {
                        ProgressRepository.addListener(installProgress) { entry ->
                            if (entry.isComplete()) {
                                bootGame()
                            }
                        }
                    }

                    thread(isDaemon = true) {
                        if (!RPCS3.instance.installKey(fd, installProgress, game.info.path)) {
                            try {
                                ProgressRepository.onProgressEvent(installProgress, -1, 0)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }

                        try {
                            descriptor.close()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                } else {
                    try {
                        descriptor?.close()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

    val detailVisible = remember { mutableStateOf(false) }
    var detailsState by remember(game.info.path) { mutableStateOf<GameDetails?>(null) }

    LaunchedEffect(detailVisible.value, game.info.path) {
        if (detailVisible.value && detailsState == null) {
            detailsState = withContext(Dispatchers.IO) {
                GameDetailsReader.read(game.info.path)
            }
        }
    }
    val tileInteraction = remember { MutableInteractionSource() }
    val tileFocused by tileInteraction.collectIsFocusedAsState()

    fun uninstallGame() {
        val deleteProgress = ProgressRepository.create(
            context,
            context.getString(R.string.progress_deleting_game)
        )
        game.addProgress(GameProgress(deleteProgress, GameProgressType.Compile))
        ProgressRepository.onProgressEvent(deleteProgress, 0, 0L)

        val path = File(game.info.path)
        val directBoot = FolderGames.isDirectBoot(game.info.path)
        val internal = isInternalGame(game.info.path) && !directBoot
        val titleId = gameTitleId(game.info.path)

        deleteScope.launch {
            val serial = withContext(Dispatchers.IO) {
                GameDetailsReader.read(game.info.path).titleId
            }.ifEmpty { titleId }

            withContext(Dispatchers.IO) {
                if (directBoot) {
                    FolderGames.removeLinks(game.info.path)
                } else {
                    if (internal && path.exists()) {
                        path.deleteRecursively()
                    }

                    runCatching {
                        parseUpdates(
                            RPCS3.instance.installedUpdates(serial, game.info.path)
                        ).forEach { entry ->
                            RPCS3.instance.uninstallUpdate(entry.path)
                        }
                    }

                    if (!internal) {
                        runCatching {
                            File(RPCS3.rootDirectory + "config/Icons/iso/$serial.PNG").delete()
                        }
                    }
                }
            }

            FileUtil.deleteCache(context, serial) { success ->
                if (!success) {
                    AlertDialogQueue.showDialog(
                        title = context.getString(R.string.games_error_unexpected_title),
                        message = context.getString(R.string.games_error_delete_cache),
                        confirmText = context.getString(R.string.action_close),
                        dismissText = ""
                    )
                }
                ProgressRepository.onProgressEvent(deleteProgress, 100, 100)
                GameRepository.remove(game)
            }
        }
    }

    fun openGameSettings() {
        deleteScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                GameDetailsReader.read(game.info.path).titleId
            }
            navigateToGameSettings(resolved.ifEmpty { gameTitleId(game.info.path) })
        }
    }

    fun openGamePatches() {
        deleteScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                GameDetailsReader.read(game.info.path).titleId
            }
            navigateToGamePatches(resolved.ifEmpty { gameTitleId(game.info.path) })
        }
    }

    fun confirmUninstall() {
        val name = game.info.name.value ?: gameTitleId(game.info.path)
        val prompt = uninstallPrompt(context, game.info.path, name)

        AlertDialogQueue.showDialog(
            title = prompt.title,
            message = prompt.message,
            confirmText = prompt.action,
            dismissText = context.getString(R.string.action_cancel),
            onConfirm = { uninstallGame() }
        )
    }

    fun launchGame() {
        if (game.hasFlag(GameFlag.Locked)) {
            licenseDialogVisible = true
            return
        }

        bootGame()
    }

    Column(modifier = modifier) {
        DropdownMenu(
            expanded = menuExpanded.value, onDismissRequest = { menuExpanded.value = false }) {
            if (game.progressList.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_settings)) },
                    leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                    onClick = {
                        menuExpanded.value = false
                        openGameSettings()
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (isManagedGame(game.info.path)) {
                                    R.string.action_uninstall
                                } else {
                                    R.string.action_remove
                                }
                            )
                        )
                    },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                    onClick = {
                        menuExpanded.value = false
                        confirmUninstall()
                    }
                )
            }
        }

        val tileShape = RoundedCornerShape(16.dp)
        val title = game.info.name.value ?: gameTitleId(game.info.path)
        val isVsh = game.info.name.value == "VSH"
        val isResumable =
            emulatorState == EmulatorState.Paused && emulatorActiveGame == game.info.path

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = if (tileFocused) 18.dp else 8.dp,
                    shape = tileShape,
                    ambientColor = Rpcs.Accent,
                    spotColor = Rpcs.Accent
                )
                .clip(tileShape)
                .background(Brush.verticalGradient(listOf(Rpcs.SurfaceRaised, Rpcs.Surface)))
                .then(
                    if (tileFocused) {
                        Modifier
                    } else {
                        Modifier.border(1.dp, Rpcs.Outline, tileShape)
                    }
                )
                .chasingBorder(isFocused = tileFocused, cornerRadius = 16.dp)
                .focusable(interactionSource = tileInteraction)
                .combinedClickable(
                    interactionSource = tileInteraction,
                    indication = null,
                    onClick = { detailVisible.value = true },
                    onLongClick = {
                        if (!isVsh) {
                            menuExpanded.value = true
                        }
                    }
                )
        ) {
            if (game.info.iconPath.value != null && !iconExists.value) {
                if (game.progressList.isNotEmpty()) {
                    val progressId = ProgressRepository.getItem(game.progressList.first().id)
                    if (progressId != null) {
                        val progressValue = progressId.value.value
                        val progressMax = progressId.value.max

                        iconExists.value =
                            (progressMax.longValue != 0L && progressValue.longValue == progressMax.longValue) || File(
                                game.info.iconPath.value!!
                            ).exists()
                    }
                } else {
                    iconExists.value = File(game.info.iconPath.value!!).exists()
                }
            }

            // PS3 ICON0.PNG is 320x176, so the cover keeps that shape instead of being cropped.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(320f / 176f)
            ) {
                if (game.info.iconPath.value != null && iconExists.value) {
                    AsyncImage(
                        model = game.info.iconPath.value,
                        contentScale = if (isVsh) ContentScale.Fit else ContentScale.Crop,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    GameCoverPlaceholder(title)
                }

                // Melt the cover into the card body.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Rpcs.SurfaceRaised.copy(alpha = 0.92f))
                            )
                        )
                )

                if (game.progressList.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Rpcs.Background.copy(alpha = 0.6f))
                    )

                    val progressChannel = game.progressList.first().id
                    val progress = ProgressRepository.getItem(progressChannel)
                    val progressValue = progress?.value?.value
                    val maxValue = progress?.value?.max

                    if (progressValue != null && maxValue != null) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            if (maxValue.longValue != 0L) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(48.dp),
                                    color = Rpcs.AccentBright,
                                    trackColor = Rpcs.Outline,
                                    progress = {
                                        progressValue.longValue.toFloat() / maxValue.longValue.toFloat()
                                    },
                                )
                            } else {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(48.dp),
                                    color = Rpcs.AccentBright,
                                    trackColor = Rpcs.Outline,
                                )
                            }
                        }
                    }
                } else if (isResumable) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .background(Rpcs.Accent, RoundedCornerShape(50))
                            .padding(start = 6.dp, end = 10.dp, top = 3.dp, bottom = 3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = Rpcs.TextPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = stringResource(R.string.games_resume_badge),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Rpcs.TextPrimary
                        )
                    }
                }

                if (game.hasFlag(GameFlag.Locked) || game.hasFlag(GameFlag.Trial)) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(30.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Rpcs.Background.copy(alpha = 0.75f))
                            .combinedClickable(onClick = { installKeyLauncher.launch("*/*") })
                    ) {
                        Icon(
                            Icons.Outlined.Lock,
                            contentDescription = stringResource(R.string.games_locked),
                            tint = Rpcs.Warning,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)) {
                Text(
                    text = title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (tileFocused) {
                                Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                            } else {
                                Modifier
                            }
                        ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Rpcs.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = gameTitleId(game.info.path),
                    style = MaterialTheme.typography.labelSmall,
                    color = Rpcs.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
    if (detailVisible.value) {
        val detailPrompt = uninstallPrompt(
            context, game.info.path, game.info.name.value ?: gameTitleId(game.info.path)
        )

        GameDetailDialog(
            title = game.info.name.value ?: gameTitleId(game.info.path),
            subtitle = detailsState?.titleId?.ifEmpty { null } ?: gameTitleId(game.info.path),
            version = detailsState?.versionLabel(context).orEmpty(),
            iconPath = if (iconExists.value) game.info.iconPath.value else null,
            gamePath = game.info.path,
            onPlay = {
                detailVisible.value = false
                launchGame()
            },
            onSettings = {
                detailVisible.value = false
                openGameSettings()
            },
            onInstallUpdate = {
                detailVisible.value = false
                navigateToGameUpdates(game.info.path)
            },
            onPatches = {
                detailVisible.value = false
                openGamePatches()
            },
            uninstallLabel = detailPrompt.action,
            uninstallTitle = detailPrompt.title,
            uninstallMessage = detailPrompt.message,
            onUninstall = if (game.info.name.value == "VSH") {
                null
            } else {
                {
                    detailVisible.value = false
                    uninstallGame()
                }
            },
            onDismissRequest = { detailVisible.value = false }
        )
    }

    if (licenseDialogVisible) {
        MissingLicenseDialog(
            onSelect = {
                licenseDialogVisible = false
                bootAfterLicenseInstall = true
                installKeyLauncher.launch("*/*")
            },
            onCancel = {
                licenseDialogVisible = false
                bootAfterLicenseInstall = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesScreen(
    navigateToGameSettings: (titleId: String) -> Unit = {},
    navigateToGamePatches: (titleId: String) -> Unit = {},
    navigateToGameUpdates: (titleId: String) -> Unit = {},
    searchQuery: String = "",
    sort: GameSort = GameSort.NameAscending
) {
    val games = remember { GameRepository.list() }
    val isRefreshing = remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val state = rememberPullToRefreshState()
    val context = LocalContext.current
    val seeded = remember { mutableSetOf<String>() }

    val gameInProgress = games.find { it.progressList.isNotEmpty() }

    LaunchedEffect(games.toList()) {
        withContext(Dispatchers.IO) {
            games.map { it.info.path }
                .filter { it != "$" && seeded.add(it) }
                .forEach { path ->
                    RecommendedConfigs.seedIfNeeded(context, GameDetailsReader.read(path).titleId)
                }
        }
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing.value,
        state = state,
        onRefresh = {
            if (gameInProgress == null) {
                isRefreshing.value = true
                thread {
                    val roots = listOf(
                        RPCS3.rootDirectory + "/config/dev_hdd0/game",
                        RPCS3.rootDirectory + "/config/games"
                    )

                    GameRepository.beginScan()
                    try {
                        roots.forEach { RPCS3.instance.collectGameInfo(it, -1) }
                        FolderGames.restore()
                    } finally {
                        GameRepository.endScan(roots)
                    }

                    Thread.sleep(300)
                    isRefreshing.value = false
                }
            }
        },
        indicator = {
            if (gameInProgress == null) {
                PullToRefreshDefaults.Indicator(
                    state = state,
                    isRefreshing = isRefreshing.value,
                    modifier = Modifier.align(Alignment.TopCenter),
                    color = MaterialTheme.colorScheme.onPrimary,
                    containerColor = MaterialTheme.colorScheme.primary
                )
            }
        },
    ) {
        val shown = remember(games.toList(), searchQuery, sort) {
            games
                .filter { game ->
                    searchQuery.isBlank() ||
                        (game.info.name.value ?: "").contains(searchQuery, ignoreCase = true)
                }
                .sortedWith(
                    compareBy(String.CASE_INSENSITIVE_ORDER) { game ->
                        game.info.name.value ?: gameTitleId(game.info.path)
                    }
                )
                .let { if (sort == GameSort.NameDescending) it.reversed() else it }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Rpcs.BackdropTop,
                        0.55f to Rpcs.BackdropMid,
                        1.0f to Rpcs.BackdropBottom
                    )
                )
        ) {
            // Soft light pooling at the bottom of the backdrop.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Rpcs.Glow.copy(alpha = 0.22f), Color.Transparent),
                            center = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                            radius = 1400f
                        )
                    )
            )

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 24.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LibraryHeader(count = shown.size)
                }

                items(count = shown.size, key = { index -> shown[index].info.path }) { index ->
                    GameItem(
                        game = shown[index],
                        navigateToGameSettings = navigateToGameSettings,
                        navigateToGamePatches = navigateToGamePatches,
                        navigateToGameUpdates = navigateToGameUpdates
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryHeader(count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 22.dp)
                .background(Rpcs.AccentBright, RoundedCornerShape(2.dp))
        )
        Text(
            text = stringResource(R.string.games_library_header),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Rpcs.TextPrimary,
            modifier = Modifier.padding(start = 10.dp)
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Rpcs.TextPrimary,
            modifier = Modifier
                .padding(start = 10.dp)
                .background(Rpcs.Accent.copy(alpha = 0.85f), RoundedCornerShape(50))
                .padding(horizontal = 9.dp, vertical = 2.dp)
        )
    }
}

/** Shown when a game has no ICON0.PNG: crimson gradient with the title's initial. */
@Composable
private fun GameCoverPlaceholder(title: String) {
    val initial = title.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(listOf(Rpcs.BackdropTop, Rpcs.Accent))
            )
    ) {
        Text(
            text = initial,
            fontSize = 44.sp,
            fontWeight = FontWeight.Black,
            color = Rpcs.TextPrimary.copy(alpha = 0.9f)
        )
    }
}

@Preview
@Composable
fun GamesScreenPreview() {
    listOf(
        "Minecraft", "Skate 3", "Mirror's Edge", "Demon's Souls"
    ).forEach { x -> GameRepository.addPreview(arrayOf(GameInfo(x, x))) }

    GamesScreen()
}
