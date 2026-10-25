package com.example.swipe.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.swipe.data.ageFromBirthDate
import com.example.swipe.data.isoToUtcMillis
import com.example.swipe.data.utcMillis
import com.example.swipe.data.utcMillisToIso
import java.util.Calendar

/** Pick a birth date; the age is shown underneath and worked out from it. Only dates that make the person 18 or older can be chosen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BirthDateField(birthDate: String?, onChange: (String) -> Unit, enabled: Boolean = true, lockedNote: String? = null) {
    var open by remember { mutableStateOf(false) }
    val age = birthDate?.let { ageFromBirthDate(it) }
    Box {
        OutlinedTextField(
            value = birthDate.orEmpty(),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Birth date") },
            placeholder = { Text("Tap to choose") },
            supportingText = { Text(lockedNote ?: age?.let { "Age: $it" } ?: "Your age is worked out from this.") },
            modifier = Modifier.fillMaxWidth()
        )
        // A read-only field doesn't pass taps on, so a clickable layer sits on top of it.
        if (enabled) Box(Modifier.matchParentSize().clickable { open = true })
    }
    if (open) {
        val now = Calendar.getInstance()
        val year = now.get(Calendar.YEAR)
        val latest = utcMillis(year - 18, now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)) // youngest allowed: exactly 18 today
        val start = birthDate?.let { isoToUtcMillis(it) }
        val state = rememberDatePickerState(
            initialSelectedDateMillis = start,
            initialDisplayedMonthMillis = start ?: utcMillis(year - 25, 0, 1),
            yearRange = (year - 100)..year,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= latest
            }
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(utcMillisToIso(it)) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } }
        ) { DatePicker(state = state) }
    }
}
