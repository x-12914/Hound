package com.hound.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.hound.app.data.Prefs
import com.hound.app.databinding.ActivityOnboardingBinding
import com.hound.app.service.SosForegroundService

/**
 * Walks the user through the permissions the guardian needs. Android requires
 * these to be requested in stages: foreground first, then background location
 * and battery exemption separately.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var prefs: Prefs

    private val basePerms = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val baseLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refresh()
        }

    private val bgLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPermissions.setOnClickListener { baseLauncher.launch(basePerms) }
        binding.btnBackground.setOnClickListener { requestBackgroundLocation() }
        binding.btnBattery.setOnClickListener { requestIgnoreBattery() }
        binding.btnFinish.setOnClickListener { finishOnboarding() }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        val baseOk = granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
            granted(Manifest.permission.RECORD_AUDIO)
        val bgOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val battOk = isIgnoringBattery()

        binding.checkPermissions.text = if (baseOk) "✓ Location & microphone granted" else "○ Location & microphone"
        binding.checkBackground.text = if (bgOk) "✓ Background location granted" else "○ Background location (\"Allow all the time\")"
        binding.checkBattery.text = if (battOk) "✓ Battery optimization disabled" else "○ Keep running in background"

        binding.btnBackground.isEnabled = baseOk && !bgOk
        binding.btnFinish.isEnabled = baseOk
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    private fun isIgnoringBattery(): Boolean {
        val pm = getSystemService(PowerManager::class.java)
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBattery() {
        if (isIgnoringBattery()) return
        startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName"),
            )
        )
    }

    private fun finishOnboarding() {
        prefs.guardianEnabled = true
        SosForegroundService.start(this)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
