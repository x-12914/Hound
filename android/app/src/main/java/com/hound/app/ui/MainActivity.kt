package com.hound.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.hound.app.data.Prefs
import com.hound.app.databinding.ActivityMainBinding
import com.hound.app.service.SosForegroundService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.guardianSwitch.setOnClickListener {
            val checked = binding.guardianSwitch.isChecked
            prefs.guardianEnabled = checked
            if (checked) SosForegroundService.start(this) else SosForegroundService.stop(this)
            render()
        }

        binding.btnTest.setOnClickListener { confirmTestSos() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnCancel.setOnClickListener {
            SosForegroundService.start(this) // ensure running
            startService(
                Intent(this, SosForegroundService::class.java)
                    .setAction(SosForegroundService.ACTION_CANCEL)
            )
        }
        binding.btnLogout.setOnClickListener { logout() }
    }

    override fun onResume() {
        super.onResume()
        // Self-heal: if protection is on, make sure the guardian is actually running.
        if (prefs.guardianEnabled) SosForegroundService.start(this)
        render()
    }

    private fun render() {
        binding.guardianSwitch.isChecked = prefs.guardianEnabled
        binding.email.text = prefs.email ?: ""
        binding.statusText.text = if (prefs.guardianEnabled)
            "Protected · press power ${prefs.pressCount}× fast to send an SOS"
        else
            "Guardian is off — you are not protected"
        binding.statusDot.setBackgroundResource(
            if (prefs.guardianEnabled) com.hound.app.R.drawable.dot_green
            else com.hound.app.R.drawable.dot_grey
        )
    }

    private fun confirmTestSos() {
        AlertDialog.Builder(this)
            .setTitle("Send a test SOS?")
            .setMessage("This creates a real alert on the dashboard so you can verify everything works. You can cancel it right after.")
            .setPositiveButton("Send test") { _, _ ->
                SosForegroundService.start(this)
                startService(
                    Intent(this, SosForegroundService::class.java)
                        .setAction(SosForegroundService.ACTION_TRIGGER)
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun logout() {
        AlertDialog.Builder(this)
            .setTitle("Sign out?")
            .setMessage("The guardian will stop protecting this device.")
            .setPositiveButton("Sign out") { _, _ ->
                SosForegroundService.stop(this)
                prefs.guardianEnabled = false
                prefs.clearSession()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
            .setNegativeButton("Stay", null)
            .show()
    }
}
