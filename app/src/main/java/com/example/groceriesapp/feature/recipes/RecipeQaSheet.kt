package com.example.groceriesapp.feature.recipes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.recipes.RecipeQaTurn
import com.example.groceriesapp.ui.theme.spacing
import kotlinx.coroutines.launch

/**
 * A small chat: every question is answered fresh by [onAskQuestion] with the prior turns as
 * context. Conversation lives only in this composable's state — closing the sheet forgets it,
 * same as [CookRecipeSheet]'s plan not surviving a dismiss. The input row sits right under the
 * title (not pinned below a weighted list) so it stays reachable without relying on
 * [Modifier.fillMaxHeight] inside [ModalBottomSheet], which measures with an unbounded height
 * and silently drops anything after a weighted child. Turns show newest-first so a fresh answer
 * is visible without scrolling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeQaSheet(
    recipeTitle: String,
    onAskQuestion: suspend (question: String, history: List<RecipeQaTurn>) -> String,
    onDismiss: () -> Unit,
) {
    val turns = remember { mutableStateListOf<RecipeQaTurn>() }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun send() {
        val question = draft.trim()
        if (question.isEmpty() || busy) return
        draft = ""
        error = null
        busy = true
        val history = turns.toList()
        scope.launch {
            runCatching { onAskQuestion(question, history) }
                .onSuccess { answer -> turns.add(0, RecipeQaTurn(question, answer)) }
                .onFailure { throwable -> error = throwable.message ?: "The AI request failed." }
            busy = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = MaterialTheme.spacing.medium,
                    end = MaterialTheme.spacing.medium,
                    bottom = MaterialTheme.spacing.extraLarge,
                ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Text(
                text = "Ask about “$recipeTitle”",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("e.g. Can I use butter instead of oil?") },
                    enabled = !busy,
                    // Bounded so a long question doesn't push the Send button down the sheet.
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = ::send, enabled = draft.isNotBlank() && !busy) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send question")
                }
            }
            if (busy) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Text(
                        text = "Thinking…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (error != null) {
                Text(
                    text = error!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (turns.isEmpty() && !busy) {
                Text(
                    text = "Ask anything — substitutions, technique, timing…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            turns.forEach { turn -> QaTurn(turn) }
        }
    }
}

@Composable
private fun QaTurn(turn: RecipeQaTurn, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
    ) {
        Text(
            text = turn.question,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = turn.answer,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
