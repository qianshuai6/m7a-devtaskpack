package com.march7th.hkrpg

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android 的 ICU 正则不能容忍**没有对应 `{` 的裸 `}`**，会抛
 * `PatternSyntaxException`；而桌面 JVM 的 `java.util.regex` 是宽容的。
 *
 * 这意味着写在 `object` / `companion object` 里的这类正则，异常发生在类初始化，
 * 症状是「一进某个页面 / 一点某个按钮就整进程闪退」，编译和本地单测都拦不住
 * （已经踩过两次：步骤编辑页、校验导出页的「一键修复」）。
 *
 * 这个测试直接扫源码里的正则字面量，把裸 `}` 找出来 —— 包括这次修的那处，
 * 谁再写新的也会在这里红。
 */
class RegexPortabilityContractTest {

    @Test
    fun regexLiteralsHaveNoBareClosingBrace() {
        val root = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File("../app/src/main/java"),
        ).firstOrNull { it.isDirectory } ?: error("找不到 src/main/java")

        val offenders = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                regexLiterals(file.readText()).forEach { literal ->
                    if (hasBareClosingBrace(literal)) {
                        offenders += "${file.path}: $literal"
                    }
                }
            }

        assertTrue(
            "以下正则里的 `}` 没有配对的 `{`，在 Android 上会抛 PatternSyntaxException：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** 抽出所有 `Regex("...")` / `Regex("""...""")` 的字面量，跳过拼接与变量用法。 */
    private fun regexLiterals(text: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        val marker = "Regex("
        while (true) {
            val idx = text.indexOf(marker, i)
            if (idx < 0) break
            var p = idx + marker.length
            while (p < text.length && text[p].isWhitespace()) p++
            if (p >= text.length || text[p] != '"') {
                i = idx + marker.length
                continue
            }
            if (text.startsWith("\"\"\"", p)) {
                val end = text.indexOf("\"\"\"", p + 3)
                if (end < 0) break
                out += text.substring(p + 3, end)
                i = end + 3
            } else {
                val sb = StringBuilder()
                p++
                while (p < text.length && text[p] != '"') {
                    if (text[p] == '\\' && p + 1 < text.length) {
                        sb.append(text[p]).append(text[p + 1])
                        p += 2
                    } else {
                        sb.append(text[p])
                        p++
                    }
                }
                out += sb.toString()
                i = p + 1
            }
        }
        return out
    }

    /**
     * 按转义符与量词配对走一遍：`\d{1,2}` 的 `}` 有配对的 `{`，合法；
     * 单独出现的 `}` 才是 ICU 会拒绝的那个。
     */
    private fun hasBareClosingBrace(literal: String): Boolean {
        var depth = 0
        var i = 0
        while (i < literal.length) {
            when (literal[i]) {
                '\\' -> {
                    i += 2
                    continue
                }

                '{' -> depth++
                '}' -> {
                    if (depth == 0) return true
                    depth--
                }
            }
            i++
        }
        return false
    }
}
