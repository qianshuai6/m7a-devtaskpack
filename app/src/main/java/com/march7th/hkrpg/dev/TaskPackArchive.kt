package com.march7th.hkrpg.dev

import com.march7th.hkrpg.data.model.CustomTaskPack
import com.march7th.hkrpg.data.resource.CustomTaskPackValidator
import com.march7th.hkrpg.utils.JsonUtils
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json

/**
 * 开发者工具读取现有任务包，以及保存可恢复草稿。
 *
 * 读写都复用原版 `.m7atask` 容器：根目录 `task.json` + `assets/`。
 * 草稿并不是另一种工程格式，因此随时可导出、导入和交给原版 App。
 */
object TaskPackArchive {
    private val json = Json(JsonUtils.common) { ignoreUnknownKeys = true }

    fun read(
        input: InputStream,
        appVersionCode: Int,
        validate: Boolean = true,
    ): TaskPackProject {
        val source = input as? BufferedInputStream ?: BufferedInputStream(input)
        source.mark(4)
        val header = ByteArray(4)
        val count = source.read(header)
        source.reset()
        val project = if (count == 4 && header.contentEquals(ZIP_MAGIC)) {
            readZip(source)
        } else {
            val manifest = readEntry(source, "task.json", MAX_MANIFEST_BYTES)
            TaskPackProject(decodeManifest(manifest))
        }
        if (validate) {
            val errors = CustomTaskPackValidator.validate(
                project.pack, appVersionCode, project.assets.keys,
            )
            require(errors.isEmpty()) { errors.joinToString("\n") }
        }
        return project
    }

    fun read(file: File, appVersionCode: Int, validate: Boolean = true): TaskPackProject =
        file.inputStream().buffered().use { read(it, appVersionCode, validate) }

    /**
     * 读取并**自动修复 JSON 语法问题**（多逗号 / 注释 / 单引号 / 全角符号 / 缺右括号）。
     *
     * 先按原样读；只有解析失败时才试修复，修完仍解析不过就抛原错误，
     * 避免把「字段错了」误当成「语法错了」掩盖掉。返回（工程, 修复说明列表）。
     */
    fun readRepairing(
        input: InputStream,
        appVersionCode: Int,
        validate: Boolean = true,
    ): Pair<TaskPackProject, List<String>> {
        val raw = input.readBytes()
        val direct = runCatching { read(raw.inputStream(), appVersionCode, validate) }
        direct.getOrNull()?.let { return it to emptyList() }
        val original = direct.exceptionOrNull() ?: error("读取失败")
        // 超大包不在内存里做文本修复，直接报原始错误。
        if (raw.size > MAX_REPAIR_BYTES) throw original
        val repaired = runCatching { readRepaired(raw, appVersionCode, validate) }.getOrElse { throw original }
        return repaired
    }

    private fun readRepaired(
        raw: ByteArray,
        appVersionCode: Int,
        validate: Boolean,
    ): Pair<TaskPackProject, List<String>> {
        val isZip = raw.size >= 4 && raw.copyOfRange(0, 4).contentEquals(ZIP_MAGIC)
        val (manifestBytes, assets) = if (isZip) {
            readZipRaw(raw.inputStream())
        } else {
            raw to emptyMap()
        }
        val result = TaskPackJsonRepair.repair(manifestBytes.toString(Charsets.UTF_8))
        require(result.parsed != null) { "JSON 语法修复后仍无法解析" }
        val pack = json.decodeFromString(CustomTaskPack.serializer(), result.text)
        val project = TaskPackProject(pack, assets)
        if (validate) {
            val errors = CustomTaskPackValidator.validate(
                project.pack, appVersionCode, project.assets.keys,
            )
            require(errors.isEmpty()) { errors.joinToString("\n") }
        }
        return project to result.fixes
    }

    private fun readZip(input: InputStream): TaskPackProject {
        val (manifest, assets) = readZipRaw(input)
        return TaskPackProject(decodeManifest(manifest), assets)
    }

    private fun readZipRaw(input: InputStream): Pair<ByteArray, Map<String, ByteArray>> {
        var manifest: ByteArray? = null
        val assets = linkedMapOf<String, ByteArray>()
        var entries = 0
        var totalBytes = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                require(++entries <= MAX_ENTRIES) { "压缩包内文件超过 $MAX_ENTRIES 个" }
                val name = entry.name.replace('\\', '/').removePrefix("./")
                require(name.isNotBlank() && !name.startsWith("/") &&
                    name.split('/').none { it.isBlank() || it == ".." }) {
                    "压缩包内含非法路径：$name"
                }
                val isManifest = name == "task.json"
                require(isManifest || name.startsWith("assets/")) {
                    "忽略任务包以外的文件：$name"
                }
                val limit = if (isManifest) MAX_MANIFEST_BYTES else MAX_ENTRY_BYTES
                val bytes = ByteArrayOutputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        totalBytes += n
                        require(totalBytes <= MAX_TOTAL_BYTES) { "任务包解压后超过 500MB" }
                        output.write(buffer, 0, n)
                        require(output.size().toLong() <= limit) { "$name 文件过大" }
                    }
                    output.toByteArray()
                }
                if (isManifest) {
                    require(manifest == null) { "压缩包里有多个 task.json" }
                    manifest = bytes
                } else {
                    require(!assets.containsKey(name)) { "模板文件重复：$name" }
                    assets[name] = bytes
                }
            }
        }
        val bytes = requireNotNull(manifest) { "压缩包里没有根目录 task.json" }
        return bytes to assets
    }

    private fun decodeManifest(bytes: ByteArray): CustomTaskPack =
        json.decodeFromString(CustomTaskPack.serializer(), bytes.toString(Charsets.UTF_8))

    private fun readEntry(input: InputStream, name: String, limit: Long): ByteArray {
        val output = ByteArrayOutputStream()
        input.use {
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = it.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                output.write(buffer, 0, n)
                require(output.size().toLong() <= limit) { "$name 文件过大" }
            }
        }
        return output.toByteArray()
    }

    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private const val MAX_MANIFEST_BYTES = 4L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 500L * 1024 * 1024
    /** 超过这个体积就不在内存里做 JSON 文本修复（正常任务包远小于它）。 */
    private const val MAX_REPAIR_BYTES = 16L * 1024 * 1024

    private const val MAX_ENTRIES = 200
}

/** 私有目录中的原子草稿；不会把 API Key、AI 提示词等写进任务包。 */
class TaskPackDraftStore(
    rootDir: File,
    private val appVersionCode: Int,
) {
    private val draft = File(rootDir, "taskpack-draft.m7atask")
    private val staging = File(rootDir, "taskpack-draft.tmp")

    fun load(): TaskPackProject? {
        if (!draft.isFile) return null
        // 草稿允许暂时不完整（例如刚新建、还没录第一步或还没填元数据）。
        return runCatching { TaskPackArchive.read(draft, appVersionCode, validate = false) }.getOrNull()
    }

    fun save(project: TaskPackProject) {
        draft.parentFile?.mkdirs()
        staging.outputStream().buffered().use { it.write(project.toZipBytes()) }
        runCatching {
            Files.move(
                staging.toPath(), draft.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
        }.onFailure { first ->
            if (first !is AtomicMoveNotSupportedException) {
                staging.delete()
                throw first
            }
            runCatching {
                Files.move(staging.toPath(), draft.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }.onFailure {
                staging.delete()
                error("无法保存草稿：${it.message}")
            }
        }
    }
}
