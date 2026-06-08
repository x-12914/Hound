package com.hound.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hound.app.data.Api
import com.hound.app.data.ContactInput
import com.hound.app.data.Prefs
import com.hound.app.databinding.ActivityLoginBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var prefs: Prefs
    private var registerMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        // Already signed in -> go straight to the dashboard/status screen.
        if (prefs.isLoggedIn()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.serverUrl.setText(prefs.baseUrl)

        binding.toggleMode.setOnClickListener {
            registerMode = !registerMode
            val vis = if (registerMode) android.view.View.VISIBLE else android.view.View.GONE
            binding.nameInput.visibility = vis
            binding.contactsSection.visibility = vis
            binding.submit.text = if (registerMode) "Create account" else "Sign in"
            binding.toggleMode.text = if (registerMode) "Have an account? Sign in" else "New here? Create account"
        }

        binding.submit.setOnClickListener { submit() }
    }

    private fun submit() {
        val url = binding.serverUrl.text.toString().trim()
        val email = binding.email.text.toString().trim()
        val pass = binding.password.text.toString()
        if (url.isEmpty() || email.isEmpty() || pass.isEmpty()) {
            toast("Fill in all fields"); return
        }

        var contacts: List<ContactInput> = emptyList()
        if (registerMode) {
            val c1 = ContactInput(
                binding.c1Name.text.toString().trim(),
                binding.c1Phone.text.toString().trim(),
                binding.c1Relation.text.toString().trim(),
            )
            val c2 = ContactInput(
                binding.c2Name.text.toString().trim(),
                binding.c2Phone.text.toString().trim(),
                binding.c2Relation.text.toString().trim(),
            )
            for (c in listOf(c1, c2)) {
                if (c.name.isEmpty() || c.phone.isEmpty() || c.relation.isEmpty()) {
                    toast("Add both emergency contacts (name, phone, and relation)")
                    return
                }
            }
            contacts = listOf(c1, c2)
        }

        val fullName = binding.nameInput.text.toString().trim()
        prefs.baseUrl = url
        binding.submit.isEnabled = false

        lifecycleScope.launch {
            try {
                val api = Api(prefs)
                withContext(Dispatchers.IO) {
                    if (registerMode) {
                        api.register(email, pass, fullName, contacts)
                        // cache locally right away so SMS fallback works offline
                        prefs.setContacts(contacts)
                        prefs.ownerName = fullName
                    } else {
                        api.login(email, pass)
                    }
                    api.registerDevice(android.os.Build.MODEL ?: "Android phone")
                    // refresh the cached contacts from the server (covers the login case)
                    runCatching {
                        val fetched = api.fetchContacts()
                        if (fetched.isNotEmpty()) prefs.setContacts(fetched)
                    }
                }
                startActivity(Intent(this@LoginActivity, OnboardingActivity::class.java))
                finish()
            } catch (e: Exception) {
                toast(e.message ?: "Login failed")
                binding.submit.isEnabled = true
            }
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
