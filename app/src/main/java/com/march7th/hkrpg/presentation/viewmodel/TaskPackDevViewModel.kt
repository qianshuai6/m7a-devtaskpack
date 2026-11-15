package com.march7th.hkrpg.presentation.viewmodel

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.march7th.hkrpg.BuildConfig
import com.march7th.hkrpg.data.config.CorePathConfig
import com.march7th.hkrpg.data.model.CustomTaskPack
import com.march7th.hkrpg.data.model.PackParam
import com.march7th.hkrpg.data.model.PackStep
import com.march7th.hkrpg.data.resource.CustomTaskPackStore
import com.march7th.hkrpg.data.resource.CustomTaskPackValidator
import com.march7th.hkrpg.data.resource.TaskPackInstallResult
import com.march7th.hkrpg.dev.AiChat
import com.march7th.hkrpg.dev.AiChatKind
import com.march7th.hkrpg.dev.AiChatMessage
import com.march7th.hkrpg.dev.AiRecordedAction
import com.march7th.hkrpg.dev.AiRecording
import com.march7th.hkrpg.dev.AiTemplateHit
import com.march7th.hkrpg.dev.TaskPackAiAction
import com.march7th.hkrpg.dev.TaskPackAiClickResult
import com.march7th.hkrpg.dev.TaskPackAiClient
import com.march7th.hkrpg.dev.TaskPackAiConfig
import com.march7th.hkrpg.dev.TaskPackArchive
import com.march7th.hkrpg.dev.TaskPackDelay
import com.march7th.hkrpg.dev.TaskPackIssueRepair
import com.march7th.hkrpg.dev.TaskPackDisplay
import com.march7th.hkrpg.dev.TaskPackDraftStore
import com.march7th.hkrpg.dev.TaskPackIconMatcher
import com.march7th.hkrpg.dev.TaskPackProjectStore
import com.march7th.hkrpg.dev.TaskPackProjectSummary
import com.march7th.hkrpg.dev.DelayField
import com.march7th.hkrpg.dev.FlatStepNode
import com.march7th.hkrpg.dev.IconTemplateEntry
import com.march7th.hkrpg.dev.StepBranch
import com.march7th.hkrpg.dev.StepPath
import com.march7th.hkrpg.dev.label
import com.march7th.hkrpg.dev.TaskPackFrameSource
import com.march7th.hkrpg.dev.TaskPackIconTemplates
import com.march7th.hkrpg.dev.TaskPackStepTree
import com.march7th.hkrpg.dev.TaskPackNaming
import com.march7th.hkrpg.dev.TaskPackOcr
import com.march7th.hkrpg.dev.TaskPackProject
import com.march7th.hkrpg.dev.TaskPackSpec
import com.march7th.hkrpg.dev.TaskPackSpecDigest
import com.march7th.hkrpg.dev.TaskPackSpecResult
import com.march7th.hkrpg.dev.TaskPackTemplateLibrary
import com.march7th.hkrpg.dev.PackTemplateEntry
import com.march7th.hkrpg.dev.SilentGestureAnalyzer
import com.march7th.hkrpg.dev.StepTemplate
import com.march7th.hkrpg.dev.normCoordinate
import com.march7th.hkrpg.domain.service.TaskCompositionService
import com.march7th.hkrpg.manager.RemoteServiceManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import android.content.pm.PackageManager
import com.march7th.hkrpg.domain.models.RemoteBackend
import com.march7th.hkrpg.manager.RemoteAccessCoordinator
import com.march7th.hkrpg.manager.ShizukuManager
import rikka.shizuku.Shizuku
import timber.log.Timber

enum class DevRecordMode {
    /** 点击/长按/滑动自动识别（原有手势录制）。 */
    GESTURE,
    TAP_TEMPLATE,
    WAIT_TEMPLATE,
    OCR_TEXT,
    /** 纯框选：只算 region，不 OCR、不注入、不生成步骤。 */
    REGION_ONLY,
    /** 独立滑动：拖动只生成 swipe，代码与点击完全分开。 */
    SWIPE,
    /** 独立按钮：点击 / 滑动自动识别（与 GESTURE 行为一致，作为单独入口，不合并）。 */
    AUTO,

    /**
     * 自适应静默录制：边玩边记，全程不阻塞。
     *
     * 触摸事件只记几何与时序，OCR 与截图都丢到后台协程异步补，
     * 录制中不弹窗、不打断；停止后统一在确认面板里逐条过一遍再生成步骤。
     */
    SILENT,
}

/**
 * 画面缩放方式。虚拟屏恒为 1920×1080（16:9），预览必须按同一宽高比等比缩放，
 * 否则会被非等比拉伸。
 * - [FIT]：等比内接，完整画面 + 黑边，绝不变形；
 * - [CROP]：等比放大到铺满可用区域，超出部分裁掉，画面更大但边缘会被裁。
 */
enum class PreviewScaleMode { FIT, CROP }

/**
 * 静默采集到的一个动作。录制期间只填几何与时序，[thumb] 与 [text] 由后台协程稍后补上，
 * 确认面板里再决定每条生成什么步骤。
 */
data class SilentAction(
    val seq: Int,
    /** tap / longPress / swipe，可在确认面板里改。 */
    val op: String,
    val x: Double,
    val y: Double,
    val toX: Double = x,
    val toY: Double = y,
    val durationMs: Long = 0L,
    val startedAt: Long = 0L,
    /** 该点附近异步 OCR 到的文字，空表示没识别到。 */
    val text: String = "",
    /** 动作发生那一刻的缩略截图（JPEG），超出上限时为空。 */
    val thumb: ByteArray? = null,
    val included: Boolean = true,
    /** [SilentGestureAnalyzer.KIND_JOYSTICK] 表示这条是摇杆推杆（op 仍是 swipe）。 */
    val kind: String = "",
    /** 轨迹净位移（归一化）：面板里显示「推杆 6%」，也用于判断是不是摇杆。 */
    val travel: Double = 0.0,
)

/** 拉框 OCR 文字：框选区域后识别出的候选文字，等用户挑一个动作再落地成步骤。 */
data class OcrRegionDraft(
    val region: List<Double>,
    val lines: List<String>,
    val text: String,
)

/**
 * 录制点击时默认采用哪种识别方式。
 * - [TEXT_OCR]：默认。OCR 取到文字就写原版已有的 `tapText`，不写坐标、不用模板；
 *   取不到文字才回退到用户点的坐标 `tap`（坐标仍来自用户，不臆造）。
 * - [COORDINATE]：始终写坐标 `tap`，文字仍写进 note。
 * - [TEMPLATE]：走已有的框选模板流程（[DevRecordMode.TAP_TEMPLATE] / [WAIT_TEMPLATE]）。
 */
enum class DevClickMode { TEXT_OCR, COORDINATE, TEMPLATE }

/**
 * 画面基准固定 1920×1080（16:9）。v1.0.13 曾跟随真机物理分辨率，
 * 实测会造成虚拟屏反复 restart、dpi 跳变、画面异常，故固定回原项目基准。
 */
const val PREVIEW_BASE_WIDTH = 1920
const val PREVIEW_BASE_HEIGHT = 1080

/** 顶栏提示内容：重取坐标 / 纯框选 region 都显示到「任务包开发者」那一块。 */
data class TopHint(
    val text: String,
    /** 可复制到剪贴板的值（如 region JSON）；为空表示纯提示。 */
    val copyValue: String = "",
    val kind: TopHintKind = TopHintKind.INFO,
)

enum class TopHintKind { INFO, COORDINATE, REGION }

/** 步骤树每页渲染的顶层步骤数，避免 10548 步一次性构造。 */
const val STEP_PAGE_SIZE = 60

/** 静默录制最多保留多少个动作：再多就不截图了，避免长会话把内存吃满。 */
const val MAX_SILENT_ACTIONS = 400

/** 静默截图的源尺寸（只用于缩略图与点位取词，不必用全分辨率）。 */
const val SILENT_THUMB_SOURCE = 720

/** 同一次触摸的多次回调合并窗口（毫秒）：窗口内同点的重复上报只更新一条。 */
const val SILENT_MERGE_MS = 350L

/** 判定「同一位置」的归一化阈值（约 1920 宽下的 12px）。 */
const val SILENT_SAME_SPOT = 0.006

data class TaskPackDevState(
    val id: String = "dev.recording",
    val name: String = "",
    val author: String = "",
    val packVersion: String = "1.0",
    val gameVersion: String = "",
    val description: String = "",
    val minAppVersionCode: Int = 11,
    val params: List<PackParam> = emptyList(),
    val steps: List<PackStep> = emptyList(),
    val assets: Map<String, ByteArray> = emptyMap(),
    val selectedStepIndex: Int? = null,
    // ---- 自适应静默录制 ----
    /** 静默采集到、尚未确认生成的动作。 */
    val silentActions: List<SilentAction> = emptyList(),
    /** 停止录制后弹出的确认面板。 */
    val silentReviewVisible: Boolean = false,
    /** 连续同向滑动是否合并成一条（手滑一次列表、微抬再补一下时最有用）。 */
    val silentMergeSwipes: Boolean = true,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val recording: Boolean = false,
    val recordNotes: Boolean = true,
    val recordIntervals: Boolean = false,
    val mode: DevRecordMode = DevRecordMode.GESTURE,
    val exporting: Boolean = false,
    val importing: Boolean = false,
    val draftSavedAt: Long = 0L,
    // ---- 多工程 ----
    /** 已保存的工程列表（按更新时间倒序）。 */
    val projects: List<TaskPackProjectSummary> = emptyList(),
    /** 当前编辑的工程存档 id；为空表示还没存过（首次「保存工程」时创建）。 */
    val currentProjectFileId: String = "",
    /** 当前工程最后一次保存时间。 */
    val projectSavedAt: Long = 0L,
    val message: String? = null,
    /** 顶栏「任务包开发者」旁的提示：重取坐标 / 纯框选 region。 */
    val topHint: TopHint? = null,
    /** 真实画面基准（跟随真机物理分辨率，横屏长边×短边）。 */
    val previewWidth: Int = PREVIEW_BASE_WIDTH,
    val previewHeight: Int = PREVIEW_BASE_HEIGHT,
    /** 真·全屏画面：隐藏顶栏/Tab/工具条，画面铺满整屏，右上角一个 X 退出。 */
    val trueFullscreen: Boolean = false,
    /** 纯框选最近一次输出的 region 文本，便于反复复制/写入选中步骤。 */
    val lastRegionText: String = "",
    // ---- 步骤树 / 大包编辑 ----
    /** 已展开的节点路径 key。 */
    val expandedPaths: Set<String> = emptySet(),
    val stepQuery: String = "",
    val stepOpFilter: String = "",
    /** 顶层已加载页数（分页渲染大包）。 */
    val stepPages: Int = 1,
    /** 步骤列表滚动目标（[StepPath.key]）：搜索命中后「跳到该步」用，滚完由 UI 清空。 */
    val pendingScrollKey: String = "",
    /** 静默确认面板里与已有步骤重复的动作序号，默认不勾选并标「重复」。 */
    val silentConflictSeqs: Set<Int> = emptySet(),
    val aiPrompt: String = "",
    val aiBaseUrl: String = "",
    val aiApiKey: String = "",
    val aiModel: String = "",
    val aiScreenText: String = "",
    /** 生成时是否把《任务文件开发规范》正文注入对话，让模型按规范输出。 */
    val aiSpecInclude: Boolean = true,
    /** 规范文档链接；App 生成前拉取正文，失败自动回退内置全文。 */
    val aiSpecUrls: List<String> = emptyList(),
    val aiSpecLoading: Boolean = false,
    val aiSpecStatus: String = "",
    val aiSpecCharCount: Int = 0,
    val aiSpecFromBuiltIn: Boolean = true,
    /** 是否允许 AI 主动索取游戏画面截图；关闭后只提供 OCR 文字。 */
    val aiAllowScreenshot: Boolean = true,
    val aiGenerating: Boolean = false,
    val aiReadingScreen: Boolean = false,
    val aiStatus: String = "",
    val aiPendingPack: CustomTaskPack? = null,
    val aiPendingAttempts: Int = 0,
    val aiScreenshotsUsed: Int = 0,
    /** 正在为哪一步从实时画面重取坐标；非空时录制页只取点、不注入、不录手势。 */
    val coordinatePickTarget: Int? = null,
    /** 是否处于「从画面重取坐标」状态（顶层 index 与树路径两种入口共用）。 */
    val coordinatePickActive: Boolean = false,
    // ---- 录制默认文字 OCR / 1920×1080 基准 / 非录制态点击 ----
    val recordClickMode: DevClickMode = DevClickMode.TEXT_OCR,
    /** 非录制状态（含 AI 生成中）也把点击注入游戏，只是不写进步骤。 */
    val idleTouchEnabled: Boolean = true,
    // ---- 模板库 ----
    val templateQuery: String = "",
    val templateCategory: Int = 0,
    val packTemplates: List<PackTemplateEntry> = emptyList(),
    /** 图标模板：仓库 410 个图标 + 整包模板里的 26 张模板图，带搜索与预览。 */
    val iconTemplates: List<IconTemplateEntry> = emptyList(),
    // ---- 画面缩放 ----
    val previewScaleMode: PreviewScaleMode = PreviewScaleMode.FIT,
    // ---- 拉框 OCR 文字 ----
    val ocrRegionDraft: OcrRegionDraft? = null,
    /** 「画面定位」选中的步骤（可为任意层级），只在左侧画面叠加标记，不写入包。 */
    val focusedStep: PackStep? = null,
    /** 定位提示，如 `#3>1 tapText`。 */
    val focusedLabel: String = "",
    val ocrRegionBusy: Boolean = false,
    // ---- AI 生成 ----
    /** 生成任务；非空表示可打断。 */
    val aiInterruptible: Boolean = false,
    /** 本轮 AI 从模板库索取的次数。 */
    val aiTemplateHits: Int = 0,
    /** 是否允许 AI 按需索取模板库（索引 + 标记）。 */
    val aiTemplateLookup: Boolean = true,
    // ---- AI 边生成边自动点击 ----
    val aiAutoClick: Boolean = true,
    val aiAutoClickCount: Int = 0,
    /** 点击成功后是否直接录成步骤（「玩家不插手」模式靠它把点击留成任务包）。 */
    val aiAutoRecord: Boolean = true,
    /** 本轮 AI 自动录进任务包的步骤数。 */
    val aiRecordedSteps: Int = 0,
    /** 本轮 token 用量摘要（含缓存命中率），用于确认前缀缓存是否真的生效。 */
    val aiUsageText: String = "",
    /** 用户本轮手动点过、或 AI 按文字点击命中的坐标；仅作参考与去重用。 */
    val aiProvidedPoints: Set<Pair<Double, Double>> = emptySet(),
    // ==================== AI 对话记录 / 主动观察 / 静默录制 ====================
    /** 生成过程按聊天记录展示：目标 → 思考 → 说明 → 动作 → 提问 → 结果。 */
    val aiChat: List<AiChatMessage> = emptyList(),
    /** 对话输入框：生成中作为补充说明，等待提问时作为回答。 */
    val aiChatInput: String = "",
    /** 模型提问、等待用户回答的问题；非空时输入框变成「回答」。 */
    val aiPendingQuestion: String? = null,
    /** 画面变化时是否主动把新 OCR 文字推给 AI。 */
    val aiWatchScreen: Boolean = true,
    /** 用户自己点游戏时，是否把「点了什么」也告诉 AI。 */
    val aiAcceptUserGesture: Boolean = true,
    /** 最近一次主动观察到的画面文字（界面上显示，便于确认观察链路是否工作）。 */
    val aiLiveObservation: String = "",
    /** AI 生成期间的静默录制：录制点击、OCR 候选与模板匹配候选。 */
    val aiRecording: Boolean = false,
    val aiRecorded: List<AiRecordedAction> = emptyList(),
    /** 后台正在补 OCR / 模板匹配的条数。 */
    val aiRecordBusy: Int = 0,
    /** 本轮模型提问 / 主动观察次数。 */
    val aiAsksUsed: Int = 0,
    val aiObservationsUsed: Int = 0,
    val ocrDiagnosis: String = "",
    val environmentInfo: String = "",
    val shizukuRequesting: Boolean = false,
)

/** 任务包开发者版的录制、编辑、AI 生成与导出。 */
@OptIn(FlowPreview::class)
class TaskPackDevViewModel(
    private val app: Application,
    pathConfig: CorePathConfig,
    private val packStore: CustomTaskPackStore,
    private val compositionService: TaskCompositionService,
    private val httpClient: OkHttpClient,
) : ViewModel() {
    private val frameSource = TaskPackFrameSource(pathConfig)
    private val ocr = TaskPackOcr(app)
    private val aiClient = TaskPackAiClient(httpClient)
    private val aiPrefs = app.getSharedPreferences("taskpack_dev_ai", Context.MODE_PRIVATE)

    /** 录制相关偏好（与 AI 配置分开，免得清 AI 设置时把录制习惯一起清掉）。 */
    private val recordPrefs = app.getSharedPreferences("taskpack_dev_record", Context.MODE_PRIVATE)
    private val draftStore = TaskPackDraftStore(
        File(app.filesDir, "taskpack-dev"), BuildConfig.VERSION_CODE,
    )

    /**
     * 工程文件的落盘根目录。
     *
     * 放在外部私有目录 `Android/data/<包名>/files/Maa/` 下：不用申请存储权限，
     * 但文件管理器和数据线都能直接看到、拷走，和主项目 MAA 的目录习惯一致。
     * 外部存储不可用（极少数情况）时退回内部 `files/taskpack-dev/`。
     */
    private val projectRootDir: File = runCatching {
        app.getExternalFilesDir(null)?.let { File(it, "Maa") }
    }.getOrNull() ?: File(app.filesDir, "taskpack-dev")

    /** 多工程存档：一个工程一个 .m7atask，可在列表里快速切换。 */
    private val projectStore = TaskPackProjectStore(
        projectRootDir,
        BuildConfig.VERSION_CODE,
        legacyRootDir = File(app.filesDir, "taskpack-dev"),
    )

    /** 工程目录，给工程面板显示「文件存在哪」。 */
    fun projectDirectory(): String = projectStore.directoryPath
    private var surface: Surface? = null
    private var lastActionAt = 0L
    private var assetSequence = 0
    private var shizukuAutoRequested = false
    /** 当前 AI 生成协程，供「打断生成」取消；同时由客户端取消底层 HTTP 请求。 */
    private var aiJob: Job? = null
    /** 画面监视协程：画面一变就把新 OCR 文字推给模型。 */
    private var watchJob: Job? = null
    /** 模型提问时挂起的等待：complete 后生成继续，null 表示跳过。 */
    private var questionDeferred: CompletableDeferred<String?>? = null
    /** 待送给模型的主动观察（画面变化 / 用户手动点击），由客户端每轮取空。 */
    private val observationQueue = java.util.ArrayDeque<String>()
    private val observationLock = Any()
    /** App 自己刚点过的时间点：这之后的画面变化是它造成的，不再重复上报。 */
    @Volatile
    private var lastSelfActionAt = 0L
    private val iconMatcher = TaskPackIconMatcher(app) { _state.value.iconTemplates }
    /** 规范正文缓存：同一组链接只拉一次，避免每次生成都联网；链接变化或手动刷新时失效。 */
    @Volatile
    private var cachedSpec: TaskPackSpecResult? = null
    private var cachedSpecKey: String = ""
    private val undoStack = ArrayDeque<StepSnapshot>()
    private val redoStack = ArrayDeque<StepSnapshot>()

    private data class StepSnapshot(
        val steps: List<PackStep>,
        val assets: Map<String, ByteArray>,
    )

    private val _state = MutableStateFlow(
        TaskPackDevState(
            author = aiPrefs.getString("author", "").orEmpty(),
            aiBaseUrl = aiPrefs.getString("baseUrl", "").orEmpty(),
            aiApiKey = aiPrefs.getString("apiKey", "").orEmpty(),
            aiModel = aiPrefs.getString("model", "").orEmpty(),
            aiAllowScreenshot = aiPrefs.getBoolean("allowScreenshot", true),
            aiSpecInclude = aiPrefs.getBoolean("specInclude", true),
            // AI 自动点击默认开启：这是「AI 生成时能推进游戏界面」的主开关，
            // 之前默认关闭导致真机上完全不触发。写入包内的坐标仍只由用户提供。
            aiAutoClick = aiPrefs.getBoolean("autoClick", true),
            aiAutoRecord = aiPrefs.getBoolean("autoRecord", true),
            aiTemplateLookup = aiPrefs.getBoolean("templateLookup", true),
            // 画面一变就主动把新 OCR 推给模型 / 用户自己点游戏也告诉模型：两者默认开启，
            // 这正是「模型看不见屏幕」最有效的补偿。
            aiWatchScreen = aiPrefs.getBoolean("watchScreen", true),
            aiAcceptUserGesture = aiPrefs.getBoolean("userGesture", true),
            aiSpecUrls = loadSpecUrls(),
            silentMergeSwipes = recordPrefs.getBoolean("mergeSwipes", true),
        )
    )
    val state: StateFlow<TaskPackDevState> = _state.asStateFlow()

    init {
        refreshEnvironment()
        refreshProjects()
        // 内置模板库索引：仓库 site/taskpacks + examples 的全部 .m7atask 原样打包。
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) {
                runCatching { TaskPackTemplateLibrary.loadPackEntries(app) }.getOrDefault(emptyList())
            }
            _state.update { it.copy(packTemplates = entries) }
        }
        // 图标模板库：仓库全部图标 + 整包模板里的模板图，统一索引供搜索与 AI 索取。
        viewModelScope.launch {
            val icons = withContext(Dispatchers.IO) {
                runCatching { TaskPackIconTemplates.load(app) }.getOrDefault(emptyList())
            }
            _state.update { it.copy(iconTemplates = icons) }
        }
        // 先把内置规范全文的字数读出来展示；联网刷新由用户手动触发。
        viewModelScope.launch {
            val len = withContext(Dispatchers.IO) { TaskPackSpec.builtIn(app).length }
            if (len > 0) _state.update { it.copy(aiSpecCharCount = len, aiSpecFromBuiltIn = true) }
        }
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { draftStore.load() }
            if (saved != null) restoreProject(saved, recordHistory = false, message = "已恢复上次未导出的草稿")
            // 草稿只保存原版任务包内容；AI 配置和网络状态不会进入文件。
            state
                .map(::projectFromState)
                .distinctUntilChanged()
                .debounce(800L)
                .collectLatest { project ->
                    val savedAt = withContext(Dispatchers.IO) {
                        runCatching { draftStore.save(project) }.getOrNull()?.let { System.currentTimeMillis() }
                    }
                    if (savedAt != null) _state.update { it.copy(draftSavedAt = savedAt) }
                    // 已经存过的工程跟着自动更新：多开几个包时不会「改完忘了点保存」。
                    // 写盘在 IO 线程，且只在有工程 id 时才写（新建未保存的工程不自动落盘）。
                    val projectId = _state.value.currentProjectFileId
                    if (projectId.isNotBlank()) {
                        val summary = withContext(Dispatchers.IO) {
                            runCatching { projectStore.save(projectId, project) }.getOrNull()
                        }
                        if (summary != null) _state.update { it.copy(projectSavedAt = summary.updatedAt) }
                    }
                }
        }
    }

    // ==================== 运行环境诊断 ====================

    fun refreshEnvironment() {
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { buildEnvironmentInfo() }
            _state.update { it.copy(environmentInfo = info) }
        }
    }

    /**
     * 进入环境页时自动补一次授权。Shizuku 服务端只在应用进程首次活跃时投递 binder，
     * 且「应用管理」列表只登记已投递过的应用；新包名的应用不会自动出现在列表里。
     * 因此在 binder 已连接而未授权时主动发起请求，让 Shizuku 弹窗完成登记与授权，
     * 用户不必先去列表里找本应用。每次 ViewModel 生命周期只自动尝试一次。
     */
    fun autoRequestShizukuIfNeeded() {
        if (shizukuAutoRequested) return
        shizukuAutoRequested = true
        val connected = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val granted = connected && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!connected || granted) return
        requestShizukuPermission(silent = true)
    }

    /** 直接打开 Shizuku 应用，便于在「应用管理」中核对本包名并手动授权。 */
    fun openShizuku(context: Context) {
        val intent = runCatching {
            context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        }.getOrNull()
        if (intent == null) {
            _state.update {
                it.copy(message = "未找到 Shizuku 应用（moe.shizuku.privileged.api），请先安装并启动 Shizuku 服务。")
            }
            return
        }
        runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onFailure {
            _state.update { s -> s.copy(message = "无法打开 Shizuku：${it.message}") }
        }
    }

    fun copyPackageName(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("packageName", app.packageName))
        _state.update { it.copy(message = "已复制包名：${app.packageName}，可在 Shizuku 授权列表中核对。") }
    }

    fun requestShizukuPermission(silent: Boolean = false) {
        if (_state.value.shizukuRequesting) return
        _state.update { it.copy(shizukuRequesting = true) }
        viewModelScope.launch {
            val granted = withContext(Dispatchers.IO) {
                runCatching { RemoteAccessCoordinator.request(RemoteBackend.SHIZUKU) }
                    .onFailure { Timber.w(it, "request shizuku permission failed") }
                    .getOrDefault(false)
            }
            val info = withContext(Dispatchers.IO) { buildEnvironmentInfo() }
            _state.update {
                it.copy(
                    shizukuRequesting = false,
                    environmentInfo = info,
                    message = if (silent) it.message else if (granted) {
                        "Shizuku 权限已获得，可以开始实时录制"
                    } else {
                        "未获得 Shizuku 权限。请对照下方诊断处理：若服务未连接，需让本应用重新被 Shizuku 登记；若已连接，请在授权弹窗中允许「${app.packageName}」。"
                    },
                )
            }
        }
    }

    private fun buildEnvironmentInfo(): String = buildList {
        val pkg = app.packageName
        val permission = "moe.shizuku.manager.permission.API_V23"
        add("安装包名：$pkg")
        add("在 Shizuku 中应显示：任务包开发者 / $pkg")
        add("应用版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        val declared = runCatching {
            app.packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
                .requestedPermissions?.contains(permission) == true
        }.getOrDefault(false)
        add("API_V23 声明：${if (declared) "已声明" else "缺失（异常，请重新安装包）"}")
        val available = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        add("Shizuku 服务：${if (available) "已连接（binder 已送达）" else "未连接（binder 未送达）"}")
        val granted = available && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        add("授权状态：${if (granted) "已授权" else "未授权"}")
        add("Shizuku 版本：${runCatching { Shizuku.getVersion() }.getOrNull() ?: "未知"}")
        add("运行 UID：${runCatching { Shizuku.getUid() }.getOrNull() ?: "未知"}")
        add("Sui：${if (ShizukuManager.isSui) "是" else "否"}")
        add("当前后端：${RemoteAccessCoordinator.configuredBackend()}")
        add("")
        when {
            !declared -> add("诊断：本包未声明 Shizuku 权限，无法使用 Shizuku，请重新完整安装本 APK。")
            !available -> {
                add("诊断：Shizuku 尚未把服务投递给本应用（换包名后首次使用最常见）。")
                add("① 确认 Shizuku 首页显示「正在运行」（截图里是 adb 模式）；")
                add("② 完全退出本应用，或到系统设置里「强制停止」，再重新打开；")
                add("   这会让 Shizuku 重新向本应用投递服务并登记到授权列表；")
                add("③ 仍显示未连接：在 Shizuku 里停止服务并重新启动，再重开本应用；")
                add("④ 变为「已连接」后，点「重新申请 Shizuku 权限」并在弹窗中允许。")
            }
            !granted -> {
                add("诊断：服务已连接，但本应用尚未获得授权。")
                add("点「重新申请 Shizuku 权限」并在弹窗中允许；")
                add("或点「打开 Shizuku」，在应用管理里找到 $pkg 并开启开关。")
            }
            else -> add("诊断：环境正常，可开始实时录制。")
        }
    }.joinToString("\n")

    private fun projectFromState(value: TaskPackDevState): TaskPackProject = TaskPackProject(
        pack = CustomTaskPack(
            schemaVersion = 1,
            id = value.id.trim(),
            name = value.name.trim(),
            author = value.author.trim(),
            version = TaskPackNaming.version(value.packVersion, value.gameVersion),
            description = value.description.trim(),
            minAppVersionCode = value.minAppVersionCode,
            params = value.params,
            steps = value.steps,
        ),
        assets = value.assets,
    )

    // ==================== 实时预览与输入 ====================

    fun onSurfaceAvailable(value: Surface) {
        surface = value
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { RemoteServiceManager.getInstanceOrNull()?.setMonitorSurface(value) }
                .onFailure { Timber.w(it, "developer preview surface failed") }
        }
    }

    fun onSurfaceDestroyed() {
        val old = surface
        surface = null
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { RemoteServiceManager.getInstanceOrNull()?.setMonitorSurface(null) }
            runCatching { old?.release() }
        }
    }

    suspend fun captureFrame(): Bitmap? = frameSource.capture()

    /** AI 页实时预览用：抓一帧缩放图，不占用全局 monitor surface（避免和录制页抢画面）。 */
    suspend fun captureAiPreview(): Bitmap? = frameSource.captureScaled(1280)

    suspend fun touchDown(x: Double, y: Double) = sendTouch("down", x, y)
    suspend fun touchMove(x: Double, y: Double) = sendTouch("move", x, y)
    suspend fun touchUp(x: Double, y: Double) = sendTouch("up", x, y)

    suspend fun touchCancel() = withContext(Dispatchers.IO) {
        runCatching { RemoteServiceManager.getInstanceOrNull()?.touchCancel() }
    }

    private suspend fun sendTouch(type: String, nx: Double, ny: Double) = withContext(Dispatchers.IO) {
        val service = RemoteServiceManager.getInstanceOrNull() ?: return@withContext
        val resolution = compositionService.displayResolution.value
        val x = (nx * resolution.width).toInt().coerceIn(0, resolution.width - 1)
        val y = (ny * resolution.height).toInt().coerceIn(0, resolution.height - 1)
        runCatching {
            when (type) {
                "down" -> service.touchDown(x, y, 0)
                "move" -> service.touchMove(x, y, 0)
                else -> service.touchUp(x, y, 0)
            }
        }.onFailure { Timber.w(it, "developer touch $type failed") }
    }

    fun setRecording(enabled: Boolean) {
        _state.update { it.copy(recording = enabled) }
        lastActionAt = 0L
        if (!enabled) {
            viewModelScope.launch { touchCancel() }
            // 静默录制停止：有采集就弹确认面板，逐条看过再决定生成什么。
            val current = _state.value
            if (current.mode == DevRecordMode.SILENT && current.silentActions.isNotEmpty()) {
                // 与已有步骤重复的动作预先标出来并取消勾选，避免同一操作被写两遍。
                val conflicts = findSilentConflicts(current.silentActions, current.steps)
                _state.update {
                    it.copy(
                        silentReviewVisible = true,
                        silentConflictSeqs = conflicts,
                        silentActions = it.silentActions.map { action ->
                            if (action.seq in conflicts) action.copy(included = false) else action
                        },
                    )
                }
            }
        }
    }

    fun setMode(mode: DevRecordMode) = _state.update {
        it.copy(
            mode = mode,
            // 只有框选模板 / 拉框 OCR 才需要 TEMPLATE 标记；手势类（含独立滑动、
        // 点击滑动自识别）保留当前识别方式，纯框选不写步骤也不注入。
            recordClickMode = when (mode) {
                DevRecordMode.TAP_TEMPLATE, DevRecordMode.WAIT_TEMPLATE,
                DevRecordMode.OCR_TEXT -> DevClickMode.TEMPLATE
                else -> it.recordClickMode
            },
        )
    }
    fun setRecordNotes(enabled: Boolean) = _state.update { it.copy(recordNotes = enabled) }
    fun setRecordIntervals(enabled: Boolean) = _state.update { it.copy(recordIntervals = enabled) }

    /** 默认文字 OCR：识别到文字就写 tapText，未识别到才回退用户点击的坐标。 */
    fun setRecordClickMode(mode: DevClickMode) = _state.update {
        it.copy(
            recordClickMode = mode,
            mode = if (mode == DevClickMode.TEMPLATE) DevRecordMode.TAP_TEMPLATE else DevRecordMode.GESTURE,
        )
    }

    fun setIdleTouchEnabled(enabled: Boolean) = _state.update { it.copy(idleTouchEnabled = enabled) }

    // ==================== 画面基准 / 真·全屏 / 顶栏提示 ====================

    /**
     * 进入开发者页时确保虚拟屏是 **1920×1080 / dpi 240**。
     *
     * v1.0.13 曾把虚拟屏切成真机物理分辨率（如 2712×1220 / dpi 480），
     * 实测会让游戏在 1920×1080 与 2712×1220 之间反复 restart、dpi 跳变，
     * 表现为画面异常。用户要的「全屏」是 1920×1080 画面居中、左右黑边放功能栏，
     * 所以这里固定回原项目基准，且 `VirtualDisplayManager.setResolution` 对相同值
     * 是幂等的（不触发 restart），不会带来额外抖动。
     */
    fun ensureDefaultResolution() {
        _state.update {
            it.copy(previewWidth = PREVIEW_BASE_WIDTH, previewHeight = PREVIEW_BASE_HEIGHT)
        }
        compositionService.restoreDefaultResolution()
    }

    fun setTrueFullscreen(enabled: Boolean) = _state.update { it.copy(trueFullscreen = enabled) }

    fun dismissTopHint() = _state.update { it.copy(topHint = null) }

    /** 把文本写进顶栏「任务包开发者」那块（重取坐标、region 输出等）。 */
    fun setTopHint(text: String, copyValue: String = "", kind: TopHintKind = TopHintKind.INFO) =
        _state.update { it.copy(topHint = TopHint(text, copyValue, kind)) }

    fun copyTopHintToClipboard() {
        val value = _state.value.topHint?.copyValue.orEmpty()
        if (value.isBlank()) return
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("taskpack", value))
        showMessage("已复制：$value")
    }

    // ==================== 纯框选 region ====================

    /**
     * 纯框选：只把框选区域换算成归一化 [x, y, w, h] 输出到顶栏，
     * **不 OCR、不注入、不生成步骤**。这个 region 值给后续步骤（ifCount / tapText 等）用。
     */
    fun emitRegion(region: List<Double>) {
        if (region.size != 4) return
        val norm = region.map { normCoordinate(it) }
        val text = TaskPackDisplay.regionText(norm)
        _state.update {
            it.copy(
                lastRegionText = text,
                topHint = TopHint(
                    text = "框选 region = $text（x, y, w, h，归一化 0~1）",
                    copyValue = text,
                    kind = TopHintKind.REGION,
                ),
            )
        }
    }

    /** 把最近一次纯框选的 region 写入指定步骤（任意嵌套路径）。 */
    fun applyRegionToPath(path: StepPath) {
        val text = _state.value.lastRegionText
        val values = text.trim().trim('[', ']').split(',').mapNotNull { it.trim().toDoubleOrNull() }
        if (values.size != 4) {
            showMessage("还没有可用的框选 region")
            return
        }
        val step = TaskPackStepTree.stepAt(_state.value.steps, path) ?: return
        mutateSteps { current ->
            current.copy(steps = TaskPackStepTree.replaceAt(current.steps, path, step.copy(region = values)))
        }
        showMessage("已把框选 region 写入该步骤")
    }

    // ==================== 步骤树 / 大包编辑 ====================

    /** 供 UI 渲染的扁平化节点列表（只展开已展开节点 + 顶层分页）。 */
    fun flatSteps(): List<FlatStepNode> {
        val s = _state.value
        val filtering = s.stepQuery.isNotBlank() || s.stepOpFilter.isNotBlank()
        // 搜索/筛选要覆盖整包（10548 步也要能定位到），不分页；仅全量浏览才分页。
        val topRange = if (filtering) {
            0 until Int.MAX_VALUE
        } else {
            0 until (s.stepPages * STEP_PAGE_SIZE)
        }
        return TaskPackStepTree.flatten(
            steps = s.steps,
            expanded = s.expandedPaths,
            query = s.stepQuery,
            opFilter = s.stepOpFilter,
            topRange = topRange,
        )
    }

    /** 顶层步骤总数（分页用）。 */
    fun topLevelCount(): Int = _state.value.steps.size

    fun toggleExpand(path: StepPath) {
        val key = path.key
        _state.update {
            val next = it.expandedPaths.toMutableSet()
            if (!next.add(key)) next.remove(key)
            it.copy(expandedPaths = next)
        }
    }

    fun expandAllToDepth(maxDepth: Int) {
        val keys = LinkedHashSet<String>()
        fun walk(list: List<PackStep>, parent: StepPath?, depth: Int, branch: StepBranch) {
            if (depth > maxDepth) return
            list.forEachIndexed { i, step ->
                val p = if (parent == null) StepPath.root(i) else parent.child(i, branch)
                val kids = listOfNotNull(step.then, step.elseSteps, step.steps)
                if (kids.any { it.isNotEmpty() }) keys += p.key
                step.then?.let { walk(it, p, depth + 1, StepBranch.THEN) }
                step.elseSteps?.let { walk(it, p, depth + 1, StepBranch.ELSE) }
                step.steps?.let { walk(it, p, depth + 1, StepBranch.STEPS) }
            }
        }
        walk(_state.value.steps, null, 0, StepBranch.ROOT)
        if (maxDepth == 0) keys.clear()
        _state.update { it.copy(expandedPaths = keys) }
    }

    fun setStepQuery(value: String) = _state.update { it.copy(stepQuery = value, stepPages = 1) }
    fun setStepOpFilter(value: String) = _state.update { it.copy(stepOpFilter = value, stepPages = 1) }
    fun loadMoreSteps() = _state.update { it.copy(stepPages = it.stepPages + 1) }

    /** UI 完成滚动后清掉目标，避免旋转屏幕又跳一次。 */
    fun clearPendingScroll() = _state.update {
        if (it.pendingScrollKey.isEmpty()) it else it.copy(pendingScrollKey = "")
    }

    /**
     * 从搜索结果直接跳到那一步：清掉搜索与 op 筛选、展开沿途所有祖先、
     * 保证该顶层步骤所在的分页已加载，然后把目标路径交给 UI 滚动并高亮。
     */
    fun revealStep(path: StepPath) {
        if (path.parts.isEmpty()) return
        val ancestors = LinkedHashSet<String>()
        var cursor = path.parent
        while (cursor != null) {
            ancestors += cursor.key
            cursor = cursor.parent
        }
        val rootIndex = path.parts.first().index
        _state.update { current ->
            current.copy(
                stepQuery = "",
                stepOpFilter = "",
                stepPages = maxOf(current.stepPages, rootIndex / STEP_PAGE_SIZE + 1),
                expandedPaths = current.expandedPaths + ancestors,
                selectedStepIndex = rootIndex.takeIf { it in current.steps.indices },
                pendingScrollKey = path.key,
            )
        }
        clearFocus()
    }

    /** 各 op 出现次数，供「按 op 筛选」下拉展示。 */
    fun opCounts(): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        fun walk(list: List<PackStep>) {
            list.forEach { s ->
                counts[s.op] = (counts[s.op] ?: 0) + 1
                s.then?.let(::walk); s.elseSteps?.let(::walk); s.steps?.let(::walk)
            }
        }
        walk(_state.value.steps)
        return counts.toSortedMap()
    }

    /** 变量名（setVar.to / expr 标识符 / {param}）用于步骤编辑时快速索引。 */
    fun variableNames(): List<String> = TaskPackStepTree.collectVariables(_state.value.steps)

    fun replaceStepAt(path: StepPath, step: PackStep) = mutateSteps { current ->
        current.copy(steps = TaskPackStepTree.replaceAt(current.steps, path, step))
    }

    fun removeStepAt(path: StepPath) = mutateSteps { current ->
        current.copy(steps = TaskPackStepTree.removeAt(current.steps, path))
    }

    fun duplicateStepAt(path: StepPath) {
        val step = TaskPackStepTree.stepAt(_state.value.steps, path) ?: return
        mutateSteps { current ->
            current.copy(steps = TaskPackStepTree.insertAfter(current.steps, path, step))
        }
    }

    fun insertStepAfterPath(path: StepPath, step: PackStep) = mutateSteps { current ->
        current.copy(steps = TaskPackStepTree.insertAfter(current.steps, path, step))
    }

    fun moveStepAt(path: StepPath, delta: Int) = mutateSteps { current ->
        current.copy(steps = TaskPackStepTree.moveAt(current.steps, path, delta))
    }

    fun appendChildStep(parentPath: StepPath, branch: StepBranch, step: PackStep) = mutateSteps { current ->
        current.copy(steps = TaskPackStepTree.appendChild(current.steps, parentPath, branch, step))
    }

    fun renameVariableEverywhere(old: String, new: String) = mutateSteps { current ->
        current.copy(steps = TaskPackStepTree.renameVariable(current.steps, old, new))
    }

    /** 从任意路径重取坐标：写回 x/y（tap 类）或 fromX/fromY（swipe 起点）。 */
    fun startPickCoordinateAt(path: StepPath) {
        if (TaskPackStepTree.stepAt(_state.value.steps, path) == null) return
        pickCoordinatePath = path
        _state.update {
            it.copy(
                coordinatePickActive = true,
                coordinatePickTarget = null,
                topHint = TopHint(
                    "重取坐标：在画面点一下写入该步骤（不操作游戏、不录制）",
                    kind = TopHintKind.COORDINATE,
                ),
            )
        }
    }

    /** 非空的路径表示「按树路径重取坐标」；旧的 index 版仍保留兼容顶层。 */
    private var pickCoordinatePath: StepPath? = null

    fun applyPickedCoordinateToPath(nx: Double, ny: Double) {
        val path = pickCoordinatePath
        if (path == null) {
            applyPickedCoordinate(nx, ny)
            return
        }
        val x = normCoordinate(nx)
        val y = normCoordinate(ny)
        val step = TaskPackStepTree.stepAt(_state.value.steps, path) ?: return
        val updated = if (step.op == "swipe" || step.op == "swipeUntil") {
            step.copy(fromX = x, fromY = y)
        } else {
            step.copy(x = x, y = y)
        }
        mutateSteps { current ->
            current.copy(steps = TaskPackStepTree.replaceAt(current.steps, path, updated))
        }
        pickCoordinatePath = null
        _state.update {
            it.copy(
                coordinatePickActive = false,
                topHint = TopHint("已把该步骤坐标更新为 ($x, $y)", kind = TopHintKind.COORDINATE),
            )
        }
    }

    fun cancelPickCoordinatePath() {
        pickCoordinatePath = null
        _state.update { it.copy(coordinatePickActive = false, topHint = null) }
    }

    fun setTemplateQuery(value: String) = _state.update { it.copy(templateQuery = value) }
    fun setTemplateCategory(value: Int) = _state.update { it.copy(templateCategory = value) }

    fun setAiAutoClick(enabled: Boolean) {
        _state.update { it.copy(aiAutoClick = enabled) }
        aiPrefs.edit().putBoolean("autoClick", enabled).apply()
    }

    fun setAiAutoRecord(enabled: Boolean) {
        _state.update { it.copy(aiAutoRecord = enabled) }
        aiPrefs.edit().putBoolean("autoRecord", enabled).apply()
    }

    /** 清掉本轮 AI 边点边录写进来的步骤（点错界面时用）。 */
    fun clearAiRecordedSteps() {
        val recorded = _state.value.aiRecordedSteps
        if (recorded <= 0) return
        mutateSteps { current ->
            var steps = current.steps
            repeat(recorded) {
                val idx = steps.indexOfLast { it.note?.contains("（AI 边点边录）") == true }
                if (idx >= 0) steps = steps.toMutableList().also { it.removeAt(idx) }
            }
            current.copy(steps = steps, selectedStepIndex = null)
        }
        _state.update { it.copy(aiRecordedSteps = 0) }
    }

    fun clearAiProvidedPoints() = _state.update { it.copy(aiProvidedPoints = emptySet()) }

    /**
     * 录下一个真实手势。截图来自按下之前，因此 note 描述的是被点的旧界面，
     * 而不是点击后的新界面。调用方按手势顺序串行调用本函数。
     */
    suspend fun recordGesture(
        frame: Bitmap?,
        startX: Double,
        startY: Double,
        endX: Double,
        endY: Double,
        moved: Boolean,
        durationMs: Long,
        actionAt: Long,
        track: List<Pair<Double, Double>> = emptyList(),
    ) {
        if (!_state.value.recording) {
            frame?.recycle()
            return
        }
        // 静默模式：只记几何与时序，截图 / OCR 全部异步补，手势处理链不等待。
        if (_state.value.mode == DevRecordMode.SILENT) {
            frame?.recycle()
            recordSilentGesture(
                startX = startX, startY = startY, endX = endX, endY = endY,
                moved = moved, durationMs = durationMs, actionAt = actionAt,
                track = track,
            )
            return
        }
        try {
            val sx = normCoordinate(startX)
            val sy = normCoordinate(startY)
            val ex = normCoordinate(endX)
            val ey = normCoordinate(endY)
            val current = _state.value
            // 独立「滑动」模式：无论拖动距离多少都只生成 swipe，绝不落成点击。
            val forceSwipe = current.mode == DevRecordMode.SWIPE
            // 「点击/滑动自识别」是独立入口：按手势几何自动判定 tap/longPress/swipe，
            // 一律写坐标、不走文字 OCR（需要 tapText 时用「文字 OCR」入口）。
            val autoDetect = current.mode == DevRecordMode.AUTO
            val wantTextOcr = !forceSwipe && !autoDetect &&
                current.recordClickMode == DevClickMode.TEXT_OCR
            // 文字 OCR 是默认识别方式：即使关掉了「文字写入 note」，仍要取词来生成 tapText。
            val text = if ((current.recordNotes || wantTextOcr) && frame != null) {
                ocr.textAt(frame, sx, sy)
            } else {
                null
            }
            val step = when {
                moved || forceSwipe -> PackStep(
                    op = "swipe",
                    fromX = sx,
                    fromY = sy,
                    toX = ex,
                    toY = ey,
                    durationMs = durationMs.coerceIn(80, 10_000),
                    note = text?.let { "从文字「$it」开始滑动" }
                        ?: "滑动 (${coord(sx)}, ${coord(sy)}) → (${coord(ex)}, ${coord(ey)})",
                )
                durationMs >= LONG_PRESS_MS -> PackStep(
                    op = "longPress",
                    x = sx,
                    y = sy,
                    durationMs = durationMs.coerceAtLeast(LONG_PRESS_MS),
                    note = text?.let { "长按文字「$it」" }
                        ?: "长按坐标 (${coord(sx)}, ${coord(sy)})",
                )
                // 默认文字 OCR：识别到文字就写原版已有的 tapText，不写坐标、不用模板。
                wantTextOcr && !text.isNullOrBlank() -> PackStep(
                    op = "tapText",
                    text = text,
                    timeoutMs = 10_000,
                    note = "点击文字「$text」（录制时 OCR 取词）",
                )
                else -> PackStep(
                    op = "tap",
                    x = sx,
                    y = sy,
                    durationMs = durationMs.coerceIn(40, LONG_PRESS_MS - 1),
                    note = text?.let { "点击文字「$it」" }
                        ?: if (wantTextOcr) {
                            "未识别到文字，已回退到用户点击的坐标 (${coord(sx)}, ${coord(sy)})"
                        } else {
                            "点击坐标 (${coord(sx)}, ${coord(sy)})；未识别到文本"
                        },
                )
            }
            appendRecorded(step, actionAt)
        } finally {
            frame?.recycle()
        }
    }

    // ==================== 自适应静默录制 ====================

    /**
     * 记录一次静默动作，并异步补截图与取词。
     *
     * 关键点：**主调用链只做一次列表追加**，不截图、不 OCR、不写文件 ——
     * 用户此刻正在正常玩游戏，任何同步的重活都会让操作发黏。
     */
    private fun recordSilentGesture(
        startX: Double,
        startY: Double,
        endX: Double,
        endY: Double,
        moved: Boolean,
        durationMs: Long,
        actionAt: Long,
        track: List<Pair<Double, Double>> = emptyList(),
    ) {
        val sx = normCoordinate(startX)
        val sy = normCoordinate(startY)
        val ex = normCoordinate(endX)
        val ey = normCoordinate(endY)
        // 有轨迹就按轨迹判定：摇杆（推出去又收回）不能被录成零长度滑动，
        // 滑动的终点也不能跟着抬起前的回拉走。没有轨迹（老路径 / 单点）退回起终点判断。
        val gesture = if (track.size >= 2) {
            SilentGestureAnalyzer.analyze(track, durationMs)
        } else {
            SilentGestureAnalyzer.analyze(
                listOf(sx to sy, ex to ey),
                durationMs,
            )
        }
        val op = gesture.op
        if (_state.value.silentActions.size >= MAX_SILENT_ACTIONS) return

        // 连续同向滑动合并：手滑一次列表、微抬再补一下会记成两条，合起来才是用户想滑的距离。
        if (_state.value.silentMergeSwipes && op == "swipe") {
            val last = _state.value.silentActions.lastOrNull()
            if (last != null && last.op == "swipe") {
                val prevGesture = SilentGestureAnalyzer.Gesture(
                    op = last.op,
                    kind = last.kind,
                    x = last.x,
                    y = last.y,
                    toX = last.toX,
                    toY = last.toY,
                    durationMs = last.durationMs,
                    travel = last.travel,
                )
                val gap = actionAt - (last.startedAt + last.durationMs)
                val merged = SilentGestureAnalyzer.mergeSwipes(prevGesture, gesture, gap)
                if (merged != null) {
                    _state.update { current ->
                        current.copy(
                            silentActions = current.silentActions.map {
                                if (it.seq == last.seq) {
                                    it.copy(
                                        toX = merged.toX,
                                        toY = merged.toY,
                                        durationMs = merged.durationMs,
                                        travel = merged.travel,
                                    )
                                } else {
                                    it
                                }
                            },
                        )
                    }
                    return
                }
            }
        }

        // 同一次触摸可能回调多次（按下 / 抬起 / 长按判定），坐标几乎不变的短时间重复上报
        // 只更新那一条的时长与类型，避免确认面板里出现互相冲突的两条。
        val recent = _state.value.silentActions.lastOrNull()
        val duplicated = recent != null && recent.op != "swipe" && op != "swipe" &&
            actionAt - recent.startedAt in 0..SILENT_MERGE_MS &&
            kotlin.math.abs(recent.x - gesture.x) < SILENT_SAME_SPOT &&
            kotlin.math.abs(recent.y - gesture.y) < SILENT_SAME_SPOT
        if (duplicated && recent != null) {
            _state.update { current ->
                current.copy(
                    silentActions = current.silentActions.map {
                        if (it.seq == recent.seq) {
                            it.copy(
                                op = if (gesture.durationMs > it.durationMs) op else it.op,
                                durationMs = maxOf(it.durationMs, gesture.durationMs.coerceIn(0, 10_000)),
                                kind = if (gesture.kind.isNotBlank()) gesture.kind else it.kind,
                            )
                        } else {
                            it
                        }
                    },
                )
            }
            return
        }
        val seq = (_state.value.silentActions.maxOfOrNull(SilentAction::seq) ?: 0) + 1
        _state.update {
            it.copy(
                silentActions = it.silentActions + SilentAction(
                    seq = seq, op = op,
                    x = gesture.x, y = gesture.y,
                    toX = gesture.toX, toY = gesture.toY,
                    durationMs = gesture.durationMs.coerceIn(0, 10_000),
                    startedAt = actionAt,
                    kind = gesture.kind,
                    travel = gesture.travel,
                ),
            )
        }
        // 后台补图与取词：失败就留空，不影响这一条动作本身。
        viewModelScope.launch(Dispatchers.IO) {
            val frame = runCatching { frameSource.captureScaled(SILENT_THUMB_SOURCE) }.getOrNull()
                ?: return@launch
            val thumb = runCatching {
                ByteArrayOutputStream().use { out ->
                    frame.compress(Bitmap.CompressFormat.JPEG, 72, out)
                    out.toByteArray()
                }
            }.getOrNull()
            val text = if (op == "swipe") {
                ""
            } else {
                runCatching { ocr.textAt(frame, gesture.x, gesture.y) }.getOrNull().orEmpty()
            }
            frame.recycle()
            _state.update { current ->
                current.copy(
                    silentActions = current.silentActions.map {
                        if (it.seq == seq) it.copy(thumb = thumb, text = text) else it
                    },
                )
            }
        }
    }

    /** 连续同向滑动是否合并（默认开）。关掉后每一次抬手都单独生成一条 swipe。 */
    fun setSilentMergeSwipes(enabled: Boolean) {
        _state.update { it.copy(silentMergeSwipes = enabled) }
        recordPrefs.edit().putBoolean("mergeSwipes", enabled).apply()
    }

    fun setSilentIncluded(seq: Int, included: Boolean) = _state.update { current ->
        current.copy(
            silentActions = current.silentActions.map {
                if (it.seq == seq) it.copy(included = included) else it
            },
        )
    }

    fun setSilentIncludedAll(included: Boolean) = _state.update { current ->
        current.copy(
            silentActions = current.silentActions.map {
                // 全选也不选「与已有步骤重复」的项，避免一键把重复步骤全加进去。
                if (included && it.seq in current.silentConflictSeqs) it.copy(included = false)
                else it.copy(included = included)
            },
        )
    }

    fun setSilentOp(seq: Int, op: String) = _state.update { current ->
        current.copy(
            silentActions = current.silentActions.map {
                // 手动改类型后，摇杆标记就不成立了（用户可能把它当普通滑动/长按用）。
                if (it.seq == seq) it.copy(op = op, kind = "") else it
            },
        )
    }

    /** 确认面板里「丢弃」：整批清掉，不写入任何步骤。 */
    fun discardSilentActions() = _state.update {
        it.copy(silentActions = emptyList(), silentReviewVisible = false, silentConflictSeqs = emptySet())
    }

    /**
     * 找出与已有步骤重复的静默动作：同一坐标的点击 / 同样的文字 / 同起终点的滑动。
     *
     * 边玩边记时很容易把「进菜单又退回」这类操作重复录进来；面板里先标成「重复」并
     * 默认不勾选，用户看过一眼再决定要不要强行加。
     */
    private fun findSilentConflicts(
        actions: List<SilentAction>,
        steps: List<PackStep>,
    ): Set<Int> {
        val flat = mutableListOf<PackStep>()
        fun walk(list: List<PackStep>) {
            list.forEach { step ->
                flat += step
                step.then?.let(::walk)
                step.elseSteps?.let(::walk)
                step.steps?.let(::walk)
            }
        }
        walk(steps)
        if (flat.isEmpty()) return emptySet()
        return actions.filter { action ->
            flat.any { step ->
                when (action.op) {
                    "swipe" -> step.op == "swipe" &&
                        near(step.fromX, action.x) && near(step.fromY, action.y) &&
                        near(step.toX, action.toX) && near(step.toY, action.toY)
                    "longPress" -> step.op == "longPress" && near(step.x, action.x) && near(step.y, action.y)
                    else -> when {
                        action.text.isNotBlank() && step.op == "tapText" -> step.text == action.text
                        step.op == "tap" -> near(step.x, action.x) && near(step.y, action.y)
                        else -> false
                    }
                }
            }
        }.mapTo(LinkedHashSet()) { it.seq }
    }

    private fun near(a: Double?, b: Double): Boolean =
        a != null && kotlin.math.abs(a - b) < SILENT_SAME_SPOT

    /**
     * 把勾选的动作一次性生成步骤（只产生一条撤销记录）。
     *
     * 规则沿用普通录制：识别到文字的点击写原版 `tapText`（不写坐标），
     * 识别不到才写 `tap`；长按写 `longPress`，拖动写 `swipe`。
     */
    fun applySilentActions() {
        // 重复项即便被手动勾上也跳过：同一操作写两遍只会让流程卡在原地。
        val conflicts = _state.value.silentConflictSeqs
        val picked = _state.value.silentActions.filter { it.included && it.seq !in conflicts }
        if (picked.isEmpty()) {
            discardSilentActions()
            return
        }
        mutateSteps { current ->
            val next = current.steps.toMutableList()
            picked.forEach { action ->
                next += when (action.op) {
                    "swipe" -> PackStep(
                        op = "swipe",
                        fromX = action.x, fromY = action.y,
                        toX = action.toX, toY = action.toY,
                        // 录出来的时长本身就带下限（240ms），这里只守住校验上限。
                        durationMs = action.durationMs.coerceIn(80, 10_000),
                        note = if (action.kind == SilentGestureAnalyzer.KIND_JOYSTICK) {
                            "静默录制摇杆：按住 ${action.durationMs}ms，推杆方向 " +
                                "(${coord(action.x)}, ${coord(action.y)}) → " +
                                "(${coord(action.toX)}, ${coord(action.toY)})"
                        } else {
                            "静默录制滑动 (${coord(action.x)}, ${coord(action.y)}) → " +
                                "(${coord(action.toX)}, ${coord(action.toY)})" +
                                " · ${action.durationMs}ms"
                        },
                    )
                    "longPress" -> PackStep(
                        op = "longPress",
                        x = action.x, y = action.y,
                        durationMs = action.durationMs.coerceAtLeast(LONG_PRESS_MS),
                        note = action.text.takeIf { it.isNotBlank() }
                            ?.let { "长按文字「$it」" }
                            ?: "静默录制长按 (${coord(action.x)}, ${coord(action.y)})",
                    )
                    else -> if (action.text.isNotBlank()) {
                        PackStep(
                            op = "tapText",
                            text = action.text,
                            timeoutMs = 10_000,
                            note = "点击文字「${action.text}」（静默录制取词）",
                        )
                    } else {
                        PackStep(
                            op = "tap",
                            x = action.x, y = action.y,
                            durationMs = action.durationMs.coerceIn(40, LONG_PRESS_MS - 1),
                            note = "静默录制点击 (${coord(action.x)}, ${coord(action.y)})；未识别到文本",
                        )
                    }
                }
            }
            current.copy(steps = next, selectedStepIndex = next.lastIndex)
        }
        _state.update {
            it.copy(
                silentActions = emptyList(),
                silentReviewVisible = false,
                silentConflictSeqs = emptySet(),
                message = "已把 ${picked.size} 个静默动作生成步骤（可撤销）",
            )
        }
    }

    /** 框选当前真实画面，裁成模板并生成原版 tapTemplate / waitTemplate。 */
    suspend fun addTemplateStep(region: List<Double>) {
        val current = _state.value
        if (!current.recording || current.mode !in setOf(
                DevRecordMode.TAP_TEMPLATE, DevRecordMode.WAIT_TEMPLATE,
            ) || region.size != 4
        ) return
        val frame = captureFrame() ?: run {
            showMessage("截取当前帧失败，未生成模板")
            return
        }
        try {
            val nx = normCoordinate(region[0])
            val ny = normCoordinate(region[1])
            val nw = normCoordinate(region[2])
            val nh = normCoordinate(region[3])
            val left = (nx * frame.width).toInt().coerceIn(0, frame.width - 1)
            val top = (ny * frame.height).toInt().coerceIn(0, frame.height - 1)
            val right = ((nx + nw) * frame.width).toInt().coerceIn(left + 1, frame.width)
            val bottom = ((ny + nh) * frame.height).toInt().coerceIn(top + 1, frame.height)
            if (right - left < 8 || bottom - top < 8) {
                showMessage("模板框太小，请框选完整按钮或图标")
                return
            }
            val crop = Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
            val bytes = ByteArrayOutputStream().use { output ->
                if (!crop.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    showMessage("模板 PNG 编码失败")
                    return
                }
                output.toByteArray()
            }
            crop.recycle()

            var path: String
            do {
                assetSequence += 1
                path = "assets/template_%03d.png".format(Locale.US, assetSequence)
            } while (_state.value.assets.containsKey(path))
            val isTap = current.mode == DevRecordMode.TAP_TEMPLATE
            val text = if (current.recordNotes) {
                ocr.textAt(frame, (left + right) / 2.0 / frame.width, (top + bottom) / 2.0 / frame.height)
            } else {
                null
            }
            val step = PackStep(
                op = if (isTap) "tapTemplate" else "waitTemplate",
                image = path,
                timeoutMs = 10_000,
                threshold = 0.80,
                region = listOf(nx, ny, normCoordinate(right.toDouble() / frame.width - nx),
                    normCoordinate(bottom.toDouble() / frame.height - ny)),
                note = if (isTap) {
                    text?.let { "点击文字「$it」（录制模板）" } ?: "点击录制模板 $path"
                } else {
                    text?.let { "等待文字「$it」所在区域（录制模板）" } ?: "等待录制模板 $path"
                },
            )
            _state.update { it.copy(assets = it.assets + (path to bytes)) }
            appendRecorded(step, System.currentTimeMillis())
        } finally {
            frame.recycle()
        }
    }

    // ==================== 拉框 OCR 文字 ====================

    /**
     * 在画面上拉框后，只识别该区域的文字。结果先展示给用户确认，
     * 由用户选择生成 tapText / waitText / assertText / readText，不自动写入步骤。
     */
    fun scanOcrRegion(region: List<Double>) {
        if (region.size != 4 || _state.value.ocrRegionBusy) return
        _state.update { it.copy(ocrRegionBusy = true) }
        viewModelScope.launch {
            val frame = captureFrame()
            if (frame == null) {
                _state.update { it.copy(ocrRegionBusy = false) }
                showMessage("截取当前帧失败，请确认 Shizuku 已授权、服务已连接")
                return@launch
            }
            val norm = listOf(
                normCoordinate(region[0]), normCoordinate(region[1]),
                normCoordinate(region[2]), normCoordinate(region[3]),
            )
            val result = ocr.readRegion(frame, norm)
            frame.recycle()
            if (result.lines.isEmpty()) {
                _state.update { it.copy(ocrRegionBusy = false) }
                showMessage("框选区域没识别到文字，可换更贴近文字的框或到「环境」页做 OCR 自检")
                return@launch
            }
            _state.update {
                it.copy(
                    ocrRegionBusy = false,
                    ocrRegionDraft = OcrRegionDraft(norm, result.lines, result.text),
                )
            }
        }
    }

    /** 用识别出的文字生成一条带 region 的文字动作，或写进已有步骤的 note。 */
    fun confirmOcrRegion(op: String, chosenText: String, varName: String = "text") {
        val draft = _state.value.ocrRegionDraft ?: return
        val text = chosenText.ifBlank { draft.text.lines().firstOrNull().orEmpty() }
        if (text.isBlank()) {
            showMessage("文字为空，未生成步骤")
            return
        }
        val step = when (op) {
            "waitText" -> PackStep(
                op = "waitText", text = text, timeoutMs = 10_000, region = draft.region,
                note = "等待框选文字「$text」",
            )
            "assertText" -> PackStep(
                op = "assertText", text = text, region = draft.region,
                note = "断言框选文字「$text」在场",
            )
            "readText" -> PackStep(
                op = "readText", region = draft.region, to = varName.ifBlank { "text" },
                note = "读取框选区域文字到变量",
            )
            "ifText" -> PackStep(
                op = "ifText", text = text, region = draft.region,
                then = listOf(PackStep(op = "log", text = "分支占位：请替换")),
                note = "框选文字「$text」在场时走 then",
            )
            else -> PackStep(
                op = "tapText", text = text, timeoutMs = 10_000, region = draft.region,
                note = "点击框选文字「$text」",
            )
        }
        mutateSteps { current ->
            current.copy(
                steps = current.steps + step,
                selectedStepIndex = current.steps.size,
            )
        }
        _state.update { it.copy(ocrRegionDraft = null) }
        showMessage("已生成 $op 步骤（含框选 region）")
    }

    fun dismissOcrRegion() = _state.update { it.copy(ocrRegionDraft = null) }

    private fun appendRecorded(step: PackStep, actionAt: Long) {
        mutateSteps { current ->
            val next = current.steps.toMutableList()
            if (current.recordIntervals && lastActionAt > 0L) {
                val gap = (actionAt - lastActionAt).coerceIn(0, 60_000)
                if (gap >= 600) next += PackStep(op = "wait", ms = gap, note = "录制时的真实操作间隔")
            }
            next += step
            current.copy(steps = next, selectedStepIndex = next.lastIndex)
        }
        lastActionAt = actionAt
    }

    // ==================== 草稿、恢复、步骤编辑与历史 ====================

    /** 所有步骤级改动统一走这里，撤销栈最多保留 30 次。 */
    private fun mutateSteps(recordHistory: Boolean = true, transform: (TaskPackDevState) -> TaskPackDevState) {
        val before = _state.value
        val after = transform(before)
        if (before.steps === after.steps && before.assets === after.assets) return
        if (recordHistory) {
            undoStack.addLast(StepSnapshot(before.steps, before.assets))
            while (undoStack.size > 30) undoStack.removeFirst()
            redoStack.clear()
        }
        _state.value = after.copy(canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty())
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(StepSnapshot(_state.value.steps, _state.value.assets))
        _state.update {
            it.copy(
                steps = previous.steps,
                assets = previous.assets,
                selectedStepIndex = it.selectedStepIndex?.coerceAtMost(previous.steps.lastIndex),
                canUndo = undoStack.isNotEmpty(),
                canRedo = true,
            )
        }
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(StepSnapshot(_state.value.steps, _state.value.assets))
        _state.update {
            it.copy(
                steps = next.steps,
                assets = next.assets,
                selectedStepIndex = it.selectedStepIndex?.coerceAtMost(next.steps.lastIndex),
                canUndo = true,
                canRedo = redoStack.isNotEmpty(),
            )
        }
    }

    fun updateMetadata(
        id: String = _state.value.id,
        name: String = _state.value.name,
        author: String = _state.value.author,
        packVersion: String = _state.value.packVersion,
        gameVersion: String = _state.value.gameVersion,
        description: String = _state.value.description,
        minAppVersionCode: Int = _state.value.minAppVersionCode,
    ) {
        // 在第一栏直接输入/粘贴 `1.2-4.7`、`1.2.4.7`、`1.0.0` 时自动识别并回填两栏，
        // 避免最终 version 被拼成 `1.2-4.7-4.7` 或者空格后面还挂着短横线。
        var packField = packVersion
        var gameField = gameVersion
        if (packVersion != _state.value.packVersion) {
            val recognized = TaskPackNaming.interpretWholeVersion(packVersion)
            if (recognized != null) {
                packField = recognized.first
                gameField = recognized.second
            }
        } else if (gameVersion != _state.value.gameVersion) {
            // 反过来把完整版本粘到游戏版本栏时同样处理。
            val recognized = TaskPackNaming.interpretWholeVersion(gameVersion)
            if (recognized != null) {
                packField = recognized.first
                gameField = recognized.second
            }
        }
        _state.update {
            it.copy(
                id = id, name = name, author = author, packVersion = packField,
                gameVersion = gameField, description = description,
                minAppVersionCode = minAppVersionCode.coerceIn(1, 1_000_000),
            )
        }
        aiPrefs.edit().putString("author", author).apply()
    }

    fun addWait(ms: Long = 1_000) = mutateSteps {
        it.copy(steps = it.steps + PackStep(op = "wait", ms = ms, note = "手动等待"))
    }

    fun addLog() = mutateSteps {
        it.copy(steps = it.steps + PackStep(op = "log", text = "录制进度 ${it.steps.size + 1}"))
    }

    fun insertWaitAfter(index: Int, ms: Long = 1_000) = mutateSteps { current ->
        if (index !in current.steps.indices) return@mutateSteps current
        val steps = current.steps.toMutableList()
        steps.add(index + 1, PackStep(op = "wait", ms = ms, note = "手动插入等待"))
        current.copy(steps = steps, selectedStepIndex = index + 1)
    }

    fun duplicateStep(index: Int) = mutateSteps { current ->
        if (index !in current.steps.indices) return@mutateSteps current
        val steps = current.steps.toMutableList()
        steps.add(index + 1, steps[index])
        current.copy(steps = steps, selectedStepIndex = index + 1)
    }

    fun selectStep(index: Int?) = _state.update {
        it.copy(
            selectedStepIndex = index?.takeIf { value -> value in it.steps.indices },
            // 换选中项就撤掉上次的定位叠加，避免两处标记互相干扰。
            focusedStep = null,
            focusedLabel = "",
        )
    }

    /**
     * 画面定位：把任意层级的步骤画到左侧实时画面上做叠加。
     *
     * 之前只能在「实时录制」页看坐标，点一下就跳走，编辑时来回切很烦；
     * 现在只改叠加状态，当前正在编辑的页不切换。
     */
    fun focusStepAt(path: StepPath) {
        val step = TaskPackStepTree.stepAt(_state.value.steps, path) ?: return
        _state.update {
            it.copy(
                focusedStep = step,
                focusedLabel = "#${path.label()} ${step.op}",
                selectedStepIndex = if (path.parts.size == 1) path.parts.first().index else null,
            )
        }
    }

    /** 重取坐标等操作结束后清掉叠加，避免旧标记误导。 */
    fun clearFocus() = _state.update { it.copy(focusedStep = null, focusedLabel = "") }

    fun removeStep(index: Int) = mutateSteps { current ->
        if (index !in current.steps.indices) return@mutateSteps current
        val steps = current.steps.toMutableList().apply { removeAt(index) }
        current.copy(
            steps = steps,
            assets = pruneAssets(steps, current.assets),
            selectedStepIndex = null,
        )
    }

    fun moveStep(index: Int, delta: Int) = mutateSteps { current ->
        val target = index + delta
        if (index !in current.steps.indices || target !in current.steps.indices) {
            return@mutateSteps current
        }
        val steps = current.steps.toMutableList()
        val item = steps.removeAt(index)
        steps.add(target, item)
        current.copy(steps = steps, selectedStepIndex = target)
    }

    fun replaceStep(index: Int, step: PackStep) = mutateSteps { current ->
        if (index !in current.steps.indices) return@mutateSteps current
        current.copy(
            steps = current.steps.mapIndexed { i, item -> if (i == index) step else item },
            assets = pruneAssets(current.steps.mapIndexed { i, item -> if (i == index) step else item }, current.assets),
            selectedStepIndex = index,
        )
    }

    fun updateStepNote(index: Int, note: String) = mutateSteps(recordHistory = false) { current ->
        if (index !in current.steps.indices) return@mutateSteps current
        current.copy(steps = current.steps.mapIndexed { i, step ->
            if (i == index) step.copy(note = note) else step
        })
    }

    fun updateStepOptional(index: Int, optional: Boolean) = mutateSteps { current ->
        if (index !in current.steps.indices) return@mutateSteps current
        current.copy(steps = current.steps.mapIndexed { i, step ->
            if (i == index) step.copy(optional = optional) else step
        })
    }

    /** 把录制得到的固定坐标点击转成原版已有的 tapText，文字必须来自录制 OCR。 */
    fun convertTapToText(index: Int) = mutateSteps { current ->
        val step = current.steps.getOrNull(index) ?: return@mutateSteps current
        if (step.op !in setOf("tap", "longPress")) return@mutateSteps current
        val text = CLICK_TEXT.find(step.note)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: return@mutateSteps current
        val updated = step.copy(
            op = "tapText",
            text = text,
            timeoutMs = 10_000,
            x = null,
            y = null,
            durationMs = null,
            note = "点击文字「$text」（由录制结果转为 OCR 点击）",
        )
        current.copy(steps = current.steps.mapIndexed { i, item -> if (i == index) updated else item })
    }

    // ==================== 配置项 params 增删改 ====================

    fun addParam() {
        _state.update { current ->
            var index = current.params.size + 1
            var key = "param$index"
            val existing = current.params.mapTo(HashSet()) { it.key }
            while (key in existing) {
                index += 1
                key = "param$index"
            }
            current.copy(params = current.params + PackParam(key = key, label = "配置项 $index", type = "string"))
        }
    }

    fun updateParam(index: Int, param: PackParam) {
        _state.update { current ->
            if (index !in current.params.indices) return@update current
            current.copy(params = current.params.mapIndexed { i, item -> if (i == index) param else item })
        }
    }

    fun removeParam(index: Int) {
        _state.update { current ->
            if (index !in current.params.indices) return@update current
            current.copy(params = current.params.toMutableList().also { it.removeAt(index) })
        }
    }

    /**
     * 复制一个配置项，插在原项后面。
     *
     * key 必须唯一（步骤里用 `{key}` 引用，重名会让执行器取到错误的值），
     * 所以复制时自动改成 `原名_copy`、`原名_copy2`…
     */
    fun duplicateParam(index: Int) {
        _state.update { current ->
            if (index !in current.params.indices) return@update current
            val source = current.params[index]
            val existing = current.params.mapTo(HashSet()) { it.key }
            val base = "${source.key}_copy"
            var key = base
            var n = 2
            while (key in existing) {
                key = "$base$n"
                n++
            }
            val copy = source.copy(key = key, label = source.label.ifBlank { source.key } + "（副本）")
            current.copy(
                params = current.params.toMutableList().apply { add(index + 1, copy) },
            )
        }
    }

    /** 配置项上移 / 下移：任务卡片上的表单顺序与 params 顺序一致，便于按使用频率排布。 */
    fun moveParam(index: Int, delta: Int) {
        _state.update { current ->
            val target = index + delta
            if (index !in current.params.indices || target !in current.params.indices) return@update current
            val list = current.params.toMutableList()
            val item = list.removeAt(index)
            list.add(target, item)
            current.copy(params = list)
        }
    }

    // ==================== 从实时画面重取坐标 ====================

    fun startPickCoordinate(index: Int) {
        if (index !in _state.value.steps.indices) return
        pickCoordinatePath = null
        _state.update {
            it.copy(
                coordinatePickTarget = index,
                coordinatePickActive = true,
                topHint = TopHint(
                    "重取坐标：在画面点一下写入 #${index + 1}（不操作游戏、不录制）",
                    kind = TopHintKind.COORDINATE,
                ),
            )
        }
    }

    fun cancelPickCoordinate() = _state.update {
        it.copy(coordinatePickTarget = null, coordinatePickActive = false, topHint = null)
    }

    /** 录制页在取点模式下把归一化坐标写回目标步骤（tap/longPress 写 x/y，swipe 写起点）。 */
    fun applyPickedCoordinate(nx: Double, ny: Double) {
        val index = _state.value.coordinatePickTarget ?: return
        val x = normCoordinate(nx)
        val y = normCoordinate(ny)
        mutateSteps { current ->
            val step = current.steps.getOrNull(index) ?: return@mutateSteps current
            val updated = when (step.op) {
                "swipe" -> step.copy(fromX = x, fromY = y)
                else -> step.copy(x = x, y = y)
            }
            current.copy(
                steps = current.steps.mapIndexed { i, item -> if (i == index) updated else item },
                selectedStepIndex = index,
            )
        }
        _state.update {
            it.copy(
                coordinatePickTarget = null,
                coordinatePickActive = false,
                topHint = TopHint(
                    "已把 #${index + 1} 的坐标更新为 (${coord(x)}, ${coord(y)})",
                    kind = TopHintKind.COORDINATE,
                ),
            )
        }
    }

    // ==================== 非录制态点击（含 AI 生成中） ====================

    /**
     * 非录制状态点画面时登记坐标。这些坐标（以及当前工程里已录制的坐标）就是
     * 参考坐标清单：AI 自动点击与写包时可以优先复用，但不强制。
     */
    fun noteIdleTouch(nx: Double, ny: Double) {
        val point = normCoordinate(nx) to normCoordinate(ny)
        _state.update {
            val next = LinkedHashSet(it.aiProvidedPoints)
            next.add(point)
            while (next.size > 80) next.remove(next.first())
            it.copy(aiProvidedPoints = next)
        }
    }

    // ==================== 全局 / 分字段 ms 延迟一键增加 ====================

    /** 给某一类 ms 字段的所有已有值统一 +deltaMs（含嵌套分支），并守住原版校验上限。 */
    fun applyDelay(field: DelayField, deltaMs: Long) {
        if (deltaMs == 0L) return
        var affected = 0
        mutateSteps { current ->
            val result = TaskPackDelay.apply(current.steps, field, deltaMs)
            affected = result.second
            current.copy(steps = result.first)
        }
        showMessage(
            "已给 $affected 个步骤的「${field.label}」各增加 $deltaMs ms" +
                "（上限 ${TaskPackDelay.limitFor(field)} ms）"
        )
    }

    /** 全局延迟：在每一个顶层步骤后插入一条 wait。 */
    fun insertWaitForEveryStep(ms: Long) {
        val count = _state.value.steps.size
        if (count == 0) {
            showMessage("当前没有步骤，无需插入")
            return
        }
        mutateSteps { current ->
            current.copy(
                steps = TaskPackDelay.insertWaitForEveryStep(current.steps, ms),
                selectedStepIndex = null,
            )
        }
        showMessage("已在 $count 个步骤后各插入 wait(${ms.coerceIn(1L, 60_000L)}ms)，共新增 $count 步")
    }

    // ==================== 内置模板库 ====================

    /** 载入仓库内置整包模板；append=true 时只把步骤与模板图追加到当前工程。 */
    fun loadPackTemplate(entry: PackTemplateEntry, append: Boolean) {
        if (_state.value.importing) return
        _state.update { it.copy(importing = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { TaskPackTemplateLibrary.loadPack(app, entry) }
            }
            _state.update { it.copy(importing = false) }
            result.onSuccess { project ->
                if (append) {
                    mutateSteps { current ->
                        current.copy(
                            steps = current.steps + project.pack.steps,
                            assets = current.assets + project.assets,
                            selectedStepIndex = null,
                        )
                    }
                    showMessage("已把内置模板「${entry.name}」的 ${project.pack.steps.size} 步追加到当前工程（模板图一并带入）")
                } else {
                    restoreProject(
                        project,
                        recordHistory = true,
                        message = "已载入内置模板「${entry.name}」，可继续编辑导出",
                    )
                }
            }.onFailure { showMessage("载入模板失败：${it.message}") }
        }
    }

    /** 插入一个 24 op 的字段骨架；坐标/模板/文字留空，必须由开发者填写。 */
    fun insertStepTemplate(template: StepTemplate) = mutateSteps { current ->
        current.copy(
            steps = current.steps + template.step,
            selectedStepIndex = current.steps.size,
        )
    }

    /**
     * 插入图标模板：把仓库/整包里的真实 PNG 原样放进当前工程 assets，
     * 再生成一条以它为 image 的模板步骤。图标来自用户挑选，工具不生成任何图像。
     */
    fun insertIconTemplate(entry: IconTemplateEntry, op: String = "tapTemplate") {
        val bytes = TaskPackIconTemplates.bytes(app, entry) ?: run {
            showMessage("读取图标失败：${entry.asset}")
            return
        }
        val path = entry.packPath
        val step = when (op) {
            "waitTemplate" -> PackStep(
                op = "waitTemplate", image = path, timeoutMs = 10_000, threshold = 0.80,
                note = "等待图标「${entry.name}」（${entry.group}）",
            )
            "assertTemplate" -> PackStep(
                op = "assertTemplate", image = path, threshold = 0.80,
                note = "断言图标「${entry.name}」在场",
            )
            "ifTemplate" -> PackStep(
                op = "ifTemplate", image = path, threshold = 0.80,
                then = listOf(PackStep(op = "log", text = "分支占位：请替换")),
                note = "图标「${entry.name}」在场时走 then",
            )
            else -> PackStep(
                op = "tapTemplate", image = path, timeoutMs = 10_000, threshold = 0.80,
                note = "点击图标「${entry.name}」（${entry.group}）",
            )
        }
        mutateSteps { current ->
            current.copy(
                steps = current.steps + step,
                assets = current.assets + (path to bytes),
                selectedStepIndex = current.steps.size,
            )
        }
        showMessage("已插入图标「${entry.name}」→ $path（${entry.width}×${entry.height}）")
    }

    /**
     * AI 按需索取模板时，把命中的图标注册进当前工程 assets，
     * 让模型随后引用 `assets/icon_xxx.png` 能通过原版校验并随包导出。
     */
    private fun registerIconForAi(entry: IconTemplateEntry): Boolean {
        val bytes = TaskPackIconTemplates.bytes(app, entry) ?: return false
        val path = entry.packPath
        if (_state.value.assets.containsKey(path)) return true
        _state.update { it.copy(assets = it.assets + (path to bytes)) }
        return true
    }

    fun setPreviewScaleMode(mode: PreviewScaleMode) =
        _state.update { it.copy(previewScaleMode = mode) }

    fun setAiTemplateLookup(enabled: Boolean) {
        _state.update { it.copy(aiTemplateLookup = enabled) }
        aiPrefs.edit().putBoolean("templateLookup", enabled).apply()
    }

    fun addStepWithOp(op: String): Boolean {
        val template = TaskPackTemplateLibrary.STEP_TEMPLATES.firstOrNull { it.op == op }
            ?: return false
        insertStepTemplate(template)
        return true
    }

    fun clearSteps() = mutateSteps {
        it.copy(steps = emptyList(), assets = emptyMap(), selectedStepIndex = null)
    }

    // ==================== 多工程存档 ====================

    /** 重新读取工程列表（打开列表对话框时调一次就够，索引很小）。 */
    fun refreshProjects() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { projectStore.list() }.getOrDefault(emptyList())
            }
            _state.update { it.copy(projects = list) }
        }
    }

    /**
     * 保存当前工程。[asNew] 为 true 时另存为一份新工程（原工程不动），
     * 用来分叉试写法；否则覆盖当前工程。
     */
    fun saveProject(asNew: Boolean = false, nameOverride: String? = null) {
        viewModelScope.launch {
            val s = _state.value
            val targetId = if (asNew) "" else s.currentProjectFileId
            val project = projectFromState(
                if (nameOverride.isNullOrBlank()) s else s.copy(name = nameOverride.trim()),
            )
            val summary = withContext(Dispatchers.IO) {
                runCatching { projectStore.save(targetId, project) }.getOrNull()
            }
            if (summary == null) {
                _state.update { it.copy(message = "保存工程失败：无法写入文件") }
                return@launch
            }
            _state.update {
                it.copy(
                    currentProjectFileId = summary.fileId,
                    projectSavedAt = summary.updatedAt,
                    projects = (it.projects.filterNot { p -> p.fileId == summary.fileId } + summary)
                        .sortedByDescending { p -> p.updatedAt },
                    message = "已保存工程「${summary.displayName}」· ${summary.stepCount} 步",
                )
            }
        }
    }

    /** 切换工程：先把当前工程落盘，再载入目标工程（含步骤、参数、素材、元数据）。 */
    fun openProject(fileId: String) {
        if (fileId == _state.value.currentProjectFileId) return
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val s = _state.value
                if (s.currentProjectFileId.isBlank()) return@withContext null
                runCatching { projectStore.save(s.currentProjectFileId, projectFromState(s)) }.getOrNull()
            }
            val project = withContext(Dispatchers.IO) {
                runCatching { projectStore.load(fileId) }.getOrNull()
            }
            if (project == null) {
                _state.update { it.copy(message = "打开工程失败：文件已损坏或被删除") }
                refreshProjects()
                return@launch
            }
            restoreProject(project, recordHistory = false)
            undoStack.clear()
            redoStack.clear()
            _state.update {
                it.copy(
                    currentProjectFileId = fileId,
                    projectSavedAt = if (saved?.fileId == fileId) saved.updatedAt else System.currentTimeMillis(),
                    canUndo = false,
                    canRedo = false,
                    message = "已打开工程「${project.pack.name.ifBlank { project.pack.id }}」",
                )
            }
        }
    }

    /** 新建工程：当前工程先保存，再清空成空白草稿。 */
    fun newProject() {
        viewModelScope.launch {
            val s = _state.value
            if (s.currentProjectFileId.isNotBlank() && s.steps.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    runCatching { projectStore.save(s.currentProjectFileId, projectFromState(s)) }
                }
            }
            startNewDraft()
            _state.update {
                it.copy(
                    currentProjectFileId = "",
                    projectSavedAt = 0L,
                    message = "已新建工程，首次「保存工程」时落盘",
                )
            }
        }
    }

    fun deleteProject(fileId: String) {
        viewModelScope.launch {
            val rest = withContext(Dispatchers.IO) {
                runCatching { projectStore.delete(fileId) }.getOrDefault(emptyList())
            }
            _state.update {
                it.copy(
                    projects = rest,
                    currentProjectFileId = if (it.currentProjectFileId == fileId) "" else it.currentProjectFileId,
                    message = "已删除工程",
                )
            }
        }
    }

    fun renameProject(fileId: String, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val rest = withContext(Dispatchers.IO) {
                runCatching { projectStore.rename(fileId, name) }.getOrDefault(emptyList())
            }
            _state.update { it.copy(projects = rest, message = "已重命名为「${name.trim()}」") }
        }
    }

    fun startNewDraft() {
        _state.update {
            it.copy(
                id = "dev.recording",
                name = "",
                packVersion = "1.0",
                gameVersion = "",
                description = "",
                minAppVersionCode = 11,
                params = emptyList(),
                steps = emptyList(),
                assets = emptyMap(),
                selectedStepIndex = null,
                recording = false,
                aiPendingPack = null,
                aiStatus = "",
                message = "已新建空白草稿",
            )
        }
        undoStack.clear()
        redoStack.clear()
        _state.update { it.copy(canUndo = false, canRedo = false) }
    }

    private fun restoreProject(
        project: TaskPackProject,
        recordHistory: Boolean,
        message: String? = null,
    ) {
        val pack = project.pack
        // 不是 `x.x-y.y` 的版本（例如 `1.0.0`）只填任务包版本栏，游戏版本留空。
        val versions = TaskPackNaming.fillForm(pack.version)
        val before = _state.value
        if (recordHistory) {
            undoStack.addLast(StepSnapshot(before.steps, before.assets))
            while (undoStack.size > 30) undoStack.removeFirst()
            redoStack.clear()
        }
        _state.update {
            it.copy(
                id = pack.id,
                name = pack.name,
                author = pack.author,
                packVersion = versions.first.ifBlank { "1.0" },
                gameVersion = versions.second,
                description = pack.description,
                minAppVersionCode = pack.minAppVersionCode,
                params = pack.params,
                steps = pack.steps,
                assets = pruneAssets(pack.steps, project.assets),
                selectedStepIndex = null,
                recording = false,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                message = message ?: it.message,
            )
        }
        assetSequence = project.assets.keys.mapNotNull {
            Regex("template_(\\d+)\\.png$").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: 0
    }

    fun importProject(uri: Uri) {
        if (_state.value.importing) return
        _state.update { it.copy(importing = true) }
        viewModelScope.launch {
            // 载入时自动尝试修复 JSON 语法问题（多逗号 / 注释 / 单引号 / 全角符号 / 缺右括号）。
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
                        TaskPackArchive.readRepairing(input, BuildConfig.VERSION_CODE)
                    } ?: error("无法打开所选文件")
                }
            }
            _state.update { it.copy(importing = false) }
            result.onSuccess { (project, fixes) ->
                val note = if (fixes.isEmpty()) {
                    "已载入现有任务包，可继续录制和编辑"
                } else {
                    "已自动修复 ${fixes.size} 处 JSON 语法问题并载入：" +
                        fixes.take(4).joinToString("、") +
                        if (fixes.size > 4) " 等" else ""
                }
                restoreProject(project, recordHistory = true, message = note)
            }.onFailure { showMessage("载入失败：${it.message}") }
        }
    }

    // ==================== 校验、导出与本机安装 ====================

    private fun currentPack(): CustomTaskPack = projectFromState(_state.value).pack

    private fun metadataErrors(): List<String> = _state.value.let {
        buildList {
            if (!TaskPackNaming.isValidPackVersion(it.packVersion)) {
                add("任务包版本必须填写，例如 1.2、1.0.0 或 1.2-4.7")
            }
            if (it.gameVersion.isNotBlank() && !TaskPackNaming.isValidTwoPart(it.gameVersion)) {
                add("游戏版本要么留空，要么写成 y.y，例如 4.6")
            }
            if (it.minAppVersionCode < 1) add("minAppVersionCode 必须大于 0")
        }
    }

    fun validateCurrent(): List<String> {
        val current = _state.value
        return metadataErrors() + CustomTaskPackValidator.validate(
            currentPack(), BuildConfig.VERSION_CODE, current.assets.keys,
        )
    }

    /**
     * 校验导出页的「一键修复」。
     *
     * 只动能确定意图的写法问题：表达式里的 `{变量}` 去掉花括号、越界数值收回区间、
     * `compare` / `color` 同义写法统一。文字的取值、坐标与模板引用一律不碰 ——
     * 修不了的继续按原文列在下面，交给用户决定。
     */
    fun repairIssues() {
        if (_state.value.exporting) return
        runCatching {
            val before = validateCurrent()
            val result = TaskPackIssueRepair.repair(currentPack())
            if (result.fixes.isEmpty()) {
                showMessage("没有可自动修复的问题（当前 ${before.size} 项需手动处理）")
                return
            }
            mutateSteps { it.copy(steps = result.pack.steps) }
            val after = validateCurrent()
            showMessage(
                buildString {
                    append("已修复：")
                    append(result.fixes.joinToString("、"))
                    append("。校验问题 ${before.size} → ${after.size}")
                    if (after.isNotEmpty()) append("，剩余需手动改")
                },
            )
        }.onFailure {
            // 修复本身出错时只提示，不让整屏跟着挂掉（原件还在，可以退回手动改）
            Timber.w(it, "repair issues failed")
            showMessage("自动修复失败：${it.message ?: it.javaClass.simpleName}，请手动修改")
        }
    }

    fun previewJson(): String = TaskPackProject.manifestJson(currentPack())
    fun suggestedFileName(): String = TaskPackNaming.archiveName(
        _state.value.id.ifBlank { "taskpack" },
        TaskPackNaming.version(_state.value.packVersion, _state.value.gameVersion),
    )

    fun exportTo(uri: Uri) {
        val errors = validateCurrent()
        if (errors.isNotEmpty()) {
            showMessage("请先修正校验问题：\n${errors.joinToString("\n")}")
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(exporting = true) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val pack = currentPack()
                    val bytes = TaskPackProject(pack, pruneAssets(pack.steps, _state.value.assets)).toZipBytes()
                    app.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                        ?: error("无法打开输出文件")
                }
            }
            _state.update { it.copy(exporting = false) }
            result.onSuccess { showMessage("已导出 ${suggestedFileName()}，可直接导入原版 App") }
                .onFailure { showMessage("导出失败：${it.message}") }
        }
    }

    fun installCurrent() {
        val errors = validateCurrent()
        if (errors.isNotEmpty()) {
            showMessage("请先修正校验问题：\n${errors.joinToString("\n")}")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val pack = currentPack()
            val bytes = TaskPackProject(pack, pruneAssets(pack.steps, _state.value.assets)).toZipBytes()
            when (val result = packStore.install(suggestedFileName(), bytes, BuildConfig.VERSION_CODE)) {
                is TaskPackInstallResult.Success -> showMessage(
                    "已安装到本机：${result.pack.displayName}，可回任务列表加入任务链"
                )
                is TaskPackInstallResult.Invalid -> showMessage(result.errors.joinToString("\n"))
            }
        }
    }

    // ==================== AI 全自动写作 ====================

    fun updateAiPrompt(value: String) = _state.update { it.copy(aiPrompt = value) }
    fun updateAiBaseUrl(value: String) = updateAiConfig(baseUrl = value)
    fun updateAiApiKey(value: String) = updateAiConfig(apiKey = value)
    fun updateAiModel(value: String) = updateAiConfig(model = value)

    fun updateAiAllowScreenshot(value: Boolean) {
        _state.update { it.copy(aiAllowScreenshot = value) }
        aiPrefs.edit().putBoolean("allowScreenshot", value).apply()
    }

    // ==================== 任务包开发规范（让 AI 按规范生成） ====================

    private fun loadSpecUrls(): List<String> {
        val raw = aiPrefs.getString("specUrls", null) ?: return TaskPackSpec.defaultUrls()
        return raw.split('\n').map { it.trim() }.filter { it.isNotBlank() }
            .ifEmpty { TaskPackSpec.defaultUrls() }
    }

    private fun persistSpecUrls(urls: List<String>) {
        aiPrefs.edit().putString("specUrls", urls.joinToString("\n")).apply()
        cachedSpec = null
    }

    private fun specKey(): String = _state.value.aiSpecUrls.joinToString("\n")

    /**
     * 链接增改删只更新本地与持久化、让缓存失效，不联网（避免编辑输入框每敲一个字就请求）。
     * 拉取线上正文统一由「从链接读取规范」按钮 / refreshAiSpec 触发。
     */
    private fun applySpecUrls(next: List<String>) {
        persistSpecUrls(next)
        _state.update {
            it.copy(
                aiSpecUrls = next,
                aiSpecFromBuiltIn = true,
                aiSpecStatus = "规范链接已改动，点「从链接读取规范」拉取正文；未刷新前生成使用 App 内置全文",
            )
        }
    }

    fun updateAiSpecInclude(value: Boolean) {
        _state.update { it.copy(aiSpecInclude = value) }
        aiPrefs.edit().putBoolean("specInclude", value).apply()
    }

    fun addAiSpecUrl(url: String) {
        val value = url.trim()
        when {
            value.isEmpty() -> {
                showMessage("规范链接不能为空")
                return
            }
            !value.startsWith("http://") && !value.startsWith("https://") -> {
                showMessage("规范链接需以 http:// 或 https:// 开头")
                return
            }
            _state.value.aiSpecUrls.any { it.equals(value, ignoreCase = true) } -> {
                showMessage("该规范链接已存在")
                return
            }
        }
        applySpecUrls(_state.value.aiSpecUrls + value)
    }

    fun updateAiSpecUrl(index: Int, url: String) {
        val current = _state.value.aiSpecUrls
        if (index !in current.indices) return
        // 编辑中的中间态允许为空/不带协议头，只在失去焦点或刷新时校验；这里不弹提示。
        if (url.contains('\n')) return
        val next = current.toMutableList().also { it[index] = url }
        persistSpecUrls(next)
        _state.update { it.copy(aiSpecUrls = next) }
    }

    fun removeAiSpecUrl(index: Int) {
        val current = _state.value.aiSpecUrls
        if (index !in current.indices) return
        applySpecUrls(current.toMutableList().also { it.removeAt(index) })
    }

    fun resetAiSpecUrls() = applySpecUrls(TaskPackSpec.defaultUrls())

    /**
     * 从规范链接拉取最新正文并缓存；任一链接失败都会自动回退内置全文。
     * 生成时不联网，直接用缓存，保证不会因为拉规范拖慢或拖垮生成。
     */
    fun refreshAiSpec() {
        if (_state.value.aiSpecLoading) return
        val urls = _state.value.aiSpecUrls
        _state.update { it.copy(aiSpecLoading = true, aiSpecStatus = "正在读取规范文档…") }
        viewModelScope.launch {
            val result = TaskPackSpec.load(app, httpClient, urls)
            cachedSpec = result
            cachedSpecKey = urls.joinToString("\n")
            _state.update {
                it.copy(
                    aiSpecLoading = false,
                    aiSpecCharCount = result.text.length,
                    aiSpecFromBuiltIn = result.fromBuiltIn,
                    aiSpecStatus = result.status,
                )
            }
        }
    }

    /** 生成时取的规范：优先命中缓存，否则用内置全文（不联网，保证生成速度）。 */
    private fun specForGeneration(include: Boolean): TaskPackSpecResult {
        if (!include) return TaskPackSpecResult()
        cachedSpec?.let { if (cachedSpecKey == specKey()) return it }
        val builtIn = TaskPackSpec.builtIn(app)
        return TaskPackSpecResult(
            text = if (builtIn.isBlank()) "" else
                "===== 规范文档：App 内置 docs/task-file.md =====\n" + builtIn.take(100_000),
            status = "使用 App 内置规范全文；点「从链接刷新」可获取线上最新版",
            fromBuiltIn = true,
        )
    }

    private fun updateAiConfig(
        baseUrl: String = _state.value.aiBaseUrl,
        apiKey: String = _state.value.aiApiKey,
        model: String = _state.value.aiModel,
    ) {
        _state.update { it.copy(aiBaseUrl = baseUrl, aiApiKey = apiKey, aiModel = model) }
        aiPrefs.edit().putString("baseUrl", baseUrl).putString("apiKey", apiKey)
            .putString("model", model).apply()
    }

    /** 手动刷新给 AI 的默认上下文：当前画面的 OCR 文字。生成时也会自动刷新一次。 */
    fun captureAiScreenText() {
        if (_state.value.aiReadingScreen) return
        viewModelScope.launch {
            _state.update { it.copy(aiReadingScreen = true) }
            val frame = captureFrame()
            val text = if (frame == null) "" else ocr.readScreen(frame)
            frame?.recycle()
            _state.update { it.copy(aiReadingScreen = false, aiScreenText = text) }
            showMessage(if (text.isBlank()) "没有读到画面文字" else "已刷新当前画面文字（默认发给 AI 的上下文）")
        }
    }

    // ==================== AI 边生成边自动点击 ====================

    /**
     * 「用户提供的坐标」清单：当前工程已录制的坐标 + 用户本轮在画面上点过的坐标。
     * AI 自动点击只接受清单里的坐标，工具不会替模型生成任何坐标。
     */
    private fun knownCoordinates(): List<Pair<Double, Double>> {
        val out = LinkedHashSet<Pair<Double, Double>>()
        _state.value.aiProvidedPoints.forEach(out::add)
        fun visit(items: List<PackStep>) {
            items.forEach { step ->
                if (step.x != null && step.y != null) out += step.x to step.y
                if (step.fromX != null && step.fromY != null) out += step.fromX to step.fromY
                step.steps?.let(::visit)
                step.then?.let(::visit)
                step.elseSteps?.let(::visit)
            }
        }
        visit(_state.value.steps)
        return out.toList()
    }

    /** 写进 prompt 的坐标清单，让模型照抄而不是臆造。 */
    fun knownCoordinateText(): String = knownCoordinates()
        .mapIndexed { index, (x, y) -> "#${index + 1} (${coord(x)}, ${coord(y)})" }
        .joinToString("\n")
        .ifBlank { "（暂无：请先在画面上取点或用实时录制产生坐标）" }

    /**
     * 数值坐标点击：坐标由 App 归一化与裁剪，模型给 0~1 或像素都能用。
     *
     * 顺带把落点附近的 OCR 文字带回去，模型据此知道「点到的是什么」，
     * 下一轮就不容易重复点同一个位置。
     */
    private suspend fun performAiClick(nx: Double, ny: Double): TaskPackAiClickResult {
        val serviceReady = RemoteServiceManager.getInstanceOrNull() != null
        if (!serviceReady) {
            return TaskPackAiClickResult(
                accepted = false,
                observation = "点击失败：Shizuku 服务未连接，无法注入触摸。" +
                    "请到「环境」页确认授权后重试；此时请仅依据已有 OCR 文字输出最终 task.json。",
            )
        }
        val before = captureFrame()
        val w = before?.width?.toDouble()?.takeIf { it > 0 } ?: 1920.0
        val h = before?.height?.toDouble()?.takeIf { it > 0 } ?: 1080.0
        // 像素坐标（常见 1920×1080）先除以画面尺寸；0~1 的归一化值原样使用。
        val pixelMode = nx > 1.5 || ny > 1.5
        val x = normCoordinate(if (pixelMode) nx / w else nx)
        val y = normCoordinate(if (pixelMode) ny / h else ny)
        val hitText = before?.let { ocr.textAt(it, x, y) }
        before?.recycle()
        touchDown(x, y)
        delay(60)
        touchUp(x, y)
        lastSelfActionAt = System.currentTimeMillis()
        delay(900)
        val frame = captureFrame()
        val text = frame?.let { ocr.readScreen(it) }.orEmpty()
        frame?.recycle()
        _state.update { it.copy(aiAutoClickCount = it.aiAutoClickCount + 1) }
        return TaskPackAiClickResult(
            accepted = true,
            observation = buildString {
                appendLine("已在 ($x, $y) 点击一次（坐标由系统换算与裁剪）。")
                if (!hitText.isNullOrBlank()) appendLine("落点处的文字：$hitText")
                appendLine("点击后的游戏画面 OCR 文字：")
                appendLine(text.ifBlank { "（未识别到文字）" })
            },
        )
    }

    /**
     * 按文字点击：模型只说按钮文字，坐标由 App 的 OCR 定位。
     *
     * 这是「玩家不插手」的主路径 —— 模型不产生任何坐标，定位、点击、录制都在本机完成。
     */
    private suspend fun performAiTextClick(query: String): TaskPackAiClickResult {
        val serviceReady = RemoteServiceManager.getInstanceOrNull() != null
        if (!serviceReady) {
            return TaskPackAiClickResult(
                accepted = false,
                observation = "点击失败：Shizuku 服务未连接，无法注入触摸。" +
                    "请到「环境」页确认授权后重试。",
            )
        }
        val frame = captureFrame() ?: return TaskPackAiClickResult(
            accepted = false,
            observation = "点击失败：无法截取当前画面，按文字定位需要截图。请改用 [[CLICK:x,y]] 或输出 task.json。",
        )
        val target = ocr.findTextCenter(frame, query)
        frame.recycle()
        if (target == null) {
            return TaskPackAiClickResult(
                accepted = false,
                observation = "没有在画面上找到「$query」。请换用画面上真实存在的文字，或改用 [[CLICK:x,y]]。",
            )
        }
        _state.update { it.copy(aiProvidedPoints = it.aiProvidedPoints + (target.x to target.y)) }
        touchDown(target.x, target.y)
        delay(60)
        touchUp(target.x, target.y)
        lastSelfActionAt = System.currentTimeMillis()
        delay(900)
        val after = captureFrame()
        val text = after?.let { ocr.readScreen(it) }.orEmpty()
        after?.recycle()
        _state.update { it.copy(aiAutoClickCount = it.aiAutoClickCount + 1) }
        return TaskPackAiClickResult(
            accepted = true,
            observation = "已点击文字「${target.text}」，坐标由系统 OCR 定位为 " +
                "(${String.format(Locale.US, "%.4f", target.x)}, ${String.format(Locale.US, "%.4f", target.y)})。" +
                "点击后的游戏画面 OCR 文字：\n" + text.ifBlank { "（未识别到文字）" },
            x = target.x,
            y = target.y,
            text = target.text,
        )
    }

    /**
     * 边点边录：AI 每次点击成功后直接追加一条步骤。
     *
     * 识别到文字就写 tapText（运行时 OCR 定位，最稳），否则写 tap 并用 App 算出的坐标。
     */
    private suspend fun recordAiAction(action: TaskPackAiAction) {
        val step = if (!action.text.isNullOrBlank()) {
            PackStep(
                op = "tapText",
                text = action.text,
                timeoutMs = 10_000,
                note = "点击文字「${action.text}」（AI 边点边录）",
            )
        } else {
            PackStep(
                op = "tap",
                x = action.x,
                y = action.y,
                durationMs = 80,
                note = "点击坐标 (${coord(action.x)}, ${coord(action.y)})（AI 边点边录）",
            )
        }
        mutateSteps { current ->
            val next = current.steps + step
            current.copy(steps = next, selectedStepIndex = next.lastIndex)
        }
        _state.update { it.copy(aiRecordedSteps = it.aiRecordedSteps + 1) }
    }

    /**
     * 打断正在进行的 AI 生成：取消协程，同时取消底层 OkHttp 请求，
     * 让长时间无响应的模型调用立即返回，而不是等到读超时。
     */
    // ==================== AI 对话记录 ====================

    /** 追加一条对话记录；超过上限只丢最早的，正在等待回答的提问会被保住。 */
    private fun chat(kind: AiChatKind, title: String, body: String, collapsible: Boolean = false) {
        val keep = _state.value.aiChat.filter { it.kind == AiChatKind.ASK }.map { it.id }.toSet()
        val message = AiChatMessage(
            id = AiChat.nextId(),
            kind = kind,
            title = title,
            body = body,
            at = System.currentTimeMillis(),
            collapsible = collapsible || kind == AiChatKind.THINKING,
        )
        _state.update { it.copy(aiChat = AiChat.append(it.aiChat, message, keep)) }
    }

    fun clearAiChat() = _state.update { it.copy(aiChat = emptyList()) }

    /**
     * 开新会话：清空对话与任务目标，回到「说一句话就开跑」的状态。
     *
     * 步骤和工程不动 —— 用户点「新会话」是想换个说法重写，不是把已有包删了。
     */
    fun startNewAiSession() {
        if (_state.value.aiGenerating) cancelAiGeneration()
        _state.update {
            it.copy(
                aiChat = emptyList(),
                aiChatInput = "",
                aiPrompt = "",
                aiPendingPack = null,
                aiUsageText = "",
                aiStatus = "",
                aiRecorded = emptyList(),
                aiRecordedSteps = 0,
                aiAutoClickCount = 0,
            )
        }
    }

    fun updateAiChatInput(value: String) = _state.update { it.copy(aiChatInput = value) }

    /**
     * 对话输入框的统一入口：
     * - 正在等回答 → 当成回答，生成继续；
     * - 正在生成 → 当成补充说明，下一轮随观察一起送达；
     * - 空闲 → 追加到任务目标并立刻开始一轮生成（聊天式追加要求）。
     */
    fun sendAiChat() {
        val text = _state.value.aiChatInput.trim()
        if (text.isEmpty()) return
        _state.update { it.copy(aiChatInput = "") }
        if (_state.value.aiPendingQuestion != null) {
            answerAiQuestion(text)
            return
        }
        if (_state.value.aiGenerating) {
            // 补充要求立刻生效：打断当前这轮，把它拼进任务目标后重新生成。
            // 之前的思考、动作、对话记录都留在面板里，用户能看出「是按新的要求重来的」；
            // 排队等本轮结束的做法看似温和，实际问题是要等半分钟以上，补充基本来不及用。
            val base = _state.value.aiPrompt.trim()
            val merged = if (base.isBlank()) text else "$base\n补充要求：$text"
            _state.update { it.copy(aiPrompt = merged) }
            chat(AiChatKind.USER, "补充要求（打断并重新生成）", text)
            chat(
                AiChatKind.SYSTEM,
                "",
                "已打断上一轮，按补充后的目标重新生成。",
            )
            cancelAiGeneration(quiet = true)
            generateWithAi()
            return
        }
        val base = _state.value.aiPrompt.trim()
        val merged = if (base.isBlank()) text else "$base\n$text"
        _state.update { it.copy(aiPrompt = merged) }
        chat(AiChatKind.USER, "追加要求", text)
        generateWithAi()
    }

    /** 回答模型的提问：生成协程会从挂起点继续。 */
    fun answerAiQuestion(text: String) {
        val deferred = questionDeferred ?: return
        questionDeferred = null
        _state.update { it.copy(aiPendingQuestion = null) }
        deferred.complete(text.trim().ifBlank { null })
    }

    /** 不回答，让模型按最稳妥的默认继续。 */
    fun skipAiQuestion() {
        val deferred = questionDeferred ?: return
        questionDeferred = null
        _state.update { it.copy(aiPendingQuestion = null) }
        deferred.complete(null)
        chat(AiChatKind.SYSTEM, "已跳过提问", "AI 会按最稳妥的默认继续，不确定的步骤会标 optional 或做成配置项。")
    }

    /** 挂起等待用户回答；超时或被打断时返回 null，让模型自行决断。 */
    private suspend fun awaitUserAnswer(question: String): String? {
        val deferred = CompletableDeferred<String?>()
        questionDeferred?.cancel()
        questionDeferred = deferred
        _state.update { it.copy(aiPendingQuestion = question) }
        val answer = withTimeoutOrNull(ASK_TIMEOUT_MS) { deferred.await() }
        if (questionDeferred === deferred) questionDeferred = null
        _state.update { it.copy(aiPendingQuestion = null) }
        return answer
    }

    // ==================== 主动观察：画面变化 / 用户手动点击 ====================

    private fun pushObservation(text: String) {
        if (text.isBlank()) return
        synchronized(observationLock) {
            if (observationQueue.size >= 24) observationQueue.removeFirst()
            observationQueue.addLast(text)
        }
    }

    /** 客户端每轮取一次，取完即清空。 */
    private fun drainObservations(): List<String> = synchronized(observationLock) {
        val out = observationQueue.toList()
        observationQueue.clear()
        out
    }

    fun setAiWatchScreen(enabled: Boolean) {
        _state.update { it.copy(aiWatchScreen = enabled) }
        aiPrefs.edit().putBoolean("watchScreen", enabled).apply()
        if (!enabled) stopScreenWatch()
    }

    fun setAiAcceptUserGesture(enabled: Boolean) {
        _state.update { it.copy(aiAcceptUserGesture = enabled) }
        aiPrefs.edit().putBoolean("userGesture", enabled).apply()
    }

    /**
     * 监视画面：画面明显变化时，把新画面的 OCR 文字主动推给模型。
     *
     * 模型看不见屏幕，只能靠文字。以前只有「生成开始」和「AI 自己点击后」两个时刻有画面快照，
     * 游戏自己转场（过场动画结束、战斗结算弹出）时模型完全不知道，写出来的流程自然对不上。
     */
    private fun startScreenWatch() {
        stopScreenWatch()
        if (!_state.value.aiWatchScreen) return
        watchJob = viewModelScope.launch(Dispatchers.IO) {
            var lastHash = 0L
            var lastText = ""
            while (isActive) {
                delay(SCREEN_WATCH_INTERVAL_MS)
                if (!_state.value.aiGenerating) break
                val frame = runCatching { frameSource.captureScaled(SCREEN_WATCH_WIDTH) }.getOrNull()
                    ?: continue
                val hash = runCatching { averageHash(frame) }.getOrDefault(0L)
                val baseline = lastHash == 0L
                val distance = if (baseline) 0 else java.lang.Long.bitCount(hash xor lastHash)
                lastHash = hash
                if (baseline || distance <= SCREEN_SAME_BITS) {
                    frame.recycle()
                    continue
                }
                // App 自己刚点过：这张新画面已经通过点击回传发给模型了，别再重复一遍。
                if (System.currentTimeMillis() - lastSelfActionAt < SELF_ACTION_QUIET_MS) {
                    frame.recycle()
                    continue
                }
                val text = runCatching { ocr.readScreen(frame) }.getOrNull().orEmpty()
                frame.recycle()
                if (text.isBlank() || text == lastText) continue
                lastText = text
                _state.update { it.copy(aiScreenText = text, aiLiveObservation = text) }
                pushObservation(
                    "游戏画面刚刚发生了变化（不是 AI 点击造成的，可能是游戏自己转场或用户操作）。\n" +
                        "当前画面 OCR 文字：\n$text\n请以这个新画面为准继续判断。",
                )
            }
        }
    }

    private fun stopScreenWatch() {
        watchJob?.cancel()
        watchJob = null
    }

    /** 16×16 灰度均值哈希：只用来判断「画面变了没有」，不参与任何识别。 */
    private fun averageHash(frame: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(frame, 16, 16, true)
        var sum = 0L
        val pixels = IntArray(256)
        scaled.getPixels(pixels, 0, 16, 0, 0, 16, 16)
        if (scaled !== frame) scaled.recycle()
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            sum += (r * 299 + g * 587 + b * 114) / 1000
        }
        val mean = sum / 256
        var hash = 0L
        for (i in 0 until 256) {
            val p = pixels[i]
            val luma = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
            if (luma >= mean) hash = hash or (1L shl i)
        }
        return hash
    }

    /**
     * 用户在 AI 生成期间自己点了游戏：把「点了哪里、那是什么文字、之后画面变成什么」告诉模型，
     * 同时按开关决定要不要顺手记进录制序列。
     */
    fun onAiUserGesture(
        frame: Bitmap?,
        startX: Double,
        startY: Double,
        endX: Double,
        endY: Double,
        moved: Boolean,
        durationMs: Long,
        actionAt: Long,
        track: List<Pair<Double, Double>>,
    ) {
        noteIdleTouch(startX, startY)
        if (_state.value.aiRecording) {
            recordAiGesture(
                frame, startX, startY, endX, endY, moved, durationMs, actionAt, track,
                source = AiRecordedAction.SOURCE_USER,
            )
            return
        }
        if (!_state.value.aiGenerating || !_state.value.aiAcceptUserGesture) {
            frame?.recycle()
            return
        }
        val x = normCoordinate(startX)
        val y = normCoordinate(startY)
        viewModelScope.launch(Dispatchers.IO) {
            // 取词与模板匹配用「按下之前」的那一帧（画面还没被这次点击改变）；
            // 点完之后的画面文字另抓一帧，两段都给模型，它才知道点下去发生了什么。
            val before = frame ?: runCatching { frameSource.captureScaled(SILENT_THUMB_SOURCE) }.getOrNull()
            val texts = before?.let { ocr.textsAt(it, x, y, limit = 3) }.orEmpty()
            val templates = if (before != null && !moved) {
                runCatching { iconMatcher.hits(before, x, y, texts, 2) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            before?.recycle()
            val after = runCatching { frameSource.captureScaled(SILENT_THUMB_SOURCE) }.getOrNull()
            val afterText = after?.let { ocr.readScreen(it) }.orEmpty()
            after?.recycle()
            if (afterText.isNotBlank()) {
                _state.update { it.copy(aiScreenText = afterText, aiLiveObservation = afterText) }
            }
            pushObservation(
                buildString {
                    appendLine("用户自己手动操作了游戏（不是 AI 发起的）：")
                    appendLine("动作：${if (moved) "滑动/拖拽" else "点击"}，起点 (${coord(x)}, ${coord(y)})，时长 ${durationMs}ms")
                    if (texts.isNotEmpty()) appendLine("该位置 OCR 候选：" + texts.joinToString(" ｜ "))
                    if (templates.isNotEmpty()) {
                        appendLine(
                            "该位置模板候选：" + templates.joinToString(" ｜ ") {
                                "${it.name}(${(it.score * 100).toInt()}%)"
                            },
                        )
                    }
                    appendLine("用户操作之后的画面 OCR 文字：")
                    append(afterText.ifBlank { "（未识别到文字）" })
                    appendLine()
                    append("用户已经替你走了一步：这一步不要重复写进任务包，但它的效果要算进来。")
                },
            )
            chat(
                AiChatKind.ACTION,
                "你手动点了游戏（已告诉 AI）",
                "位置 (${coord(x)}, ${coord(y)})" +
                    if (texts.isNotEmpty()) "，命中文字「${texts.first()}」" else "",
            )
        }
    }

    // ==================== AI 生成期间的静默录制 ====================

    fun startAiRecording() {
        val current = _state.value
        if (current.recording) {
            showMessage("请先停止当前录制，再开始 AI 录制")
            return
        }
        if (current.aiGenerating) {
            showMessage("生成中不能录制；先打断生成，录完再让 AI 完善")
            return
        }
        _state.update { it.copy(aiRecording = true, aiRecorded = emptyList(), aiRecordBusy = 0) }
        chat(
            AiChatKind.SYSTEM,
            "开始静默录制",
            "点击、长按、滑动、摇杆都会被记下来，并附带当时的 OCR 文字候选与图标模板匹配结果；" +
                "重复的动作也保留（只会标出来）。录制期间不影响你操作，录完点「让 AI 完善」即可。",
        )
    }

    fun stopAiRecording() {
        if (!_state.value.aiRecording) return
        val count = _state.value.aiRecorded.size
        _state.update { it.copy(aiRecording = false) }
        chat(
            AiChatKind.SYSTEM,
            "静默录制结束 · $count 条动作",
            if (count == 0) "没有录到动作。" else "点「让 AI 完善」把这 ${count} 条录制补成完整任务包；也可以直接生成步骤再手改。",
        )
    }

    fun clearAiRecording() {
        _state.update { it.copy(aiRecorded = emptyList(), aiRecordBusy = 0) }
    }

    /**
     * 录一条手势（AI 录制模式）。与普通静默录制的区别：
     * 完全不去重、不合并，全部保留，冲突只标记；后台补 OCR 多候选 + 模板候选 + 缩略图。
     */
    fun recordAiGesture(
        frame: Bitmap?,
        startX: Double,
        startY: Double,
        endX: Double,
        endY: Double,
        moved: Boolean,
        durationMs: Long,
        actionAt: Long,
        track: List<Pair<Double, Double>>,
        source: String = AiRecordedAction.SOURCE_USER,
    ) {
        if (!_state.value.aiRecording) {
            frame?.recycle()
            return
        }
        if (_state.value.aiRecorded.size >= AiRecording.MAX_ACTIONS) {
            frame?.recycle()
            return
        }
        val path = track.ifEmpty {
            listOf(normCoordinate(startX) to normCoordinate(startY))
        }
        val gesture = SilentGestureAnalyzer.analyze(path, durationMs)
        val duplicateOf = _state.value.aiRecorded.lastOrNull { prev ->
            prev.op == gesture.op &&
                kotlin.math.abs(prev.x - gesture.x) < SILENT_SAME_SPOT &&
                kotlin.math.abs(prev.y - gesture.y) < SILENT_SAME_SPOT &&
                (gesture.op != "swipe" ||
                    (kotlin.math.abs(prev.toX - gesture.toX) < SILENT_SAME_SPOT &&
                        kotlin.math.abs(prev.toY - gesture.toY) < SILENT_SAME_SPOT))
        }?.seq
        val seq = (_state.value.aiRecorded.maxOfOrNull { it.seq } ?: 0) + 1
        val note = if (gesture.kind == SilentGestureAnalyzer.KIND_JOYSTICK) {
            "摇杆：按住 ${durationMs}ms，推杆 ${(gesture.travel * 100).toInt()}%"
        } else {
            ""
        }
        val action = AiRecordedAction(
            seq = seq,
            op = gesture.op,
            source = source,
            x = gesture.x,
            y = gesture.y,
            toX = gesture.toX,
            toY = gesture.toY,
            durationMs = gesture.durationMs,
            at = actionAt,
            duplicateOf = duplicateOf,
            note = note,
        )
        _state.update { it.copy(aiRecorded = it.aiRecorded + action, aiRecordBusy = it.aiRecordBusy + 1) }
        chat(
            AiChatKind.ACTION,
            "录制 #$seq ${gesture.op}" + if (note.isNotBlank()) " · $note" else "",
            "(${coord(gesture.x)}, ${coord(gesture.y)})" +
                if (gesture.op == "swipe") " → (${coord(gesture.toX)}, ${coord(gesture.toY)})" else "" +
                    "，${gesture.durationMs}ms",
        )
        // 后台补：OCR 多候选、图标模板候选、缩略图。失败就留空，不影响录制本身。
        viewModelScope.launch(Dispatchers.IO) {
            // 用按下之前的那一帧：这时候画面还是「按钮正在被点」的状态，取词才准。
            val shot = frame ?: runCatching { frameSource.captureScaled(SILENT_THUMB_SOURCE) }.getOrNull()
            val thumb = shot?.let {
                runCatching {
                    ByteArrayOutputStream().use { out ->
                        it.compress(Bitmap.CompressFormat.JPEG, 72, out)
                        out.toByteArray()
                    }
                }.getOrNull()
            }
            val texts = if (shot == null || gesture.op == "swipe") {
                emptyList()
            } else {
                runCatching { ocr.textsAt(shot, gesture.x, gesture.y, limit = 3) }.getOrDefault(emptyList())
            }
            val templates: List<AiTemplateHit> = if (shot == null || gesture.op == "swipe") {
                emptyList()
            } else {
                runCatching { iconMatcher.hits(shot, gesture.x, gesture.y, texts, 3) }
                    .getOrDefault(emptyList())
            }
            shot?.recycle()
            // 命中的图标注册进工程素材：这样后面写 tapTemplate 时路径一定合法。
            templates.forEach { hit ->
                _state.value.iconTemplates.firstOrNull { it.packPath == hit.path }?.let(::registerIconForAi)
            }
            _state.update { current ->
                current.copy(
                    aiRecorded = current.aiRecorded.map {
                        if (it.seq == seq) it.copy(texts = texts, templates = templates, thumb = thumb) else it
                    },
                    aiRecordBusy = (current.aiRecordBusy - 1).coerceAtLeast(0),
                )
            }
        }
    }

    /** 录制序列直接转成步骤（不经过 AI 时的兜底）。 */
    fun applyAiRecordingAsSteps() {
        val actions = _state.value.aiRecorded
        if (actions.isEmpty()) {
            showMessage("还没有录到动作")
            return
        }
        val steps = AiRecording.toSteps(actions, _state.value.assets.keys)
        mutateSteps { current ->
            current.copy(steps = current.steps + steps, selectedStepIndex = current.steps.size)
        }
        chat(AiChatKind.SYSTEM, "已把 ${steps.size} 条录制转成步骤", "可以在右侧步骤列表继续微调。")
        showMessage("已生成 ${steps.size} 条步骤")
    }

    /**
     * 「录制完由 AI 完善」：把录制序列作为输入发给模型，让它补上等待、判断、循环等
     * 录制阶段无法体现的部分，并把候选（OCR / 模板）整理成确定写法。
     */
    fun completeRecordingWithAi() {
        val actions = _state.value.aiRecorded
        if (actions.isEmpty()) {
            showMessage("还没有录到动作，先录一段再让 AI 完善")
            return
        }
        if (_state.value.aiRecordBusy > 0) {
            showMessage("还在补 OCR / 模板匹配，稍等一两秒再点")
            return
        }
        // 先把录制落成基础步骤：AI 在此基础上增删改，比让它凭空重写更贴近实际操作。
        val steps = AiRecording.toSteps(actions, _state.value.assets.keys)
        if (steps.isNotEmpty()) {
            mutateSteps { current ->
                current.copy(steps = current.steps + steps, selectedStepIndex = current.steps.size)
            }
        }
        val timeline = AiRecording.timelineText(actions)
        chat(
            AiChatKind.USER,
            "录制完成，交给 AI 完善（${actions.size} 条）",
            timeline,
            collapsible = true,
        )
        val instruction = buildString {
            val base = _state.value.aiPrompt.trim()
            if (base.isNotBlank()) {
                appendLine(base)
                appendLine()
            }
            appendLine("下面是玩家刚才的真实操作录制（已按顺序转成基础步骤放进当前任务包）：")
            appendLine(timeline)
            appendLine("请在这个基础上完善成完整任务包：")
            appendLine("1. 每条录制都补上「先等待界面出现（waitText/waitTemplate）、再点击」的稳定写法；")
            appendLine("2. OCR 候选有多个时，选真正的按钮文字，不要用旁边的标题或说明；")
            appendLine("3. 模板候选比文字更稳时优先用 tapTemplate；引用前确认路径已在可用素材里；")
            appendLine("4. 标了「与 #n 重复」的动作按业务判断要保留还是去掉；")
            appendLine("5. 补上必要的循环、条件判断、返回与结束条件；参数用 params 声明。")
            appendLine("不要臆造没有录制过的界面。")
        }
        startGeneration(instruction)
    }

    fun cancelAiGeneration(quiet: Boolean = false) {
        val job = aiJob
        if (job == null || !job.isActive) return
        aiClient.cancelActive()
        questionDeferred?.cancel()
        questionDeferred = null
        stopScreenWatch()
        job.cancel()
        aiJob = null
        _state.update {
            it.copy(
                aiGenerating = false,
                aiInterruptible = false,
                aiPendingPack = null,
                aiPendingQuestion = null,
                aiStatus = if (quiet) "" else "已打断生成（本轮结果已丢弃，可修改任务目标后重新生成）",
            )
        }
    }

    fun generateWithAi() = startGeneration(_state.value.aiPrompt)

    /**
     * 一轮生成的公共入口。
     *
     * [instruction] 可以是任务目标，也可以是「录制序列 + 完善要求」；其余流程完全一样。
     */
    private fun startGeneration(instruction: String) {
        val current = _state.value
        if (current.aiGenerating) return
        if (instruction.isBlank()) {
            showMessage("先写清任务目标、起点界面和结束条件")
            return
        }
        val metadata = metadataErrors()
        if (metadata.isNotEmpty()) {
            showMessage(metadata.joinToString("\n"))
            return
        }
        val basePack = currentPack()
        // 规范正文：模型打不开 URL，由 App 读取全文/缓存后注入对话。生成前先取好，不联网、不阻塞。
        val spec = specForGeneration(current.aiSpecInclude)
        // 规范 33KB（约 15K tokens）每轮整份重发，是输入 token 的最大来源，也是前缀缓存
        // 最难稳住的部分：这里压成「只依赖原文内容」的稳定摘录，同一份规范永远得到同一份文本，
        // 服务端前缀缓存可以直接命中；摘录没覆盖的细节让模型用 [[NEED_SPEC:关键词]] 现取原文段落。
        val specDigestText = TaskPackSpecDigest.digest(spec.text)
        val useSpecDigest = specDigestText.length < spec.text.length
        // 模板库索引：整包模板 + 24 个步骤骨架 + 全部图标模板，注入对话供 AI 按需索取。
        val templateIndex = buildTemplateIndex(current)
        synchronized(observationLock) { observationQueue.clear() }
        _state.update {
            it.copy(
                aiGenerating = true,
                aiInterruptible = true,
                aiPendingPack = null,
                aiScreenshotsUsed = 0,
                aiAutoClickCount = 0,
                aiRecordedSteps = 0,
                aiUsageText = "",
                aiTemplateHits = 0,
                aiAsksUsed = 0,
                aiObservationsUsed = 0,
                aiPendingQuestion = null,
                aiSpecCharCount = spec.text.length,
                aiSpecFromBuiltIn = spec.fromBuiltIn,
                aiStatus = "正在识别当前画面文字…",
            )
        }
        startScreenWatch()
        // 用局部变量持有 job：打断后立刻重新生成时，旧协程的 finally 可能晚于新协程启动，
        // 直接写 `aiJob = null` 会把新 job 的引用清掉，导致之后「打断」按不动。
        var job: Job? = null
        job = viewModelScope.launch {
            try {
                // 默认上下文：抓当前帧做分块 OCR。AI 未索取画面时，只有这些文字进入对话。
                val frame = captureFrame()
                val screenText = if (frame == null) current.aiScreenText else ocr.readScreen(frame)
                frame?.recycle()
                _state.update {
                    it.copy(aiScreenText = screenText, aiStatus = "正在生成并通过原版校验器检查…")
                }
                val outcome = aiClient.generate(
                    config = TaskPackAiConfig(current.aiBaseUrl, current.aiApiKey, current.aiModel),
                    instruction = instruction,
                    basePack = basePack,
                    // 动态取最新素材：AI 索取图标模板时素材会注册进工程，校验要看到它们。
                    availableAssets = { _state.value.assets.keys },
                    appVersionCode = BuildConfig.VERSION_CODE,
                    screenText = screenText,
                    specText = if (useSpecDigest) specDigestText else spec.text,
                    specDigest = useSpecDigest,
                    specProvider = { query -> TaskPackSpecDigest.search(spec.text, query) },
                    specSourceNote = spec.status,
                    allowScreenshot = current.aiAllowScreenshot,
                    // 只有 AI 主动索取标记时才会调用这里，抓取并压缩当前帧发给模型。
                    screenshotProvider = { frameSource.captureJpegBase64() },
                    // 生成期自动点击：模型可按画面自行给坐标推进界面，坐标不写进任务包。
                    allowAutoClick = current.aiAutoClick,
                    knownCoordinates = knownCoordinateText(),
                    clickHandler = ::performAiClick,
                    // 按文字点击：模型只说按钮文字，坐标由本机 OCR 给出（推荐路径）。
                    textClickHandler = ::performAiTextClick,
                    // 边点边录：点中就把这次动作写进任务包，玩家不用另外再录一遍。
                    onActionRecorded = if (current.aiAutoRecord) ::recordAiAction else null,
                    // 模板库：索引 + 按需检索（命中图标会自动注册素材，保证校验与导出都带上）。
                    templateIndex = if (current.aiTemplateLookup) templateIndex else "",
                    templateProvider = if (current.aiTemplateLookup) {
                        { query -> lookupTemplates(query) }
                    } else null,
                    onStatus = { status -> _state.update { it.copy(aiStatus = status) } },
                    // 画面变化 / 用户手动点击：每轮请求前补给模型。
                    observations = ::drainObservations,
                    // 模型提问：挂起等用户回答（面板上会出现输入框）。
                    askHandler = ::awaitUserAnswer,
                    onChat = { message ->
                        val keep = _state.value.aiChat
                            .filter { it.kind == AiChatKind.ASK }
                            .map { it.id }
                            .toSet()
                        _state.update { it.copy(aiChat = AiChat.append(it.aiChat, message, keep)) }
                    },
                )
                // 已打断：底层请求被 cancel 可能返回 IOException，不能让错误结果覆盖「已打断」状态。
                if (!isActive || outcome.interrupted) {
                    _state.update {
                        it.copy(
                            aiGenerating = false, aiInterruptible = false, aiPendingPack = null,
                            aiTemplateHits = outcome.templateHits,
                            aiAsksUsed = outcome.asksUsed,
                            aiObservationsUsed = outcome.observationsUsed,
                            aiStatus = "已打断生成（本轮结果已丢弃）",
                        )
                    }
                    chat(AiChatKind.ERROR, "已打断生成", "本轮结果已丢弃，可修改要求后重新生成。")
                    return@launch
                }
                val pack = outcome.pack
                val specNote = if (spec.text.isBlank()) {
                    "；未附带规范文档（已关闭）"
                } else {
                    "；已随请求附带规范 ${spec.text.length} 字${if (spec.fromBuiltIn) "（内置）" else "（链接）"}"
                }
                _state.update {
                    it.copy(
                        aiGenerating = false,
                        aiInterruptible = false,
                        aiPendingPack = pack,
                        aiPendingAttempts = outcome.attempts,
                        aiScreenshotsUsed = outcome.screenshotsUsed,
                        aiTemplateHits = outcome.templateHits,
                        aiAsksUsed = outcome.asksUsed,
                        aiObservationsUsed = outcome.observationsUsed,
                        aiUsageText = outcome.usage.summary(),
                        aiStatus = if (pack == null) {
                            (outcome.error ?: "生成失败") + specNote
                        } else {
                            val shots = if (outcome.screenshotsUsed > 0) {
                                "；AI 索取了 ${outcome.screenshotsUsed} 张画面"
                            } else {
                                "；全程仅用 OCR 文字"
                            }
                            val clicks = if (outcome.clicksUsed > 0) {
                                val rec = if (current.aiAutoRecord) {
                                    "，已自动录成 ${outcome.clicksUsed} 条步骤"
                                } else {
                                    "（仅推进界面未录制）"
                                }
                                "；AI 自动点击了 ${outcome.clicksUsed} 次$rec"
                            } else ""
                            val tpl = if (outcome.templateHits > 0) {
                                "；从模板库索取了 ${outcome.templateHits} 次"
                            } else ""
                            "第 ${outcome.attempts} 轮通过原版校验$shots$clicks$tpl$specNote；请检查变更后再应用"
                        },
                    )
                }
                if (pack == null) {
                    chat(
                        AiChatKind.ERROR,
                        "生成失败",
                        (outcome.error ?: "未知错误") + specNote,
                    )
                } else {
                    chat(
                        AiChatKind.SYSTEM,
                        "生成完成，待你确认",
                        buildString {
                            appendLine("步骤 ${basePack.steps.size} → ${pack.steps.size}，配置项 ${basePack.params.size} → ${pack.params.size}")
                            appendLine("第 ${outcome.attempts} 轮通过原版校验$specNote")
                            if (outcome.asksUsed > 0) appendLine("向你提问 ${outcome.asksUsed} 次")
                            if (outcome.observationsUsed > 0) appendLine("主动同步画面/操作 ${outcome.observationsUsed} 次")
                            append("在下方卡片里点「应用并继续编辑」才会覆盖当前工程。")
                        },
                    )
                }
            } catch (e: CancellationException) {
                // 打断：状态已由 cancelAiGeneration 更新，这里不再覆盖。
                throw e
            } finally {
                if (aiJob === job) {
                    aiJob = null
                    stopScreenWatch()
                    _state.update { it.copy(aiInterruptible = false, aiPendingQuestion = null) }
                }
            }
        }
        aiJob = job
    }

    /** 模板库索引摘要：让模型知道有哪些整包、步骤骨架与图标分类可索取。 */
    private fun buildTemplateIndex(state: TaskPackDevState): String = buildString {
        // 固定按 id 排序：模板加载顺序在不同设备 / 不同时间可能不同，
        // 顺序一抖，system 前缀就整段失效，服务端缓存再也命不中。
        val packs = state.packTemplates.sortedBy { it.id }
        appendLine("整包模板 ${packs.size} 个（可直接参考其流程写法）：")
        packs.take(12).forEach {
            appendLine("  ${it.id}｜${it.name}｜${it.stepCount} 步｜${it.description.take(60)}")
        }
        appendLine("步骤模板：${TaskPackTemplateLibrary.STEP_TEMPLATES.joinToString("、") { it.op }}")
        appendLine()
        append(TaskPackIconTemplates.aiIndex(state.iconTemplates))
    }

    /**
     * AI 按需索取模板：检索图标模板 + 整包模板 + 步骤骨架，
     * 命中的图标会原样注册进工程 assets，返回确切路径与字段写法供模型照抄。
     */
    private suspend fun lookupTemplates(query: String): String = withContext(Dispatchers.IO) {
        val state = _state.value
        val sb = StringBuilder()
        val icons = TaskPackIconTemplates.lookup(state.iconTemplates, query, limit = 6)
        if (icons.isNotEmpty()) {
            sb.appendLine("【图标模板】以下路径已加入当前包可用素材，可直接作为 image 引用：")
            icons.forEach { entry ->
                registerIconForAi(entry)
                sb.appendLine("  ${entry.packPath}｜${entry.name}｜${entry.subtitle}")
                sb.appendLine("    例：{\"op\":\"tapTemplate\",\"image\":\"${entry.packPath}\",\"timeoutMs\":10000,\"threshold\":0.8}")
            }
        }
        val packs = state.packTemplates.filter {
            TaskPackTemplateLibrary.matches(query, it.searchText)
        }.take(2)
        if (packs.isNotEmpty()) {
            sb.appendLine("【整包模板】")
            packs.forEach {
                sb.appendLine("  ${it.id}｜${it.name}｜${it.stepCount} 步｜${it.description.take(100)}")
                sb.appendLine("    文件名 ${it.fileName}，可在模板库「载入为新工程」查看完整 step 写法。")
            }
        }
        val steps = TaskPackTemplateLibrary.STEP_TEMPLATES.filter {
            TaskPackTemplateLibrary.matches(query, it.searchText)
        }.take(3)
        if (steps.isNotEmpty()) {
            sb.appendLine("【步骤模板】")
            steps.forEach {
                sb.appendLine("  ${it.op}｜${it.title}｜字段 ${it.fields.joinToString(", ")}")
                sb.appendLine("    ${it.description}")
            }
        }
        sb.toString().ifBlank {
            "没有匹配「$query」的模板。可尝试更短的关键词（如 close、领取、tapText、货币战争）。"
        }
    }

    /** 抓当前帧做 OCR 自检，把识别条数和诊断直接显示出来。 */
    fun runOcrDiagnosis() {
        viewModelScope.launch {
            _state.update { it.copy(ocrDiagnosis = "正在识别…") }
            val frame = captureFrame()
            if (frame == null) {
                _state.update { it.copy(ocrDiagnosis = "截取画面失败：请确认 Shizuku 已授权、服务已连接") }
                return@launch
            }
            val info = ocr.diagnose(frame)
            frame.recycle()
            _state.update { it.copy(ocrDiagnosis = info) }
        }
    }

    fun pendingAiJson(): String = _state.value.aiPendingPack?.let(TaskPackProject::manifestJson).orEmpty()

    fun applyPendingAi() {
        val pack = _state.value.aiPendingPack ?: return
        restoreProject(
            TaskPackProject(pack, pruneAssets(pack.steps, _state.value.assets)),
            recordHistory = true,
            message = "AI 任务包已应用，可用撤销恢复生成前的步骤",
        )
        _state.update {
            it.copy(
                aiPendingPack = null,
                aiStatus = "已应用 AI 结果；导出前仍会再次经过原版校验",
            )
        }
    }

    fun discardPendingAi() = _state.update {
        it.copy(aiPendingPack = null, aiStatus = "已放弃 AI 结果，当前工程未改动")
    }

    private fun pruneAssets(steps: List<PackStep>, assets: Map<String, ByteArray>): Map<String, ByteArray> {
        val used = TaskPackProject.referencedAssets(steps)
        return assets.filterKeys { it in used }
    }

    fun showMessage(message: String) = _state.update { it.copy(message = message) }
    fun dismissMessage() = _state.update { it.copy(message = null) }

    override fun onCleared() {
        onSurfaceDestroyed()
        super.onCleared()
    }

    companion object {
        private const val LONG_PRESS_MS = 550L
        private val CLICK_TEXT = Regex("点击文字「([^」]+)」")

        /** 模型提问等多久算超时（超时按「用户不想回答」处理，让模型自行决断）。 */
        private const val ASK_TIMEOUT_MS = 10 * 60 * 1000L

        /** 画面监视的轮询间隔：越短越及时，但每次都要截帧 + 算哈希。 */
        private const val SCREEN_WATCH_INTERVAL_MS = 1500L

        /** 监视用的截图宽度：给哈希和分块 OCR 都够用，又不至于太慢。 */
        private const val SCREEN_WATCH_WIDTH = 960

        /** 汉明距离小于等于这个值就算「同一画面」（16×16 哈希，抖动一般 0~8 位）。 */
        private const val SCREEN_SAME_BITS = 10

        /** App 自己点完后的静默窗口：这段内的画面变化已由点击回传，不再重复上报。 */
        private const val SELF_ACTION_QUIET_MS = 2500L
    }
}

private fun coord(value: Double): String = String.format(Locale.US, "%.4f", value)
