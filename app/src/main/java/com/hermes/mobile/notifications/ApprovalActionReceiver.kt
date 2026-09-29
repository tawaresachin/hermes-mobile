package com.hermes.mobile.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.hermes.mobile.MainActivity
import com.hermes.mobile.data.runs.RunController
import com.hermes.mobile.network.HermesApiService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles the approval action buttons on the notification (see
 * [ResponseWatcherService.notifyApproval]). A tap broadcasts
 * [ResponseWatcherService.ACTION_APPROVAL_CHOICE] with the session, run and
 * choice extras and resolves the pending approval WITHOUT opening the app —
 * the chat screen's card clears via the run's "approval.responded" event or
 * the next status poll.
 *
 * Resolution mirrors RunController's own paths: live turn → the controller
 * (which also clears the in-memory card); no live turn (process died) →
 * straight to the API with the persisted run id, exactly like
 * RunController.recover() would.
 */
@AndroidEntryPoint
class ApprovalActionReceiver : BroadcastReceiver() {

    @Inject lateinit var runController: RunController
    @Inject lateinit var api: HermesApiService

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ResponseWatcherService.ACTION_APPROVAL_CHOICE) return
        val sessionId = intent.getStringExtra(ResponseWatcherService.EXTRA_SESSION_ID).orEmpty()
        val runId = intent.getStringExtra(ResponseWatcherService.EXTRA_RUN_ID).orEmpty()
        val requestId = intent.getStringExtra(ResponseWatcherService.EXTRA_REQUEST_ID).orEmpty()
        val choice = intent.getStringExtra(ResponseWatcherService.EXTRA_CHOICE).orEmpty()
        if (sessionId.isBlank() || choice.isBlank()) {
            NotificationManagerCompat.from(context).cancel(ResponseWatcherService.NOTIF_ID_APPROVAL)
            return
        }
        if (runController.isBusy(sessionId)) {
            runController.resolveApproval(sessionId, choice)
        } else if (runId.isNotBlank()) {
            // Cold-start path (process died while the approval was pending):
            // resolve through the API directly — the app's recover() flow or
            // the next chat open settles the run state. Fire-and-forget: a
            // tap must never block or crash the receiver.
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                api.resolveApproval(runId, choice, requestId)
            }
        }
        NotificationManagerCompat.from(context).cancel(ResponseWatcherService.NOTIF_ID_APPROVAL)
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
            })
    }
}
