package com.example.swipe.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.example.swipe.data.findPlace
import com.example.swipe.data.hasLocationPermission
import com.example.swipe.data.locationSwitchOn

/**
 * The city field, filled in from the phone's location.
 * required = true: location must be on and the city comes from it (it can only be typed if the phone can't work it out).
 * required = false: the person can type a city or tap "Use my location".
 * onReady(true) tells the screen the city is good to save.
 */
@Composable
fun CityField(city: String, onCity: (String) -> Unit, onCoords: (Double, Double) -> Unit, required: Boolean, onReady: (Boolean) -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasLocationPermission(context)) }
    var switchOn by remember { mutableStateOf(locationSwitchOn(context)) }
    var finding by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var hasCoords by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var asked by remember { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result.values.any { it }
    }
    fun ask() = permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))

    LaunchedEffect(granted, attempt) {
        switchOn = locationSwitchOn(context)
        if (granted && switchOn && (required || attempt > 0 || city.isBlank())) {
            finding = true
            failed = false
            val found = findPlace(context)
            if (found != null) {
                onCoords(found.lat, found.lon)
                onCity(found.name ?: "Location found")
                hasCoords = true
            } else failed = true
            finding = false
        } else if (!granted && required && !asked) {
            asked = true
            ask()
        }
    }

    LaunchedEffect(granted, switchOn, hasCoords, required, finding) {
        onReady(!required || (granted && switchOn && !finding && hasCoords))
    }

    // The location can never be typed: it only ever comes from the phone's location.
    OutlinedTextField(
        value = city,
        onValueChange = {},
        readOnly = true,
        label = { Text("Location (city, country)") },
        singleLine = true,
        supportingText = {
            Text(
                when {
                    finding -> "Finding your location..."
                    !granted -> if (required) "Location is required. Tap Allow location." else "Tap Use my location. It can't be typed."
                    !switchOn -> "Turn on location in your phone settings, then tap Try again."
                    failed -> "We couldn't work out your location. Check your internet, then tap Use my location again."
                    else -> "Filled in from your phone's location. It can't be typed."
                }
            )
        },
        modifier = Modifier.fillMaxWidth()
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            !granted -> {
                TextButton(onClick = { ask() }) { Text(if (required) "Allow location" else "Use my location") }
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
                }) { Text("Open settings") }
            }
            !switchOn -> {
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) { Text("Open location settings") }
                TextButton(onClick = { attempt++ }) { Text("Try again") }
            }
            else -> TextButton(onClick = { attempt++ }, enabled = !finding) { Text("Use my location again") }
        }
    }
}
