package com.hound.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hound.app.data.Api
import com.hound.app.data.Prefs
import com.hound.app.databinding.ActivityLoginBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        // Already signed in -> straight to the status screen.
        if (prefs.isLoggedIn()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.submit.setOnClickListener { login() }
        binding.goToSignup.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }
    }

    private fun login() {
        val email = binding.email.text.toString().trim()
        val pass = binding.password.text.toString()
        binding.loginError.text = ""
        if (email.isEmpty() || pass.isEmpty()) {
            binding.loginError.text = "Enter your email and password."
            return
        }
        binding.submit.isEnabled = false

        lifecycleScope.launch {
            try {
                val api = Api(prefs)
                withContext(Dispatchers.IO) {
                    api.login(email, pass)
                    api.registerDevice(android.os.Build.MODEL ?: "Android phone")
                    // cache contacts locally so SMS fallback works offline
                    runCatching {
                        val fetched = api.fetchContacts()
                        if (fetched.isNotEmpty()) prefs.setContacts(fetched)
                    }
                }
                startActivity(Intent(this@LoginActivity, OnboardingActivity::class.java))
                finish()
            } catch (e: Exception) {
                binding.loginError.text = e.message ?: "Sign in failed."
                binding.submit.isEnabled = true
            }
        }
    }
}
