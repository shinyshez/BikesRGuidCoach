package com.mtbanalyzer.viewer

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.mtbanalyzer.MediaPermissions
import com.mtbanalyzer.R
import com.mtbanalyzer.SettingsManager

/**
 * Pairing screen: turn the link on, then scan the code from the viewer phone. Everything
 * here happens before the recorder goes on the tripod.
 */
class ViewerLinkActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ViewerLinkActivity"
        private const val QR_PIXELS = 720
    }

    private lateinit var qrImage: ImageView
    private lateinit var urlText: TextView
    private lateinit var statusText: TextView
    private lateinit var hintText: TextView
    private lateinit var toggleButton: Button

    private lateinit var settingsManager: SettingsManager

    private val statusListener: (ViewerLinkController.Status) -> Unit = { status ->
        runOnUiThread { render(status) }
    }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Denied only means no notification; the server still runs.
            startLink()
        }

    private val requestMediaRead =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            render(ViewerLinkController.status())
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer_link)

        settingsManager = SettingsManager(this)

        qrImage = findViewById(R.id.viewerLinkQr)
        urlText = findViewById(R.id.viewerLinkUrl)
        statusText = findViewById(R.id.viewerLinkStatus)
        hintText = findViewById(R.id.viewerLinkHint)
        toggleButton = findViewById(R.id.viewerLinkToggle)

        findViewById<View>(R.id.viewerLinkBack).setOnClickListener { finish() }
        toggleButton.setOnClickListener { onToggle() }

        // Scanning a code off a screen that keeps dimming is miserable.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStart() {
        super.onStart()
        ViewerLinkController.addListener(statusListener)
        if (!MediaPermissions.hasReadVideoPermission(this)) {
            requestMediaRead.launch(MediaPermissions.readVideoPermission())
        }
    }

    override fun onStop() {
        ViewerLinkController.removeListener(statusListener)
        super.onStop()
    }

    private fun onToggle() {
        if (ViewerLinkController.status().running) {
            settingsManager.setViewerLinkEnabled(false)
            ViewerLinkService.stop(this)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startLink()
    }

    private fun startLink() {
        settingsManager.setViewerLinkEnabled(true)
        ViewerLinkService.start(this)
    }

    private fun render(status: ViewerLinkController.Status) {
        if (status.running && status.url != null) {
            toggleButton.setText(R.string.viewer_link_turn_off)
            statusText.setText(R.string.viewer_link_on)
            urlText.text = "http://${status.address}:${status.port}"
            urlText.visibility = View.VISIBLE
            qrImage.visibility = View.VISIBLE
            showQr(status.url)
            hintText.setText(
                if (MediaPermissions.hasReadVideoPermission(this)) {
                    R.string.viewer_link_hint_on
                } else {
                    R.string.viewer_link_hint_no_media_permission
                }
            )
        } else {
            toggleButton.setText(R.string.viewer_link_turn_on)
            statusText.text = status.error ?: getString(R.string.viewer_link_off)
            urlText.visibility = View.GONE
            qrImage.visibility = View.GONE
            qrImage.setImageDrawable(null)
            hintText.setText(R.string.viewer_link_hint_off)
        }
    }

    private fun showQr(url: String) {
        try {
            qrImage.setImageBitmap(encodeQr(url, QR_PIXELS))
        } catch (e: Exception) {
            Log.e(TAG, "Could not render the QR code", e)
            qrImage.visibility = View.GONE
            urlText.text = url
        }
    }

    private fun encodeQr(text: String, size: Int): Bitmap {
        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                pixels[row + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
