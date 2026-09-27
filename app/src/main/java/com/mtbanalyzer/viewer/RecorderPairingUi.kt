package com.mtbanalyzer.viewer

import android.text.InputType
import android.util.Log
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The viewer's dialogs: pairing with a recorder (scan its QR, or type the URL shown under
 * it), unpairing, and fetching a recorder clip with a progress bar before it opens.
 *
 * No App Link or intent filter on http:// (Phase 2 spec §5): it cannot be scoped to local
 * addresses, so it would offer this app for every web link the phone opens.
 */
class RecorderPairingUi(
    private val activity: AppCompatActivity,
    private val onPairingChanged: () -> Unit
) {

    companion object {
        private const val TAG = "RecorderPairingUi"
        private val DIALOG_THEME = androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert
    }

    private val client = RecorderClient()

    /** The connect button: pair when unpaired, otherwise offer to unpair or re-scan. */
    fun showMenu() {
        val current = RecorderSession.recorder
        if (current == null) {
            AlertDialog.Builder(activity, DIALOG_THEME)
                .setTitle("Connect to recorder")
                .setMessage(
                    "On the recording phone, open Settings › Viewer Link. Join its hotspot, " +
                        "then scan the code it shows."
                )
                .setPositiveButton("Scan code") { _, _ -> scan() }
                .setNeutralButton("Type address") { _, _ -> typeAddress() }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            AlertDialog.Builder(activity, DIALOG_THEME)
                .setTitle("Connected to ${current.device}")
                .setMessage("Disconnecting also clears the clips downloaded from it.")
                .setPositiveButton("Scan new code") { _, _ -> scan() }
                .setNegativeButton("Disconnect") { _, _ -> disconnect() }
                .setNeutralButton("Close", null)
                .show()
        }
    }

    private fun scan() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        GmsBarcodeScanning.getClient(activity, options).startScan()
            .addOnSuccessListener { barcode -> connect(barcode.rawValue.orEmpty()) }
            .addOnFailureListener { e ->
                // No Play services, or the scanner module is still downloading.
                Log.w(TAG, "Code scanner unavailable", e)
                toast("Scanner not available here. Type the address instead.")
                typeAddress()
            }
    }

    private fun typeAddress() {
        val input = EditText(activity).apply {
            hint = "http://192.168.43.1:8080/?t=…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        AlertDialog.Builder(activity, DIALOG_THEME)
            .setTitle("Recorder address")
            .setMessage("The link shown under the code on the recording phone.")
            .setView(padded(input))
            .setPositiveButton("Connect") { _, _ -> connect(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun connect(text: String) {
        val address = RecorderAddress.parse(text)
        if (address == null) {
            toast("That isn't a Viewer Link code")
            return
        }
        val progress = progressDialog("Connecting…", null)
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { client.pair(address) } }
            progress.dismiss()
            result.onSuccess { recorder ->
                // A different recorder than before: its predecessor's downloads are no use.
                val previous = RecorderSession.recorder
                if (previous != null && previous.id != recorder.id) {
                    withContext(Dispatchers.IO) { RecorderSession.forget(activity) }
                }
                RecorderSession.pair(recorder)
                toast("Connected to ${recorder.device}")
                onPairingChanged()
            }.onFailure { e ->
                Log.w(TAG, "Pairing failed", e)
                toast(failureMessage(e))
            }
        }
    }

    private fun disconnect() {
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) { RecorderSession.forget(activity) }
            onPairingChanged()
        }
    }

    /**
     * Downloads [clipId] from the paired recorder if it is not cached yet, with a cancellable
     * progress dialog, then hands over the local file.
     */
    fun fetchClip(recorder: Recorder, clipId: Long, title: String, then: (File) -> Unit) {
        val cache = RecorderSession.cache(activity)
        cache.cached(recorder.id, clipId)?.let {
            then(it)
            return
        }

        var job: Job? = null
        val cancelled = AtomicBoolean(false)
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        val dialog = progressDialog(title, bar) {
            cancelled.set(true)
            job?.cancel()
        }
        job = activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    cache.fetch(
                        recorder,
                        clipId,
                        onProgress = { done, total ->
                            if (total > 0) bar.post { bar.progress = (done * 1000 / total).toInt() }
                        },
                        isCancelled = { cancelled.get() }
                    )
                }
            }
            dialog.dismiss()
            result.onSuccess(then).onFailure { e ->
                if (e !is RemoteClipCache.CancelledException) {
                    Log.w(TAG, "Download failed", e)
                    toast(failureMessage(e))
                }
            }
        }
    }

    /** Our own explanations pass through; socket errors ("ECONNREFUSED …") mean nothing to a coach. */
    fun failureMessage(e: Throwable): String = when (e) {
        is RecorderClient.RecorderException -> e.message ?: "The recorder refused"
        else -> "Can't reach the recorder. Are both phones on its hotspot?"
    }

    private fun progressDialog(title: String, bar: ProgressBar?, onCancel: (() -> Unit)? = null): AlertDialog {
        val builder = AlertDialog.Builder(activity, DIALOG_THEME)
            .setTitle(title)
            .setCancelable(onCancel != null)
            .setView(padded(bar ?: ProgressBar(activity)))
        if (onCancel != null) {
            builder.setNegativeButton("Cancel") { _, _ -> onCancel() }
            builder.setOnCancelListener { onCancel() }
        }
        return builder.show()
    }

    private fun padded(view: android.view.View): FrameLayout {
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        return FrameLayout(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
}
