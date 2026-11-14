package com.march7th.hkrpg.dev

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * JSON 语法「一键修复」。
 *
 * 手写 / 从别处复制的 task.json 常见几类小毛病：
 * - 结尾多一个逗号（`"steps": [...],}`）；
 * - 用 `//` 或 `/* */` 写注释（JSON 标准不允许）；
 * - 用单引号写字符串、用中文引号 “ ”；
 * - 用全角冒号 `：`、中文逗号 `，`；
 * - Python 风格的 `True` / `False` / `None`；
 * - 文件被截断，末尾少了 `}` / `]`。
 *
 * 这里全部在「字符串外」处理，且**只修语法、不改字段与值**：修完还会真的用
 * kotlinx.serialization 解析一次，解析不过就当作修不了，绝不返回半成品。
 */
object TaskPackJsonRepair {

    data class Result(
        /** 修复后的文本。 */
        val text: String,
        /** 做了哪些修复，供界面显示「已自动修复 N 处」。 */
        val fixes: List<String>,
        /** 修复后的文本能否被 JSON 解析器接受。 */
        val parsed: JsonElement?,
    ) {
        val ok: Boolean get() = parsed != null
    }

    private val parser = Json { ignoreUnknownKeys = true; isLenient = false }

    fun repair(raw: String): Result {
        val fixes = LinkedHashSet<String>()
        val text = normalize(raw, fixes)
        val element = runCatching { parser.parseToJsonElement(text) }.getOrNull()
        return Result(text, fixes.toList(), element)
    }

    private fun normalize(raw: String, fixes: MutableSet<String>): String {
        val out = StringBuilder(raw.length + 16)
        var i = 0
        var inString = false
        /** 当前字符串的结束符：正常是 `"`，从单引号串转过来时是 `'`。 */
        var closing = '"'

        fun fix(note: String) {
            fixes += note
        }

        while (i < raw.length) {
            val c = raw[i]
            if (!inString) {
                when {
                    c == '\uFEFF' || c == '\u200B' -> {
                        fix("去掉 BOM / 零宽字符")
                        i++
                    }
                    c == '"' || c == '\u201C' -> {
                        inString = true
                        closing = '"'
                        out.append('"')
                        i++
                    }
                    c == '\'' || c == '\u2018' -> {
                        inString = true
                        closing = '\''
                        out.append('"')
                        fix("单引号字符串改成双引号")
                        i++
                    }
                    c == '/' && i + 1 < raw.length && raw[i + 1] == '/' -> {
                        while (i < raw.length && raw[i] != '\n') i++
                        fix("去掉 // 注释")
                    }
                    c == '/' && i + 1 < raw.length && raw[i + 1] == '*' -> {
                        i += 2
                        while (i + 1 < raw.length && !(raw[i] == '*' && raw[i + 1] == '/')) i++
                        i = (i + 2).coerceAtMost(raw.length)
                        fix("去掉 /* */ 注释")
                    }
                    c == '\uFF1A' -> {
                        out.append(':')
                        fix("全角冒号改成半角")
                        i++
                    }
                    c == '\uFF0C' -> {
                        out.append(',')
                        fix("中文逗号改成半角")
                        i++
                    }
                    c == '\uFF1B' -> {
                        out.append(';')
                        i++
                    }
                    // Python 风格字面量
                    raw.startsWith("True", i) -> {
                        out.append("true"); i += 4; fix("True/False/None 改成 JSON 字面量")
                    }
                    raw.startsWith("False", i) -> {
                        out.append("false"); i += 5; fix("True/False/None 改成 JSON 字面量")
                    }
                    raw.startsWith("None", i) -> {
                        out.append("null"); i += 4; fix("True/False/None 改成 JSON 字面量")
                    }
                    else -> {
                        out.append(c)
                        i++
                    }
                }
                continue
            }

            // 字符串内部
            when {
                c == '\\' && i + 1 < raw.length -> {
                    out.append(c).append(raw[i + 1])
                    i += 2
                }
                c == closing -> {
                    inString = false
                    out.append('"')
                    i++
                }
                c == '\n' || c == '\r' -> {
                    out.append("\\n")
                    fix("字符串内换行改成 \\n")
                    i++
                }
                c == '"' -> {
                    out.append("\\\"")
                    fix("字符串内双引号转义")
                    i++
                }
                c == '\u201D' && closing == '"' -> {
                    inString = false
                    out.append('"')
                    fix("中文引号改成双引号")
                    i++
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }

        var text = out.toString()
        if (inString) {
            // 末尾字符串没闭合：补一个引号，至少让后面的括号检查能继续。
            text += "\""
            fix("补全未闭合的引号")
        }
        text = dropTrailingCommas(text, fixes)
        text = balanceBrackets(text, fixes)
        return text
    }

    /** 去掉对象/数组最后一个元素后面的逗号，以及连续逗号。 */
    private fun dropTrailingCommas(text: String, fixes: MutableSet<String>): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inString = false
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                out.append(c)
                if (c == '\\' && i + 1 < text.length) {
                    out.append(text[i + 1]); i += 2; continue
                }
                if (c == '"') inString = false
                i++
                continue
            }
            if (c == '"') {
                inString = true; out.append(c); i++; continue
            }
            if (c == ',') {
                var j = i + 1
                while (j < text.length && text[j].isWhitespace()) j++
                val next = text.getOrNull(j)
                if (next == '}' || next == ']' || next == ',') {
                    // 直接跳过这个逗号：前面若还有逗号（`,,`）就只留后面那个的语义
                    fixes += "去掉多余的逗号"
                    i++
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** 末尾少括号时补齐（只补结尾，不猜中间缺失的位置）。 */
    private fun balanceBrackets(text: String, fixes: MutableSet<String>): String {
        val stack = ArrayDeque<Char>()
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (c == '\\') i++
                else if (c == '"') inString = false
                i++
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> stack.addLast('}')
                '[' -> stack.addLast(']')
                '}', ']' -> if (stack.isNotEmpty() && stack.last() == c) stack.removeLast()
            }
            i++
        }
        if (stack.isEmpty()) return text
        fixes += "补全结尾缺失的 ${stack.reversed().joinToString("")}"
        return text.trimEnd().trimEnd(',').let { it + stack.reversed().joinToString("") }
    }
}
