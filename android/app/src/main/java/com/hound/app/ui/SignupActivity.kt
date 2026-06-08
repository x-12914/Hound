package com.hound.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.animation.AnimationUtils
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.hound.app.R
import com.hound.app.data.Api
import com.hound.app.data.ContactInput
import com.hound.app.data.Prefs
import com.hound.app.databinding.ActivitySignupBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A friendly, step-by-step account-creation wizard. */
class SignupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignupBinding
    private lateinit var prefs: Prefs
    private var step = 0
    private val lastStep = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        binding = ActivitySignupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.flipper.inAnimation = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
        binding.flipper.outAnimation = AnimationUtils.loadAnimation(this, android.R.anim.fade_out)

        binding.btnNext.setOnClickListener { onNext() }
        binding.btnBack.setOnClickListener { onBack() }
        updateUi()
    }

    private fun onNext() {
        binding.errorText.text = ""
        val problem = validate(step)
        if (problem != null) {
            binding.errorText.text = problem
            return
        }
        if (step < lastStep) {
            step++
            binding.flipper.showNext()
            updateUi()
        } else {
            register()
        }
    }

    private fun onBack() {
        binding.errorText.text = ""
        if (step == 0) {
            finish() // back to login
        } else {
            step--
            binding.flipper.showPrevious()
            updateUi()
        }
    }

    override fun onBackPressed() {
        if (step == 0) super.onBackPressed() else onBack()
    }

    private fun updateUi() {
        val accent = ContextCompat.getColor(this, R.color.accent)
        val border = ContextCompat.getColor(this, R.color.border)
        for (i in 0 until binding.progressBar.childCount) {
            binding.progressBar.getChildAt(i).setBackgroundColor(if (i <= step) accent else border)
        }
        binding.btnNext.text = when (step) {
            0 -> "Get started"
            lastStep -> "Create account"
            else -> "Continue"
        }
        binding.btnBack.text = if (step == 0) "Sign in" else "Back"
    }

    /** Returns an error message, or null if the step's input is valid. */
    private fun validate(s: Int): String? = when (s) {
        0 -> null
        1 -> {
            val name = binding.nameInput.text.toString().trim()
            val email = binding.email.text.toString().trim()
            val pass = binding.password.text.toString()
            when {
                name.isEmpty() -> "What should we call you?"
                !email.contains("@") || !email.contains(".") -> "Enter a valid email address."
                pass.length < 6 -> "Password should be at least 6 characters."
                else -> null
            }
        }
        2 -> contactProblem(
            binding.c1Name.text.toString(), binding.c1Phone.text.toString(),
            binding.c1Relation.text.toString(), "first",
        )
        3 -> contactProblem(
            binding.c2Name.text.toString(), binding.c2Phone.text.toString(),
            binding.c2Relation.text.toString(), "second",
        )
        else -> null
    }

    private fun contactProblem(name: String, phone: String, relation: String, which: String): String? =
        when {
            name.trim().isEmpty() -> "Add the name of your $which contact."
            phone.trim().length < 6 -> "Add a valid phone number for your $which contact."
            relation.trim().isEmpty() -> "How are they related to you?"
            else -> null
        }

    private fun register() {
        val email = binding.email.text.toString().trim()
        val pass = binding.password.text.toString()
        val fullName = binding.nameInput.text.toString().trim()
        val contacts = listOf(
            ContactInput(
                binding.c1Name.text.toString().trim(),
                binding.c1Phone.text.toString().trim(),
                binding.c1Relation.text.toString().trim(),
            ),
            ContactInput(
                binding.c2Name.text.toString().trim(),
                binding.c2Phone.text.toString().trim(),
                binding.c2Relation.text.toString().trim(),
            ),
        )

        setBusy(true)
        lifecycleScope.launch {
            try {
                val api = Api(prefs)
                withContext(Dispatchers.IO) {
                    api.register(email, pass, fullName, contacts)
                    prefs.setContacts(contacts)
                    prefs.ownerName = fullName
                    api.registerDevice(android.os.Build.MODEL ?: "Android phone")
                }
                startActivity(Intent(this@SignupActivity, OnboardingActivity::class.java))
                finish()
            } catch (e: Exception) {
                binding.errorText.text = e.message ?: "Couldn't create your account."
                setBusy(false)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.btnNext.isEnabled = !busy
        binding.btnBack.isEnabled = !busy
        binding.btnNext.text = if (busy) "Creating…" else "Create account"
    }
}
