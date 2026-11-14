<div align="center">
  <h1 align="center">
    <img src="./docs/icon.png" width="150">
    <br/>
    March7thAssistant<br/>
    <sub>任务包开发者版 · TaskPack Dev</sub>
  </h1>
</div>

<div align="center">

分支 `m7a-devtaskpack` · 版本 **v1.0.17**（versionCode 28）<br/>
包名 `com.march7th.hkrpg.taskpack` · 可与原版共存安装

Android 8.0+ · arm64-v8a · Shizuku

</div>

<br/>

> 这是 [March7thAssistant Android Local](https://github.com/biirekha857-star/March7thAssistantAndroidlocal) 的**任务包开发分支**。
> 同一套执行能力之上，加了一整套「写任务包」的工具：**实时录制 · 树形编辑 · AI 起草**。
> 导出的仍是被原版直接导入的 `.m7atask` —— **没有新增执行接口、没有改执行器**。

## 这个分支解决什么问题

写 `.m7atask` 原本要手改 `task.json`：坐标靠截图量、嵌套分支靠数括号、模板图要自己裁、`params` 要手写 JSON。步骤一多就没人愿意改第二个版本。

开发者版把这件事变成「在游戏画面上点一遍，回编辑器里改文字」：

| 以前 | 现在 |
|---|---|
| 截图量坐标，手填 `x/y` | 在画面上点一下，自动归一化写入 |
| 猜这步点的是什么 | 自动 OCR 落点文字，写进 `note`：`点击文字「开始挑战」` |
| 数括号找第 37 步 | 步骤卡外显 `#37 tap · 3>1>2`，按 `note` 直接搜 |
| 一个新包复制整个工程 | 多工程并存，切换前自动落盘 |
| 想不出流程怎么写 | 把当前画面交给 AI，缺的界面它自己点开再写 |

## 是 / 不是

| 是 | 不是 |
|---|---|
| 任务包的录制器、编辑器、AI 起草工具 | 新的任务执行器 |
| 产出 `schemaVersion=1`、24 个原有 op、`task.json` + `assets/` | 不新增 op、不改 `ScriptedTaskRunner` |
| 独立包名，可与原版共存 | 不读写原版的数据目录 |
| 坐标与图标只来自你录制或明确提供的素材 | 工具与 AI 都不会自行生成坐标或图标 |

## 五分钟做出第一个包

1. 从 [Releases](https://github.com/qianshuai6/March7thAssistantAndroidlocal/releases/tag/v1.0.17) 装 `TaskPackDeveloper-taskpack-v1.0.17-SIGNED.apk`（覆盖安装不用卸载）。
2. 打开「任务包开发 → 环境」，让 Shizuku 授权（见[下文](#独立包名与-shizuku-授权)）。未授权只能编辑与导出，不能录。
3. 切「实时录制」，在游戏画面上点、长按、拖动 —— 每一下生成一步，落点有文字就写成 `tapText` 并附 `note`。
4. 去「步骤编辑」补字段：`timeoutMs`、`maxTries`、分支条件；坐标不合适就「画面定位 → 重取坐标」。
5. 顶栏「导出」存成 `.m7atask`，装回原版导入试跑；也能直接装到本机试跑。

## 界面：1920×1080 居中，黑边放功能栏

- 虚拟屏恒为 **1920×1080 / dpi 240**，不跟随真机物理分辨率 —— 避免游戏在两套分辨率间反复 `restart`、dpi 跳变导致画面异常。
- **专注画面**：16:9 画面严格居中，全面屏左右多出来的黑边正好放功能栏。左侧是竖向功能 Tab 与录制模式，右侧是工具栏、控制与提示，画面完全不被遮挡、也不非等比拉伸。侧栏宽度按可用高度反算画面宽后取剩余黑边的一半（16:9 屏保底 92 dp）。
- **分栏编辑**：进编辑类 Tab 自动切成左画面 / 右工作区（0.58 : 0.42），容纳步骤树、AI 与模板。顶栏可随时手动切换。
- **真全屏**：隐藏顶栏与两侧栏，只剩纯 16:9 画面，右上角 X 退出。
- 工具条可切「等比完整」（整幅可见）与「等比铺满」（放大裁边），两种都恒等比例；坐标一律归一化到 0～1，不影响包格式。
- 顶栏压到 42 dp 与状态栏共用一条，横屏允许内容延伸到挖孔短边，控件按 `safeDrawing` 避开状态栏、挖孔与手势条，适配小米澎湃 OS3 等机型。

## 录制

| 模式 | 行为 |
|---|---|
| **实时录制**（默认文字 OCR） | 短按 → `tap`，长按 → `longPress`，拖动 → `swipe`。落点识别到文字就写原版已有的 `tapText`（不写坐标、不用模板），识别不到才回退坐标 `tap` |
| **纯框选 region** | 拖框只输出归一化 `[x, y, w, h]` 到顶栏，带一键复制；不 OCR、不注入触摸、不生成步骤 |
| **滑动** | 独立录制 `swipe`，与点击完全分开，避免手势互相判定 |
| **点击 / 滑动自识别** | 一个按键，按手势自动判定 tap / longPress / swipe |
| **拉框 OCR 文字** | 只识别框内文字（ROI 分块 + 小框放大），列出每一行让你挑，生成 `tapText` / `waitText` / `assertText` / `ifText` / `readText`，自动带上框选的 `region`；录制与否都可用，不注入触摸 |
| **框选模板** | 在画面上框选按钮或图标，只把**你框出的真实裁图**存成 `assets/template_*.png`，生成 `tapTemplate` / `waitTemplate` 等 |
| **静默自适应** | 边玩边记：录制中只同步记坐标与时序，OCR 与截图全在后台协程，不弹窗、不写文件、不阻塞手势队列。暂停后弹确认面板，逐条附**该动作发生时的截图**、坐标、时长与自动取到的文字，可勾选、改类型；确认后一次性生成步骤（一条撤销记录） |

**取词为什么不会糊**：原 `OcrEngine.detect()` 会把最长边超过 960 px 的帧整体缩小，横屏 2400×1080 被压成 960×432，小字直接丢。开发者版不改原引擎，改为按点击点裁 900×600 主 ROI、560×380 紧邻 ROI 触发放大识别，再以全帧低阈值回退取最近文本框；给 AI 的全屏 OCR 则按 ≤960 px 分块。

**静默录制不会掉步也不会记重**：同一次触摸的多次回调在 350 ms、同点窗口（归一化 0.006 ≈ 12 px @1920）内合并成一条；停止时与已有步骤重复的动作标「重复」并默认不勾选，点全选也不会选上；单次最多保留 400 个动作。

## 编辑

| 能力 | 说明 |
|---|---|
| 树形结构 | `ifExpr` / `ifCount` 的 `then` / `else` / `steps` 按层级展开折叠，任意层级就地改字段、插入、移动、删除、重取坐标；实测 10548 步 / 1379 个分支 / 3 层嵌套可编辑 |
| 24 op 全字段 | 一键插入任意 op 骨架，编辑该动作的**全部字段**（`stable` / `offsetX` / `offsetY` / `tolerance` / `minArea` / `maxArea` / `compare` / `value` / `ratio` / `intervalMs` / `maxTries` / `times` / `ok` 及分支 JSON 子步骤） |
| 外显 note 与路径 | 折叠态直接显示 `#3 tap · 3>1>2`，与 `task.json` 的嵌套位置一一对应，不用展开就能按 `note` 找代码 |
| 搜索 | 支持 `note` 内容、op、字段名、字段值、变量名（如 `点击文字`、`durationMs`、`9000`），可叠加 op 筛选；搜索遍历整包，配合分页与结果上限，万步包不卡 |
| 搜索后定位 | 命中项点「定位到该步」：清掉筛选、展开沿途祖先、补齐分页并滚到那一步，直接在原位置改 |
| 画面定位 | 只在左侧画面叠加该步的点 / 滑动线 / `region`，**不跳转页面**；任意层级都能定位与重取坐标，结果统一显示在顶栏下方并可复制回填 |
| 延迟工具 | 全局在每步后插 `wait`，或给已有 `wait.ms` / `durationMs` / `timeoutMs` / `intervalMs` 一键 `+N ms`（可负数），递归作用于嵌套分支并守住校验上限 |
| 配置项 `params` | 增删改 string / int / bool、默认值、int min/max；每行「⋮」支持复制（key 自动 `_copy`）、上移、下移、删除 |
| 快速索引 | 写任意步骤时可一键插入已有 `{key}`、变量名与最近框选的 `region` |
| 多工程 | 顶栏「工程」：新建 / 保存 / 另存为 / 改名 / 删除 / 切换。每个工程独立存成 `.m7atask`，切换前自动落盘，改多个包不会丢内容 |
| 草稿与导入 | 会话草稿自动保存，重启恢复上次未导出的内容；可载入现有 `.m7atask` / `task.json` 继续编辑，保留 `params` 与素材引用 |
| 一键修 JSON | 载入或 AI 返回的 JSON 有语法问题时自动修：尾逗号、`//` 与 `/* */` 注释、单引号、中文引号、全角冒号/逗号、`True/False/None`、截断缺右括号。只修语法不改字段，修不好按原错误报出 |
| 校验与导出 | 导出前复用原项目 `CustomTaskPackValidator`；可存 `.m7atask` 或装到本机试跑；`minAppVersionCode` 直接填，不必手改 JSON |

## AI 起草

接 OpenAI 兼容的 Chat Completions 服务，把「看画面 → 点 → 再看 → 继续写」串成闭环。

- **默认只发文字**：请求只带当前画面 OCR，普通文本模型也能用；模型主动输出 `[[NEED_SCREENSHOT]]` 才抓帧以 `image_url` 回填（最多 3 张，可完全禁用）。
- **主动点击**：模型要进入的界面还没出现在 OCR / 截图里时，**必须先**输出 `[[CLICK:x,y]]`，App 真实点击后把新画面的 OCR 回喂，最多 8 次。首轮直接交卷而一次没点过，会提醒一次「先探索再定稿」。
- **坐标边界**：`[[CLICK:x,y]]` 只是生成期的探索，**不会写进包**；`task.json` 里的 `x/y/region` 只能来自你录制或明确提供的坐标，没有就改用 `tapText` / `waitText` / `tapTemplate`。
- **可打断**：生成中随时点「打断生成」，同时取消协程与底层 OkHttp 请求，不等读超时；本轮结果丢弃，不留残缺包。
- **读规范**：把《任务文件开发规范》全文作为独立 system 消息注入（内置离线全文，也可添加多个规范链接，GitHub `blob` 自动转 `raw`，失败回退内置）。
- **按需取模板**：注入整包模板、24 个步骤骨架与 436 个图标模板的索引摘要；模型输出 `[[NEED_TEMPLATE:关键词]]` 时回喂确切 `assets/` 路径与字段写法（最多 4 次），命中的图标自动注册进当前工程。
- **校验回喂**：生成结果过一遍原版校验器，最多回喂修正 3 轮。

## 模板库

| 类别 | 内容 |
|---|---|
| 整包模板 | 仓库 `site/taskpacks/` 6 个 + `examples/` 1 个 `.m7atask`，含真实模板图，可「载入为新工程」或「追加步骤」 |
| 步骤模板 | 覆盖 `docs/task-file.md` 全部 24 个 op 的骨架；新建步骤可按场景搜索（点击文字 / 翻页 / 颜色计数…）后插入 |
| 图标模板 | 仓库 `starrail/images` 410 个 + 整包模板内 26 张，共 436 张真实素材，带缩略图、分类、中英文搜索 |
| 角色索引 | 官方中文译名（黄泉 / 砂金 / 流萤…，含命途与时装形态），中文直接搜 |

每类默认铺**两行网格**（整包与步骤 2 列、图标 4 列），超出点「展开更多」；类型按钮也分两行，窄屏不挤压。选中图标可一键插入 `tapTemplate` / `waitTemplate` / `assertTemplate` / `ifTemplate`，原图原样进 `assets/` 随包导出。

## 版本号

`version` 形如 `1.2-4.7`：前段任务包版本，后段崩铁游戏版本，短横线连接。

- 「包版本」栏可直接粘贴整串 `1.2-4.7`，自动拆成两栏，**不会**再补一个短横线变成 `1.2-4.7-4.7`
- 不是这种格式时（如 `1.0.0`，或暂时没有对应游戏版本）只填「包版本」，「游戏版本」留空，最终 `version` 就是 `1.0.0`，不带尾部短横线
- 旧包的 `1.2.4.7` 四点写法导入时仍自动解析成同样两段

## 导出与兼容性

```
demo-1.2-4.7.m7atask      # 就是个 zip
├── task.json             # schemaVersion=1，24 个原有 op
└── assets/               # 你框选/插入的模板图
```

- 执行器不认识的东西一个都没有：不新增字段、不新增 op、不改 `ScriptedTaskRunner`
- `params` 的 key 沿用原版校验规则（字母数字下划线），`minAppVersionCode` 低于该版本会被原版拒绝导入
- 开发版校验器与原版同一份，能导出的包原版一定能导入

## 独立包名与 Shizuku 授权

开发者版用独立 `applicationId`（`com.march7th.hkrpg.taskpack`），桌面名「任务包开发者」，可与原版共存。实时录制依赖 Shizuku 提供截图与触摸注入。

1. 首次安装、换包名或换签名后，Shizuku 会把它当**新应用**。打开「环境」页会自动发起一次授权，弹窗允许即可；也可用「重新申请权限」「打开 Shizuku」「复制包名」。
2. 「环境」页显示本包实际包名、`API_V23` 权限声明、Shizuku 版本、运行 UID、服务连接与授权状态，并按「未连接 / 已连接未授权 / 已授权」给出对应步骤。

> **为什么新包名会「识别不到 Shizuku」**：Shizuku 不扫全部已装应用，而是在进程首次活跃时检查是否声明 `moe.shizuku.manager.permission.API_V23`，然后向 `<包名>.shizuku` 投递 binder，每个 UID 只投一次。换包名后首次投递没发生，就既不在授权列表里、也发不出请求。
>
> 遇到「未连接（binder 未送达）」：① 确认 Shizuku 首页是「正在运行」（adb / 无线调试启动的，每次开机要重启）；② **强制停止本应用后重开**，让 Shizuku 重新投递；③ 仍不行就在 Shizuku 里停止服务再启动；④ 变「已连接」后点「重新申请权限」。

## 构建

| 组件 | 版本 |
|---|---|
| JDK | 17+ |
| Android SDK | compileSdk 37 |
| NDK | 29.0.14206865（必需，编 `libbridge.so`） |
| CMake | 3.22.1 |
| 内存 | 建议 8 GB 可用，低于此值 R8 易卡死 |

```bash
sdkmanager --install "ndk;29.0.14206865"   # 必需

# 可选签名：local.properties
#   KEYSTORE_PATH=/path/to/your.jks
#   KEYSTORE_PASSWORD=***
#   KEY_ALIAS=***
#   KEY_PASSWORD=***

./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

R8 强度在 `local.properties` 里配：`maa.r8=none|light|full`（默认 `light`）。

> `libbridge.so` 的 JNI 类名不能混淆，`proguard-rules.pro` 已用 `-keep class ... { *; }` 锁定；改成 `{ native <methods>; }` 会 `UnsatisfiedLinkError: JNI_ERR returned from JNI_OnLoad`。

## 常见问题

**画面被拉伸 / 比例不对？** 虚拟屏固定 1920×1080，`SurfaceView` 同尺寸等比居中投帧；侧栏吃掉的是黑边余量，不会挤画面。若仍异常，先确认没装成跟随真机分辨率的旧版（v1.0.13 及以前有此问题）。

**步骤编辑打不开 / 点进去闪退？** v1.0.14 修过一个正则问题：ICU 正则不容忍未转义的 `}`，解析 `{变量}` 时抛 `PatternSyntaxException`。升级即可。

**静默录制少记或多记？** 单次上限 400 个动作；重复判定阈值 0.006（约 12 px / 1920），手指抖动大时可能漏判，面板里手动取消勾选即可。

**AI 不点击、直接交 JSON？** 先确认「环境」页 Shizuku 是已授权 —— 未连接时点击会被拒并回喂「服务未连接」。其次确认生成页的自动点击开关是开的；v1.0.17 起协议改成强制，未见过的界面必须先点。

**导入老包报 JSON 语法错？** 直接导，v1.0.17 会自动修尾逗号、注释、单引号、全角符号与截断，提示里会写修了哪几类。

## 已知限制

- 实时录制、AI 主动点击依赖 Shizuku；未授权只能编辑与导出。
- 静默录制最多 400 个动作，超出不再附图。
- 部分 ROM 上虚拟屏尺寸回调顺序不同，画面偶发需要重进页面才绑定。
- 任务包坐标必须由用户录制或明确提供；AI 生成期的探索点击不写入包。
- 自动化本身有账号风险，见下文免责声明。

## 上游与致谢

本分支 fork 自 [biirekha857-star/March7thAssistantAndroidlocal](https://github.com/biirekha857-star/March7thAssistantAndroidlocal)，其执行能力与任务逻辑来自这些项目 —— **代码是他们写的，功劳是他们的**，请优先给上游点 Star。

| 项目 | 作者 | 用途 | 许可证 |
|---|---|---|---|
| [March7thAssistant](https://github.com/moesnow/March7thAssistant) | [@moesnow](https://github.com/moesnow) | 崩铁任务逻辑来源 | GPL-3.0 |
| [MAA-Meow](https://github.com/Aliothmoon/MAA-Meow) | [@Aliothmoon](https://github.com/Aliothmoon) | 技术底座：虚拟屏采集、Shizuku 特权服务、Compose UI、任务链框架 | AGPL-3.0 |
| [MaaAssistantArknights](https://github.com/MaaAssistantArknights/MaaAssistantArknights) | [@MistEO](https://github.com/MistEO) 与 MAA 开发组 | MAA 内核本体 | AGPL-3.0 |
| [RapidOCR](https://github.com/RapidAI/RapidOCR) | RapidAI | OCR 模型 | Apache-2.0 |
| [OpenCV](https://opencv.org/) | OpenCV 团队 | 模板匹配与图像处理 | Apache-2.0 |

## 免责声明

1. 本分支仅供**学习与技术交流**，基于虚拟屏、图像识别与 Shizuku 提权实现，不修改游戏文件、不注入游戏进程、不修改内存。
2. **使用自动化工具可能违反游戏用户协议，存在账号被封禁的风险**，风险由使用者自行承担，作者与贡献者不承担任何责任。
3. **请勿在米游社等官方社区讨论、宣传或分享本项目**。
4. 本项目完全免费开源，付费渠道获得即为被骗，请退款并举报。
5. 不对任何数据丢失、账号损失、设备损坏承担责任。
6. 使用即表示已阅读、理解并同意以上全部条款。

## 许可证

基于上游 [MAA-Meow](https://github.com/Aliothmoon/MAA-Meow)（AGPL-3.0）二次开发，衍生作品沿用同一许可证，因此本项目同样以 [**AGPL-3.0**](LICENSE) 发布。

| 场景 | 要求 |
|---|---|
| 自己用、自己改 | 随便用，不公开也行 |
| 分发修改版 | 必须开源，且同样用 AGPL |
| 作为网络服务提供 | 必须向用户提供完整源码 |
