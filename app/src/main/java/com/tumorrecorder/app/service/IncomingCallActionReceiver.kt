package com.tumorrecorder.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tumorrecorder.app.ui.incall.InCallActivity

/**
 * Handles direct Answer / Private Answer / Decline / Hangup actions tapped
 * on the heads-up CallStyle incoming & ongoing call notifications.
 */
class IncomingCallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "Received call notification action: $action")
        when (action) {
            ACTION_ANSWER_RECORD -> {
                CallManager.answer(recordThisCall = true)
                openInCallUi(context)
            }
            ACTION_ANSWER_PRIVATE -> {
                CallManager.answer(recordThisCall = false)
                openInCallUi(context)
            }
            ACTION_DECLINE -> {
                CallManager.reject()
            }
            ACTION_HANGUP -> {
                CallManager.disconnect()
            }
        }
    }

    private fun openInCallUi(context: Context) {
        val uiIntent = Intent(context, InCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        runCatching { context.startActivity(uiIntent) }
    }

    companion object {
        private const val TAG = "TumorRecorder_CallAct"
        const val ACTION_ANSWER_RECORD = "com.tumorrecorder.app.action.ANSWER_RECORD"
        const val ACTION_ANSWER_PRIVATE = "com.tumorrecorder.app.action.ANSWER_PRIVATE"
        const val ACTION_DECLINE = "com.tumorrecorder.app.action.DECLINE_CALL"
        const val ACTION_HANGUP = "com.tumorrecorder.app.action.HANGUP_CALL"
    }
}
