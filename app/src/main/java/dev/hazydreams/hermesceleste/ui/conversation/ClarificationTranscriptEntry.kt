package dev.hazydreams.hermesceleste.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hazydreams.hermesceleste.network.ClarificationExchange
import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.network.clarificationChoiceIsRecommended
import dev.hazydreams.hermesceleste.network.clarificationChoiceLabel
import dev.hazydreams.hermesceleste.network.encodeClarificationChoices
import dev.hazydreams.hermesceleste.ui.CelesteAccent
import dev.hazydreams.hermesceleste.ui.CelesteAccentContent
import dev.hazydreams.hermesceleste.ui.CelesteSurfacePrimary
import dev.hazydreams.hermesceleste.ui.CelesteSurfaceRaised
import dev.hazydreams.hermesceleste.ui.CelesteSurfaceSelected
import dev.hazydreams.hermesceleste.ui.CelesteTextMuted
import dev.hazydreams.hermesceleste.ui.CelesteTextPrimary

@Composable
internal fun ClarificationTranscriptEntry(
    message: ConversationMessage,
    onRespond: (messageId: String, requestId: String, answer: String) -> Unit,
) {
    val clarification = message.clarification ?: return
    val messageId = message.id ?: return
    if (message.pending) {
        PendingClarification(
            messageId = messageId,
            clarification = clarification,
            onRespond = onRespond,
        )
    } else {
        SettledClarification(clarification)
    }
}

@Composable
private fun PendingClarification(
    messageId: String,
    clarification: ClarificationExchange,
    onRespond: (messageId: String, requestId: String, answer: String) -> Unit,
) {
    var selectedChoices by remember(messageId, clarification.requestId) { mutableStateOf(emptyList<String>()) }
    var customAnswer by remember(messageId, clarification.requestId) { mutableStateOf("") }
    val requestId = clarification.requestId
    val typedAnswer = customAnswer.trim()
    val stagedAnswer = when {
        selectedChoices.isEmpty() -> typedAnswer
        clarification.multiSelect -> encodeClarificationChoices(selectedChoices)
        else -> selectedChoices.single()
    }
    val enabled = requestId != null && !clarification.submitting

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(CelesteSurfacePrimary)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = clarification.question,
                    modifier = Modifier.weight(1f),
                    color = CelesteTextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(10.dp))
                ClarificationMarker("?")
            }

            if (requestId == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = CelesteTextMuted,
                        strokeWidth = 2.dp,
                    )
                }
            } else {
                if (clarification.choices.isNotEmpty()) {
                    val choiceGroup = if (clarification.multiSelect) {
                        Modifier
                    } else {
                        Modifier.selectableGroup()
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(choiceGroup),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        clarification.choices.forEach { choice ->
                            val selected = choice in selectedChoices
                            ClarificationChoiceRow(
                                choice = choice,
                                selected = selected,
                                multiSelect = clarification.multiSelect,
                                enabled = enabled,
                                onClick = {
                                    customAnswer = ""
                                    selectedChoices = if (clarification.multiSelect) {
                                        if (selected) selectedChoices - choice else selectedChoices + choice
                                    } else {
                                        listOf(choice)
                                    }
                                },
                            )
                        }
                    }
                }

                ClarificationTextField(
                    value = customAnswer,
                    onValueChange = { value ->
                        customAnswer = value
                        if (value.isNotBlank()) selectedChoices = emptyList()
                    },
                    enabled = enabled,
                    onSubmit = {
                        if (stagedAnswer.isNotBlank()) {
                            onRespond(messageId, requestId, stagedAnswer)
                        }
                    },
                    placeholder = if (clarification.choices.isEmpty()) {
                        "Type your answer"
                    } else {
                        "Other (type your answer)"
                    },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { requestId?.let { onRespond(messageId, it, "") } },
                enabled = enabled,
                colors = ButtonDefaults.textButtonColors(contentColor = CelesteTextMuted),
            ) {
                Text("Skip")
            }
            Spacer(Modifier.width(4.dp))
            Button(
                onClick = { requestId?.let { onRespond(messageId, it, stagedAnswer) } },
                enabled = enabled && stagedAnswer.isNotBlank(),
                modifier = Modifier.heightIn(min = 40.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CelesteAccent,
                    contentColor = CelesteAccentContent,
                    disabledContainerColor = CelesteSurfacePrimary,
                    disabledContentColor = CelesteTextMuted,
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                if (clarification.submitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = CelesteAccentContent,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Continue", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ClarificationChoiceRow(
    choice: String,
    selected: Boolean,
    multiSelect: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val selectionModifier = if (multiSelect) {
        Modifier.toggleable(
            value = selected,
            enabled = enabled,
            role = Role.Checkbox,
            onValueChange = { onClick() },
        )
    } else {
        Modifier.selectable(
            selected = selected,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = onClick,
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(if (selected) CelesteSurfaceSelected else CelesteSurfaceRaised)
            .then(selectionModifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(19.dp)
                .background(
                    color = if (selected) CelesteAccent else CelesteSurfaceSelected,
                    shape = if (multiSelect) RoundedCornerShape(5.dp) else CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    Modifier
                        .size(7.dp)
                        .background(
                            color = CelesteAccentContent,
                            shape = if (multiSelect) RoundedCornerShape(2.dp) else CircleShape,
                        ),
                )
            }
        }
        Spacer(Modifier.width(11.dp))
        Text(
            text = clarificationChoiceLabel(choice),
            modifier = Modifier.weight(1f),
            color = CelesteTextPrimary,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (clarificationChoiceIsRecommended(choice)) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Recommended",
                color = CelesteAccent,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ClarificationTextField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onSubmit: () -> Unit,
    placeholder: String,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(CelesteSurfaceRaised),
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = CelesteTextPrimary),
        cursorBrush = SolidColor(CelesteAccent),
        minLines = 1,
        maxLines = 4,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (enabled && value.isNotBlank()) onSubmit() }),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = CelesteTextMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
private fun SettledClarification(clarification: ClarificationExchange) {
    val skipped = clarification.answer.isNullOrBlank()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 2.dp)
            .semantics { stateDescription = if (skipped) "Clarification skipped" else "Clarification answered" },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ClarificationLine(
            text = clarification.question,
            marker = "?",
            color = CelesteTextPrimary,
            fontWeight = FontWeight.Medium,
        )
        ClarificationLine(
            text = clarification.answer?.takeIf(String::isNotBlank) ?: "Skipped",
            marker = "A",
            color = CelesteTextMuted,
            fontStyle = if (skipped) FontStyle.Italic else FontStyle.Normal,
        )
    }
}

@Composable
private fun ClarificationLine(
    text: String,
    marker: String,
    color: Color,
    fontWeight: FontWeight? = null,
    fontStyle: FontStyle = FontStyle.Normal,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            color = color,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = fontWeight,
            fontStyle = fontStyle,
        )
        Spacer(Modifier.width(10.dp))
        ClarificationMarker(marker)
    }
}

@Composable
private fun ClarificationMarker(text: String) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .background(CelesteSurfaceSelected, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = CelesteAccent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
