package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.TavernCard
import me.rerere.rikkahub.data.model.TavernMacros
import me.rerere.rikkahub.data.model.TavernWorldbook

/**
 * 酒馆上下文组装（DSH 缓存架构）。
 *
 * 布局：
 * [0] 静态前缀 system（主提示 + 角色卡字段 + 工具prompt）——卡在会话内不变，前缀字节稳定
 * [1..] 示例消息（mes_example，静态）
 * [..] 历史消息
 * [末尾] 动态尾部 system（世界书命中 + PHI + 深度提示）——每轮追加，不碰前缀
 *
 * 与 CicaTavern 的 cache-aware 组装同思路：世界书内容不按官方位置插进前缀区，
 * 而是集中追加，换取 95%+ 缓存命中。位置语义用标签保留。
 */
object TavernAssembler {

    /** 卡片主提示：卡自带 system_prompt，缺省用官方兜底句 */
    fun mainPrompt(card: TavernCard, userName: String): String =
        card.systemPrompt.ifBlank {
            "Write ${card.name}'s next reply in a fictional chat between ${card.name} and $userName."
        }

    /** 静态前缀里的角色卡字段块（description/personality/scenario），进前缀字节 */
    fun staticCardBlock(card: TavernCard, userName: String): String {
        val t = { s: String -> TavernMacros.replace(s, macroCtx(card, userName)) }
        return buildString {
            card.description.takeIf { it.isNotBlank() }?.let {
                appendLine(); append(t(it))
            }
            card.personality.takeIf { it.isNotBlank() }?.let {
                appendLine(); append(t(it))
            }
            card.scenario.takeIf { it.isNotBlank() }?.let {
                appendLine(); append(t(it))
            }
        }
    }

    /** mes_example → 示例消息（<START> 分块，user/assistant 交替），静态 */
    fun exampleMessages(card: TavernCard, userName: String): List<UIMessage> {
        val raw = card.mesExample
        if (raw.isBlank() || raw.trim().equals("<START>", ignoreCase = true)) return emptyList()
        val text = if (raw.trimStart().startsWith("<START>", ignoreCase = true)) raw else "<START>\n" + raw.trim()
        val blocks = Regex("<START>", RegexOption.IGNORE_CASE).split(text).drop(1)
        return blocks.flatMap { block -> parseExampleBlock(block, card, userName) }
    }

    /**
     * 动态尾部：世界书命中条目 + PHI + 深度提示。
     * [context] 为已截断的历史消息文本（供扫描），每轮重算，只追加不改前缀。
     */
    fun dynamicTail(
        card: TavernCard,
        historyText: String,
        userName: String,
        scanDepth: Int = 2,
        states: Map<Int, TavernWorldbook.EntryState> = emptyMap(),
    ): List<UIMessage> {
        val parts = mutableListOf<String>()

        // 世界书：内嵌条目按 order 排序后合并为一条 system
        if (card.embeddedBook.isNotEmpty()) {
            val entries = TavernWorldbook.select(
                entries = card.embeddedBook,
                context = historyText,
                states = states,
                scanDepth = scanDepth,
            )
            if (entries.isNotEmpty()) {
                val t = { s: String -> TavernMacros.replace(s, macroCtx(card, userName)) }
                parts.add(entries.joinToString("\n\n") { e ->
                    val tag = when (e.position) {
                        0 -> "[before_char]"
                        1 -> "[after_char]"
                        4 -> "[at_depth:${e.depth}]"
                        else -> ""
                    }
                    listOf(tag, t(e.content)).filter { it.isNotBlank() }.joinToString("\n")
                })
            }
        }

        // PHI（历史后指令）与深度提示：官方都贴近上下文末尾
        card.postHistoryInstructions.takeIf { it.isNotBlank() }?.let {
            parts.add(TavernMacros.replace(it, macroCtx(card, userName)))
        }
        card.depthPrompt.takeIf { it.isNotBlank() }?.let {
            parts.add(TavernMacros.replace(it, macroCtx(card, userName)))
        }

        if (parts.isEmpty()) return emptyList()
        return listOf(
            UIMessage.system(parts.joinToString("\n\n")).copy(isSynthetic = true)
        )
    }

    /** 宏上下文 */
    fun macroCtx(card: TavernCard, userName: String, lastMessage: String = "") = TavernMacros.MacroContext(
        charName = card.name,
        userName = userName,
        lastMessage = lastMessage,
        card = card,
    )

    /** <START> 块 → 消息（官方 parseExampleIntoIndividual：前缀行切角色，跳过说明行） */
    private fun parseExampleBlock(block: String, card: TavernCard, userName: String): List<UIMessage> {
        val lines = block.trim().lines()
        val startIndex = if (lines.firstOrNull()?.contains("should talk", ignoreCase = true) == true) 1 else 0
        val result = mutableListOf<UIMessage>()
        val buf = mutableListOf<String>()
        var role: MessageRole? = null

        fun flush() {
            val r = role ?: return
            val content = buf.joinToString("\n").trim()
            if (content.isNotBlank()) {
                result.add(
                    (if (r == MessageRole.USER) UIMessage.user(content) else UIMessage.assistant(content))
                        .copy(isSynthetic = true)
                )
            }
            buf.clear()
        }

        for (i in startIndex until lines.size) {
            val line = lines[i]
            val isUser = line.startsWith("$userName:")
            val isChar = line.startsWith("${card.name}:")
            if (isUser || isChar) {
                flush()
                role = if (isUser) MessageRole.USER else MessageRole.ASSISTANT
                buf.add(line.substringAfter(':'))
            } else {
                buf.add(line)
            }
        }
        flush()
        return result
    }
}
