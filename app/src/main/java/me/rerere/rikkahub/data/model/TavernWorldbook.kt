package me.rerere.rikkahub.data.model

import kotlin.random.Random

/**
 * 世界书匹配引擎（SillyTavern world-info 语义，参照 CicaTavern worldbook.mjs）。
 *
 * 纯函数式：输入条目 + 扫描文本 + 各条目状态，输出本轮激活条目。
 * 无 Android 依赖，可单元测试。
 */
object TavernWorldbook {

    /** 各条目跨轮状态（粘性剩余轮数/冷却剩余轮数），按 uid 记 */
    data class EntryState(
        val stickyTurnsLeft: Int = 0,
        val cooldownTurnsLeft: Int = 0,
        val delayTurnsLeft: Int = 0,
        val activeThisTurn: Boolean = false,
    )

    /** 单轮匹配结果：激活的条目（已排序、已分组选胜、已扣预算外） */
    fun select(
        entries: List<TavernBookEntry>,
        context: String,
        states: Map<Int, EntryState> = emptyMap(),
        scanDepth: Int = 2,
        budgetTokens: Int = 0,          // 0 = 不限制
        recursive: Boolean = false,
        maxRecursionSteps: Int = 0,
        random: Random = Random.Default,
    ): List<TavernBookEntry> {
        val scanText = if (scanDepth <= 0) "" else context
        val activated = mutableListOf<TavernBookEntry>()

        for (entry in entries) {
            if (!entry.enabled) continue
            val st = states[entry.uid] ?: EntryState()

            // 延迟：还没到可触发的轮数
            if (entry.delay > 0 && st.delayTurnsLeft > 0) continue
            // 冷却中不可触发（粘性除外：粘着的条目续期不走概率与冷却）
            val sticky = entry.sticky > 0 && st.stickyTurnsLeft > 0
            if (!sticky && st.cooldownTurnsLeft > 0) continue

            // 概率判定（粘性续期跳过；useProbability=false 跳过）
            if (!sticky && entry.useProbability && entry.probability < 100) {
                if (random.nextInt(100) >= entry.probability) continue
            }

            // 常驻（蓝灯）直接激活；否则按关键词判定
            val hit = entry.constant || matches(entry, scanText)
            if (hit) activated.add(entry)
        }

        // 分组选胜：同组只留一个（权重随机）
        val winners = selectGroupWinners(activated, random)
        // order 越小越靠前（官方 insertion_order）
        return winners.sortedWith(compareBy({ it.order }, { it.uid }))
    }

    /** 条目是否命中扫描文本（关键词 + selective logic） */
    fun matches(entry: TavernBookEntry, context: String): Boolean {
        if (entry.keys.isEmpty() && entry.secondaryKeys.isEmpty()) return false
        return if (entry.selective && entry.secondaryKeys.isNotEmpty()) {
            // 官方：主关键词必须先命中任一，否则直接失败
            if (entry.keys.isEmpty()) return false
            val anyPrimary = entry.keys.any { keyMatches(it, context, entry) }
            if (!anyPrimary) return false
            val anySecondary = entry.secondaryKeys.any { keyMatches(it, context, entry) }
            val allSecondary = entry.secondaryKeys.all { keyMatches(it, context, entry) }
            when (entry.selectiveLogic) {
                0 -> anySecondary            // AND_ANY（默认）
                1 -> !allSecondary           // NOT_ALL
                2 -> !anySecondary           // NOT_ANY
                3 -> allSecondary            // AND_ALL
                else -> anySecondary
            }
        } else {
            entry.keys.any { keyMatches(it, context, entry) }
        }
    }

    /** 单个关键词匹配：/pattern/flags 内联正则优先（官方 parseRegexFromString） */
    private fun keyMatches(key: String, context: String, entry: TavernBookEntry): Boolean {
        inlineRegex(key)?.let { return it.containsMatchIn(context) }
        return if (entry.matchWholeWords && !isRegexKey(key, entry)) {
            // 整词：非正则关键词用 \b 包裹
            val pattern = "\\b${Regex.escape(key.trim())}\\b"
            val opts = if (entry.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            runCatching { Regex(pattern, opts).containsMatchIn(context) }.getOrDefault(false)
        } else if (entry.caseSensitive) {
            context.contains(key)
        } else {
            context.contains(key, ignoreCase = true)
        }
    }

    private fun isRegexKey(key: String, entry: TavernBookEntry): Boolean =
        entry.scanDepth != Int.MIN_VALUE && key.startsWith("/") && key.lastIndexOf("/") > 0

    /** 官方 parseRegexFromString：/pattern/flags（flags ∈ gimsuy） */
    private fun inlineRegex(input: String): Regex? {
        if (!input.startsWith("/")) return null
        val last = input.lastIndexOf('/')
        if (last <= 0) return null
        val pattern = input.substring(1, last)
        val flags = input.substring(last + 1)
        if (flags.any { it !in "gimsuy" }) return null
        if (Regex("(^|[^\\\\])/").containsMatchIn(pattern)) return null
        return runCatching {
            val opts = buildSet {
                if ('i' in flags) add(RegexOption.IGNORE_CASE)
                if ('m' in flags) add(RegexOption.MULTILINE)
                if ('s' in flags) add(RegexOption.DOT_MATCHES_ALL)
            }
            Regex(pattern.replace("\\/", "/"), opts)
        }.getOrNull()
    }

    /** 同组互斥：group 非空的条目按组各留一个（groupWeight 加权随机） */
    private fun selectGroupWinners(entries: List<TavernBookEntry>, random: Random): List<TavernBookEntry> {
        val grouped = entries.filter { it.group.isNotBlank() }.groupBy { it.group }
        val losers = mutableSetOf<TavernBookEntry>()
        for ((_, group) in grouped) {
            if (group.size <= 1) continue
            // 权重随机选一个赢家
            val total = group.sumOf { it.groupWeight.coerceAtLeast(0) }
            var pick = if (total > 0) random.nextInt(total) else 0
            val winner = group.firstOrNull { e ->
                pick -= e.groupWeight.coerceAtLeast(0)
                pick < 0
            } ?: group.first()
            group.forEach { if (it !== winner) losers.add(it) }
        }
        return entries.filterNot { it in losers }
    }
}
