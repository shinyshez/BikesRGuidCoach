package com.mtbanalyzer

import android.content.ContentResolver
import android.content.ContentUris
import android.content.res.Configuration
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.ListPreference
import androidx.preference.PreferenceCategory
import androidx.preference.Preference
import com.mtbanalyzer.detector.RiderDetectorManager
import com.mtbanalyzer.detector.RiderDetector.ConfigType
import com.mtbanalyzer.tuning.DetectionTuningActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"
        
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
    
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        
        // The PreferenceFragmentCompat handles orientation changes automatically
        // but we can add any custom handling here if needed
        Log.d("SettingsActivity", "Configuration changed: orientation = ${
            if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        }")
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private lateinit var settingsManager: SettingsManager
        private lateinit var detectorManager: RiderDetectorManager
        private var detectorConfigCategory: PreferenceCategory? = null
        
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            
            // Initialize managers
            settingsManager = SettingsManager(requireContext())
            detectorManager = RiderDetectorManager(requireContext(), settingsManager)
            detectorManager.initialize()
            
            // Create detector config category
            detectorConfigCategory = PreferenceCategory(requireContext()).apply {
                title = "Detector Settings"
                order = 100 // Position it after detection category
            }
            
            // Add it to the screen
            preferenceScreen.addPreference(detectorConfigCategory!!)
            
            // Set the dependency after preferences are added
            detectorConfigCategory?.dependency = "rider_detection_enabled"
            
            // Load initial detector config
            loadDetectorConfig()
            
            // Set up preference listeners
            findPreference<SwitchPreferenceCompat>("rider_detection_enabled")?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                Toast.makeText(context, if (enabled) "Rider detection enabled" else "Rider detection disabled", Toast.LENGTH_SHORT).show()
                true
            }
            
            findPreference<ListPreference>("detector_type")?.setOnPreferenceChangeListener { _, newValue ->
                val detectorType = newValue as String
                val detectorName = when(detectorType) {
                    "pose" -> "ML Kit Pose Detection"
                    "motion" -> "Motion Detection"
                    "optical_flow" -> "Optical Flow Detection"
                    "hybrid" -> "Hybrid Detection"
                    else -> "Unknown"
                }
                
                // Switch detector and reload config
                detectorManager.switchDetector(detectorType)
                loadDetectorConfig()
                
                Toast.makeText(context, "Detection method: $detectorName", Toast.LENGTH_SHORT).show()
                true
            }
            
            findPreference<SeekBarPreference>("recording_duration")?.setOnPreferenceChangeListener { _, newValue ->
                val duration = newValue as Int
                Toast.makeText(context, "Recording duration: ${duration}s", Toast.LENGTH_SHORT).show()
                true
            }
            
            findPreference<SeekBarPreference>("detection_sensitivity")?.setOnPreferenceChangeListener { _, newValue ->
                val sensitivity = newValue as Int
                Toast.makeText(context, "Detection sensitivity: ${sensitivity}%", Toast.LENGTH_SHORT).show()
                true
            }
            
            findPreference<SwitchPreferenceCompat>("haptic_feedback")?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                Toast.makeText(context, "Haptic feedback: ${if (enabled) "On" else "Off"}", Toast.LENGTH_SHORT).show()
                true
            }
            
            // Motion detection settings
            findPreference<SeekBarPreference>("motion_threshold")?.setOnPreferenceChangeListener { _, newValue ->
                val threshold = newValue as Int
                Toast.makeText(context, "Motion threshold: $threshold", Toast.LENGTH_SHORT).show()
                true
            }
            
            findPreference<SeekBarPreference>("min_motion_area")?.setOnPreferenceChangeListener { _, newValue ->
                val area = newValue as Int
                Toast.makeText(context, "Min motion area: $area pixels", Toast.LENGTH_SHORT).show()
                true
            }
            
            findPreference<SwitchPreferenceCompat>("show_motion_overlay")?.setOnPreferenceChangeListener { _, newValue ->
                val showOverlay = newValue as Boolean
                Toast.makeText(context, if (showOverlay) "Motion overlay enabled" else "Motion overlay disabled", Toast.LENGTH_SHORT).show()
                true
            }
            
            findPreference<androidx.preference.Preference>("clear_cache")?.setOnPreferenceClickListener {
                showClearVideosDialog()
                true
            }

            findPreference<androidx.preference.Preference>("detection_tuning")?.setOnPreferenceClickListener {
                startActivity(android.content.Intent(requireContext(), DetectionTuningActivity::class.java))
                true
            }
        }
        
        private fun showClearVideosDialog() {
            AlertDialog.Builder(requireContext())
                .setTitle("Clear All Videos")
                .setMessage("Are you sure you want to delete all recorded MTB videos? This action cannot be undone.")
                .setPositiveButton("Delete All") { _, _ ->
                    clearAllVideos()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        
        private fun clearAllVideos() {
            try {
                val contentResolver = requireContext().contentResolver
                val projection = arrayOf(MediaStore.Video.Media._ID)
                val selection = "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
                val selectionArgs = arrayOf("MTB_%")
                
                val cursor = contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null
                )
                
                var deletedCount = 0
                cursor?.use {
                    val idColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    while (it.moveToNext()) {
                        val id = it.getLong(idColumn)
                        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                        val deleted = contentResolver.delete(uri, null, null)
                        if (deleted > 0) {
                            deletedCount++
                        }
                    }
                }
                
                Toast.makeText(
                    requireContext(), 
                    if (deletedCount > 0) "Deleted $deletedCount videos" else "No videos to delete", 
                    Toast.LENGTH_LONG
                ).show()
                
            } catch (e: SecurityException) {
                Log.e("SettingsFragment", "Permission denied to delete videos", e)
                Toast.makeText(requireContext(), "Permission needed to delete videos", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e("SettingsFragment", "Error deleting videos", e)
                Toast.makeText(requireContext(), "Error deleting videos: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        
        private fun loadDetectorConfig() {
            // Clear existing detector config preferences
            detectorConfigCategory?.removeAll()
            
            // Get current detector config options
            val configOptions = detectorManager.getCurrentDetectorConfigOptions()
            
            // Create preferences for each config option
            for ((key, option) in configOptions) {
                val preference = when (option.type) {
                    ConfigType.BOOLEAN -> {
                        SwitchPreferenceCompat(requireContext()).apply {
                            this.key = option.key
                            title = option.displayName
                            summary = option.description
                            setDefaultValue(option.defaultValue as? Boolean ?: false)
                        }
                    }
                    ConfigType.INTEGER -> {
                        SeekBarPreference(requireContext()).apply {
                            this.key = option.key
                            title = option.displayName
                            summary = option.description
                            setDefaultValue(option.defaultValue as? Int ?: 0)
                            min = (option.minValue as? Number)?.toInt() ?: 0
                            max = (option.maxValue as? Number)?.toInt() ?: 100
                            showSeekBarValue = true
                            seekBarIncrement = 1
                        }
                    }
                    ConfigType.FLOAT -> {
                        SeekBarPreference(requireContext()).apply {
                            this.key = option.key
                            title = option.displayName
                            summary = option.description
                            // Convert float to int by multiplying by 10 for SeekBar
                            val floatDefault = (option.defaultValue as? Number)?.toDouble() ?: 0.0
                            setDefaultValue((floatDefault * 10).toInt())
                            min = ((option.minValue as? Number)?.toDouble() ?: 0.0).let { (it * 10).toInt() }
                            max = ((option.maxValue as? Number)?.toDouble() ?: 10.0).let { (it * 10).toInt() }
                            showSeekBarValue = true
                            seekBarIncrement = 1
                            summary = "${option.description} (displayed as x10)"
                        }
                    }
                    else -> {
                        // For STRING and ENUM types, create basic preference for now
                        Preference(requireContext()).apply {
                            this.key = option.key
                            title = option.displayName
                            summary = option.description
                            isEnabled = false
                        }
                    }
                }
                
                detectorConfigCategory?.addPreference(preference)
            }
        }
    }
}