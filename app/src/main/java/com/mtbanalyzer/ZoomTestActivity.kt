package com.mtbanalyzer

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ZoomTestActivity : AppCompatActivity() {
    private lateinit var zoomView: ZoomableImageView
    private lateinit var zoomInfoText: TextView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_zoom_test)
        
        zoomView = findViewById(R.id.zoomableImageView)
        zoomInfoText = findViewById(R.id.zoomInfoText)
        
        // Set up zoom info listener
        zoomView.setZoomInfoListener { scale, translationX, translationY ->
            val info = String.format(
                "Scale: %.2f\nTranslation: (%.0f, %.0f)",
                scale, translationX, translationY
            )
            zoomInfoText.text = info
        }
        
        // Reset button
        findViewById<Button>(R.id.resetButton).setOnClickListener {
            zoomView.reset()
        }
        
        // Back button
        findViewById<Button>(R.id.backButton).setOnClickListener {
            finish()
        }
    }
}