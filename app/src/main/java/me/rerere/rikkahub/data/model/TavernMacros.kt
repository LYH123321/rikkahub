package me.rerere.rikkahub.data.model

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.random.Random

/**
 * 酒馆核心宏（参照 CicaTavern replaceMacros / SillyTavern macros.js）。
 *
 * 自包含：调用方传入卡名、用户名、可选的 lastMessage，替换 {{char}} 等宏。
 * 未知宏原样保留，不破坏模板。
 */
object TavernMacros {

    data class MacroContext(
        val charName: String,
        val userName: String,
        val personaDescription: String = "",
        val lastMessage: String = "",
        val card: TavernCard? = null,
        val now: LocalDate = LocalDate.now(),
        val random: Random = Random.Default,
    )

    /** 替换一段文本里的全部已知宏；{{getvar::x}} 这类变量宏由调用方先处理 */
    fun replace(text: String, ctx: MacroContext): String {
        if (!text.contains("{{")) return text
        var out = text
        val card = ctx.card

        // 角色与用户
        out = out.replace("{{char}}", ctx.charName, ignoreCase = true)
            .replace("{{user}}", ctx.userName, ignoreCase = true)
            .replace("<BOT>", ctx.charName)
            .replace("<USER>", ctx.userName)

        // 时间类
        out = out.replace("{{time}}", LocalTime.now()
            .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())), ignoreCase = true)
        out = out.replace("{{date}}", ctx.now.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())), ignoreCase = true)
        out = out.replace("{{weekday}}", ctx.now.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault()), ignoreCase = true)

        // 随机数：{{random:1,100}} 或 {{random}}
        out = Regex("\\{\\{random:(-?\\d+)\\s*,\\s*(-?\\d+)\\}\\}", RegexOption.IGNORE_CASE).replace(out) { m ->
            val (a, b) = m.destructured
            val lo = a.toInt(); val hi = b.toInt()
            (if (lo <= hi) random(lo, hi) else random(hi, lo)).toString()
        }
        out = out.replace("{{random}}", ctx.random.nextInt(1, 101).toString(), ignoreCase = true)

        // 掷骰：{{roll:1d6}} / {{roll:2d20+3}}
        out = Regex("\\{\\{roll:\\s*(\\d*)d(\\d+)([+-]\\d+)?\\s*\\}\\}", RegexOption.IGNORE_CASE).replace(out) { m ->
            val count = (m.groupValues[1].ifBlank { "1" }).toInt().coerceIn(1, 100)
            val sides = m.groupValues[2].toInt().coerceAtLeast(1)
            val mod = m.groupValues[3].toIntOrNull() ?: 0
            ((1..count).sumOf { ctx.random.nextInt(1, sides + 1) } + mod).toString()
        }

        // 卡片字段（有卡才替换，避免误伤普通文本）
        if (card != null) {
            out = out
                .replace("{{description}}", card.description, ignoreCase = true)
                .replace("{{personality}}", card.personality, ignoreCase = true)
                .replace("{{scenario}}", card.scenario, ignoreCase = true)
                .replace("{{mesExample}}", card.mesExample, ignoreCase = true)
                .replace("{{systemPrompt}}", card.systemPrompt, ignoreCase = true)
        }
        out = out.replace("{{persona}}", ctx.personaDescription, ignoreCase = true)
        out = out.replace("{{lastMessage}}", ctx.lastMessage, ignoreCase = true)

        // {{//注释}} 官方注释宏 → 删除
        out = Regex("\\{\\{//[^}]*\\}\\}").replace(out, "")
        return out
    }

    private fun random(lo: Int, hi: Int): Int = Random.nextInt(lo, hi + 1)
}
