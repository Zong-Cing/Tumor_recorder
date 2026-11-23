package com.tumorrecorder.app.data.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DeviceCallLogEntry(
    val id: Long,
    val number: String,
    val cachedName: String,
    val type: Int,
    val dateMillis: Long,
    val durationSeconds: Long
) {
    val displayTitle: String
        get() = cachedName.ifBlank { number.ifBlank { "未知號碼" } }

    val hasCachedName: Boolean
        get() = cachedName.isNotBlank() && cachedName != number

    val typeLabel: String
        get() = when (type) {
            CallLog.Calls.INCOMING_TYPE -> "來電"
            CallLog.Calls.OUTGOING_TYPE -> "撥出"
            CallLog.Calls.MISSED_TYPE -> "未接來電"
            CallLog.Calls.REJECTED_TYPE -> "已拒接"
            CallLog.Calls.BLOCKED_TYPE -> "已封鎖"
            CallLog.Calls.VOICEMAIL_TYPE -> "語音信箱"
            else -> "通話"
        }

    val isMissedOrRejected: Boolean
        get() = type == CallLog.Calls.MISSED_TYPE ||
            type == CallLog.Calls.REJECTED_TYPE ||
            type == CallLog.Calls.BLOCKED_TYPE
}

/**
 * Reads the Android system CallLog (outgoing / incoming / missed calls)
 * so the main dialer screen behaves like a standard Phone app.
 */
class DeviceCallLogRepository(private val context: Context) {

    fun hasCallLogPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun loadRecentCalls(limit: Int = 80): List<DeviceCallLogEntry> = withContext(Dispatchers.IO) {
        if (!hasCallLogPermission()) return@withContext emptyList()

        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
        )

        val entries = mutableListOf<DeviceCallLogEntry>()
        runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
                val numIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val nameIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val typeIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val durIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)

                while (cursor.moveToNext() && entries.size < limit) {
                    entries.add(
                        DeviceCallLogEntry(
                            id = cursor.getLong(idIdx),
                            number = cursor.getString(numIdx).orEmpty().trim(),
                            cachedName = cursor.getString(nameIdx).orEmpty().trim(),
                            type = cursor.getInt(typeIdx),
                            dateMillis = cursor.getLong(dateIdx),
                            durationSeconds = cursor.getLong(durIdx).coerceAtLeast(0L)
                        )
                    )
                }
            }
        }.onFailure { e ->
            Log.w(TAG, "Failed to query system CallLog", e)
        }
        entries
    }

    companion object {
        private const val TAG = "TumorRecorder_CallLog"
    }
}
