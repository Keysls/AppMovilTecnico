package com.enetfiber.tecnico.ui.login

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.enetfiber.tecnico.databinding.ActivityLoginBinding
import com.enetfiber.tecnico.ui.LoginState
import com.enetfiber.tecnico.ui.LoginViewModel
import com.enetfiber.tecnico.ui.main.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val vm: LoginViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnIngresar.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val pass  = binding.etPassword.text.toString()
            if (email.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "Completa todos los campos", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            vm.login(email, pass)
        }

        lifecycleScope.launch {
            vm.state.collect { state ->
                when (state) {
                    is LoginState.Idle    -> setLoading(false)
                    is LoginState.Loading -> setLoading(true)
                    is LoginState.Success -> {
                        startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                        finish()
                    }
                    is LoginState.Error -> {
                        setLoading(false)
                        binding.tvError.text       = state.msg
                        binding.tvError.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.btnIngresar.isEnabled    = !loading
        binding.progress.visibility      = if (loading) View.VISIBLE else View.GONE
        binding.tvError.visibility       = View.GONE
    }
}