package com.mtbanalyzer

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat

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

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            
            // Set up preference listeners
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
        }
    }
}