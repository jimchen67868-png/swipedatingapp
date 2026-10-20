package com.example.swipe.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val REASONS = listOf(
    "fake" to "Fake profile or scam",
    "photo" to "Inappropriate or illegal photo",
    "harassment" to "Harassment or abuse",
    "underage" to "Looks under 18",
    "spam" to "Spam",
    "other" to "Something else"
)

@Composable
fun ReportDialog(name: String, onReport: (String) -> Unit, onBlock: () -> Unit, onDismiss: () -> Unit) {
    var reason by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report or block $name") },
        text = {
            Column {
                Text("They won't see you and you won't see them. Reports are reviewed by us.", fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                REASONS.forEach { (key, label) ->
                    Row(Modifier.fillMaxWidth().clickable { reason = key }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = reason == key, onClick = { reason = key })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = reason != null, onClick = { reason?.let(onReport) }) { Text("Report and block") } },
        dismissButton = {
            Row {
                TextButton(onClick = onBlock) { Text("Just block") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
