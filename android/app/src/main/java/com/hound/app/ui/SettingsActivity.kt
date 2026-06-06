package com.hound.app.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hound.app.data.Prefs
import com.hound.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.pressCount.setText(prefs.pressCount.toString())
        binding.pressWindow.setText(prefs.pressWindowMs.toString())
        binding.locInterval.setText(prefs.locationIntervalSec.toString())
        binding.audioClip.setText(prefs.audioClipSec.toString())
        binding.captureAudio.isChecked = prefs.captureAudio
        binding.serverUrl.setText(prefs.baseUrl)

        binding.save.setOnClickListener { save() }
    }

    private fun save() {
        prefs.pressCount = binding.pressCount.text.toString().toIntOrNull()?.coerceIn(2, 8) ?: 3
        prefs.pressWindowMs = binding.pressWindow.text.toString().toLongOrNull()?.coerceIn(800L, 5000L) ?: 2000L
        prefs.locationIntervalSec = binding.locInterval.text.toString().toIntOrNull()?.coerceIn(3, 120) ?: 10
        prefs.audioClipSec = binding.audioClip.text.toString().toIntOrNull()?.coerceIn(5, 60) ?: 15
        prefs.captureAudio = binding.captureAudio.isChecked
        prefs.baseUrl = binding.serverUrl.text.toString().trim()
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}
