package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.TavernCard
import me.rerere.rikkahub.data.model.TavernRegexScript

/**
 * 助手酒馆设置（CicaTavern 式）：角色卡字段 + 开场白 + PHI/深度提示 + 预设信息 + 正则脚本开关。
 *
 * 有 tavernData 或 tavernPreset 时显示；编辑直接回写 assistant。
 * 文本字段用本地 state + 失焦提交，避免每字符触发整卡更新。
 */
@Composable
fun TavernSettingsCard(
    assistant: Assistant,
    modifier: Modifier = Modifier,
    onAssistantUpdate: (Assistant) -> Unit,
) {
    val card = assistant.tavernData
    val preset = assistant.tavernPreset
    if (card == null && preset == null) return

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("酒馆设置", style = MaterialTheme.typography.titleMedium)

            if (card != null) {
                SectionTitle("角色设定")
                TavernTextField(
                    label = "角色名", initial = card.name,
                ) { onAssistantUpdate(assistant.copy(tavernData = card.copy(name = it))) }
                TavernTextField(
                    label = "描述 (description)", initial = card.description, minLines = 3,
                ) { onAssistantUpdate(assistant.copy(tavernData = card.copy(description = it))) }
                TavernTextField(
                    label = "性格 (personality)", initial = card.personality, minLines = 2,
                ) { onAssistantUpdate(assistant.copy(tavernData = card.copy(personality = it))) }
                TavernTextField(
                    label = "场景 (scenario)", initial = card.scenario, minLines = 2,
                ) { onAssistantUpdate(assistant.copy(tavernData = card.copy(scenario = it))) }
                TavernTextField(
                    label = "开场白 (first_mes)", initial = card.firstMessage, minLines = 2,
                ) { onAssistantUpdate(assistant.copy(tavernData = card.copy(firstMessage = it))) }

                HorizontalDivider()
                SectionTitle("上下文注入")
                TavernTextField(
                    label = "历史后指令 (PHI)", initial = card.postHistoryInstructions, minLines = 2,
                ) { onAssistantUpdate(assistant.copy(tavernData = card.copy(postHistoryInstructions = it))) }
                TavernTextField(
                    label = "深度提示 (depth_prompt)", initial = card.depthPrompt, minLines = 2,
                ) { onAssistantUpdate(assistant.copy(tavernData = card.copy(depthPrompt = it))) }

                if (card.regexScripts.isNotEmpty()) {
                    HorizontalDivider()
                    SectionTitle("角色卡正则")
                    card.regexScripts.forEach { script ->
                        RegexScriptRow(script) { updated ->
                            onAssistantUpdate(assistant.copy(
                                tavernData = card.copy(regexScripts = card.regexScripts.map {
                                    if (it.id == updated.id) updated else it
                                })
                            ))
                        }
                    }
                }
            }

            if (preset != null) {
                HorizontalDivider()
                SectionTitle("预设")
                Text(
                    text = "${preset.name}（${preset.orderedEnabled.size}/${preset.prompts.size} 条启用）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "全局预设可在 扩展 → 提示词 导入（应用于所有会话）；此处为助手级绑定。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (preset.regexScripts.isNotEmpty()) {
                    SectionTitle("预设正则")
                    preset.regexScripts.forEach { script ->
                        RegexScriptRow(script) { updated ->
                            onAssistantUpdate(assistant.copy(
                                tavernPreset = preset.copy(regexScripts = preset.regexScripts.map {
                                    if (it.id == updated.id) updated else it
                                })
                            ))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

/** 本地 state + 失焦/切换焦点提交，避免每字符回写整卡 */
@Composable
private fun TavernTextField(
    label: String,
    initial: String,
    minLines: Int = 1,
    onCommit: (String) -> Unit,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    OutlinedTextField(
        value = value,
        onValueChange = { value = it },
        label = { Text(label) },
        minLines = minLines,
        maxLines = 8,
        modifier = Modifier.fillMaxWidth(),
    )
    // 失焦提交：OutlinedTextField 无 onFocusLost，用 interactionSource？简化：
    // 在 value 与 initial 不一致时由外层定时/切换提交复杂；这里直接提供"应用"按钮更直观
    if (value != initial) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.TextButton(onClick = { onCommit(value) }) {
                Text("应用")
            }
        }
    }
}

@Composable
private fun RegexScriptRow(
    script: TavernRegexScript,
    onToggle: (TavernRegexScript) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = script.scriptName.ifBlank { script.id.ifBlank { "未命名正则" } },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = script.findRegex,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(
            checked = !script.disabled,
            onCheckedChange = { enabled -> onToggle(script.copy(disabled = !enabled)) },
        )
    }
}
