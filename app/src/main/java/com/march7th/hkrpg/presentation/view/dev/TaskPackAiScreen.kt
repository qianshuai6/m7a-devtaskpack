package com.march7th.hkrpg.presentation.view.dev

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.march7th.hkrpg.constant.Routes
import com.march7th.hkrpg.presentation.viewmodel.TaskPackDevState
import com.march7th.hkrpg.presentation.viewmodel.TaskPackDevViewModel
import org.koin.androidx.compose.koinViewModel

/**
 * AI 写任务包：独立竖屏页面。
 *
 * 原来它只是开发者页（强制横屏）里的一个 Tab，挤在右侧窄栏里；而写包这件事本质是
 * 「跟模型聊天 + 看着游戏画面」，横屏窄栏两头都不占。现在单独开一页：
 * 竖屏、界面像聊天软件（顶部标题栏 + 消息流 + 底部输入区），上方留一张 16:9 的画面卡片
 * 边点边看。任务包编辑仍然回到开发者页（横屏），两边共用同一个 ViewModel。
 */
@Composable
fun TaskPackAiScreen(navController: NavController) {
    // 与开发者页共用同一个 ViewModel：AI 页只是「换了个竖屏房子」，
    // 步骤、工程、对话都还在原实例里，来回切不会丢。
    val parentEntry = remember(navController.currentBackStackEntry) {
        runCatching { navController.getBackStackEntry(Routes.TASK_PACK_DEVELOPER) }.getOrNull()
    }
    val viewModel: TaskPackDevViewModel = if (parentEntry != null) {
        koinViewModel(viewModelStoreOwner = parentEntry)
    } else {
        koinViewModel()
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 竖屏：开发者页会把 Activity 锁成横屏，这一页进来先改回来，退出再交还给开发者页。
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(activity) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    var previewExpanded by rememberSaveable { mutableStateOf(true) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val chatList = rememberLazyListState()

    LaunchedEffect(state.aiChat.size, state.aiPendingPack, state.aiPendingQuestion) {
        val extra = if (state.aiPendingPack != null) 1 else 0
        val count = state.aiChat.size + extra
        if (count > 0) chatList.animateScrollToItem(count - 1)
    }

    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回任务包开发",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (state.aiGenerating) "正在写任务包" else "AI 写任务包",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            state.aiModel.ifBlank { "未配置模型" } +
                                if (state.aiChat.isNotEmpty()) " · 本轮 ${state.aiChat.size} 条" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "设置",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(
                        onClick = {
                            viewModel.startNewAiSession()
                            previewExpanded = true
                        },
                        enabled = !state.aiGenerating,
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "新会话",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            }
        },
        bottomBar = {
            AiBottomComposer(
                viewModel = viewModel,
                state = state,
                onOpenSettings = { showSettings = true },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            // 画面卡片：和「首页」那种卡片一个位置，16:9 圆角，右上角可收起。
            GamePreviewCard(
                viewModel = viewModel,
                state = state,
                expanded = previewExpanded,
                onToggle = { previewExpanded = !previewExpanded },
            )
            LazyColumn(
                state = chatList,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.aiChat.isEmpty() && state.aiPendingPack == null) {
                    item("welcome") { AiWelcome(viewModel) }
                }
                items(state.aiChat, key = { it.id }) { message -> AiChatBubble(message) }
                state.aiPendingPack?.let { pending ->
                    item("pending") { AiPendingResultCard(viewModel, state, pending) }
                }
            }
        }
    }

    if (showSettings) {
        Dialog(
            onDismissRequest = { showSettings = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(0.94f).fillMaxSize(0.9f),
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("AI 设置", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { showSettings = false }) {
                            Icon(Icons.Filled.Close, contentDescription = "关闭", modifier = Modifier.size(20.dp))
                        }
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AiSettingsPanel(viewModel, state)
                    }
                }
            }
        }
    }
}

/** 顶部画面卡片：16:9，可收起；收起后把高度让给对话。 */
@Composable
private fun GamePreviewCard(
    viewModel: TaskPackDevViewModel,
    state: TaskPackDevState,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (expanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black),
            ) {
                LiveRecorderView(viewModel, state, Modifier.fillMaxSize())
                // 右下角状态条：录制中 / 生成中一眼可见。
                val badge = when {
                    state.aiRecording -> "静默录制中 · ${state.aiRecorded.size} 条"
                    state.aiGenerating -> "AI 生成中"
                    else -> ""
                }
                if (badge.isNotBlank()) {
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(10.dp)
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                IconButton(
                    onClick = onToggle,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(32.dp),
                ) {
                    Icon(
                        Icons.Filled.ExpandLess,
                        contentDescription = "收起画面",
                        tint = Color.White,
                        modifier = Modifier
                            .size(20.dp)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                    )
                }
            }
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().clickable { onToggle() },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "游戏画面已收起",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Filled.ExpandMore,
                        contentDescription = "展开画面",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** 底部输入区：图一那种「一个大圆角框 + 功能 chips + 圆形发送」，键盘弹出自动上移。 */
@Composable
private fun AiBottomComposer(
    viewModel: TaskPackDevViewModel,
    state: TaskPackDevState,
    onOpenSettings: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (state.aiGenerating) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            // 功能 chips：写包时最常用的几个开关，不用进设置翻。
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = state.aiRecording,
                    onClick = {
                        if (state.aiRecording) viewModel.stopAiRecording() else viewModel.startAiRecording()
                    },
                    label = {
                        Text(
                            when {
                                state.aiRecording -> "录制中 ${state.aiRecorded.size}"
                                state.aiRecorded.isNotEmpty() -> "已录 ${state.aiRecorded.size} 条"
                                else -> "静默录制"
                            },
                        )
                    },
                )
                if (!state.aiRecording && state.aiRecorded.isNotEmpty()) {
                    TextButton(
                        onClick = { viewModel.completeRecordingWithAi() },
                        enabled = !state.aiGenerating && state.aiRecordBusy == 0,
                    ) { Text("让 AI 完善") }
                    TextButton(onClick = { viewModel.clearAiRecording() }) { Text("清空") }
                }
                FilterChip(
                    selected = state.aiAutoClick,
                    onClick = { viewModel.setAiAutoClick(!state.aiAutoClick) },
                    label = { Text("自动点击") },
                )
                FilterChip(
                    selected = state.aiAutoRecord,
                    onClick = { viewModel.setAiAutoRecord(!state.aiAutoRecord) },
                    label = { Text("边点边录") },
                )
                FilterChip(
                    selected = state.aiTemplateLookup,
                    onClick = { viewModel.setAiTemplateLookup(!state.aiTemplateLookup) },
                    label = { Text("模板库") },
                )
                FilterChip(
                    selected = state.aiAllowScreenshot,
                    onClick = { viewModel.updateAiAllowScreenshot(!state.aiAllowScreenshot) },
                    label = { Text("可看画面") },
                )
            }

            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.weight(1f),
                ) {
                    OutlinedTextField(
                        value = state.aiChatInput,
                        onValueChange = viewModel::updateAiChatInput,
                        placeholder = {
                            Text(
                                when {
                                    state.aiPendingQuestion != null -> "回答 AI 的问题…"
                                    state.aiGenerating -> "补充要求（发送即打断并重新生成）…"
                                    else -> "说目标，或继续追加要求…"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        maxLines = 5,
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .size(46.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "设置", modifier = Modifier.size(22.dp))
                }
                IconButton(
                    onClick = { viewModel.sendAiChat() },
                    enabled = state.aiChatInput.isNotBlank(),
                    modifier = Modifier
                        .size(46.dp)
                        .background(
                            if (state.aiChatInput.isNotBlank()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            CircleShape,
                        ),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (state.aiGenerating) "打断并重新生成" else "发送",
                        tint = if (state.aiChatInput.isNotBlank()) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        state.aiGenerating -> state.aiStatus.ifBlank { "正在生成…" }
                        state.aiStatus.isNotBlank() -> state.aiStatus
                        else -> "内容由 AI 生成，应用前请检查"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (state.aiGenerating) {
                    TextButton(onClick = { viewModel.cancelAiGeneration() }) { Text("打断") }
                } else if (state.aiPendingPack != null) {
                    TextButton(onClick = { viewModel.applyPendingAi() }) { Text("应用") }
                }
            }
        }
    }
}

private fun Context.findActivity(): android.app.Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is android.app.Activity) return current
        current = current.baseContext
    }
    return current as? android.app.Activity
}
