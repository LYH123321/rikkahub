package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement

/**
 * SillyTavern 预设（Chat Completion preset，参照 CicaTavern presetFor/engine）。
 *
 * prompts + prompt_order 启用序 + extensions.regex_scripts（预设正则）。
 * DSH 组装：chatHistory 标记之前的启用 prompts 进静态前缀（预设文本固定=缓存安全），
 * 之后的进动态尾部；in-chat 深度注入同样走尾部。
 */
@Serializable
data class TavernPreset(
    val name: String = "",
    val prompts: List<PresetPrompt> = emptyList(),
    val regexScripts: List<TavernRegexScript> = emptyList(),
) {
    /**
     * 按 prompt_order 解析出的启用条目（保持预设顺序）。
     * prompts 里没有出现在 order 中的视为未启用；marker 条目参与排序。
     */
    val orderedEnabled: List<PresetPrompt>
        get() {
            val byId = prompts.associateBy { it.identifier }
            return enabledOrder
                .mapNotNull { (id, enabled) -> byId[id]?.takeIf { enabled } }
                .filter { it.marker || it.content.isNotBlank() }
        }

    /** 解析 prompt_order 后的 (identifier, enabled) 序列 */
    @Transient
    internal var enabledOrder: List<Pair<String, Boolean>> = emptyList()
}

@Serializable
data class PresetPrompt(
    val identifier: String = "",
    val name: String = "",
    val role: String = "system",     // system / user / assistant
    val content: String = "",
    val marker: Boolean = false,     // 占位标记（charDescription/chatHistory…）
    val injectionPosition: Int = 0,  // 0=相对（按顺序） 1=绝对（in-chat @depth）
    val injectionDepth: Int = 4,
    val injectionOrder: Int = 100,
)

/**
 * 预设解析（宽容：缺字段回退默认，不挡导入）。
 */
object TavernPresetParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 识别：顶层有 prompts 数组 + prompt_order → 预设 */
    fun isPreset(text: String): Boolean = runCatching {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return false
        root["prompts"] is JsonArray && root["prompt_order"] != null
    }.getOrDefault(false)

    fun parse(text: String, fileName: String? = null): TavernPreset? = runCatching {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return null
        val promptsEl = root["prompts"] as? JsonArray ?: return null
        val ext = root["extensions"] as? JsonObject

        val prompts = promptsEl.mapNotNull { raw ->
            val p = raw as? JsonObject ?: return@mapNotNull null
            PresetPrompt(
                identifier = p.str("identifier"),
                name = p.str("name"),
                role = p.str("role").ifBlank { "system" },
                content = p.str("content"),
                marker = p.bool("marker") ?: false,
                injectionPosition = p.int("injection_position") ?: 0,
                injectionDepth = p.int("injection_depth") ?: 4,
                injectionOrder = p.int("injection_order") ?: 100,
            )
        }

        // prompt_order: [{character_id, order:[{identifier, enabled}]}]，取第一个（100001=默认）
        val orderEl = root["prompt_order"] as? JsonArray
        val enabledOrder = orderEl?.firstOrNull()?.let { it as? JsonObject }
            ?.get("order")?.let { o ->
                (o as? JsonArray)?.mapNotNull { e ->
                    (e as? JsonObject)?.let {
                        val id = it.str("identifier")
                        if (id.isBlank()) null else id to (it.bool("enabled") ?: false)
                    }
                }
            } ?: emptyList()

        val regexScripts = TavernCardParser.parseScriptsPublic(ext?.get("regex_scripts"))

        // 名称：顶层 name > 首个启用非标记条目的 name > 文件名
        val name = root.str("name").ifBlank {
            val byId = prompts.associateBy { it.identifier }
            enabledOrder.firstNotNullOfOrNull { (id, en) ->
                byId[id]?.takeIf { en && !it.marker && it.name.isNotBlank() }?.name
            } ?: fileName.orEmpty()
        }

        TavernPreset(
            name = name,
            prompts = prompts,
            regexScripts = regexScripts,
        ).apply { enabledOrder = enabledOrder }
    }.getOrNull()
}

/**
 * 预设正则应用（SillyTavern regex 脚本，参照 CicaTavern regex-guard）。
 * placement: 0=user input 1=AI output；findRegex 支持 /pattern/flags 内联形式。
 * replaceString 支持 $1..$9（Kotlin 原生）与 {{match}}（整体匹配）。
 */
object TavernRegex {
    /** 对一条消息文本应用启用的正则脚本 */
    fun apply(text: String, scripts: List<TavernRegexScript>, isUserMessage: Boolean): String {
        var out = text
        for (script in scripts) {
            if (script.disabled) continue
            // placement 空视为全部应用；否则按消息角色匹配
            val wanted = if (isUserMessage) 0 else 1
            if (script.placement.isNotEmpty() && wanted !in script.placement) continue
            val pattern = script.findRegex.trim()
            if (pattern.isBlank()) continue
            // 去掉 /pattern/flags 包装
            val (body, flags) = unwrapRegex(pattern)
            runCatching {
                val opts = buildSet {
                    if (flags.contains('i')) add(RegexOption.IGNORE_CASE)
                    if (flags.contains('m')) add(RegexOption.MULTILINE)
                    if (flags.contains('s')) add(RegexOption.DOT_MATCHES_ALL)
                }
                val regex = Regex(body, opts)
                val replacement = script.replaceString.replace("{{match}}", "$0")
                // ST 默认全局替换
                out = regex.replace(out, replacement)
            }
        }
        return out
    }

    /** /pattern/flags → (pattern, flags)；普通 pattern 原样返回 */
    private fun unwrapRegex(input: String): Pair<String, String> {
        if (input.startsWith("/") && input.lastIndexOf("/") > 0) {
            val last = input.lastIndexOf('/')
            val body = input.substring(1, last)
            val flags = input.substring(last + 1)
            if (flags.all { it in "gimsuy" } && !Regex("(^|[^\\\\])/").containsMatchIn(body)) {
                return body.replace("\\/", "/") to flags
            }
        }
        return input to ""
    }
}

object PresetSerializer : me.rerere.rikkahub.data.export.ExportSerializer<TavernPreset> {
    override val type = "tavern_preset"

    override fun export(data: TavernPreset): me.rerere.rikkahub.data.export.ExportData {
        return me.rerere.rikkahub.data.export.ExportData(
            type = type,
            data = me.rerere.rikkahub.data.export.ExportSerializer.DefaultJson.encodeToJsonElement(data),
        )
    }

    override fun import(context: android.content.Context, uri: android.net.Uri): Result<TavernPreset> {
        return runCatching {
            val text = readUri(context, uri)
            TavernPresetParser.parse(text)
                ?: error("Unsupported preset format")
        }
    }

    override fun getExportFileName(data: TavernPreset): String {
        return "${data.name.ifBlank { type }}.json"
    }
}

// —— JsonElement 宽容取值（与 TavernCardParser 同款） ——
private fun JsonObject.str(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull ?: ""

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

private fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.let { v ->
        v.toBooleanStrictOrNull() ?: v.toIntOrNull()?.let { it != 0 }
    }
