package com.example.tascyn.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.google.zxing.BarcodeFormat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

abstract class DynamicQrFetchResult {
    data class Success(val tasks: List<Task>, val deviceId: String) : DynamicQrFetchResult()
    data class Expired(val message: String) : DynamicQrFetchResult()
    data class NotFound(val message: String) : DynamicQrFetchResult()
    data class Error(val error: String) : DynamicQrFetchResult()
}

data class DynamicQrSession(
    val deviceId: String,
    val updatedAt: Long,
    val expiresAt: Long,
    val unhideExpiresAt: Long,
    val isExpired: Boolean,
    val tasksPayload: String?,
    val activeTasksCount: Int
)

object DynamicQrSyncManager {

    private const val PREFS_NAME = "tascyn_dynamic_qr_prefs"
    private const val KEY_DEVICE_ID = "key_persistent_device_id"
    private const val KEY_ASSOCIATED_PAYLOAD = "key_associated_payload"
    private const val KEY_UPDATED_AT = "key_updated_at"
    private const val KEY_EXPIRES_AT = "key_expires_at"
    private const val KEY_IS_EXPIRED = "key_is_expired"
    private const val KEY_ACTIVE_COUNT = "key_active_count"

    const val UNHIDE_DURATION_MS = 30_000L // 30 seconds
    const val DATA_EXPIRATION_MS = 300_000L // 5 minutes (5 * 60 * 1000)

    private val executor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var cachedQrBitmap: Bitmap? = null

    @Volatile
    private var cachedBlurredQrBitmap: Bitmap? = null

    @Volatile
    private var currentSession: DynamicQrSession? = null

    private var expirationRunnable: Runnable? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Permanent unique device sync ID for this phone.
     * Guaranteed never to change across app runs.
     */
    @Synchronized
    fun getDeviceSyncId(context: Context): String {
        val prefs = getPrefs(context)
        var devId = prefs.getString(KEY_DEVICE_ID, null)
        if (devId.isNullOrBlank()) {
            val randomPart = UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
            devId = "TSYN-$randomPart"
            prefs.edit().putString(KEY_DEVICE_ID, devId).apply()
        }
        return devId
    }

    /**
     * Fixed dynamic QR payload string for this device.
     */
    fun getDeviceDynamicQrPayload(context: Context): String {
        val devId = getDeviceSyncId(context)
        return "TASCYN_DYNAMIC:$devId"
    }

    /**
     * Generates or returns the cached fixed QR code Bitmap for this device.
     * This bitmap NEVER changes for this device.
     */
    @Synchronized
    fun getDeviceQrBitmap(context: Context, sizePx: Int = 460): Bitmap {
        cachedQrBitmap?.let { return it }
        val payload = getDeviceDynamicQrPayload(context)
        val barcodeEncoder = BarcodeEncoder()
        val bitmap = barcodeEncoder.encodeBitmap(payload, BarcodeFormat.QR_CODE, sizePx, sizePx)
        cachedQrBitmap = bitmap
        return bitmap
    }

    /**
     * Generates or returns a blurred version of the device QR bitmap for the default state.
     */
    @Synchronized
    fun getBlurredDeviceQrBitmap(context: Context, sizePx: Int = 460): Bitmap {
        cachedBlurredQrBitmap?.let { return it }
        val cleanBitmap = getDeviceQrBitmap(context, sizePx)
        val blurred = generateBlurredBitmap(cleanBitmap)
        cachedBlurredQrBitmap = blurred
        return blurred
    }

    private fun generateBlurredBitmap(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        // Downscale to 1/8 size to blur pixels naturally
        val smallW = (width * 0.12f).toInt().coerceAtLeast(16)
        val smallH = (height * 0.12f).toInt().coerceAtLeast(16)
        val scaledDown = Bitmap.createScaledBitmap(source, smallW, smallH, true)

        // Scale back up with bilinear filtering
        val blurred = Bitmap.createScaledBitmap(scaledDown, width, height, true)
        scaledDown.recycle()

        // Apply a subtle darkening canvas overlay
        val canvas = Canvas(blurred)
        val paint = Paint().apply {
            color = 0x55000000
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        return blurred
    }

    /**
     * Returns the current session state or loads it from storage.
     */
    fun getCurrentSession(context: Context): DynamicQrSession {
        val devId = getDeviceSyncId(context)
        val session = currentSession
        if (session != null && session.deviceId == devId) {
            // Check if 5 minutes have passed since updatedAt
            val now = System.currentTimeMillis()
            if (!session.isExpired && now >= session.expiresAt) {
                eraseAndExpireData(context, devId)
                return currentSession ?: session.copy(isExpired = true, tasksPayload = null)
            }
            return session
        }

        val prefs = getPrefs(context)
        val updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        val isExpired = prefs.getBoolean(KEY_IS_EXPIRED, true)
        val payload = prefs.getString(KEY_ASSOCIATED_PAYLOAD, null)
        val activeCount = prefs.getInt(KEY_ACTIVE_COUNT, 0)
        val now = System.currentTimeMillis()

        val actualExpired = isExpired || (expiresAt > 0L && now >= expiresAt) || payload == null
        val actualPayload = if (actualExpired) null else payload

        val loaded = DynamicQrSession(
            deviceId = devId,
            updatedAt = updatedAt,
            expiresAt = expiresAt,
            unhideExpiresAt = updatedAt + UNHIDE_DURATION_MS,
            isExpired = actualExpired,
            tasksPayload = actualPayload,
            activeTasksCount = activeCount
        )
        currentSession = loaded
        return loaded
    }

    /**
     * Triggered when the user clicks "Unhide" on the blurred QR code.
     * Takes fresh task data, associates it with this device's dynamic QR,
     * resets the 30-second unhide timer and 5-minute expiration timer,
     * and publishes the association to cloud relay.
     */
    fun onUserClickedUnhide(
        context: Context,
        tasksPayload: String,
        activeTasksCount: Int,
        onExpiration: (() -> Unit)? = null
    ): DynamicQrSession {
        val devId = getDeviceSyncId(context)
        val now = System.currentTimeMillis()
        val expiresAt = now + DATA_EXPIRATION_MS
        val unhideExpiresAt = now + UNHIDE_DURATION_MS

        val prefs = getPrefs(context)
        prefs.edit()
            .putLong(KEY_UPDATED_AT, now)
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .putBoolean(KEY_IS_EXPIRED, false)
            .putString(KEY_ASSOCIATED_PAYLOAD, tasksPayload)
            .putInt(KEY_ACTIVE_COUNT, activeTasksCount)
            .apply()

        val newSession = DynamicQrSession(
            deviceId = devId,
            updatedAt = now,
            expiresAt = expiresAt,
            unhideExpiresAt = unhideExpiresAt,
            isExpired = false,
            tasksPayload = tasksPayload,
            activeTasksCount = activeTasksCount
        )
        currentSession = newSession

        // Cancel previous expiration timer
        expirationRunnable?.let { mainHandler.removeCallbacks(it) }

        // Schedule new expiration timer for 5 minutes
        val runnable = Runnable {
            eraseAndExpireData(context, devId)
            onExpiration?.invoke()
        }
        expirationRunnable = runnable
        mainHandler.postDelayed(runnable, DATA_EXPIRATION_MS)

        // Publish ACTIVE dynamic data to cloud relay asynchronously
        executor.execute {
            publishToCloudRelay(
                deviceId = devId,
                status = "ACTIVE",
                payload = tasksPayload,
                updatedAt = now,
                expiresAt = expiresAt,
                activeCount = activeTasksCount
            )
        }

        return newSession
    }

    /**
     * Erases all data associated with the dynamic QR and marks it as EXPIRED.
     */
    fun eraseAndExpireData(context: Context, deviceId: String) {
        val prefs = getPrefs(context)
        prefs.edit()
            .putBoolean(KEY_IS_EXPIRED, true)
            .remove(KEY_ASSOCIATED_PAYLOAD)
            .apply()

        val old = currentSession
        if (old != null && old.deviceId == deviceId) {
            currentSession = old.copy(
                isExpired = true,
                tasksPayload = null
            )
        }

        // Publish EXPIRED event to cloud relay asynchronously
        executor.execute {
            publishToCloudRelay(
                deviceId = devIdOrFallback(context, deviceId),
                status = "EXPIRED",
                payload = null,
                updatedAt = System.currentTimeMillis(),
                expiresAt = 0L,
                activeCount = 0
            )
        }
    }

    private fun devIdOrFallback(context: Context, id: String): String {
        return if (id.isNotBlank()) id else getDeviceSyncId(context)
    }

    /**
     * Publishes state to open pub/sub relay (ntfy.sh).
     */
    private fun publishToCloudRelay(
        deviceId: String,
        status: String,
        payload: String?,
        updatedAt: Long,
        expiresAt: Long,
        activeCount: Int
    ) {
        try {
            val topic = "tascyn_sync_${deviceId.lowercase()}"
            val url = URL("https://ntfy.sh/$topic")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Title", "Tascyn Dynamic Sync")

            val json = JSONObject().apply {
                put("status", status)
                put("deviceId", deviceId)
                put("updatedAt", updatedAt)
                put("expiresAt", expiresAt)
                put("activeCount", activeCount)
                if (payload != null) put("payload", payload)
            }

            OutputStreamWriter(conn.outputStream, "UTF-8").use {
                it.write(json.toString())
                it.flush()
            }

            val responseCode = conn.responseCode
            conn.disconnect()
        } catch (e: Exception) {
            // Network failures do not crash the app; local state is preserved
            e.printStackTrace()
        }
    }

    /**
     * Fetches the data associated with a scanned dynamic QR device ID.
     * Checks local device state first (if scanned locally or on same device),
     * otherwise queries cloud relay.
     */
    fun fetchAssociatedData(
        deviceId: String,
        context: Context,
        repository: TaskManagerRepository,
        onResult: (DynamicQrFetchResult) -> Unit
    ) {
        executor.execute {
            try {
                val myDeviceId = getDeviceSyncId(context)
                // Check if scanning own device or if local session matches
                if (deviceId.equals(myDeviceId, ignoreCase = true)) {
                    val localSession = getCurrentSession(context)
                    if (localSession.isExpired || localSession.tasksPayload == null || System.currentTimeMillis() >= localSession.expiresAt) {
                        mainHandler.post {
                            onResult(DynamicQrFetchResult.Expired("This QR code data has expired (5 minute limit). Please tap Unhide on the host phone to load fresh data."))
                        }
                        return@execute
                    }
                    val tasks = repository.parseTasksFromQrPayload(localSession.tasksPayload)
                    mainHandler.post {
                        if (tasks.isNotEmpty()) {
                            onResult(DynamicQrFetchResult.Success(tasks, deviceId))
                        } else {
                            onResult(DynamicQrFetchResult.Error("No valid tasks found in dynamic QR payload."))
                        }
                    }
                    return@execute
                }

                // Query cloud relay for remote device's associated data
                val topic = "tascyn_sync_${deviceId.lowercase()}"
                val url = URL("https://ntfy.sh/$topic/json?poll=1")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 8000
                conn.readTimeout = 8000

                val responseCode = conn.responseCode
                if (responseCode != 200) {
                    mainHandler.post {
                        onResult(DynamicQrFetchResult.NotFound("No active sync session found for device $deviceId. Ask the host to tap Unhide."))
                    }
                    conn.disconnect()
                    return@execute
                }

                val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                val lines = reader.readLines()
                conn.disconnect()

                if (lines.isEmpty()) {
                    mainHandler.post {
                        onResult(DynamicQrFetchResult.NotFound("No data associated with this QR code. Ask the host to tap Unhide."))
                    }
                    return@execute
                }

                // Read the latest message (last line of json stream)
                var targetPayload: String? = null
                var isExpired = false
                var timestamp = 0L

                for (line in lines.reversed()) {
                    if (line.isBlank()) continue
                    try {
                        val obj = JSONObject(line)
                        val messageStr = obj.optString("message", "")
                        if (messageStr.isNotBlank()) {
                            val inner = JSONObject(messageStr)
                            val status = inner.optString("status", "")
                            val expAt = inner.optLong("expiresAt", 0L)
                            val now = System.currentTimeMillis()

                            if (status == "EXPIRED" || (expAt > 0 && now > expAt)) {
                                isExpired = true
                                break
                            } else if (status == "ACTIVE") {
                                targetPayload = inner.optString("payload", null)
                                timestamp = inner.optLong("updatedAt", 0L)
                                if (expAt > 0 && now > expAt) {
                                    isExpired = true
                                }
                                break
                            }
                        }
                    } catch (e: Exception) {
                        // ignore malformed lines
                    }
                }

                val now = System.currentTimeMillis()
                if (isExpired || (timestamp > 0 && now - timestamp > DATA_EXPIRATION_MS)) {
                    mainHandler.post {
                        onResult(DynamicQrFetchResult.Expired("This QR code data has expired (5 minute limit). Ask the host to tap Unhide to load fresh data."))
                    }
                    return@execute
                }

                if (targetPayload.isNullOrBlank()) {
                    mainHandler.post {
                        onResult(DynamicQrFetchResult.NotFound("QR data not found or already erased. Please ask the sender to tap Unhide on their device."))
                    }
                    return@execute
                }

                val tasks = repository.parseTasksFromQrPayload(targetPayload)
                mainHandler.post {
                    if (tasks.isNotEmpty()) {
                        onResult(DynamicQrFetchResult.Success(tasks, deviceId))
                    } else {
                        onResult(DynamicQrFetchResult.Error("No valid tasks could be extracted from QR."))
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                mainHandler.post {
                    onResult(DynamicQrFetchResult.Error("Failed to fetch dynamic QR data: ${e.localizedMessage}"))
                }
            }
        }
    }
}
