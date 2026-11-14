package com.march7th.hkrpg.presentation.view.dev

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.march7th.hkrpg.data.model.PackParam
import com.march7th.hkrpg.data.model.PackStep
import com.march7th.hkrpg.data.resource.CustomTaskPackValidator.KNOWN_OPS
import com.march7th.hkrpg.dev.DelayField
import com.march7th.hkrpg.dev.PackTemplateEntry
import com.march7th.hkrpg.dev.StepTemplate
import com.march7th.hkrpg.dev.TaskPackDisplay
import com.march7th.hkrpg.dev.TaskPackTemplateLibrary
import com.march7th.hkrpg.dev.FlatStepNode
import com.march7th.hkrpg.dev.IconTemplateEntry
import com.march7th.hkrpg.dev.StepBranch
import com.march7th.hkrpg.dev.StepPath
import com.march7th.hkrpg.dev.TaskPackProjectSummary
import com.march7th.hkrpg.dev.label
import com.march7th.hkrpg.dev.TaskPackIconTemplates
import com.march7th.hkrpg.dev.TaskPackNaming
import com.march7th.hkrpg.presentation.viewmodel.DevClickMode
import com.march7th.hkrpg.presentation.viewmodel.DevRecordMode
import com.march7th.hkrpg.presentation.viewmodel.SilentAction
import com.march7th.hkrpg.presentation.viewmodel.OcrRegionDraft
import com.march7th.hkrpg.presentation.viewmodel.PreviewScaleMode
import com.march7th.hkrpg.presentation.viewmodel.STEP_PAGE_SIZE
import androidx.compose.foundation.lazy.rememberLazyListState
import com.march7th.hkrpg.presentation.viewmodel.TaskPackDevState
import com.march7th.hkrpg.presentation.viewmodel.TaskPackDevViewModel
import com.march7th.hkrpg.presentation.viewmodel.TopHint
import com.march7th.hkrpg.presentation.viewmodel.TopHintKind
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.koin.androidx.compose.koinViewModel

/** 从 Compose 的 Context 一路解包拿到 Activity，用于动态设置横屏。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** 任务包开发者版主页面：实时录制、步骤编辑、AI 写作、导出。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskPackDeveloperScreen(
    navController: NavController,
    viewModel: TaskPackDevViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    var confirmNewDraft by rememberSaveable { mutableStateOf(false) }
    // 多工程存档面板：顶部「工程」打开，可切换 / 另存 / 重命名 / 删除。
    var showProjects by rememberSaveable { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let(viewModel::exportTo) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::importProject) }

    // 处理任务包开发时强制横屏：竖屏看游戏画面太窄，不方便点击。
    // 全面屏适配：允许内容延伸到挖孔「短边」，避免澎湃 OS3 等机型横屏时被系统整块
    // letterbox 缩进；交互控件再用 safeDrawing insets 逐边避开挖孔与底部手势条。
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(activity) {
        val window = activity?.window
        val previousOrientation = activity?.requestedOrientation
        val previousCutout = window?.attributes?.layoutInDisplayCutoutMode
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        if (window != null) {
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attrs
        }
        onDispose {
            activity?.requestedOrientation = previousOrientation
                ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (window != null && previousCutout != null) {
                val attrs = window.attributes
                attrs.layoutInDisplayCutoutMode = previousCutout
                window.attributes = attrs
            }
        }
    }

    // 顶栏吃掉状态栏/顶部挖孔，内容区避开左右挖孔与底部手势条；均排除 IME，键盘交由系统处理。
    val topSafe = WindowInsets.safeDrawing.exclude(WindowInsets.ime)
        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    val contentSafe = WindowInsets.safeDrawing.exclude(WindowInsets.ime)
        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)

    // 默认「专注画面」：1920×1080 画面严格 16:9 居中，左右黑边放功能栏
    // （左侧功能 Tab / 录制模式，右侧工具条 / 控制）。切到编辑类 Tab 自动回到左右分栏。
    var splitMode by rememberSaveable { mutableStateOf(false) }

    // 虚拟屏固定 1920×1080 / dpi 240。v1.0.13 曾跟随真机物理分辨率，
    // 实测会让游戏在两种分辨率间反复 restart、dpi 跳变，画面异常。
    LaunchedEffect(Unit) { viewModel.ensureDefaultResolution() }

    // 真·全屏：隐藏顶栏与左右功能栏，只留 16:9 纯画面，右上角一个 X 退出。
    if (state.trueFullscreen) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            LiveRecorderView(viewModel, state, Modifier.fillMaxSize())
            IconButton(
                onClick = { viewModel.setTrueFullscreen(false) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(topSafe)
                    .padding(8.dp),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "退出全屏画面",
                    tint = Color.White,
                    modifier = Modifier
                        .size(28.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                        .padding(4.dp),
                )
            }
            // 全屏时也把重取坐标 / region 提示浮在顶部，不打断画面。
            state.topHint?.let { hint ->
                TopHintBar(
                    hint = hint,
                    onCopy = viewModel::copyTopHintToClipboard,
                    onDismiss = viewModel::dismissTopHint,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .windowInsetsPadding(topSafe)
                        .padding(8.dp)
                        .widthIn(max = 420.dp),
                )
            }
        }
    } else {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            // 紧凑自定义顶栏：42dp（原 Material3 TopAppBar 为 64dp），省下的高度全给画面。
            // 重取坐标 / 纯框选 region 的提示统一显示在「任务包开发者」这块顶栏下方。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .windowInsetsPadding(topSafe),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Text("任务包开发者", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        state.projects.firstOrNull { it.fileId == state.currentProjectFileId }
                            ?.let { "工程 ${it.displayName}" } ?: "草稿自动保存",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.weight(1f))
                    CompactAction("工程") {
                        viewModel.refreshProjects()
                        showProjects = true
                    }
                    CompactAction(
                        text = if (state.importing) "载入中…" else "导入现有包",
                        enabled = !state.importing,
                    ) { importLauncher.launch(arrayOf("*/*")) }
                    CompactAction("新建") { confirmNewDraft = true }
                    CompactAction("撤销", state.canUndo, viewModel::undo)
                    CompactAction("重做", state.canRedo, viewModel::redo)
                    CompactAction("真全屏") { viewModel.setTrueFullscreen(true) }
                    CompactAction(if (splitMode) "专注画面" else "分栏编辑") {
                        splitMode = !splitMode
                    }
                }
                state.topHint?.let { hint ->
                    TopHintBar(
                        hint = hint,
                        onCopy = viewModel::copyTopHintToClipboard,
                        onDismiss = viewModel::dismissTopHint,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(contentSafe),
        ) {
            if (state.importing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                )
            }
            if (!splitMode) {
                // 默认「专注画面」：中间 1920×1080 画面严格 16:9 居中，
                // 左右两侧因全面屏比例多出的黑边用来放功能栏，画面不被遮挡也不拉伸。
                FocusLayout(
                    viewModel = viewModel,
                    state = state,
                    tab = tab,
                    onTabChange = { index ->
                        tab = index
                        if (index != 0) splitMode = true
                    },
                )
            } else {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val landscape = maxWidth > maxHeight * 1.15f
                    if (landscape) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            MonitorPane(
                                viewModel = viewModel,
                                state = state,
                                modifier = Modifier.weight(0.58f).fillMaxHeight(),
                            )
                            Box(
                                Modifier
                                    .fillMaxHeight()
                                    .width(1.dp)
                                    .background(
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                    )
                            )
                            WorkPane(
                                viewModel = viewModel,
                                state = state,
                                tab = tab,
                                onTabChange = { tab = it },
                                onImport = { importLauncher.launch(arrayOf("*/*")) },
                                onExport = { exportLauncher.launch(viewModel.suggestedFileName()) },
                                modifier = Modifier.weight(0.42f).fillMaxHeight(),
                            )
                        }
                    } else {
                        // 旋转过渡 / 竖屏兜底：画面固定高度，下方仍是 Tab + 工作区。
                        Column(modifier = Modifier.fillMaxSize()) {
                            MonitorPane(
                                viewModel = viewModel,
                                state = state,
                                modifier = Modifier.fillMaxWidth().height(300.dp),
                            )
                            WorkPane(
                                viewModel = viewModel,
                                state = state,
                                tab = tab,
                                onTabChange = { tab = it },
                                onImport = { importLauncher.launch(arrayOf("*/*")) },
                                onExport = { exportLauncher.launch(viewModel.suggestedFileName()) },
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
    } // end of else (非真全屏)

    if (state.silentReviewVisible) {
        SilentReviewDialog(viewModel, state)
    }

    if (showProjects) {
        ProjectDialog(
            viewModel = viewModel,
            state = state,
            onDismiss = { showProjects = false },
        )
    }

    if (confirmNewDraft) {
        AlertDialog(
            onDismissRequest = { confirmNewDraft = false },
            title = { Text("新建空白草稿") },
            text = { Text("当前步骤会被清空（可撤销按钮不会恢复元数据），建议先导出备份。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmNewDraft = false
                    viewModel.startNewDraft()
                }) { Text("新建") }
            },
            dismissButton = {
                TextButton(onClick = { confirmNewDraft = false }) { Text("取消") }
            },
        )
    }

    state.ocrRegionDraft?.let { draft ->
        OcrRegionDialog(draft, viewModel)
    }
    if (state.ocrRegionBusy) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("正在识别框选区域…") },
            text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
            confirmButton = {},
        )
    }

    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            title = { Text("任务包开发者") },
            text = { Text(message, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissMessage) { Text("知道了") }
            },
        )
    }
}

/**
 * 顶栏「任务包开发者」下方的提示条：重取坐标结果与纯框选 region 都显示在这里。
 * region / 坐标带「复制」按钮，直接产出可粘贴进 task.json 的文本。
 */
@Composable
private fun TopHintBar(
    hint: TopHint,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = when (hint.kind) {
        TopHintKind.REGION -> MaterialTheme.colorScheme.secondaryContainer
        TopHintKind.COORDINATE -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Row(
        modifier = modifier
            .background(container)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            hint.text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (hint.kind == TopHintKind.REGION) FontFamily.Monospace else null,
            modifier = Modifier.weight(1f),
        )
        if (hint.copyValue.isNotBlank()) {
            IconButton(onClick = onCopy, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = "复制",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        TextButton(onClick = onDismiss) { Text("关闭", style = MaterialTheme.typography.labelSmall) }
    }
}

/** 拉框 OCR 结果：挑一行文字 + 选一个动作，生成带 region 的步骤。 */
@Composable
private fun OcrRegionDialog(draft: OcrRegionDraft, viewModel: TaskPackDevViewModel) {
    var chosenText by remember(draft) { mutableStateOf(draft.lines.firstOrNull().orEmpty()) }
    var op by remember(draft) { mutableStateOf("tapText") }
    var varName by remember(draft) { mutableStateOf("text") }
    val readMode = op == "readText"
    val ops = listOf(
        "tapText" to "点击文字",
        "waitText" to "等待文字",
        "assertText" to "断言文字",
        "ifText" to "文字分支",
        "readText" to "读文字到变量",
    )
    AlertDialog(
        onDismissRequest = viewModel::dismissOcrRegion,
        title = { Text("框选区域识别到 ${draft.lines.size} 行文字") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "region = [${draft.region.joinToString(", ") { "%.3f".format(it) }}]（0~1 归一化，随步骤一起写入）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("点一行作为要用的文字：", style = MaterialTheme.typography.labelSmall)
                Column(
                    modifier = Modifier.heightIn(max = 150.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    draft.lines.forEach { line ->
                        FilterChip(
                            selected = chosenText == line,
                            onClick = { chosenText = line },
                            label = { Text(line, maxLines = 2) },
                        )
                    }
                }
                OutlinedTextField(
                    value = if (readMode) varName else chosenText,
                    onValueChange = { if (readMode) varName = it else chosenText = it },
                    label = { Text(if (readMode) "变量名 to" else "文字（可手动修正）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("生成动作：", style = MaterialTheme.typography.labelSmall)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ops.forEach { (key, label) ->
                        FilterChip(
                            selected = op == key,
                            onClick = { op = key },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.confirmOcrRegion(op, chosenText, varName) }) {
                Text("生成步骤")
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissOcrRegion) { Text("取消") }
        },
    )
}

// ==================== 实时录制 ====================

private val DEV_TAB_TITLES = listOf("实时录制", "步骤编辑", "AI 生成", "校验导出", "环境", "模板库")

/** 顶栏紧凑文字按钮，避免默认 TextButton 的内边距把顶栏撑高。 */
@Composable
private fun CompactAction(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * 默认「专注画面」布局：中间 1920×1080 画面严格 16:9 居中，
 * 全面屏设备左右两侧多出来的黑边正好用来放功能栏——
 * 左侧功能 Tab + 录制模式，右侧工具条 + 控制，画面完全不被遮挡、不变形。
 *
 * 这是用户要的「全面屏画面」：不是把画面拉满整屏，而是 1920×1080 画面居中，
 * 左右黑边塞功能栏。虚拟屏恒为 1920×1080 / dpi 240，不做任何分辨率切换。
 */
@Composable
private fun FocusLayout(
    viewModel: TaskPackDevViewModel,
    state: TaskPackDevState,
    tab: Int,
    onTabChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 按内容区高度算出 16:9 画面宽度，剩余的一半就是每侧黑边宽度。
        // 黑边过窄（16:9 屏 / 竖屏兜底）时给侧栏保底 92dp，画面等比缩小仍严格 16:9。
        val pictureWidth = maxHeight * (16f / 9f)
        val sideWidth = maxOf(92f, minOf(190f, (maxWidth.value - pictureWidth.value) / 2f)).dp
        Row(modifier = Modifier.fillMaxSize()) {
            FocusLeftBar(
                viewModel = viewModel,
                state = state,
                tab = tab,
                onTabChange = onTabChange,
                modifier = Modifier.width(sideWidth).fillMaxHeight(),
            )
            MonitorPane(
                viewModel = viewModel,
                state = state,
                showToolbar = false,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            FocusRightBar(
                viewModel = viewModel,
                state = state,
                modifier = Modifier.width(sideWidth).fillMaxHeight(),
            )
        }
    }
}

/** 左侧黑边：竖排功能 Tab + 录制模式切换（不遮挡画面）。 */
@Composable
private fun FocusLeftBar(
    viewModel: TaskPackDevViewModel,
    state: TaskPackDevState,
    tab: Int,
    onTabChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "功能",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DEV_TAB_TITLES.forEachIndexed { index, title ->
            TextButton(
                onClick = { onTabChange(index) },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (index == tab) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        Text(
            "录制模式",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RecordModeChips(viewModel, state, compact = true)
        Text(
            "点非录制 Tab 会自动切到分栏编辑，便于查看步骤 / AI / 模板内容。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** 右侧黑边：竖排工具条与控制（不遮挡画面）。 */
@Composable
private fun FocusRightBar(
    viewModel: TaskPackDevViewModel,
    state: TaskPackDevState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "工具",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DevToolbarItems(viewModel, state, compact = true)
    }
}

/** 录制模式 chips：左侧栏用短标签，底部横向工具条用完整标签。 */
@Composable
private fun RecordModeChips(
    viewModel: TaskPackDevViewModel,
    state: TaskPackDevState,
    compact: Boolean,
) {
    FilterChip(
        selected = state.mode == DevRecordMode.GESTURE &&
            state.recordClickMode == DevClickMode.TEXT_OCR,
        onClick = { viewModel.setRecordClickMode(DevClickMode.TEXT_OCR) },
        label = { Text(if (compact) "文字 OCR" else "文字 OCR（默认）") },
    )
    FilterChip(
        selected = state.mode == DevRecordMode.GESTURE &&
            state.recordClickMode == DevClickMode.COORDINATE,
        onClick = { viewModel.setRecordClickMode(DevClickMode.COORDINATE) },
        label = { Text("坐标") },
    )
    FilterChip(
        selected = state.mode == DevRecordMode.OCR_TEXT,
        onClick = { viewModel.setMode(DevRecordMode.OCR_TEXT) },
        label = { Text(if (compact) "拉框 OCR" else "拉框 OCR 文字") },
    )
    FilterChip(
        selected = state.mode == DevRecordMode.TAP_TEMPLATE,
        onClick = { viewModel.setMode(DevRecordMode.TAP_TEMPLATE) },
        label = { Text(if (compact) "模板点击" else "框选模板点击") },
    )
    FilterChip(
        selected = state.mode == DevRecordMode.WAIT_TEMPLATE,
        onClick = { viewModel.setMode(DevRecordMode.WAIT_TEMPLATE) },
        label = { Text(if (compact) "模板等待" else "框选模板等待") },
    )
    // 三个并列独立入口（不是把自识别合并进别处，各占一个单独位置）。
    FilterChip(
        selected = state.mode == DevRecordMode.REGION_ONLY,
        onClick = { viewModel.setMode(DevRecordMode.REGION_ONLY) },
        label = { Text(if (compact) "纯框选 region" else "纯框选 region") },
    )
    FilterChip(
        selected = state.mode == DevRecordMode.SWIPE,
        onClick = { viewModel.setMode(DevRecordMode.SWIPE) },
        label = { Text("滑动") },
    )
    FilterChip(
        selected = state.mode == DevRecordMode.AUTO,
        onClick = { viewModel.setMode(DevRecordMode.AUTO) },
        label = { Text(if (compact) "自识别" else "点击/滑动自识别") },
    )
    // 静默录制：边玩边记，OCR/截图全在后台，停止后统一确认。
    FilterChip(
        selected = state.mode == DevRecordMode.SILENT,
        onClick = { viewModel.setMode(DevRecordMode.SILENT) },
        label = { Text(if (compact) "静默录制" else "自适应静默（边玩边记）") },
    )
}

/** 画面缩放模式：两种都严格等比例，绝不非等比拉伸。 */
@Composable
private fun PreviewScaleChips(viewModel: TaskPackDevViewModel, state: TaskPackDevState) {
    FilterChip(
        selected = state.previewScaleMode == PreviewScaleMode.FIT,
        onClick = { viewModel.setPreviewScaleMode(PreviewScaleMode.FIT) },
        label = { Text("等比完整") },
    )
    FilterChip(
        selected = state.previewScaleMode == PreviewScaleMode.CROP,
        onClick = { viewModel.setPreviewScaleMode(PreviewScaleMode.CROP) },
        label = { Text("等比铺满") },
    )
}

/**
 * 录制控制项：横向工具条（分栏模式）与右侧竖排工具条（专注模式）共用同一套逻辑，
 * 避免两处控件状态不一致。
 */
@Composable
private fun DevToolbarItems(
    viewModel: TaskPackDevViewModel,
    state: TaskPackDevState,
    compact: Boolean,
) {
    val picking = state.coordinatePickActive
    Text(
        "基准 ${state.previewWidth}×${state.previewHeight} · 已录 ${state.steps.size} 步",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
        onClick = { viewModel.setRecording(!state.recording) },
        enabled = !picking,
    ) { Text(if (state.recording) "暂停录制" else "开始录制") }
    OutlinedButton(onClick = viewModel::addWait, enabled = !picking) {
        Text("+ 等待 1 秒")
    }
    OutlinedButton(onClick = viewModel::addLog, enabled = !picking) {
        Text("+ 日志")
    }
    if (picking) {
        Text(
            "取点中：在画面点一下写入步骤（提示见顶部）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = {
            viewModel.cancelPickCoordinate()
            viewModel.cancelPickCoordinatePath()
        }) { Text("取消取点") }
    }
    if (!compact) {
        Text("录制识别", style = MaterialTheme.typography.labelSmall)
        RecordModeChips(viewModel, state, compact = false)
    }
    Text("画面", style = MaterialTheme.typography.labelSmall)
    PreviewScaleChips(viewModel, state)
    IconButton(onClick = { viewModel.setTrueFullscreen(true) }) {
        Icon(Icons.Filled.Fullscreen, contentDescription = "真全屏画面")
    }
    Text("非录制可点击", style = MaterialTheme.typography.labelSmall)
    Switch(
        checked = state.idleTouchEnabled,
        onCheckedChange = viewModel::setIdleTouchEnabled,
    )
}

/**
 * 画面区：上部为实时预览（等比内接、完全无遮挡），下部可选一条紧凑控制条。
 * 默认专注布局把工具条放到右侧黑边，这里只留画面；分栏模式仍保留底部横条。
 */
@Composable
private fun MonitorPane(
    viewModel: TaskPackDevViewModel,
    state: TaskPackDevState,
    modifier: Modifier = Modifier,
    showToolbar: Boolean = true,
) {
    Column(modifier = modifier) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LiveRecorderView(viewModel, state, Modifier.fillMaxSize().padding(4.dp))
        }
        if (showToolbar) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DevToolbarItems(viewModel, state, compact = false)
            }
        }
    }
}

/** 右栏：Tab 固定在顶部，内容区始终占满剩余高度，不会被画面顶掉。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkPane(
    viewModel: TaskPackDevViewModel,
    state: TaskPackDevState,
    tab: Int,
    onTabChange: (Int) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        PrimaryTabRow(selectedTabIndex = tab) {
            DEV_TAB_TITLES.forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onClick = { onTabChange(index) },
                        text = { Text(title) },
                    )
                }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            when (tab) {
                0 -> RecorderTab(viewModel, state)
                1 -> EditorTab(
                    viewModel = viewModel,
                    state = state,
                    onImport = onImport,
                    // 定位只改叠加标记，页面不跳走：左侧画面本来就一直可见。
                    onLocate = { path -> viewModel.focusStepAt(path) },
                    onRepick = { path ->
                        viewModel.focusStepAt(path)
                        if (path.parts.size == 1) {
                            viewModel.startPickCoordinate(path.parts.first().index)
                        } else {
                            viewModel.startPickCoordinateAt(path)
                        }
                    },
                )
                2 -> AiTab(viewModel, state)
                3 -> ExportTab(viewModel, state, onExport)
                4 -> EnvironmentTab(viewModel, state)
                5 -> TemplateTab(viewModel, state)
                else -> EnvironmentTab(viewModel, state)
            }
        }
    }
}

/** 录制 Tab：录制开关 + 已录步骤列表（画面与控制常驻左栏）。 */
@Composable
private fun RecorderTab(viewModel: TaskPackDevViewModel, state: TaskPackDevState) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "已录 ${state.steps.size} 步 · 坐标 0~1 · schemaVersion 1",
            style = MaterialTheme.typography.titleSmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("文字写入 note", style = MaterialTheme.typography.labelMedium)
            Switch(checked = state.recordNotes, onCheckedChange = viewModel::setRecordNotes)
            Spacer(Modifier.width(12.dp))
            Text("记录操作间隔", style = MaterialTheme.typography.labelMedium)
            Switch(checked = state.recordIntervals, onCheckedChange = viewModel::setRecordIntervals)
        }
        Text(
            "默认「文字 OCR」：点击时识别到文字就生成原版 tapText（不写坐标、不用模板），" +
                "识别不到才回退到用户点击的坐标。切「拉框 OCR 文字」后在画面拖框，" +
                "只识别框内文字，挑一行生成 tapText / waitText / assertText / readText（带 region）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "画面基准统一 1920×1080、严格 16:9 不拉伸：工具条「等比完整」留最小黑边，" +
                "「等比铺满」放大填满并裁掉边缘。坐标 0~1。非录制状态（含 AI 生成中）也可直接点击游戏画面推进界面，不会写入步骤。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "默认「专注画面」：1920×1080 画面居中，左侧黑边放功能 Tab / 录制模式，右侧黑边放工具条；" +
                "点选步骤可在画面叠加显示坐标。顶栏可切「专注画面 / 分栏编辑」。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        Text(
            "「自适应静默」是边玩边记：录制中只记点击/长按/滑动的坐标与时序，" +
                "OCR 和截图全部丢到后台，不弹窗、不阻塞，正常玩就行；点「暂停录制」后" +
                "会弹出确认面板，每条都带当时的截图，勾选后再统一生成步骤。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (state.steps.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "还没有步骤。点左侧「开始录制」，然后在画面上操作游戏。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(state.steps) { index, step ->
                    StepCard(
                        index = index,
                        step = step,
                        total = state.steps.size,
                        editable = false,
                        selected = state.selectedStepIndex == index,
                        assetBytes = step.image?.let(state.assets::get),
                        onSelect = { viewModel.selectStep(index) },
                        onNote = {},
                        onOptional = {},
                        onMove = {},
                        onDelete = { viewModel.removeStep(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveRecorderView(
    viewModel: TaskPackDevViewModel,
    state: TaskPackDevState,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    var surfaceView by remember { mutableStateOf<SurfaceView?>(null) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    val selectedIndex = state.selectedStepIndex
    // 定位可以是任意层级的步骤（树里点「画面定位」），优先用它；没有定位再退回顶层选中项。
    val selectedStep = state.focusedStep ?: selectedIndex?.let(state.steps::getOrNull)
    val recordingNow by rememberUpdatedState(state.recording)
    val idleTouchNow by rememberUpdatedState(state.idleTouchEnabled)
    // 静默模式不在这里截帧：截图交给 ViewModel 的后台协程，手势链不等待，游玩不卡。
    val silentNow by rememberUpdatedState(state.mode == DevRecordMode.SILENT)
    val gestureEvents = remember { Channel<DevGestureEvent>(Channel.UNLIMITED) }

    // 画面基准固定 1920×1080（16:9）：虚拟屏与 SurfaceView 缓冲同尺寸，
    // 画面等比居中投帧，全面屏多出来的左右空间由外层放功能栏，不拉伸、不遮挡。
    // 归一化坐标仍是 0~1，与像素尺寸无关。
    val previewWidth = state.previewWidth
    val previewHeight = state.previewHeight
    LaunchedEffect(surfaceView, previewWidth, previewHeight) {
        surfaceView?.holder?.setFixedSize(previewWidth, previewHeight)
    }

    // AwaitPointerEventScope 是受限协程作用域，不能直接调用 ViewModel 的挂起函数；
    // 手势事件先入队，再在普通协程里串行完成截图、注入和录制。
    LaunchedEffect(viewModel, gestureEvents) {
        var frame: Bitmap? = null
        var active = false
        var startX = 0.0
        var startY = 0.0
        var actionAt = 0L
        for (event in gestureEvents) {
            when (event) {
                is DevGestureEvent.Down -> {
                    frame?.recycle()
                    frame = null
                    // 非录制态（含 AI 生成中）也注入真实触摸，只是不截图、不写步骤。
                    active = recordingNow || idleTouchNow
                    if (active) {
                        if (recordingNow && !silentNow) {
                            frame = viewModel.captureFrame()
                        } else if (!recordingNow) {
                            // 用户手动点过的坐标登记为「用户提供的坐标」，供 AI 自动点击引用。
                            viewModel.noteIdleTouch(event.x, event.y)
                        }
                        startX = event.x
                        startY = event.y
                        actionAt = event.actionAt
                        viewModel.touchDown(startX, startY)
                    }
                }
                is DevGestureEvent.Move -> if (active) viewModel.touchMove(event.x, event.y)
                is DevGestureEvent.Up -> {
                    val captured = frame
                    frame = null
                    if (active) {
                        viewModel.touchUp(event.x, event.y)
                        if (recordingNow) {
                            viewModel.recordGesture(
                                frame = captured,
                                startX = startX,
                                startY = startY,
                                endX = event.x,
                                endY = event.y,
                                moved = event.moved,
                                durationMs = event.durationMs,
                                actionAt = actionAt,
                            )
                        } else {
                            captured?.recycle()
                        }
                    } else {
                        captured?.recycle()
                    }
                    active = false
                }
                DevGestureEvent.Cancel -> {
                    viewModel.touchCancel()
                    frame?.recycle()
                    frame = null
                    active = false
                }
            }
        }
    }

    // 以固定 1920×1080 为基准：FIT 等比内接居中（两侧黑边放功能栏），
    // CROP 等比放大铺满并裁掉边缘。两种都严格 16:9，绝不拉伸变形。
    val previewRatio = previewWidth.toFloat() / previewHeight.toFloat().coerceAtLeast(1f)
    val cover = state.previewScaleMode == PreviewScaleMode.CROP
    Box(
        modifier = modifier
            .background(Color.Black, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .fitAspectInBounds(previewRatio, cover)
    ) {
        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    surfaceView = this
                    holder.setFormat(PixelFormat.RGBA_8888)
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            holder.setFixedSize(previewWidth, previewHeight)
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) {
                            // 不再苛刻要求 width/height 精确等于 1920×1080：
                            // 部分 ROM 上 setFixedSize 生效时机与回调顺序不同，旧判断会导致
                            // surface 永不绑定或绑到错误的缓冲尺寸，表现为画面被拉伸。
                            // 只要 surface 有效就交给特权进程，缓冲尺寸由 setFixedSize 固定。
                            if (width > 0 && height > 0) {
                                viewModel.onSurfaceAvailable(holder.surface)
                            }
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            viewModel.onSurfaceDestroyed()
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (state.coordinatePickActive) {
            // 重取坐标模式：点一下画面即写入目标步骤（顶层 index 或任意树路径），
            // 不向游戏注入、不产生录制步骤。
            Box(
                modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures { offset ->
                        viewModel.applyPickedCoordinateToPath(
                            offset.normX(size),
                            offset.normY(size),
                        )
                    }
                },
            )
        } else if (state.mode == DevRecordMode.OCR_TEXT ||
            state.mode == DevRecordMode.REGION_ONLY
        ) {
            // 拉框 OCR 文字 / 纯框选 region：画面上拖出一个区域。
            // 纯框选只把 [x,y,w,h] 输出到顶栏，不 OCR、不注入、不生成步骤；
            // OCR 文字则弹窗挑一行再生成带 region 的动作。两者都不向游戏注入触摸。
            val regionOnly = state.mode == DevRecordMode.REGION_ONLY
            Box(
                modifier = Modifier.fillMaxSize().pointerInput(regionOnly) {
                    detectDragGestures(
                        onDragStart = { dragStart = it; dragEnd = it },
                        onDrag = { change, _ -> dragEnd = change.position },
                        onDragCancel = { dragStart = null; dragEnd = null },
                        onDragEnd = {
                            val start = dragStart
                            val end = dragEnd
                            dragStart = null
                            dragEnd = null
                            if (start != null && end != null &&
                                (end - start).getDistance() >= 16f
                            ) {
                                val left = minOf(start.x, end.x) / size.width
                                val top = minOf(start.y, end.y) / size.height
                                val right = maxOf(start.x, end.x) / size.width
                                val bottom = maxOf(start.y, end.y) / size.height
                                val region = listOf(
                                    left.coerceIn(0f, 1f).toDouble(),
                                    top.coerceIn(0f, 1f).toDouble(),
                                    (right - left).coerceIn(0f, 1f).toDouble(),
                                    (bottom - top).coerceIn(0f, 1f).toDouble(),
                                )
                                if (regionOnly) viewModel.emitRegion(region)
                                else viewModel.scanOcrRegion(region)
                            }
                        },
                    )
                },
            )
        } else if ((state.recording && state.mode in setOf(
                DevRecordMode.GESTURE, DevRecordMode.SWIPE, DevRecordMode.AUTO,
                DevRecordMode.SILENT,
            )) || (!state.recording && state.idleTouchEnabled)
        ) {
            // 录制态：截图 + 注入 + 写步骤；非录制态（含 AI 生成中）：只注入、不写步骤。
            Box(
                modifier = Modifier.fillMaxSize()
                    .pointerInput(state.recording, state.idleTouchEnabled) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val start = down.position
                        val injectedAt = SystemClock.uptimeMillis()
                        val actionAt = System.currentTimeMillis()
                        gestureEvents.trySend(
                            DevGestureEvent.Down(start.normX(size), start.normY(size), actionAt)
                        )
                        var distance = 0f
                        var completed = false
                        try {
                            while (!completed) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null) {
                                    if (event.changes.all { !it.pressed }) {
                                        gestureEvents.trySend(DevGestureEvent.Cancel)
                                        completed = true
                                    }
                                    continue
                                }
                                if (change.pressed) {
                                    if (change.positionChanged()) {
                                        distance = maxOf(distance, (change.position - start).getDistance())
                                        gestureEvents.trySend(
                                            DevGestureEvent.Move(
                                                change.position.normX(size), change.position.normY(size),
                                            )
                                        )
                                        change.consume()
                                    }
                                } else {
                                    change.consume()
                                    completed = true
                                    gestureEvents.trySend(
                                        DevGestureEvent.Up(
                                            x = change.position.normX(size),
                                            y = change.position.normY(size),
                                            moved = distance >= 24f,
                                            durationMs = SystemClock.uptimeMillis() - injectedAt,
                                        )
                                    )
                                }
                            }
                        } finally {
                            if (!completed) gestureEvents.trySend(DevGestureEvent.Cancel)
                        }
                    }
                }
            )
        } else if (state.recording) {
            Box(
                modifier = Modifier.fillMaxSize().pointerInput(state.mode) {
                    detectDragGestures(
                        onDragStart = { dragStart = it; dragEnd = it },
                        onDrag = { change, _ -> dragEnd = change.position },
                        onDragCancel = { dragStart = null; dragEnd = null },
                        onDragEnd = {
                            val start = dragStart
                            val end = dragEnd
                            dragStart = null
                            dragEnd = null
                            if (start != null && end != null && (end - start).getDistance() >= 16f) {
                                val left = minOf(start.x, end.x) / size.width
                                val top = minOf(start.y, end.y) / size.height
                                val right = maxOf(start.x, end.x) / size.width
                                val bottom = maxOf(start.y, end.y) / size.height
                                scope.launch {
                                    viewModel.addTemplateStep(
                                        listOf(
                                            left.coerceIn(0f, 1f).toDouble(),
                                            top.coerceIn(0f, 1f).toDouble(),
                                            (right - left).coerceIn(0f, 1f).toDouble(),
                                            (bottom - top).coerceIn(0f, 1f).toDouble(),
                                        )
                                    )
                                }
                            }
                        },
                    )
                }
            )
        }

        val start = dragStart
        val end = dragEnd
        if (start != null && end != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    color = Color.Red,
                    topLeft = Offset(minOf(start.x, end.x), minOf(start.y, end.y)),
                    size = Size(kotlin.math.abs(end.x - start.x), kotlin.math.abs(end.y - start.y)),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
        if (selectedStep != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val x = selectedStep.x
                val y = selectedStep.y
                if (x != null && y != null) {
                    val center = Offset((x * size.width).toFloat(), (y * size.height).toFloat())
                    drawCircle(Color.Red, radius = 8.dp.toPx(), center = center, style = Stroke(3.dp.toPx()))
                    drawLine(Color.Red, center - Offset(18.dp.toPx(), 0f), center + Offset(18.dp.toPx(), 0f))
                    drawLine(Color.Red, center - Offset(0f, 18.dp.toPx()), center + Offset(0f, 18.dp.toPx()))
                }
                val fromX = selectedStep.fromX
                val fromY = selectedStep.fromY
                val toX = selectedStep.toX
                val toY = selectedStep.toY
                if (fromX != null && fromY != null && toX != null && toY != null) {
                    drawLine(
                        color = Color.Yellow,
                        start = Offset((fromX * size.width).toFloat(), (fromY * size.height).toFloat()),
                        end = Offset((toX * size.width).toFloat(), (toY * size.height).toFloat()),
                        strokeWidth = 4.dp.toPx(),
                    )
                }
                val region = selectedStep.region
                if (region?.size == 4) {
                    drawRect(
                        color = Color.Cyan,
                        topLeft = Offset((region[0] * size.width).toFloat(), (region[1] * size.height).toFloat()),
                        size = Size((region[2] * size.width).toFloat(), (region[3] * size.height).toFloat()),
                        style = Stroke(width = 3.dp.toPx()),
                    )
                }
            }
            Text(
                "叠加显示 ${state.focusedLabel.ifBlank { "#${(selectedIndex ?: 0) + 1} ${selectedStep.op}" }}" +
                    " 的归一化位置（不会写入包）",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.TopStart)
                    .background(Color.Black.copy(alpha = 0.55f)).padding(6.dp),
            )
        }
        // 画面模式角标：真机上可直接确认当前是哪种缩放，以及实际生效的分辨率。
        Text(
            TaskPackDisplay.badge(previewWidth, previewHeight, if (cover) "等比铺满" else "等比完整"),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
        if (!state.recording) {
            Column(
                modifier = Modifier.align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "实时画面预览 · 基准 ${previewWidth}×${previewHeight}（跟随真机）",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    if (state.idleTouchEnabled) {
                        "未录制：点击会直接操作游戏，但不写入步骤（AI 生成时同样可点）"
                    } else {
                        "未录制，画面点击已关闭"
                    },
                    color = Color.White.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    "若无画面，请到「环境」页确认 Shizuku 已授权",
                    color = Color.White.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }

    DisposableEffect(viewModel) {
        onDispose { viewModel.onSurfaceDestroyed() }
    }
}

/**
 * 在父给的边界内按 [ratio] 取矩形并居中，**宽高比恒定**，绝不非等比拉伸。
 *
 * - [cover] = false（等比内接 FIT）：取最大内接矩形，画面完整、两侧或上下留黑边；
 * - [cover] = true（等比铺满 CROP）：取最小外切矩形，画面填满可用区域，超出部分由
 *   外层 `clip` 裁掉。SurfaceView 的布局尺寸始终是严格的 16:9，配合
 *   `holder.setFixedSize(1920, 1080)` 的 16:9 buffer，从根本上避免画面被拉伸变形。
 *
 * 被修饰的 Box 对外仍占满可用空间（画黑底与圆角），但内容按内/外切尺寸测量，
 * 因此内部 fillMaxSize 的手势层 / Canvas 拿到的 size 就是真实画面尺寸，
 * 归一化坐标与画面像素一一对应。
 */
private fun Modifier.fitAspectInBounds(ratio: Float, cover: Boolean = false): Modifier =
    layout { measurable, constraints ->
        val r = ratio.coerceIn(0.5f, 4f)  // 恒为 16:9，只做异常值兜底
        val boundW = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else 1920f
        val boundH = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else boundW / r
        val innerW = (if (cover) maxOf(boundW, boundH * r) else minOf(boundW, boundH * r))
            .roundToInt().coerceAtLeast(1)
        val innerH = (innerW / r).roundToInt().coerceAtLeast(1)
        val placeable = measurable.measure(Constraints.fixed(innerW, innerH))
        val outerW = if (constraints.hasBoundedWidth) constraints.maxWidth else innerW
        val outerH = if (constraints.hasBoundedHeight) constraints.maxHeight else innerH
        layout(outerW, outerH) {
            placeable.placeRelative((outerW - innerW) / 2, (outerH - innerH) / 2)
        }
    }

private fun Offset.normX(size: IntSize): Double = (x / size.width).coerceIn(0f, 1f).toDouble()
private fun Offset.normY(size: IntSize): Double = (y / size.height).coerceIn(0f, 1f).toDouble()

private sealed interface DevGestureEvent {
    data class Down(val x: Double, val y: Double, val actionAt: Long) : DevGestureEvent
    data class Move(val x: Double, val y: Double) : DevGestureEvent
    data class Up(
        val x: Double,
        val y: Double,
        val moved: Boolean,
        val durationMs: Long,
    ) : DevGestureEvent
    data object Cancel : DevGestureEvent
}

// ==================== 步骤编辑 ====================

/** 全局 / 分字段 ms 延迟一键增加：递归作用于整棵步骤树，守住原版校验上限。 */
@Composable
private fun DelayToolsCard(viewModel: TaskPackDevViewModel) {
    var delta by rememberSaveable { mutableStateOf("200") }
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("全局 / 分字段 ms 延迟一键增加", style = MaterialTheme.typography.titleMedium)
            Text(
                "填一个增加量（可填负数减少），点下面的按钮即可批量作用；" +
                    "wait.ms≤60000、timeoutMs≤120000，其余≤60000，超出会自动截断。所有嵌套分支一并处理。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = delta,
                    onValueChange = { delta = it },
                    label = { Text("增加量 ms") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { viewModel.insertWaitForEveryStep(delta.toLongOrNull() ?: 0L) }) {
                    Text("每步后插入 wait")
                }
            }
            Text("单独给已有字段一键增加：", style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DelayField.values().forEach { field ->
                    OutlinedButton(
                        onClick = { viewModel.applyDelay(field, delta.toLongOrNull() ?: 0L) },
                    ) { Text(field.label) }
                }
            }
        }
    }
}

/** 一键插入 24 个 op 中任意一个的字段骨架，配合步骤编辑补全全部字段。 */
@Composable
private fun AddStepCard(viewModel: TaskPackDevViewModel) {
    var menu by remember { mutableStateOf(false) }
    var pickerVisible by remember { mutableStateOf(false) }
    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("新建步骤（${KNOWN_OPS.size} 种 op）", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "插入字段骨架后点「编辑」补全全部字段；坐标与模板图请在画面上取点/框选。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    OutlinedButton(onClick = { menu = true }) { Text("选择 op") }
                    DropdownMenu(
                        expanded = menu,
                        onDismissRequest = { menu = false },
                        modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    ) {
                        KNOWN_OPS.sorted().forEach { op ->
                            DropdownMenuItem(
                                text = { Text(op) },
                                onClick = {
                                    menu = false
                                    viewModel.addStepWithOp(op)
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            // 模板库里的步骤模板与「模板库」Tab 用的是同一份数据：不用记 op 名，按场景挑。
            OutlinedButton(
                onClick = { pickerVisible = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("从模板库添加（按场景挑 op 骨架）")
            }
        }
    }
    if (pickerVisible) {
        StepTemplatePickerDialog(
            onDismiss = { pickerVisible = false },
            onPick = { template ->
                viewModel.insertStepTemplate(template)
                pickerVisible = false
            },
        )
    }
}

/** 「新建步骤 → 从模板库添加」：带搜索的步骤模板选择器。 */
@Composable
private fun StepTemplatePickerDialog(
    onDismiss: () -> Unit,
    onPick: (StepTemplate) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val templates = remember(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            TaskPackTemplateLibrary.STEP_TEMPLATES
        } else {
            TaskPackTemplateLibrary.STEP_TEMPLATES.filter { it.searchText.contains(q, ignoreCase = true) }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth(0.92f)) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Text("从模板库选择步骤", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索：op / 场景 / 字段名（如 点击文字、翻页、颜色计数）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (templates.isEmpty()) {
                    Text("没有匹配的模板", style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(templates, key = { it.op }) { template ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { onPick(template) },
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                                Text(
                                    "${template.op} · ${template.title}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    template.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "字段：${template.fields.joinToString(", ")}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            }
        }
    }
}

@Composable
private fun EditorTab(
    viewModel: TaskPackDevViewModel,
    state: TaskPackDevState,
    onImport: () -> Unit,
    onLocate: (StepPath) -> Unit,
    onRepick: (StepPath) -> Unit = {},
) {
    // 编辑目标改成「树路径」，支持任意层 then / else / steps 里的步骤单独编辑。
    var editingPath by remember { mutableStateOf<StepPath?>(null) }
    var editingParamIndex by remember { mutableStateOf<Int?>(null) }
    var showJson by remember { mutableStateOf(false) }
    var renameOld by remember { mutableStateOf("") }
    var renameNew by remember { mutableStateOf("") }
    var opMenu by remember { mutableStateOf(false) }

    // 由 state 派生扁平化节点：分支折叠 + 分页，10548 步也不会一次性构造。
    val nodes = viewModel.flatSteps()
    val opCounts = viewModel.opCounts()
    val variables = viewModel.variableNames()
    val paramKeys = state.params.map { it.key }
    val listState = rememberLazyListState()

    // 搜索命中后点「定位到该步」：滚到目标节点并高亮，直接在原位置改那段代码。
    // 前面固定 5 个卡片（信息 / 配置项 / 延迟工具 / 新建步骤 / 步骤树工具条）。
    LaunchedEffect(state.pendingScrollKey) {
        val key = state.pendingScrollKey
        if (key.isEmpty()) return@LaunchedEffect
        val index = nodes.indexOfFirst { it.pathKey == key }
        if (index >= 0) listState.animateScrollToItem(index + STEP_LIST_HEADER_ITEMS)
        viewModel.clearPendingScroll()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("任务包信息", style = MaterialTheme.typography.titleMedium)
                    // 作者署名放在最上面：录制 / 试用时最常改的就是它。
                    OutlinedTextField(
                        value = state.author,
                        onValueChange = { viewModel.updateMetadata(author = it) },
                        label = { Text("作者署名（最常用，放最上面）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.id,
                        onValueChange = { viewModel.updateMetadata(id = it) },
                        label = { Text("唯一 id（作者.任务名）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.name,
                        onValueChange = { viewModel.updateMetadata(name = it) },
                        label = { Text("任务包名称（不超过 24 字）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = state.packVersion,
                            onValueChange = { viewModel.updateMetadata(packVersion = it) },
                            label = { Text("包版本 x.x") },
                            singleLine = true,
                            supportingText = { Text("也可直接粘贴 1.2-4.7 或 1.0.0") },
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = state.gameVersion,
                            onValueChange = { viewModel.updateMetadata(gameVersion = it) },
                            label = { Text("游戏版本 y.y（可留空）") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // 需求 8：minAppVersionCode 可直接编写（低于该 versionCode 的 App 拒绝导入）。
                    OutlinedTextField(
                        value = state.minAppVersionCode.toString(),
                        onValueChange = { raw ->
                            viewModel.updateMetadata(
                                minAppVersionCode = raw.filter(Char::isDigit).toIntOrNull() ?: 1,
                            )
                        },
                        label = { Text("minAppVersionCode（最低 App 版本，默认 11）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "最终 version=${TaskPackNaming.version(state.packVersion, state.gameVersion)}" +
                            "（x.x 为任务包版本，y.y 为游戏版本；游戏版本留空就只写任务包版本）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = state.description,
                        onValueChange = { viewModel.updateMetadata(description = it) },
                        label = { Text("用途、起点界面与前置条件") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "params=${state.params.size} · 模板=${state.assets.size}（导入包的参数会保留）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onImport, enabled = !state.importing) {
                        Text("载入现有 .m7atask / task.json 继续编辑")
                    }
                }
            }
        }
        // ==================== 配置项 params 增删改 ====================
        item {
            Card {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("配置项 ${state.params.size}", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = viewModel::addParam) { Text("+ 添加") }
                    }
                    Text(
                        "在任务卡片上渲染成表单，步骤里用 {key} 引用；编写步骤时可从「快速索引」一键插入 {key}。key 只能字母数字下划线，type 为 int/bool/string。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.params.isEmpty()) {
                        Text("（无配置项）", style = MaterialTheme.typography.bodySmall)
                    }
                    state.params.forEachIndexed { index, param ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "{${param.key}} · ${param.label.ifBlank { "（无标题）" }}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    "type=${param.type} · default=${param.default ?: "空"}" +
                                        (param.min?.let { " · min=$it" } ?: "") +
                                        (param.max?.let { " · max=$it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { editingParamIndex = index }) { Text("编辑") }
                            // 复制 / 上移 / 下移 / 删除收进一个菜单：右侧栏窄，四个图标会挤成一团。
                            var paramMenu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { paramMenu = true }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
                                }
                                DropdownMenu(expanded = paramMenu, onDismissRequest = { paramMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("复制此项") },
                                        leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                                        onClick = {
                                            paramMenu = false
                                            viewModel.duplicateParam(index)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("上移") },
                                        enabled = index > 0,
                                        leadingIcon = { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null) },
                                        onClick = {
                                            paramMenu = false
                                            viewModel.moveParam(index, -1)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("下移") },
                                        enabled = index < state.params.lastIndex,
                                        leadingIcon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null) },
                                        onClick = {
                                            paramMenu = false
                                            viewModel.moveParam(index, 1)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("删除") },
                                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                        onClick = {
                                            paramMenu = false
                                            viewModel.removeParam(index)
                                        },
                                    )
                                }
                            }
                        }
                        if (index < state.params.lastIndex) HorizontalDivider()
                    }
                }
            }
        }
        item { DelayToolsCard(viewModel) }
        item { AddStepCard(viewModel) }

        // ==================== 步骤树工具条（大包优化） ====================
        item {
            Card {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("步骤树 ${state.steps.size} 步", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = viewModel::undo, enabled = state.canUndo) { Text("撤销") }
                        TextButton(onClick = viewModel::redo, enabled = state.canRedo) { Text("重做") }
                    }
                    Text(
                        "分支默认折叠，点某步的「展开」逐层进入 then / else / steps；真实复杂包（万级步骤）按页渲染，不会卡死。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = state.stepQuery,
                        onValueChange = viewModel::setStepQuery,
                        label = { Text("搜索：note 内容 / op / 字段名（durationMs、timeoutMs…）/ 字段值 / 变量名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box {
                            OutlinedButton(onClick = { opMenu = true }) {
                                Text(
                                    if (state.stepOpFilter.isBlank()) "全部 op"
                                    else "${state.stepOpFilter}（${opCounts[state.stepOpFilter] ?: 0}）",
                                )
                            }
                            DropdownMenu(expanded = opMenu, onDismissRequest = { opMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("全部 op（${state.steps.size}）") },
                                    onClick = { viewModel.setStepOpFilter(""); opMenu = false },
                                )
                                opCounts.forEach { (op, n) ->
                                    DropdownMenuItem(
                                        text = { Text("$op（$n）") },
                                        onClick = { viewModel.setStepOpFilter(op); opMenu = false },
                                    )
                                }
                            }
                        }
                        TextButton(onClick = { viewModel.expandAllToDepth(2) }) { Text("展开 2 层") }
                        TextButton(onClick = { viewModel.expandAllToDepth(0) }) { Text("全部折叠") }
                    }
                    // 变量名全局重命名：真实包里 7268 个 setVar + 844 个 ifExpr 全靠变量串联。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = renameOld,
                            onValueChange = { renameOld = it },
                            label = { Text("旧变量名") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(6.dp))
                        OutlinedTextField(
                            value = renameNew,
                            onValueChange = { renameNew = it },
                            label = { Text("新变量名") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            enabled = renameOld.isNotBlank() && renameNew.isNotBlank(),
                            onClick = {
                                viewModel.renameVariableEverywhere(renameOld.trim(), renameNew.trim())
                            },
                        ) { Text("全局重命名") }
                    }
                    if (variables.isNotEmpty()) {
                        Text(
                            "已出现变量（${variables.size}）：${variables.take(24).joinToString(", ")}" +
                                if (variables.size > 24) " …" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ==================== 树形步骤列表 ====================
        items(nodes, key = { it.pathKey }) { node ->
            val searching = state.stepQuery.isNotBlank() || state.stepOpFilter.isNotBlank()
            TreeStepNodeCard(
                node = node,
                viewModel = viewModel,
                assetBytes = node.step.image?.let(state.assets::get),
                onEdit = { editingPath = node.path },
                onLocate = { onLocate(node.path) },
                onRepick = { onRepick(node.path) },
                // 搜索/筛选结果里多一个「定位到该步」：清掉筛选、展开祖先并滚过去。
                showReveal = searching,
                onReveal = { viewModel.revealStep(node.path) },
            )
        }
        // 未筛选时分页加载更多顶层步骤。
        if (state.stepQuery.isBlank() && state.stepOpFilter.isBlank() &&
            state.steps.size > state.stepPages * STEP_PAGE_SIZE
        ) {
            item {
                Button(onClick = viewModel::loadMoreSteps, modifier = Modifier.fillMaxWidth()) {
                    Text("加载更多顶层步骤（已显示 ${state.stepPages * STEP_PAGE_SIZE} / ${state.steps.size}）")
                }
            }
        }
        item {
            Card {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("task.json 预览", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { showJson = !showJson }) {
                            Text(if (showJson) "收起" else "展开（大包按需加载）")
                        }
                    }
                    if (showJson) {
                        Spacer(Modifier.height(8.dp))
                        SelectionContainer {
                            Text(
                                text = viewModel.previewJson(),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    } else {
                        Text(
                            "大包（如 10548 步 / 1.6 MB）默认不渲染整段 JSON，点「展开」再生成。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    val path = editingPath
    val step = path?.let { com.march7th.hkrpg.dev.TaskPackStepTree.stepAt(state.steps, it) }
    if (path != null && step != null) {
        StepEditDialog(
            step = step,
            assetNames = state.assets.keys.sorted(),
            paramKeys = paramKeys,
            variableNames = variables,
            regionText = state.lastRegionText,
            onDismiss = { editingPath = null },
            onApply = {
                viewModel.replaceStepAt(path, it)
                editingPath = null
            },
            onRepick = {
                editingPath = null
                viewModel.startPickCoordinateAt(path)
            },
        )
    }
    val paramIndex = editingParamIndex
    val param = paramIndex?.let(state.params::getOrNull)
    if (paramIndex != null && param != null) {
        ParamEditDialog(
            param = param,
            onDismiss = { editingParamIndex = null },
            onApply = {
                viewModel.updateParam(paramIndex, it)
                editingParamIndex = null
            },
        )
    }
}

/**
 * 步骤列表前固定有几个卡片（任务包信息 / 配置项 / 延迟工具 / 新建步骤 / 步骤树工具条）。
 * 「定位到该步」滚动时要跳过这几项，数值和 EditorTab 里的 item 数量必须一致。
 */
private const val STEP_LIST_HEADER_ITEMS = 5

/** 树形编辑器里的一行：显示层级缩进、分支标签、展开/折叠，以及增删改排序与复制。 */
@Composable
private fun TreeStepNodeCard(
    node: FlatStepNode,
    viewModel: TaskPackDevViewModel,
    assetBytes: ByteArray?,
    onEdit: () -> Unit,
    onLocate: () -> Unit,
    onRepick: () -> Unit,
    showReveal: Boolean = false,
    onReveal: () -> Unit = {},
) {
    val step = node.step
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = (8 + node.depth * 14).dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (node.hasChildren) {
                    IconButton(onClick = { viewModel.toggleExpand(node.path) }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            if (node.expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (node.expanded) "折叠" else "展开",
                        )
                    }
                } else {
                    Spacer(Modifier.width(28.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    // 路径形如 3>1>2（顶层第 4 步 → then 第 2 步 → steps 第 3 步），
                    // 直接对应 task.json 的嵌套位置，便于按 note 找到代码后回到包里定位。
                    Text(
                        "#${node.indexInList + 1} ${step.op}  ·  ${node.path.label()}" +
                            if (node.depth > 0) "  ·  ${node.branchLabel}" else "",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        step.summary(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                    )
                    // note 单独一行常显：录制的每一步都写了「点击文字「xxx」」，
                    // 折叠状态下也能靠它一眼找到目标步骤。
                    Text(
                        if (step.note.isBlank()) "note：（空）" else "note：${step.note}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (step.note.isBlank()) MaterialTheme.colorScheme.outline
                        else MaterialTheme.colorScheme.primary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (node.hasChildren) {
                        Text(
                            "子步骤 ${node.childCount} 个${if (node.expanded) "（已展开）" else "（已折叠）"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (assetBytes != null) {
                val bitmap = remember(assetBytes) {
                    BitmapFactory.decodeByteArray(assetBytes, 0, assetBytes.size)
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "模板预览",
                        modifier = Modifier.padding(vertical = 4.dp).heightIn(max = 72.dp),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showReveal) {
                    // 搜索结果里的第一入口：关掉筛选，回到完整列表的这一步。
                    TextButton(onClick = onReveal) { Text("定位到该步") }
                }
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = { viewModel.duplicateStepAt(node.path) }) { Text("复制") }
                TextButton(onClick = {
                    viewModel.insertStepAfterPath(
                        node.path, PackStep(op = "wait", ms = 1000, note = "插入的等待"),
                    )
                }) { Text("后插等待") }
                TextButton(onClick = { viewModel.moveStepAt(node.path, -1) }) { Text("上移") }
                TextButton(onClick = { viewModel.moveStepAt(node.path, 1) }) { Text("下移") }
                TextButton(onClick = onLocate) { Text("画面定位") }
                TextButton(onClick = onRepick) { Text("重取坐标") }
                TextButton(onClick = { viewModel.removeStepAt(node.path) }) { Text("删除") }
                // 分支容器：可在 then / else / steps 里追加一步，逐层搭出多层嵌套。
                if (step.op in setOf(
                        "ifExpr", "ifText", "ifTemplate", "ifCount",
                        "whileExpr", "repeat",
                    )
                ) {
                    TextButton(onClick = {
                        val branch = when (step.op) {
                            "whileExpr", "repeat" -> StepBranch.STEPS
                            else -> StepBranch.THEN
                        }
                        viewModel.appendChildStep(
                            node.path, branch,
                            PackStep(op = "log", text = "新分支步骤：请编辑"),
                        )
                        if (!node.expanded) viewModel.toggleExpand(node.path)
                    }) { Text("分支内加一步") }
                }
            }
        }
    }
}

/** 配置项编辑：key/label/type/default/min/max，全部落到原版 PackParam 字段。 */
@Composable
private fun ParamEditDialog(
    param: PackParam,
    onDismiss: () -> Unit,
    onApply: (PackParam) -> Unit,
) {
    var key by remember(param) { mutableStateOf(param.key) }
    var label by remember(param) { mutableStateOf(param.label) }
    var type by remember(param) { mutableStateOf(param.type) }
    var default by remember(param) {
        mutableStateOf(
            runCatching { (param.default as? JsonPrimitive)?.content }.getOrNull().orEmpty()
        )
    }
    var min by remember(param) { mutableStateOf(param.min?.toString().orEmpty()) }
    var max by remember(param) { mutableStateOf(param.max?.toString().orEmpty()) }
    var typeMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑配置项") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SimpleField("key（字母数字下划线，步骤里用 {key} 引用）", key) { key = it }
                SimpleField("label（任务卡片上的标题）", label) { label = it }
                Box {
                    OutlinedButton(onClick = { typeMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("type：$type")
                    }
                    DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        listOf("string", "int", "bool").forEach {
                            DropdownMenuItem(
                                text = { Text(it) },
                                onClick = { type = it; typeMenu = false },
                            )
                        }
                    }
                }
                SimpleField("default（默认值）", default) { default = it }
                if (type == "int") {
                    SimpleField("min（可空）", min) { min = it }
                    SimpleField("max（可空）", max) { max = it }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!Regex("^[A-Za-z0-9_]+$").matches(key.trim())) {
                    error = "key 只能含字母、数字、下划线且不为空"
                    return@TextButton
                }
                val defaultValue: JsonPrimitive? = if (default.isBlank()) null else when (type) {
                    "int" -> default.trim().toLongOrNull()?.let { JsonPrimitive(it) }
                        ?: run { error = "int 的 default 必须是整数"; return@TextButton }
                    "bool" -> JsonPrimitive(default.trim().equals("true", true) || default.trim() == "1")
                    else -> JsonPrimitive(default)
                }
                val minVal = min.trim().toIntOrNull()
                val maxVal = max.trim().toIntOrNull()
                if (type == "int" && minVal != null && maxVal != null && minVal > maxVal) {
                    error = "min 不能大于 max"
                    return@TextButton
                }
                onApply(
                    PackParam(
                        key = key.trim(),
                        label = label.trim(),
                        type = type,
                        default = defaultValue,
                        min = if (type == "int") minVal else null,
                        max = if (type == "int") maxVal else null,
                    )
                )
            }) { Text("应用") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun StepCard(
    index: Int,
    step: PackStep,
    total: Int,
    editable: Boolean,
    selected: Boolean = false,
    assetBytes: ByteArray? = null,
    onSelect: () -> Unit = {},
    onNote: (String) -> Unit,
    onOptional: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit = {},
    onInsertWait: () -> Unit = {},
    onEdit: () -> Unit = {},
    onConvertTap: () -> Unit = {},
    onLocate: () -> Unit = {},
    onRepick: () -> Unit = {},
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (selected) "● " else "") + "#${index + 1}  ${step.op}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                if (editable) {
                    IconButton(onClick = { onMove(-1) }, enabled = index > 0) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上移")
                    }
                    IconButton(onClick = { onMove(1) }, enabled = index < total - 1) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下移")
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除")
                }
            }
            Text(
                text = step.summary(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            if (assetBytes != null) {
                val bitmap = remember(assetBytes) {
                    BitmapFactory.decodeByteArray(assetBytes, 0, assetBytes.size)
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "模板预览",
                        modifier = Modifier.padding(vertical = 6.dp).heightIn(max = 96.dp),
                    )
                }
            }
            if (editable) {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = onDuplicate) { Text("复制") }
                    TextButton(onClick = onInsertWait) { Text("后插等待") }
                    TextButton(onClick = onEdit) { Text("编辑参数") }
                    TextButton(onClick = onRepick) { Text("重取坐标") }
                    TextButton(onClick = onLocate) { Text("实时画面定位") }
                    if (step.op in setOf("tap", "longPress") && step.note.contains("点击文字「")) {
                        TextButton(onClick = onConvertTap) { Text("转 OCR 点击") }
                    }
                }
                OutlinedTextField(
                    value = step.note,
                    onValueChange = onNote,
                    label = { Text("note：说明本步点击的文字或用途") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = step.optional, onCheckedChange = onOptional)
                    Text("optional：失败仅警告，不中断流程", style = MaterialTheme.typography.bodySmall)
                }
            } else if (step.note.isNotBlank()) {
                Text(step.note, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** 已录制步骤的完整编辑：可切换 op、改所有常用字段、改 note/optional、模板下拉、重取坐标。 */
@Composable
private fun StepEditDialog(
    step: PackStep,
    assetNames: List<String>,
    paramKeys: List<String> = emptyList(),
    variableNames: List<String> = emptyList(),
    regionText: String = "",
    onDismiss: () -> Unit,
    onApply: (PackStep) -> Unit,
    onRepick: () -> Unit,
) {
    var op by remember(step) { mutableStateOf(step.op) }
    var opMenu by remember { mutableStateOf(false) }
    var note by remember(step) { mutableStateOf(step.note) }
    var optional by remember(step) { mutableStateOf(step.optional) }
    var text by remember(step) { mutableStateOf(step.text.orEmpty()) }
    var ms by remember(step) { mutableStateOf(step.ms?.toString().orEmpty()) }
    var x by remember(step) { mutableStateOf(step.x?.toString().orEmpty()) }
    var y by remember(step) { mutableStateOf(step.y?.toString().orEmpty()) }
    var fromX by remember(step) { mutableStateOf(step.fromX?.toString().orEmpty()) }
    var fromY by remember(step) { mutableStateOf(step.fromY?.toString().orEmpty()) }
    var toX by remember(step) { mutableStateOf(step.toX?.toString().orEmpty()) }
    var toY by remember(step) { mutableStateOf(step.toY?.toString().orEmpty()) }
    var duration by remember(step) { mutableStateOf(step.durationMs?.toString().orEmpty()) }
    var timeout by remember(step) { mutableStateOf(step.timeoutMs?.toString().orEmpty()) }
    var threshold by remember(step) { mutableStateOf(step.threshold?.toString().orEmpty()) }
    var region by remember(step) { mutableStateOf(step.region?.joinToString(", ").orEmpty()) }
    var image by remember(step) { mutableStateOf(step.image.orEmpty()) }
    var imageMenu by remember { mutableStateOf(false) }
    var color by remember(step) { mutableStateOf(step.color.orEmpty()) }
    var expr by remember(step) { mutableStateOf(step.expr.orEmpty()) }
    var toVar by remember(step) { mutableStateOf(step.to.orEmpty()) }
    // ---- docs/task-file.md 里其余全部字段 ----
    var stable by remember(step) { mutableStateOf(step.stable?.toString().orEmpty()) }
    var offsetX by remember(step) { mutableStateOf(step.offsetX?.toString().orEmpty()) }
    var offsetY by remember(step) { mutableStateOf(step.offsetY?.toString().orEmpty()) }
    var tolerance by remember(step) { mutableStateOf(step.tolerance?.toString().orEmpty()) }
    var minArea by remember(step) { mutableStateOf(step.minArea?.toString().orEmpty()) }
    var maxArea by remember(step) { mutableStateOf(step.maxArea?.toString().orEmpty()) }
    var compare by remember(step) { mutableStateOf(step.compare.orEmpty()) }
    var value by remember(step) { mutableStateOf(step.value?.toString().orEmpty()) }
    var ratio by remember(step) { mutableStateOf(step.ratio?.toString().orEmpty()) }
    var intervalMs by remember(step) { mutableStateOf(step.intervalMs?.toString().orEmpty()) }
    var maxTries by remember(step) { mutableStateOf(step.maxTries?.toString().orEmpty()) }
    var times by remember(step) {
        mutableStateOf(step.times?.let { (it as? JsonPrimitive)?.content }.orEmpty())
    }
    var ok by remember(step) { mutableStateOf(step.ok?.toString().orEmpty()) }
    var stepsJson by remember(step) { mutableStateOf(stepListToJson(step.steps)) }
    var thenJson by remember(step) { mutableStateOf(stepListToJson(step.then)) }
    var elseJson by remember(step) { mutableStateOf(stepListToJson(step.elseSteps)) }
    var formError by remember(step) { mutableStateOf<String?>(null) }

    // 各字段按当前 op 是否相关来决定显示与写入，切换 op 不会丢掉原有值。
    val hasText = op in setOf("log", "waitText", "tapText", "assertText", "ifText", "swipeUntil")
    val hasMs = op == "wait"
    val hasPoint = op in setOf("tap", "longPress")
    val hasSwipe = op in setOf("swipe", "swipeUntil")
    val hasTemplate = op in setOf(
        "waitTemplate", "tapTemplate", "assertTemplate", "ifTemplate", "swipeUntil",
    )
    val hasRegion = op in setOf(
        "waitTemplate", "tapTemplate", "assertTemplate", "ifTemplate",
        "waitText", "tapText", "assertText", "ifText", "readNumber", "readText",
        "ifCount", "assertCount", "waitColor", "swipeUntil",
    )
    val hasTimeout = op in setOf(
        "waitTemplate", "tapTemplate", "waitText", "tapText", "readNumber",
        "waitColor", "swipeUntil",
    )
    val hasColor = op in setOf("ifCount", "assertCount", "waitColor")
    val hasVar = op in setOf("readNumber", "readText", "setVar", "ifExpr", "whileExpr")
    val hasStable = op == "waitTemplate"
    val hasOffset = op == "tapTemplate"
    val hasArea = op in setOf("ifCount", "assertCount", "waitColor")
    val hasCompare = op in setOf("ifCount", "assertCount")
    val hasRatio = op == "waitColor"
    val hasSwipeUntil = op == "swipeUntil"
    val hasRepeat = op == "repeat"
    val hasChildren = op in setOf("repeat", "whileExpr")
    val hasBranch = op in setOf("ifTemplate", "ifText", "ifExpr", "ifCount")
    val hasStop = op == "stop"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑步骤") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "坐标与 region 均为 0~1，region 格式为 x, y, w, h。切换动作后仅写入该动作相关字段。",
                    style = MaterialTheme.typography.bodySmall,
                )
                // 切换 op
                Box {
                    OutlinedButton(onClick = { opMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("动作 op：$op")
                    }
                    DropdownMenu(expanded = opMenu, onDismissRequest = { opMenu = false }) {
                        KNOWN_OPS.sorted().forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text(candidate) },
                                onClick = { op = candidate; opMenu = false },
                            )
                        }
                    }
                }
                SimpleField("note（说明本步点击的文字或用途）", note) { note = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = optional, onCheckedChange = { optional = it })
                    Text("optional：失败仅警告", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                // 快速索引：params 的 {key}、已出现变量名、框选 region，点一下插入当前字段。
                QuickIndexBar(
                    paramKeys = paramKeys,
                    variableNames = variableNames,
                    regionText = regionText,
                    hasExpr = hasVar,
                    hasText = hasText,
                    hasTo = hasVar,
                    hasRegion = hasRegion,
                    onInsertExpr = { expr = expr + it },
                    onInsertText = { text = text + it },
                    onInsertTo = { toVar = toVar + it },
                    onInsertRegion = { region = it },
                )
                if (hasText) SimpleField("text（文字）", text) { text = it }
                if (hasMs) SimpleField("ms（等待毫秒）", ms) { ms = it }
                if (hasPoint) {
                    SimpleField("x", x) { x = it }
                    SimpleField("y", y) { y = it }
                    SimpleField("durationMs", duration) { duration = it }
                    TextButton(onClick = onRepick) { Text("从实时画面重取 x / y") }
                }
                if (hasSwipe) {
                    SimpleField("fromX", fromX) { fromX = it }
                    SimpleField("fromY", fromY) { fromY = it }
                    SimpleField("toX", toX) { toX = it }
                    SimpleField("toY", toY) { toY = it }
                    SimpleField("durationMs", duration) { duration = it }
                }
                if (hasTemplate) {
                    Box {
                        OutlinedButton(onClick = { imageMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (image.isBlank()) "选择模板 image" else "模板：$image")
                        }
                        DropdownMenu(expanded = imageMenu, onDismissRequest = { imageMenu = false }) {
                            if (assetNames.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("（暂无模板，先到实时录制框选）") },
                                    onClick = { imageMenu = false },
                                )
                            } else {
                                assetNames.forEach { name ->
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = { image = name; imageMenu = false },
                                    )
                                }
                            }
                        }
                    }
                    SimpleField("image（模板路径）", image) { image = it }
                    SimpleField("threshold", threshold) { threshold = it }
                }
                if (hasRegion) {
                    SimpleField("region（x, y, w, h）", region) { region = it }
                    if (regionText.isNotBlank()) {
                        TextButton(onClick = {
                            region = regionText.removePrefix("[").removeSuffix("]")
                        }) { Text("填入最近一次纯框选 region（再点「应用」保存）") }
                    }
                }
                if (hasTimeout) SimpleField("timeoutMs", timeout) { timeout = it }
                if (hasColor) SimpleField("color（#RRGGBB）", color) { color = it }
                if (hasVar) {
                    SimpleField("to（变量名）", toVar) { toVar = it }
                    SimpleField("expr（表达式）", expr) { expr = it }
                }
                if (hasStable) SimpleField("stable（连续命中帧数）", stable) { stable = it }
                if (hasOffset) {
                    SimpleField("offsetX（点击中心 x 偏移）", offsetX) { offsetX = it }
                    SimpleField("offsetY（点击中心 y 偏移）", offsetY) { offsetY = it }
                }
                if (hasArea) {
                    SimpleField("tolerance（颜色容差 0~128）", tolerance) { tolerance = it }
                    SimpleField("minArea（连通块面积下限）", minArea) { minArea = it }
                    SimpleField("maxArea（连通块面积上限）", maxArea) { maxArea = it }
                }
                if (hasCompare) {
                    SimpleField("compare（>= > <= < == !=）", compare) { compare = it }
                    SimpleField("value（比较目标值，整数）", value) { value = it }
                }
                if (hasRatio) SimpleField("ratio（颜色占比 0~1）", ratio) { ratio = it }
                if (hasSwipeUntil) {
                    SimpleField("intervalMs（每次滑动后等待）", intervalMs) { intervalMs = it }
                    SimpleField("maxTries（最多滑动次数，上限 30）", maxTries) { maxTries = it }
                }
                if (hasRepeat) {
                    SimpleField("times（次数：整数或 {参数}）", times) { times = it }
                }
                if (hasStop) {
                    SimpleField("ok（true / false，留空 = 默认 true）", ok) { ok = it }
                }
                if (hasBranch || hasChildren) {
                    HorizontalDivider()
                    Text(
                        "分支 / 子步骤：填 JSON 数组，元素就是 PackStep（留空表示不写该分支）。" +
                            "可从「模板库」插入骨架，或复制现有步骤的 JSON 修改。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (hasChildren) StepListField("steps（子步骤数组）", stepsJson) { stepsJson = it }
                    if (hasBranch) {
                        StepListField("then（条件成立时执行）", thenJson) { thenJson = it }
                        StepListField("else（条件不成立时执行）", elseJson) { elseJson = it }
                    }
                }
                if (!(hasText || hasMs || hasPoint || hasSwipe || hasTemplate ||
                        hasRegion || hasTimeout || hasColor || hasVar || hasStable ||
                        hasOffset || hasArea || hasCompare || hasRatio || hasSwipeUntil ||
                        hasRepeat || hasChildren || hasBranch || hasStop)
                ) {
                    Text(
                        "此动作没有额外字段，只需 note / optional。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val updated = runCatching {
                    fun double(raw: String, label: String): Double? = when {
                        raw.isBlank() -> null
                        else -> raw.toDoubleOrNull() ?: throw IllegalArgumentException("$label 不是数字")
                    }
                    fun long(raw: String, label: String): Long? = when {
                        raw.isBlank() -> null
                        else -> raw.toLongOrNull() ?: throw IllegalArgumentException("$label 不是整数")
                    }
                    fun int(raw: String, label: String): Int? = when {
                        raw.isBlank() -> null
                        else -> raw.toIntOrNull() ?: throw IllegalArgumentException("$label 不是整数")
                    }
                    fun bool(raw: String, label: String): Boolean? = when {
                        raw.isBlank() -> null
                        raw.trim().equals("true", true) || raw.trim() == "1" -> true
                        raw.trim().equals("false", true) || raw.trim() == "0" -> false
                        else -> throw IllegalArgumentException("$label 只能是 true / false")
                    }
                    fun timesValue(raw: String): kotlinx.serialization.json.JsonElement? = when {
                        raw.isBlank() -> null
                        raw.trim().startsWith("{") -> JsonPrimitive(raw.trim())
                        else -> raw.trim().toLongOrNull()?.let { JsonPrimitive(it) }
                            ?: throw IllegalArgumentException("times 必须是整数或 {参数}")
                    }
                    fun stepList(raw: String, label: String): List<PackStep>? {
                        if (raw.isBlank()) return null
                        return runCatching {
                            STEP_LIST_JSON.decodeFromString(ListSerializer(PackStep.serializer()), raw)
                        }.getOrElse {
                            throw IllegalArgumentException("$label 不是合法的步骤数组：${it.message}")
                        }
                    }
                    val parsedRegion = if (hasRegion && region.isNotBlank()) {
                        region.split(',', '，').map { part ->
                            part.trim().toDoubleOrNull() ?: throw IllegalArgumentException("region 必须是数字")
                        }.also { require(it.size == 4) { "region 必须恰好四个数字" } }
                    } else {
                        step.region
                    }
                    val parsedSteps = if (hasChildren) stepList(stepsJson, "steps") else step.steps
                    val parsedThen = if (hasBranch) stepList(thenJson, "then") else step.then
                    val parsedElse = if (hasBranch) stepList(elseJson, "else") else step.elseSteps
                    step.copy(
                        op = op,
                        note = note,
                        optional = optional,
                        text = if (hasText) text.ifBlank { null } else step.text,
                        ms = if (hasMs) long(ms, "ms") else step.ms,
                        x = if (hasPoint) double(x, "x") else step.x,
                        y = if (hasPoint) double(y, "y") else step.y,
                        fromX = if (hasSwipe) double(fromX, "fromX") else step.fromX,
                        fromY = if (hasSwipe) double(fromY, "fromY") else step.fromY,
                        toX = if (hasSwipe) double(toX, "toX") else step.toX,
                        toY = if (hasSwipe) double(toY, "toY") else step.toY,
                        durationMs = if (hasPoint || hasSwipe) long(duration, "durationMs") else step.durationMs,
                        image = if (hasTemplate) image.ifBlank { null } else step.image,
                        threshold = if (hasTemplate) double(threshold, "threshold") else step.threshold,
                        region = parsedRegion,
                        timeoutMs = if (hasTimeout) long(timeout, "timeoutMs") else step.timeoutMs,
                        color = if (hasColor) color.ifBlank { null } else step.color,
                        to = if (hasVar) toVar.ifBlank { null } else step.to,
                        expr = if (hasVar) expr.ifBlank { null } else step.expr,
                        stable = if (hasStable) int(stable, "stable") else step.stable,
                        offsetX = if (hasOffset) double(offsetX, "offsetX") else step.offsetX,
                        offsetY = if (hasOffset) double(offsetY, "offsetY") else step.offsetY,
                        tolerance = if (hasArea) int(tolerance, "tolerance") else step.tolerance,
                        minArea = if (hasArea) int(minArea, "minArea") else step.minArea,
                        maxArea = if (hasArea) int(maxArea, "maxArea") else step.maxArea,
                        compare = if (hasCompare) compare.ifBlank { null } else step.compare,
                        value = if (hasCompare) int(value, "value") else step.value,
                        ratio = if (hasRatio) double(ratio, "ratio") else step.ratio,
                        intervalMs = if (hasSwipeUntil) long(intervalMs, "intervalMs") else step.intervalMs,
                        maxTries = if (hasSwipeUntil) int(maxTries, "maxTries") else step.maxTries,
                        times = if (hasRepeat) timesValue(times) else step.times,
                        ok = if (hasStop) bool(ok, "ok") else step.ok,
                        steps = parsedSteps,
                        then = parsedThen,
                        elseSteps = parsedElse,
                    )
                }.getOrElse {
                    formError = it.message
                    return@TextButton
                }
                onApply(updated)
            }) { Text("应用") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 步骤编辑时的快速索引：把 params 的 {key}、已有变量名、最近框选的 region
 * 一键插入 expr / text / to / region 字段，避免手抄 7268 个 setVar 里的变量名。
 */
@Composable
private fun QuickIndexBar(
    paramKeys: List<String>,
    variableNames: List<String>,
    regionText: String,
    hasExpr: Boolean,
    hasText: Boolean,
    hasTo: Boolean,
    hasRegion: Boolean,
    onInsertExpr: (String) -> Unit,
    onInsertText: (String) -> Unit,
    onInsertTo: (String) -> Unit,
    onInsertRegion: (String) -> Unit,
) {
    if (paramKeys.isEmpty() && variableNames.isEmpty() && regionText.isBlank()) return
    var target by remember {
        mutableStateOf(if (hasExpr) "expr" else if (hasText) "text" else "to")
    }
    val targets = buildList {
        if (hasExpr) add("expr" to "表达式")
        if (hasText) add("text" to "文字")
        if (hasTo) add("to" to "变量名")
    }
    if (targets.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("快速索引 →", style = MaterialTheme.typography.labelSmall)
            targets.forEach { (key, label) ->
                FilterChip(
                    selected = target == key,
                    onClick = { target = key },
                    label = { Text(label) },
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        }
        val sink: (String) -> Unit = when (target) {
            "text" -> onInsertText
            "to" -> onInsertTo
            else -> onInsertExpr
        }
        if (paramKeys.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                paramKeys.forEach { key ->
                    FilterChip(
                        selected = false,
                        onClick = { sink("{$key}") },
                        label = { Text("{$key}") },
                    )
                }
            }
        }
        if (variableNames.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                variableNames.take(30).forEach { v ->
                    FilterChip(selected = false, onClick = { sink(v) }, label = { Text(v) })
                }
            }
        }
        if (hasRegion && regionText.isNotBlank()) {
            OutlinedButton(onClick = { onInsertRegion(regionText.removePrefix("[").removeSuffix("]")) }) {
                Text("粘贴框选 region $regionText")
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun SimpleField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 分支/子步骤 JSON 编解码：与 task.json 用的是同一套 PackStep 结构，不新增字段。 */
private val STEP_LIST_JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }

private fun stepListToJson(list: List<PackStep>?): String = list?.let {
    STEP_LIST_JSON.encodeToString(ListSerializer(PackStep.serializer()), it)
}.orEmpty()

@Composable
private fun StepListField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        minLines = 2,
        maxLines = 6,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun PackStep.summary(): String = when (op) {
    "tap", "longPress" -> "x=$x, y=$y, durationMs=${durationMs ?: "默认"}"
    "swipe" -> "($fromX,$fromY) → ($toX,$toY)"
    "tapTemplate", "waitTemplate", "assertTemplate", "ifTemplate" -> "image=$image, region=$region"
    "tapText", "waitText", "assertText", "ifText" -> "text=$text, region=$region"
    "wait" -> "ms=$ms"
    "log" -> text.orEmpty()
    "repeat" -> "times=$times, ${steps?.size ?: 0} 个子步骤"
    else -> listOfNotNull(text, image, expr).joinToString(" · ")
}

// ==================== AI ====================

/**
 * 任务包开发规范配置：模型无法自己打开 URL，因此由 App 读取规范正文注入对话。
 * - 内置 docs/task-file.md 全文，离线可用、默认生效；
 * - 可添加/编辑/删除规范链接，点「从链接读取规范」拉取线上最新正文（GitHub blob 自动转 raw）；
 * - 拉取失败或链接清空时自动回退内置全文，保证生成始终带规范。
 */
@Composable
private fun SpecSection(viewModel: TaskPackDevViewModel, state: TaskPackDevState) {
    var newUrl by remember { mutableStateOf("") }
    Card {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("任务包开发规范（让 AI 先读）", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "AI 模型打不开链接，所以 App 会把规范正文放进对话。已内置 docs/task-file.md 全文，" +
                            "可加链接读取线上最新版；这样生成的任务包才不容易不符合规范。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.aiSpecInclude,
                    onCheckedChange = viewModel::updateAiSpecInclude,
                )
            }
            Text(
                "当前规范：${if (state.aiSpecFromBuiltIn) "内置全文" else "链接正文"} · " +
                    "${state.aiSpecCharCount} 字 · " +
                    (if (state.aiSpecInclude) "生成时随请求发送" else "已关闭（不发送）"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.aiSpecLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            Text("规范链接", style = MaterialTheme.typography.labelMedium)
            state.aiSpecUrls.forEachIndexed { index, url ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { viewModel.updateAiSpecUrl(index, it) },
                        label = { Text("链接 ${index + 1}") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { viewModel.removeAiSpecUrl(index) }) {
                        Icon(Icons.Default.Delete, contentDescription = "删除该规范链接")
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newUrl,
                    onValueChange = { newUrl = it },
                    placeholder = { Text("https://…/docs/task-file.md") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        viewModel.addAiSpecUrl(newUrl)
                        newUrl = ""
                    },
                    enabled = newUrl.isNotBlank(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = "添加规范链接")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = viewModel::refreshAiSpec,
                    enabled = !state.aiSpecLoading,
                    modifier = Modifier.weight(1f),
                ) { Text(if (state.aiSpecLoading) "读取中…" else "从链接读取规范") }
                OutlinedButton(
                    onClick = viewModel::resetAiSpecUrls,
                    modifier = Modifier.weight(1f),
                ) { Text("恢复默认") }
            }
            if (state.aiSpecStatus.isNotBlank()) {
                Text(
                    state.aiSpecStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AiTab(viewModel: TaskPackDevViewModel, state: TaskPackDevState) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("AI 全自动写任务包", style = MaterialTheme.typography.titleLarge)
        Text(
            "支持 OpenAI 兼容的 Chat Completions 服务。生成时会把《任务文件开发规范》全文发给 AI，避免写出不符合规范的包；" +
                "默认只把当前画面的 OCR 文字交给 AI，AI 主动索取时才发截图或检索内置模板库；" +
                "生成期间 AI 可自动点击游戏画面推进多层界面，你也可以随时点「打断生成」中止。" +
                "结果按原版规则校验，失败最多回喂修正三轮。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "顶栏可切「专注画面 / 分栏编辑」对照实时画面；画面控制条可切「等比完整 / 等比铺满」。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.aiBaseUrl,
            onValueChange = viewModel::updateAiBaseUrl,
            label = { Text("API 地址 / Base URL") },
            placeholder = { Text("https://api.openai.com/v1") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.aiApiKey,
            onValueChange = viewModel::updateAiApiKey,
            label = { Text("API Key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.aiModel,
            onValueChange = viewModel::updateAiModel,
            label = { Text("模型名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Key 只保存在本应用私有配置中，不写进任务包；AI 只在开发者工具进程联网，生成的任务包本身仍无网络能力。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 画面策略开关：默认只给 OCR 文字，允许 AI 主动索取截图。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("允许 AI 主动索取游戏画面", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (state.aiAllowScreenshot) {
                        "开启：AI 需要时才发截图；关闭：始终只发 OCR 文字，纯文本模型也能用"
                    } else {
                        "关闭：无论 AI 是否索取，都只提供 OCR 文字，不发任何图片"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.aiAllowScreenshot,
                onCheckedChange = viewModel::updateAiAllowScreenshot,
            )
        }
        // AI 边生成边自动点击：默认开启，模型可按画面自行决定坐标推进界面（不写进任务包）。
        Card {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("AI 生成时自动点击游戏画面", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "开启后 AI 写多层流程时会主动请求点击（点进下一层、开菜单、确认弹窗），" +
                                "系统真实点击游戏并把新画面的 OCR 文字回给它，最多 8 次。" +
                                "点击坐标由模型依据画面自行判断，只用于推进界面、**不会写进任务包**；" +
                                "包内坐标仍只能来自你录制或明确提供。生成期间你自己也可以直接点画面。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.aiAutoClick,
                        onCheckedChange = viewModel::setAiAutoClick,
                    )
                }
                Text(
                    "本轮自动点击 ${state.aiAutoClickCount} 次 · 用户提供的坐标 ${state.aiProvidedPoints.size} 个",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = viewModel::clearAiProvidedPoints) { Text("清空手动提供的坐标") }
                Card(
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Text(
                            "用户提供的坐标清单（可写进任务包）",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            viewModel.knownCoordinateText(),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.heightIn(max = 140.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
        // AI 从模板库按需索取：注入索引，模型输出标记后 App 检索并回喂确切路径。
        Card {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("允许 AI 从模板库找需要的", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "开启后会把整包模板、24 个步骤骨架和 ${state.iconTemplates.size} 个图标模板的索引发给 AI；" +
                                "AI 需要时输出标记索取，App 检索后回喂确切 assets 路径与字段写法，" +
                                "命中的图标会自动加入当前工程并随包导出。图标全部来自仓库现有素材。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.aiTemplateLookup,
                        onCheckedChange = viewModel::setAiTemplateLookup,
                    )
                }
                Text(
                    "本轮索取模板 ${state.aiTemplateHits} 次",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // 任务包开发规范：让 AI 真正「读到」规范正文（内置全文 + 可添加规范链接）
        SpecSection(viewModel, state)
        HorizontalDivider()
        OutlinedTextField(
            value = state.aiPrompt,
            onValueChange = viewModel::updateAiPrompt,
            label = { Text("任务目标") },
            placeholder = { Text("例如：从大厅进入邮件，点全部领取，确认后返回大厅。写清起点和结束条件。") },
            minLines = 5,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = viewModel::captureAiScreenText,
                enabled = !state.aiReadingScreen,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (state.aiReadingScreen) "识别中…" else "刷新画面文字")
            }
            OutlinedButton(
                onClick = viewModel::runOcrDiagnosis,
                modifier = Modifier.weight(1f),
            ) {
                Text("OCR 自检")
            }
        }
        if (state.ocrDiagnosis.isNotBlank()) {
            Card {
                Text(
                    state.ocrDiagnosis,
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (state.aiScreenText.isNotBlank()) {
            Card {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        "将作为默认上下文发给 AI 的画面文字：",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        state.aiScreenText,
                        modifier = Modifier.heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = viewModel::generateWithAi,
                enabled = !state.aiGenerating,
                modifier = Modifier.weight(1f),
            ) {
                if (state.aiGenerating) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (state.aiGenerating) "正在生成与校验…" else "生成 / 改写完整任务包")
            }
            if (state.aiGenerating) {
                OutlinedButton(
                    onClick = viewModel::cancelAiGeneration,
                    enabled = state.aiInterruptible,
                ) { Text("打断生成") }
            } else {
                OutlinedButton(onClick = viewModel::cancelAiGeneration, enabled = false) {
                    Text("打断生成")
                }
            }
        }
        if (state.aiGenerating) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                "生成中可随时点「打断生成」立即停止（会取消正在进行的模型请求），本轮结果将丢弃。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.aiStatus.isNotBlank()) {
            Text(state.aiStatus, style = MaterialTheme.typography.bodyMedium)
        }
        if (!state.aiGenerating && state.aiStatus.contains("通过原版校验")) {
            Text(
                if (state.aiScreenshotsUsed > 0) {
                    "本轮 AI 主动索取并发送了 ${state.aiScreenshotsUsed} 张游戏画面"
                } else {
                    "本轮 AI 未索取画面，全程仅依据 OCR 文字生成"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.aiPendingPack?.let { pending ->
            val beforeOps = state.steps.groupingBy { it.op }.eachCount()
            val afterOps = pending.steps.groupingBy { it.op }.eachCount()
            Card {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("生成结果待确认（未覆盖当前工程）", fontWeight = FontWeight.Bold)
                    Text("步骤：${state.steps.size} → ${pending.steps.size}；第 ${state.aiPendingAttempts} 轮通过原版校验")
                    Text("id=${pending.id} · version=${pending.version} · author=${pending.author}")
                    (beforeOps.keys + afterOps.keys).sorted().forEach { op ->
                        val before = beforeOps[op] ?: 0
                        val after = afterOps[op] ?: 0
                        if (before != after) Text("$op：$before → $after", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "生成结果 task.json（只读预览）",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    SelectionContainer {
                        Text(
                            viewModel.pendingAiJson(),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::applyPendingAi) { Text("应用并继续编辑") }
                        OutlinedButton(onClick = viewModel::discardPendingAi) { Text("放弃") }
                    }
                }
            }
        }
        Text(
            "约束：AI 不得新增 op 或字段；包内坐标只来自用户录制或明确提供，图标只能来自模板库索取到的真实路径。没有坐标时优先 tapText / waitText。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// ==================== 模板库 ====================

/**
 * 内置仓库全部模板 + 搜索索引：
 * - 整包模板：site/taskpacks/ 6 个 + examples/ 1 个 `.m7atask`，原样内置（含真实模板图与坐标）；
 * - 步骤模板：24 个 op 的字段骨架，坐标/模板/文字留空，必须由开发者填写；
 * - 图标模板：仓库 starrail 的 images 目录 410 个图标 + 整包模板里的 26 张模板图，
 *   原样内置、可预览、可一键插入当前工程（图标来自仓库，工具不生成任何图像）；
 * - 搜索栏：按包名、作者、描述、op、字段名、图标名/分类/中文场景词索引。
 */
/**
 * 静默录制的确认面板：停止录制后弹出，逐条列出后台采集到的动作 + 当时截图。
 *
 * 这里不做任何耗时操作（图已经在录制时异步压好），勾完点「生成步骤」一次性写入，
 * 只产生一条撤销记录。
 */
@Composable
private fun SilentReviewDialog(viewModel: TaskPackDevViewModel, state: TaskPackDevState) {
    val actions = state.silentActions
    val conflicts = state.silentConflictSeqs
    val picked = actions.count { it.included && it.seq !in conflicts }
    Dialog(
        onDismissRequest = viewModel::discardSilentActions,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // 横屏可用高度有限：列表按屏幕高度取一半，剩下的留给标题和底部按钮，
        // 保证「确认并生成」始终完整可见、不会被手势条盖住。
        BoxWithConstraints {
            val listMax = maxHeight * 0.5f
            Surface(
                shape = RoundedCornerShape(14.dp),
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth(0.94f),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("静默录制完成 · ${actions.size} 个动作", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "录制期间没有做任何 OCR / 截图同步操作，所以不影响手上操作；" +
                            "每条的截图和取词是后台补的。勾选要保留的，再统一生成步骤。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (conflicts.isNotEmpty()) {
                        Text(
                            "有 ${conflicts.size} 条与已有步骤重复（标「重复」），已自动取消勾选；" +
                                "确认无误就直接点下面的按钮。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { viewModel.setSilentIncludedAll(true) }) { Text("全选") }
                        TextButton(onClick = { viewModel.setSilentIncludedAll(false) }) { Text("全不选") }
                        Spacer(Modifier.weight(1f))
                        Text(
                            "已选 $picked / ${actions.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = listMax),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(actions, key = { it.seq }) { action ->
                            SilentActionRow(action, viewModel, action.seq in conflicts)
                        }
                    }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = viewModel::discardSilentActions,
                            modifier = Modifier.height(46.dp),
                        ) { Text("全部丢弃") }
                        Button(
                            enabled = picked > 0,
                            onClick = viewModel::applySilentActions,
                            modifier = Modifier.weight(1f).height(46.dp),
                        ) {
                            Text(
                                if (picked > 0) "确认并生成 $picked 步" else "请先勾选动作",
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 确认面板里的一行：截图 + 动作类型 + 坐标/时长 + 识别文字。 */
@Composable
private fun SilentActionRow(
    action: SilentAction,
    viewModel: TaskPackDevViewModel,
    conflict: Boolean = false,
) {
    var opMenu by remember { mutableStateOf(false) }
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(
                checked = action.included,
                onCheckedChange = { viewModel.setSilentIncluded(action.seq, it) },
                modifier = Modifier.size(32.dp),
            )
            val thumb = remember(action.thumb) {
                action.thumb?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            }
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                    .clip(RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (thumb != null) {
                    Image(
                        bitmap = thumb.asImageBitmap(),
                        contentDescription = "动作截图",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text("无图", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("#${action.seq}", style = MaterialTheme.typography.labelMedium)
                    if (conflict) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "重复",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.errorContainer,
                                    RoundedCornerShape(4.dp),
                                )
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Box {
                        TextButton(
                            onClick = { opMenu = true },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        ) {
                            Text("${action.op} ▾", style = MaterialTheme.typography.labelMedium)
                        }
                        DropdownMenu(expanded = opMenu, onDismissRequest = { opMenu = false }) {
                            listOf("tap" to "点击 tap", "longPress" to "长按 longPress", "swipe" to "滑动 swipe")
                                .forEach { (op, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            opMenu = false
                                            viewModel.setSilentOp(action.seq, op)
                                        },
                                    )
                                }
                        }
                    }
                }
                Text(
                    if (action.op == "swipe") {
                        "(${action.x.coordText()}, ${action.y.coordText()}) → " +
                            "(${action.toX.coordText()}, ${action.toY.coordText()}) · ${action.durationMs}ms"
                    } else {
                        "(${action.x.coordText()}, ${action.y.coordText()}) · ${action.durationMs}ms"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (action.text.isBlank()) "未取到文字（生成 click 坐标）" else "识别：${action.text}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (action.text.isBlank()) MaterialTheme.colorScheme.outline
                    else MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 坐标统一保留 3 位小数，和包里的归一化写法一致。 */
private fun Double.coordText(): String = String.format(Locale.US, "%.3f", this)

/** 工程命名请求：[fileId] 为空表示「另存为」，否则表示给已有工程改名。 */
private data class ProjectNameInput(
    val fileId: String?,
    val title: String,
    val initial: String,
)

/**
 * 工程面板：同时写多个任务包时，在这里切换 / 另存 / 重命名 / 删除。
 *
 * 每个工程是独立的 `.m7atask`（含步骤、params、assets），切换前当前工程会先落盘，
 * 所以来回切不会丢内容；已保存过的工程还会跟随编辑自动更新。
 */
@Composable
private fun ProjectDialog(
    viewModel: TaskPackDevViewModel,
    state: TaskPackDevState,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf<ProjectNameInput?>(null) }
    var pendingDelete by remember { mutableStateOf<TaskPackProjectSummary?>(null) }
    val current = state.projects.firstOrNull { it.fileId == state.currentProjectFileId }
    val formatter = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }
    fun stamp(at: Long): String = if (at <= 0) "未保存" else formatter.format(Date(at))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("工程（${state.projects.size}）") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "当前：${current?.displayName ?: "未保存的新工程"} · ${stamp(state.projectSavedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "每个工程单独存成 .m7atask（步骤 + 配置项 + 素材），切换前会自动保存，" +
                        "所以可以同时写多个包来回改。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.projects.isEmpty()) {
                    Text(
                        "还没有保存过工程。写完几步点「保存当前」，就会出现在这里。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.projects.forEach { item ->
                    val isCurrent = item.fileId == state.currentProjectFileId
                    Card {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { viewModel.openProject(item.fileId) },
                            ) {
                                Text(
                                    item.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "v${item.version} · ${item.stepCount} 步 · ${stamp(item.updatedAt)}" +
                                        if (isCurrent) " · 当前" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { viewModel.openProject(item.fileId) }) {
                                Text(if (isCurrent) "重新载入" else "打开", style = MaterialTheme.typography.labelMedium)
                            }
                            TextButton(onClick = {
                                input = ProjectNameInput(item.fileId, "重命名工程", item.displayName)
                            }) { Text("改名", style = MaterialTheme.typography.labelMedium) }
                            TextButton(onClick = { pendingDelete = item }) {
                                Text("删除", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { viewModel.saveProject() }) { Text("保存当前") }
                TextButton(onClick = {
                    input = ProjectNameInput(
                        fileId = null,
                        title = "另存为新工程",
                        initial = (current?.displayName ?: "新工程") + "-副本",
                    )
                }) { Text("另存为") }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    viewModel.newProject()
                    onDismiss()
                }) { Text("新建工程") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )

    input?.let { request ->
        var text by remember(request) { mutableStateOf(request.initial) }
        AlertDialog(
            onDismissRequest = { input = null },
            title = { Text(request.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("工程名（同时作为包名）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "「另存为」会基于当前内容复制一份新工程，原来的工程保持不动。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = text.isNotBlank(),
                    onClick = {
                        if (request.fileId == null) {
                            viewModel.saveProject(asNew = true, nameOverride = text)
                        } else {
                            viewModel.renameProject(request.fileId, text)
                        }
                        input = null
                    },
                ) { Text(if (request.fileId == null) "另存为" else "改名") }
            },
            dismissButton = { TextButton(onClick = { input = null }) { Text("取消") } },
        )
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除工程") },
            text = { Text("「${item.displayName}」会被删掉，步骤与素材都不保留，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteProject(item.fileId)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

/**
 * 模板库的「两行」布局：每类默认最多 2 行（[columns] 列 × 2 行），超出的先折叠。
 *
 * 外层是 verticalScroll 的 Column，不能嵌 LazyVerticalGrid（两层滚动会抢手势），
 * 所以这里手动 chunk 成 Row；expand 状态由调用方持有。
 */
@Composable
private fun <T> TwoRowGrid(
    items: List<T>,
    expanded: Boolean,
    columns: Int = 2,
    content: @Composable (T) -> Unit,
) {
    val limit = columns * 2
    val visible = if (expanded) items else items.take(limit)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        visible.chunked(columns).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { item ->
                    Box(modifier = Modifier.weight(1f)) { content(item) }
                }
                // 最后一行不满时用空位撑开，卡片宽度始终一致。
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun TemplateTab(viewModel: TaskPackDevViewModel, state: TaskPackDevState) {
    val query = state.templateQuery
    val category = state.templateCategory
    val allPacks = state.packTemplates
    val allSteps = TaskPackTemplateLibrary.STEP_TEMPLATES
    val allIcons = state.iconTemplates
    val packs = allPacks.filter { TaskPackTemplateLibrary.matches(query, it.searchText) }
    val steps = allSteps.filter { TaskPackTemplateLibrary.matches(query, it.searchText) }
    val characterIcons = allIcons.filter {
        com.march7th.hkrpg.dev.TaskPackCharacterNames.isCharacter(it.asset)
    }
    // 「角色」分类只看角色头像；「图标」分类排除角色避免重复。
    val icons = allIcons
        .filter { category != 4 || com.march7th.hkrpg.dev.TaskPackCharacterNames.isCharacter(it.asset) }
        .filter { category != 3 || !com.march7th.hkrpg.dev.TaskPackCharacterNames.isCharacter(it.asset) }
        .filter { TaskPackIconTemplates.matches(query, it) }
    val showPacks = category == 0 || category == 1
    val showSteps = category == 0 || category == 2
    val showIcons = category == 0 || category == 3 || category == 4
    // 图标有 400+ 个，分页渲染避免一次性解码全部缩略图拖慢列表。
    var iconLimit by remember(query, category) { mutableStateOf(8) }
    val visibleIcons = icons.take(iconLimit)
    // 每类默认只铺 2 行（2 列 × 2 行 = 4 个），点「展开更多」再看全部。
    var packsExpanded by remember(query, category) { mutableStateOf(false) }
    var stepsExpanded by remember(query, category) { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("模板库 · 项目全部模板", style = MaterialTheme.typography.titleLarge)
        Text(
            "整包模板来自仓库 site/taskpacks/ 与 examples/；步骤模板覆盖 docs/task-file.md 全部 24 个 op；" +
                "图标模板为仓库 starrail/images 下 410 个图标与整包内的 26 张模板图，均为真实素材、原样内置。" +
                "搜索可命中名称、分类、op、字段与中文场景词。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::setTemplateQuery,
            label = { Text("搜索：包名 / 场景 / op / 字段 / 图标名") },
            placeholder = { Text("例如 tapText、货币战争、关闭、领取、close_x、timeoutMs") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // 类型按钮排两行：窄屏上 5 个挤一行会压成「⋯」，分行后每个都能看清。
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = category == 0,
                onClick = { viewModel.setTemplateCategory(0) },
                label = { Text("全部") },
            )
            FilterChip(
                selected = category == 1,
                onClick = { viewModel.setTemplateCategory(1) },
                label = { Text("整包 ${allPacks.size}") },
            )
            FilterChip(
                selected = category == 2,
                onClick = { viewModel.setTemplateCategory(2) },
                label = { Text("步骤 ${allSteps.size}") },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = category == 3,
                onClick = { viewModel.setTemplateCategory(3) },
                label = { Text("图标 ${allIcons.size - characterIcons.size}") },
            )
            FilterChip(
                selected = category == 4,
                onClick = { viewModel.setTemplateCategory(4) },
                label = { Text("角色 ${characterIcons.size}") },
            )
        }
        if (category == 4) {
            Text(
                "角色头像已补官方中文索引，可直接搜「黄泉」「砂金」「流萤」「白厄」「遐蝶」「大黑塔」等；" +
                    "时装与命途变体同样可搜到。图标文件仍是仓库原图，仅追加搜索词。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showPacks) {
            Text(
                "整包模板（命中 ${packs.size} / ${allPacks.size}）",
                style = MaterialTheme.typography.titleMedium,
            )
            if (packs.isEmpty()) Text("没有匹配的整包模板", style = MaterialTheme.typography.bodySmall)
            TwoRowGrid(packs, packsExpanded) { entry -> PackTemplateCard(entry, viewModel) }
            if (packs.size > 4) {
                OutlinedButton(
                    onClick = { packsExpanded = !packsExpanded },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (packsExpanded) "收起（只显示 2 行）"
                        else "展开更多（还剩 ${packs.size - 4} 个）",
                    )
                }
            }
        }
        if (showSteps) {
            Text(
                "步骤模板 · 24 个 op（命中 ${steps.size} / ${allSteps.size}）",
                style = MaterialTheme.typography.titleMedium,
            )
            if (steps.isEmpty()) Text("没有匹配的步骤模板", style = MaterialTheme.typography.bodySmall)
            TwoRowGrid(steps, stepsExpanded) { template -> StepTemplateCard(template, viewModel) }
            if (steps.size > 4) {
                OutlinedButton(
                    onClick = { stepsExpanded = !stepsExpanded },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (stepsExpanded) "收起（只显示 2 行）"
                        else "展开更多（还剩 ${steps.size - 4} 个）",
                    )
                }
            }
        }
        if (showIcons) {
            Text(
                "图标模板（命中 ${icons.size} / ${allIcons.size}，显示 ${visibleIcons.size} 个）",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "图标与模板图都来自仓库现有资源。插入后原图会放进当前工程的 assets/，" +
                    "可直接导出给原版使用；不会由工具生成或改写图像。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (icons.isEmpty()) Text("没有匹配的图标模板", style = MaterialTheme.typography.bodySmall)
            // 图标是竖排小卡，一行放 4 个，两行共 8 个；再多点按钮续。
            TwoRowGrid(visibleIcons, expanded = true, columns = 4) { entry ->
                IconTemplateCard(entry, viewModel, compact = true)
            }
            if (icons.size > visibleIcons.size) {
                OutlinedButton(
                    onClick = { iconLimit += 8 },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("再显示 8 个（还剩 ${icons.size - visibleIcons.size} 个）") }
            }
        }
    }
}

@Composable
private fun IconTemplateCard(
    entry: IconTemplateEntry,
    viewModel: TaskPackDevViewModel,
    compact: Boolean = false,
) {
    val context = LocalContext.current
    val thumb = remember(entry.id) { TaskPackIconTemplates.thumbnail(context, entry) }
    var opMenu by remember { mutableStateOf(false) }
    // 两行网格里一行放 4 个，横排会挤成一条，所以紧凑态改竖排：图上、名在下。
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(if (compact) 6.dp else 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(if (compact) 44.dp else 56.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                    .clip(RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (thumb != null) {
                    Image(
                        bitmap = thumb,
                        contentDescription = entry.name,
                        modifier = Modifier.fillMaxSize().padding(3.dp),
                    )
                } else {
                    Text("PNG", style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                entry.name,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!compact) {
                Text(
                    entry.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    entry.packPath,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                TextButton(
                    onClick = { opMenu = true },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) { Text("插入", style = MaterialTheme.typography.labelSmall) }
                DropdownMenu(expanded = opMenu, onDismissRequest = { opMenu = false }) {
                    listOf(
                        "tapTemplate" to "点击模板 tapTemplate",
                        "waitTemplate" to "等待模板 waitTemplate",
                        "assertTemplate" to "断言模板 assertTemplate",
                        "ifTemplate" to "条件分支 ifTemplate",
                    ).forEach { (op, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                opMenu = false
                                viewModel.insertIconTemplate(entry, op)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PackTemplateCard(entry: PackTemplateEntry, viewModel: TaskPackDevViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                entry.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "v${entry.version} · ${entry.author}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                entry.description,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${entry.stepCount} 步 · ${entry.source}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = { viewModel.loadPackTemplate(entry, append = false) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) { Text("载入", style = MaterialTheme.typography.labelMedium) }
                OutlinedButton(
                    onClick = { viewModel.loadPackTemplate(entry, append = true) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) { Text("追加", style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

@Composable
private fun StepTemplateCard(template: StepTemplate, viewModel: TaskPackDevViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                template.op,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                template.title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                template.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "字段：${template.fields.joinToString(", ")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            OutlinedButton(
                onClick = { viewModel.insertStepTemplate(template) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) { Text("插入步骤", style = MaterialTheme.typography.labelMedium) }
        }
    }
}

// ==================== 导出 ====================

@Composable
private fun EnvironmentTab(viewModel: TaskPackDevViewModel, state: TaskPackDevState) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.refreshEnvironment()
        viewModel.autoRequestShizukuIfNeeded()
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("运行环境诊断", style = MaterialTheme.typography.titleLarge)
        Text(
            "实时录制依赖 Shizuku 的截图与触摸注入。Shizuku 按包名登记应用，换包名或签名后就是新应用，需重新授权；首次进入本页会自动发起一次授权请求。",
            style = MaterialTheme.typography.bodySmall,
        )
        Card {
            Column(modifier = Modifier.padding(12.dp)) {
                SelectionContainer {
                    Text(
                        text = state.environmentInfo.ifBlank { "正在读取…" },
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::refreshEnvironment) { Text("刷新状态") }
            OutlinedButton(
                onClick = { viewModel.requestShizukuPermission() },
                enabled = !state.shizukuRequesting,
            ) {
                Text(if (state.shizukuRequesting) "申请中…" else "重新申请权限")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.openShizuku(context) }) { Text("打开 Shizuku") }
            OutlinedButton(onClick = { viewModel.copyPackageName(context) }) { Text("复制包名") }
        }
        if (state.shizukuRequesting) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            "若打开 Shizuku 后，应用管理里仍没有上面显示的包名，说明手机上装的还是旧包（com.march7th.hkrpg）。请安装本次交付的新 APK 后再操作；旧文件名以 v1.0.5 结尾，请忽略并删除。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ExportTab(
    viewModel: TaskPackDevViewModel,
    state: TaskPackDevState,
    onExport: () -> Unit,
) {
    val errors = viewModel.validateCurrent()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("原版兼容校验与导出", style = MaterialTheme.typography.titleLarge)
        Card {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("文件：${viewModel.suggestedFileName()}")
                Text("id：${state.id}")
                Text("步骤：${state.steps.size} · 模板：${state.assets.size} · 参数：${state.params.size}")
                Text("schemaVersion：1 · minAppVersionCode：${state.minAppVersionCode}")
                Text(
                    "草稿自动保存；导出时只打包实际引用的模板，不写入 AI 配置",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (errors.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Color(0xFF2E7D32))
                Spacer(Modifier.width(6.dp))
                Text("已通过原版校验器，可导出给原项目使用")
            }
        } else {
            Card {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("还需修正 ${errors.size} 项：", fontWeight = FontWeight.Bold)
                    errors.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        Button(onClick = onExport, enabled = !state.exporting, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(if (state.exporting) "正在导出…" else "导出 .m7atask")
        }
        OutlinedButton(onClick = viewModel::installCurrent, modifier = Modifier.fillMaxWidth()) {
            Text("安装到本机任务列表（用于试跑）")
        }
        Text(
            "导出的 ZIP 根目录为 task.json，模板位于 assets/；没有新增字段、动作或执行接口，可直接在原版「设置 → 数据 → 导入任务包」中使用。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
