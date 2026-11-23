package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

/**
 * 酒馆角色卡（SillyTavern V1/V2/V3 兼容，字段参照 CicaTavern normalizeCard）。
 *
 * 自包含数据类：只保留提示词组装需要的字段，不依赖 Settings 或其他模块。
 * 角色卡导入会话后不变——组装请求时进入静态前缀，DSH 缓存安全。
 */
@Serializable
data class TavernCard(
    val spec: String = "",               // "chara_card_v2" / "chara_card_v3" / "v1"
    val specVersion: String = "",
    val name: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val firstMessage: String = "",       // 开场白 first_mes
    val alternateGreetings: List<String> = emptyList(), // alternate_greetings
    val mesExample: String = "",         // 示例对话 <START> 分块
    val systemPrompt: String = "",       // 卡片自带 system_prompt
    val postHistoryInstructions: String = "", // PHI：历史后指令
    val creatorNotes: String = "",       // creator_notes / creatorcomment
    val creator: String = "",
    val tags: List<String> = emptyList(),
    val characterVersion: String = "",
    val depthPrompt: String = "",        // extensions.depth_prompt.prompt
    val depthPromptDepth: Int = 4,       // extensions.depth_prompt.depth
    val depthPromptRole: String = "system", // extensions.depth_prompt.role: system/user/assistant
    val embeddedBook: List<TavernBookEntry> = emptyList(), // character_book 内嵌世界书
    val regexScripts: List<TavernRegexScript> = emptyList(), // extensions.regex_scripts
) {
    /** 全部开场白：first_mes + alternate_greetings */
    val allGreetings: List<String>
        get() = buildList {
            if (firstMessage.isNotBlank()) add(firstMessage)
            addAll(alternateGreetings.filter { it.isNotBlank() })
        }
}

/**
 * 世界书条目（SillyTavern world info，参照 CicaTavern worldbook.mjs）。
 * 字段名对齐官方 JSON，导入导出无损。
 */
@Serializable
data class TavernBookEntry(
    val uid: Int = 0,
    val comment: String = "",            // 条目名/备注
    val keys: List<String> = emptyList(),        // 主关键词 key
    val secondaryKeys: List<String> = emptyList(), // 二级关键词 keysecondary
    val selective: Boolean = false,      // 启用二级关键词逻辑
    val selectiveLogic: Int = 0,         // 0=AND_ANY 1=NOT_ALL 2=NOT_ANY 3=AND_ALL
    val content: String = "",            // 注入内容
    val constant: Boolean = false,       // 常驻（蓝灯），无需关键词
    val enabled: Boolean = true,
    val order: Int = 100,                // insertion_order，越小越靠前
    val position: Int = 0,               // 0=before_char 1=after_char 2=before_AN 3=after_AN 4=@depth
    val depth: Int = 4,                  // position=4 时的注入深度
    val caseSensitive: Boolean = false,  // case_sensitive
    val matchWholeWords: Boolean = false, // match_whole_words
    val probability: Int = 100,          // 触发概率 0-100
    val useProbability: Boolean = true,  // useProbability（false=跳过概率判定）
    val group: String = "",              // 分组（同组互斥）
    val groupWeight: Int = 100,          // 同组权重
    val sticky: Int = 0,                 // 激活后持续 N 轮
    val cooldown: Int = 0,               // 冷却 N 轮
    val delay: Int = 0,                  // 延迟 N 轮才可触发
    val excludeRecursion: Boolean = false, // 内容不参与递归扫描
    val preventRecursion: Boolean = false, // 禁止由它触发递归
    val scanDepth: Int? = null,          // 条目级扫描深度（null=全局默认）
    val ignoreBudget: Boolean = false,   // 跳过 token 预算
    val global: Boolean = false,         // true=全局世界书，false=卡内嵌
)

/**
 * 正则脚本（SillyTavern regex_scripts，参照 CicaTavern regex-guard）。
 * placement 位掩码：0=user input 1=AI output 2=slash 3=world info 5=reasoning
 */
@Serializable
data class TavernRegexScript(
    val id: String = "",
    val scriptName: String = "",
    val findRegex: String = "",
    val replaceString: String = "",
    val placement: List<Int> = emptyList(),
    val disabled: Boolean = false,
    val promptOnly: Boolean = false,     // 只影响发给模型的内容，不改显示
    val markdownOnly: Boolean = false,   // 只影响显示
    val minDepth: Int? = null,
    val maxDepth: Int? = null,
)
