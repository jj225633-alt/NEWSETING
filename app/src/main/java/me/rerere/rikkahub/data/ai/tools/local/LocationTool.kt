package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * 星汐: 让 AI 在用户授权后查询手机当前位置（经纬度 + 大致地址）。
 * 只在 AI 调用工具时读取一次，不在背景持续追踪。
 */
internal fun buildLocationTool(context: Context): Tool = Tool(
    name = "get_location",
    description = """
        Get the phone's current location once: latitude, longitude, accuracy in meters, when it was measured,
        and a reverse-geocoded address (country, city, district, street) when available.
        Only call this when location is relevant to the conversation (e.g. the user asks where they are,
        whether they got home, or for nearby suggestions). Location is not tracked in the background.
        If location permission is missing or location services are off, an error is returned;
        ask the user to grant permission in the app instead of guessing.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { })
    },
    execute = {
        listOf(UIMessagePart.Text(readLocation(context).toString()))
    }
)

private fun Context.hasLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun locationError(code: String, message: String): JsonObject = buildJsonObject {
    put("error", code)
    put("message", message)
}

@SuppressLint("MissingPermission")
private suspend fun readLocation(context: Context): JsonObject {
    if (!context.hasLocationPermission()) {
        return locationError(
            "permission_denied",
            "Location permission is not granted. Ask the user to enable the location tool in the assistant's local tools settings and allow location access."
        )
    }
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return locationError("unavailable", "Location service is not available on this device.")
    val enabledProviders = manager.getProviders(true)
    if (enabledProviders.isEmpty()) {
        return locationError("location_disabled", "Location services are turned off on the phone. Ask the user to turn on location.")
    }

    val preferred = listOf(
        LocationManager.FUSED_PROVIDER.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S },
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
    ).filterNotNull().filter { it in enabledProviders }

    var location: Location? = null
    for (provider in preferred) {
        location = withTimeoutOrNull(20_000) { manager.currentLocation(provider) }
        if (location != null) break
    }
    if (location == null) {
        // 拿不到即时位置时，退而使用最近一次已知的位置
        location = enabledProviders
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }
    if (location == null) {
        return locationError("no_fix", "Could not get a location fix right now (e.g. indoors or no signal). Try again later.")
    }

    val address = reverseGeocode(context, location)
    val ageSeconds = (System.currentTimeMillis() - location.time).coerceAtLeast(0) / 1000
    return buildJsonObject {
        put("latitude", location.latitude)
        put("longitude", location.longitude)
        if (location.hasAccuracy()) put("accuracy_meters", location.accuracy.toDouble())
        put("provider", location.provider ?: "unknown")
        put("measured_at", Instant.ofEpochMilli(location.time).atZone(ZoneId.systemDefault()).withNano(0).toString())
        put("age_seconds", ageSeconds)
        if (address != null) {
            put("address", buildJsonObject {
                address.countryName?.let { put("country", it) }
                address.adminArea?.let { put("admin_area", it) }
                address.locality?.let { put("city", it) }
                address.subLocality?.let { put("district", it) }
                address.thoroughfare?.let { put("street", it) }
                address.getAddressLine(0)?.let { put("full", it) }
            })
        }
    }
}

@SuppressLint("MissingPermission")
private suspend fun LocationManager.currentLocation(provider: String): Location? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        return runCatching { getLastKnownLocation(provider) }.getOrNull()
    }
    return suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        runCatching {
            getCurrentLocation(provider, signal, Executor { it.run() }) { location ->
                if (continuation.isActive) continuation.resume(location)
            }
        }.onFailure {
            if (continuation.isActive) continuation.resume(null)
        }
    }
}

private suspend fun reverseGeocode(context: Context, location: Location): Address? {
    if (!Geocoder.isPresent()) return null
    val geocoder = Geocoder(context, Locale.getDefault())
    return withTimeoutOrNull(10_000) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { continuation ->
                runCatching {
                    geocoder.getFromLocation(location.latitude, location.longitude, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                        }

                        override fun onError(errorMessage: String?) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    })
                }.onFailure {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                runCatching { geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull() }.getOrNull()
            }
        }
    }
}
