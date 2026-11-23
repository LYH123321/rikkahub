package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 酒馆角色卡解析（SillyTavern V1/V2/V3 JSON，参照 CicaTavern normalizeCard）。
 *
 * 宽容解析：字段缺失/类型不对一律回退默认值，不抛异常——
 * 社区卡片格式五花八门，解析必须不挡导入。
 */
object TavernCardParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 解析卡片 JSON 文本；无 name 时返回 null（无法当角色卡用） */
    fun parse(text: String): TavernCard? = runCatching {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return null
        // V2/V3: { spec, spec_version, data: {...} }；V1: 平铺
        val spec = root.str("spec")
        val data = (root["data"] as? JsonObject)
            ?.takeIf { spec.startsWith("chara_card") }
            ?: root
        val ext = data["extensions"] as? JsonObject ?: JsonObject(emptyMap())

        TavernCard(
            spec = spec.ifBlank { if (root["data"] != null) "chara_card_v2" else "v1" },
            specVersion = root.str("spec_version"),
            name = data.str("name").trim(),
            description = data.str("description"),
            personality = data.str("personality"),
            scenario = data.str("scenario"),
            firstMessage = data.str("first_mes"),
            alternateGreetings = data.arr("alternate_greetings"),
            mesExample = data.str("mes_example"),
            systemPrompt = data.str("system_prompt"),
            postHistoryInstructions = data.str("post_history_instructions"),
            creatorNotes = data.str("creator_notes").ifBlank { data.str("creatorcomment") },
            creator = data.str("creator"),
            tags = data.arr("tags"),
            characterVersion = data.str("character_version"),
            depthPrompt = ext.obj("depth_prompt")?.str("prompt").orEmpty(),
            depthPromptDepth = ext.obj("depth_prompt")?.int("depth") ?: 4,
            depthPromptRole = ext.obj("depth_prompt")?.str("role")?.ifBlank { "system" } ?: "system",
            embeddedBook = parseBook(data["character_book"]),
            regexScripts = parseScripts(ext["regex_scripts"]),
        ).takeIf { it.name.isNotBlank() }
    }.getOrNull()

    /** character_book：{ entries: [...] } 或直接数组 */
    private fun parseBook(element: JsonElement?): List<TavernBookEntry> {
        val entries = when (element) {
            is JsonObject -> element["entries"] as? JsonArray
            is JsonArray -> element
            else -> null
        } ?: return emptyList()
        return entries.mapNotNull { raw ->
            val e = raw as? JsonObject ?: return@mapNotNull null
            val ext = e["extensions"] as? JsonObject ?: JsonObject(emptyMap())
            TavernBookEntry(
                uid = e.int("uid") ?: e.int("id") ?: 0,
                comment = e.str("comment").ifBlank { e.str("name") },
                keys = e.arr("keys").ifEmpty { e.arr("key") },
                secondaryKeys = e.arr("secondary_keys").ifEmpty { e.arr("keysecondary") },
                selective = e.bool("selective") ?: false,
                selectiveLogic = e.int("selectiveLogic") ?: ext.int("selectiveLogic") ?: 0,
                content = e.str("content"),
                constant = e.bool("constant") ?: false,
                enabled = e.bool("enabled")
                    ?: (e["disable"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()?.not()
                    ?: true,
                order = e.int("insertion_order") ?: e.int("order") ?: 100,
                position = e.int("position") ?: ext.int("position") ?: 0,
                depth = e.int("depth") ?: ext.int("depth") ?: 4,
                caseSensitive = e.bool("case_sensitive") ?: ext.bool("case_sensitive") ?: e.bool("caseSensitive") ?: false,
                matchWholeWords = e.bool("match_whole_words") ?: ext.bool("match_whole_words") ?: e.bool("matchWholeWords") ?: false,
                probability = e.int("probability") ?: 100,
                useProbability = e.bool("useProbability") ?: ext.bool("useProbability") ?: true,
                group = e.str("group"),
                groupWeight = e.int("group_weight") ?: 100,
                sticky = e.int("sticky") ?: ext.int("sticky") ?: 0,
                cooldown = e.int("cooldown") ?: ext.int("cooldown") ?: 0,
                delay = e.int("delay") ?: ext.int("delay") ?: 0,
                excludeRecursion = e.bool("exclude_recursion") ?: ext.bool("exclude_recursion") ?: false,
                preventRecursion = e.bool("prevent_recursion") ?: ext.bool("prevent_recursion") ?: false,
                scanDepth = e.int("scan_depth") ?: ext.int("scan_depth"),
                ignoreBudget = e.bool("ignore_budget") ?: ext.bool("ignore_budget") ?: false,
            )
        }
    }

    /** regex_scripts 数组 */
    private fun parseScripts(element: JsonElement?): List<TavernRegexScript> {
        val arr = element as? JsonArray ?: return emptyList()
        return arr.mapNotNull { raw ->
            val e = raw as? JsonObject ?: return@mapNotNull null
            TavernRegexScript(
                id = e.str("id"),
                scriptName = e.str("scriptName"),
                findRegex = e.str("findRegex"),
                replaceString = e.str("replaceString"),
                placement = e["placement"]?.let { p ->
                    (p as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }
                        ?: listOf((p as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0)
                } ?: emptyList(),
                disabled = e.bool("disabled") ?: false,
                promptOnly = e.bool("promptOnly") ?: false,
                markdownOnly = e.bool("markdownOnly") ?: false,
                minDepth = e.int("minDepth"),
                maxDepth = e.int("maxDepth"),
            )
        }
    }
}

// —— JsonElement 宽容取值扩展 ——
private fun JsonObject.str(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull ?: ""

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

private fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.let { v ->
        v.toBooleanStrictOrNull() ?: v.toIntOrNull()?.let { it != 0 }
    }

private fun JsonObject.obj(key: String): JsonObject? =
    this[key] as? JsonObject


/** 字符串数组：容忍单个字符串、数字数组等畸形输入 */
private fun JsonObject.arr(key: String): List<String> = when (val e = this[key]) {
    is JsonArray -> e.mapNotNull { el ->
        when (el) {
            is JsonPrimitive -> el.contentOrNull?.takeIf { it.isNotBlank() }
            else -> null
        }
    }
    is JsonPrimitive -> listOfNotNull(e.contentOrNull?.takeIf { it.isNotBlank() })
    else -> emptyList()
}
