package com.example.swipe.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** True when the phone's location switch is on (not just the app's permission). */
fun locationSwitchOn(context: Context): Boolean {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return runCatching { LocationManagerCompat.isLocationEnabled(lm) }.getOrDefault(true)
}

@SuppressLint("MissingPermission")
private suspend fun currentLocation(context: Context): Location? {
    // Google's location service first (fast and accurate), then whatever the phone last knew.
    runCatching {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val loc = suspendCancellableCoroutine<Location?> { cont ->
            client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        }
        if (loc != null) return loc
    }
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return runCatching { lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time } }.getOrNull()
}

private suspend fun cityFromLocation(context: Context, lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
    runCatching {
        if (!Geocoder.isPresent()) return@runCatching null
        val geocoder = Geocoder(context, Locale.getDefault())
        val list: List<Address>? = if (Build.VERSION.SDK_INT >= 33) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) { if (cont.isActive) cont.resume(addresses) }
                    override fun onError(errorMessage: String?) { if (cont.isActive) cont.resume(emptyList()) }
                })
            }
        } else {
            @Suppress("DEPRECATION")
            geocoder.getFromLocation(lat, lon, 1)
        }
        val a = list?.firstOrNull()
        val place = a?.locality ?: a?.subAdminArea ?: a?.adminArea
        val country = a?.countryName
        when {
            place != null && country != null -> "$place, $country"
            place != null -> place
            else -> country
        }?.take(50)
    }.getOrNull()
}

class Place(val lat: Double, val lon: Double, val name: String?)

/** Where the phone is: coordinates (sent to the server) and a name for showing on screen. */
suspend fun findPlace(context: Context): Place? {
    val loc = currentLocation(context) ?: return null
    return Place(loc.latitude, loc.longitude, cityFromLocation(context, loc.latitude, loc.longitude))
}

/** City and country where the phone is, like "Paris, France", or null if it can't be found. Needs the location permission. */
suspend fun findCity(context: Context): String? {
    val loc = currentLocation(context) ?: return null
    return cityFromLocation(context, loc.latitude, loc.longitude)
}
