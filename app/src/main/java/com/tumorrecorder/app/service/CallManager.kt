package com.tumorrecorder.app.service

import android.content.Context
import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.util.Log
import com.tumorrecorder.app.domain.model.CallDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ActiveCallUiState(
    val phoneNumber: String = "",
    val state: Int = Call.STATE_NEW,
    val isIncoming: Boolean = false,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val shouldRecordThisCall: Boolean = false,
    val connectedAtMillis: Long? = null,
    val quickPatientName: String = "",
    val quickPatientIdentifier: String = "",
    val quickCaseManager: String = ""
) {
    val stateLabel: String
        get() = when (state) {
            Call.STATE_RINGING -> "來電響鈴中..."
            Call.STATE_DIALING -> "撥號中..."
            Call.STATE_CONNECTING -> "連線中..."
            Call.STATE_ACTIVE -> "通話中"
            Call.STATE_HOLDING -> "通話保留中"
            Call.STATE_DISCONNECTING -> "掛斷中..."
            Call.STATE_DISCONNECTED -> "通話已結束"
            else -> "準備中..."
        }
}

/**
 * Coordinates the active Android Telecom Call (from TumorInCallService),
 * the full-screen InCallActivity UI, and selective recording via CallRecordingService.
 */
object CallManager {

    private const val TAG = "TumorRecorder_CallMgr"
    private const val PENDING_OUTGOING_TTL_MS = 20_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var inCallService: InCallService? = null

    @Volatile
    private var activeCall: Call? = null

    @Volatile
    private var pendingOutgoingRecordFlag: Boolean? = null

    @Volatile
    private var pendingOutgoingSetAtMillis: Long = 0L

    private val _callState = MutableStateFlow<ActiveCallUiState?>(null)
    val callState: StateFlow<ActiveCallUiState?> = _callState.asStateFlow()

    val isManagedByInCallService: Boolean
        get() = inCallService != null && activeCall != null

    /**
     * Called right before placing an outgoing call from our in-app Dialpad.
     */
    fun prepareAppOutgoingCall(recordThisCall: Boolean) {
        pendingOutgoingRecordFlag = recordThisCall
        pendingOutgoingSetAtMillis = System.currentTimeMillis()
    }

    /**
     * Used by CallStateReceiver (when NOT default dialer) to check whether this outgoing
     * call was explicitly launched from inside TumorRecorder with recording enabled.
     */
    fun consumePendingAppOutgoingRecord(): Boolean {
        val flag = pendingOutgoingRecordFlag ?: return false
        val age = System.currentTimeMillis() - pendingOutgoingSetAtMillis
        pendingOutgoingRecordFlag = null
        return flag && age in 0..PENDING_OUTGOING_TTL_MS
    }

    fun attachService(service: InCallService) {
        inCallService = service
    }

    fun detachService(service: InCallService) {
        if (inCallService === service) {
            inCallService = null
        }
    }

    fun onCallAdded(service: InCallService, call: Call) {
        inCallService = service
        activeCall?.unregisterCallback(callCallback)
        activeCall = call

        val number = call.details?.handle?.schemeSpecificPart.orEmpty()
        val initialState = getCallStateCompat(call)
        val isIncoming = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            call.details?.callDirection == Call.Details.DIRECTION_INCOMING || initialState == Call.STATE_RINGING
        } else {
            initialState == Call.STATE_RINGING
        }
        Log.i(TAG, "onCallAdded: number=$number initialState=$initialState isIncoming=$isIncoming")

        val shouldRecord = if (isIncoming) {
            // Wait for user to tap "接聽並錄音" vs "僅接聽（不錄音）"
            false
        } else {
            val age = System.currentTimeMillis() - pendingOutgoingSetAtMillis
            val explicit = if (age in 0..PENDING_OUTGOING_TTL_MS) pendingOutgoingRecordFlag else null
            pendingOutgoingRecordFlag = null
            // If dialed from inside our app, respect user's button choice; otherwise default true when InCallService handles it
            explicit ?: true
        }

        _callState.value = ActiveCallUiState(
            phoneNumber = number,
            state = initialState,
            isIncoming = isIncoming,
            shouldRecordThisCall = shouldRecord
        )

        call.registerCallback(callCallback)

        if (initialState == Call.STATE_ACTIVE && shouldRecord) {
            startRecordingIfNeeded(service, number, isIncoming)
        }
    }

    fun onCallRemoved(service: InCallService, call: Call) {
        if (activeCall === call) {
            call.unregisterCallback(callCallback)
            activeCall = null
        }
        if (CallRecordingService.recordingState.value.isRecording) {
            CallRecordingService.stopRecording(service)
        }
        _callState.value = null
    }

    @Suppress("DEPRECATION")
    fun onAudioStateChanged(audioState: CallAudioState?) {
        val current = _callState.value ?: return
        if (audioState == null) return
        _callState.value = current.copy(
            isMuted = audioState.isMuted,
            isSpeakerOn = audioState.route == CallAudioState.ROUTE_SPEAKER
        )
    }

    fun answer(recordThisCall: Boolean) {
        val call = activeCall ?: return
        val current = _callState.value ?: return
        _callState.value = current.copy(shouldRecordThisCall = recordThisCall)
        call.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    fun reject() {
        val call = activeCall ?: return
        if (getCallStateCompat(call) == Call.STATE_RINGING) {
            call.reject(false, null)
        } else {
            call.disconnect()
        }
    }

    fun disconnect() {
        activeCall?.disconnect()
    }

    fun toggleMute() {
        val service = inCallService ?: return
        val current = _callState.value ?: return
        val next = !current.isMuted
        service.setMuted(next)
        _callState.value = current.copy(isMuted = next)
    }

    @Suppress("DEPRECATION")
    fun toggleSpeaker() {
        val current = _callState.value ?: return
        setSpeakerEnabled(!current.isSpeakerOn)
    }

    @Suppress("DEPRECATION")
    fun setSpeakerEnabled(enabled: Boolean): Boolean {
        val service = inCallService ?: return false
        service.setAudioRoute(
            if (enabled) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE
        )
        _callState.value = _callState.value?.copy(isSpeakerOn = enabled)
        return true
    }

    fun playDtmf(digit: Char) {
        val call = activeCall ?: return
        call.playDtmfTone(digit)
        scope.launch {
            delay(180L)
            runCatching { call.stopDtmfTone() }
        }
    }

    fun toggleRecordingForActiveCall(context: Context) {
        val current = _callState.value ?: return
        if (CallRecordingService.recordingState.value.isRecording) {
            _callState.value = current.copy(shouldRecordThisCall = false)
            CallRecordingService.stopRecording(context)
        } else {
            _callState.value = current.copy(shouldRecordThisCall = true)
            if (current.state == Call.STATE_ACTIVE) {
                startRecordingIfNeeded(context, current.phoneNumber, current.isIncoming)
            }
        }
    }

    @Volatile
    private var lastQuickNotes: Triple<String, String, String> = Triple("", "", "")

    fun updateQuickNotes(patientName: String, patientIdentifier: String, caseManager: String) {
        lastQuickNotes = Triple(patientName.trim(), patientIdentifier.trim(), caseManager.trim())
        val current = _callState.value ?: return
        _callState.value = current.copy(
            quickPatientName = patientName,
            quickPatientIdentifier = patientIdentifier,
            quickCaseManager = caseManager
        )
    }

    fun snapshotQuickNotes(): Triple<String, String, String> {
        val c = _callState.value
        val result = if (c != null) {
            Triple(c.quickPatientName.trim(), c.quickPatientIdentifier.trim(), c.quickCaseManager.trim())
        } else {
            lastQuickNotes
        }
        lastQuickNotes = Triple("", "", "")
        return result
    }

    private fun startRecordingIfNeeded(context: Context, number: String, isIncoming: Boolean) {
        if (CallRecordingService.recordingState.value.isRecording) return
        val launchContext = CallRecorderAccessibilityService.instance ?: context
        val direction = if (isIncoming) CallDirection.INCOMING else CallDirection.OUTGOING
        runCatching {
            CallRecordingService.startRecording(
                context = launchContext,
                phoneNumber = number,
                direction = direction,
                fromPhoneCall = true
            )
        }.onFailure { e ->
            Log.e(TAG, "Failed to start CallRecordingService from CallManager", e)
        }
    }

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            val current = _callState.value ?: return
            val number = call.details?.handle?.schemeSpecificPart.orEmpty().ifBlank { current.phoneNumber }
            val connectedAt = if (state == Call.STATE_ACTIVE && current.connectedAtMillis == null) {
                System.currentTimeMillis()
            } else {
                current.connectedAtMillis
            }

            _callState.value = current.copy(
                phoneNumber = number,
                state = state,
                connectedAtMillis = connectedAt
            )

            val service = inCallService as? TumorInCallService
            Log.i(TAG, "onStateChanged: number=$number state=$state isIncoming=${current.isIncoming}")
            when (state) {
                Call.STATE_RINGING -> {
                    service?.onCallRinging(number.ifBlank { "未知來電" })
                }

                Call.STATE_ACTIVE -> {
                    service?.onCallActive(number.ifBlank { "通話中" })
                    if (current.shouldRecordThisCall && inCallService != null) {
                        startRecordingIfNeeded(inCallService!!, number, current.isIncoming)
                    }
                }

                Call.STATE_DISCONNECTED -> {
                    service?.cancelCallNotifications()
                    if (inCallService != null && CallRecordingService.recordingState.value.isRecording) {
                        CallRecordingService.stopRecording(inCallService!!)
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getCallStateCompat(call: Call): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            call.details?.state ?: call.state
        } else {
            call.state
        }
    }
}
