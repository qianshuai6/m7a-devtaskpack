package com.march7th.hkrpg.dev

import com.march7th.hkrpg.data.model.PackStep
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 一个工程的概览信息。列表只读这份索引，不去解压每个 `.m7atask`
 * （真实复杂包单个就有 1.6MB / 上万步，扫一遍会很慢）。
 */
@Serializable
data class TaskPackProjectSummary(
    /** 存档文件名（不含扩展名），切换/删除都靠它。 */
    val fileId: String = "",
    /** 包 id，仅用于展示与同名判断。 */
    val packId: String = "",
    val name: String = "",
    val version: String = "",
    val stepCount: Int = 0,
    val updatedAt: Long = 0L,
) {
    val displayName: String get() = name.ifBlank { packId.ifBlank { "未命名工程" } }
}

/**
 * 多工程存档：同时写多个任务包时，每个工程单独一个 `.m7atask` 文件。
 *
 * 用原版 `.m7atask` 而不是自定义 JSON，好处是「切到哪个工程」都能直接拿去导出/分享，
 * 不依赖 App 私有格式；`assets/` 里的模板图也原样跟着工程走。
 *
 * 目录结构：
 * ```
 * files/taskpack-dev/taskpack-projects/
 *   index.json          概览索引
 *   proj-1730000000.m7atask
 *   proj-1730001234.m7atask
 * ```
 */
class TaskPackProjectStore(
    rootDir: File,
    private val appVersionCode: Int,
) {
    private val dir = File(rootDir, "taskpack-projects")
    private val indexFile = File(dir, "index.json")
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val sequence = AtomicLong(System.currentTimeMillis())

    private fun ensureDir() {
        if (!dir.isDirectory) dir.mkdirs()
    }

    fun list(): List<TaskPackProjectSummary> {
        ensureDir()
        val raw = runCatching {
            if (indexFile.isFile) json.decodeFromString(INDEX_SERIALIZER, indexFile.readText()) else emptyList()
        }.getOrDefault(emptyList())
        // 索引里记着但文件被外部删掉的条目要过滤掉，避免列表点进去报错。
        return raw
            .filter { File(dir, "${it.fileId}.m7atask").isFile }
            // 同一毫秒保存的两个工程 updatedAt 会相同，再按 fileId 倒序兜底，
            // 保证「最近保存的排在前面」这件事是确定的。
            .sortedWith(
                compareByDescending<TaskPackProjectSummary> { it.updatedAt }
                    .thenByDescending { it.fileId },
            )
    }

    fun load(fileId: String): TaskPackProject? {
        val file = File(dir, "$fileId.m7atask")
        if (!file.isFile) return null
        return runCatching {
            TaskPackArchive.read(file, appVersionCode, validate = false)
        }.getOrNull()
    }

    fun newFileId(): String = "proj-${sequence.incrementAndGet()}"

    /**
     * 写入一个工程并更新索引。[fileId] 为空时自动生成。
     * 返回实际使用的概览（含最终的 fileId），供调用方记住「当前工程」。
     */
    fun save(fileId: String, project: TaskPackProject): TaskPackProjectSummary {
        ensureDir()
        val actualId = fileId.ifBlank { newFileId() }
        val target = File(dir, "$actualId.m7atask")
        val staging = File(dir, "$actualId.tmp")
        staging.outputStream().buffered().use { it.write(project.toZipBytes()) }
        // 覆盖保存时先删旧文件：部分文件系统上 renameTo 不覆盖已存在目标。
        if (target.exists()) target.delete()
        if (!staging.renameTo(target)) {
            staging.delete()
            error("无法写入工程文件：${target.name}")
        }
        val summary = TaskPackProjectSummary(
            fileId = actualId,
            packId = project.pack.id,
            name = project.pack.name,
            version = project.pack.version,
            stepCount = countSteps(project.pack.steps),
            updatedAt = System.currentTimeMillis(),
        )
        val merged = list().filterNot { it.fileId == actualId } + summary
        writeIndex(merged)
        return summary
    }

    /** 只改显示名（不动包文件名，避免列表里正在用的引用失效）。 */
    fun rename(fileId: String, newName: String): List<TaskPackProjectSummary> {
        val updated = list().map {
            if (it.fileId == fileId) {
                // 索引里的 name 是显示名；导出的包名仍以文件内 task.json 为准。
                it.copy(name = newName.trim(), updatedAt = System.currentTimeMillis())
            } else it
        }
        writeIndex(updated)
        return updated
    }

    fun delete(fileId: String): List<TaskPackProjectSummary> {
        runCatching { File(dir, "$fileId.m7atask").delete() }
        runCatching { File(dir, "$fileId.tmp").delete() }
        val rest = list().filterNot { it.fileId == fileId }
        writeIndex(rest)
        return rest
    }

    private fun writeIndex(entries: List<TaskPackProjectSummary>) {
        ensureDir()
        runCatching { indexFile.writeText(json.encodeToString(INDEX_SERIALIZER, entries)) }
    }

    /** 递归数步骤（含 then / else / steps），列表里显示「多少步」才准。 */
    private fun countSteps(items: List<PackStep>): Int {
        var total = 0
        fun visit(list: List<PackStep>) {
            list.forEach { step ->
                total += 1
                step.steps?.let(::visit)
                step.then?.let(::visit)
                step.elseSteps?.let(::visit)
            }
        }
        visit(items)
        return total
    }

    private companion object {
        val INDEX_SERIALIZER = kotlinx.serialization.builtins.ListSerializer(
            TaskPackProjectSummary.serializer(),
        )
    }
}
