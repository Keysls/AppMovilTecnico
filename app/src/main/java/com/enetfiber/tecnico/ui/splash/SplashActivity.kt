package com.enetfiber.tecnico.ui.splash

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.enetfiber.tecnico.data.local.SessionDataStore
import com.enetfiber.tecnico.databinding.ActivitySplashBinding
import com.enetfiber.tecnico.ui.login.LoginActivity
import com.enetfiber.tecnico.ui.main.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@SuppressLint("CustomSplashScreen")
@AndroidEntryPoint
class SplashActivity : AppCompatActivity() {

    @Inject lateinit var session: SessionDataStore
    private lateinit var binding: ActivitySplashBinding


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.txtSplashEstado.text = "Verificando sesión..."

        // I11 FIX: sin delay fijo. Navega apenas la verificación termina,
        // con un piso de 600ms para que el splash no "parpadee".
        lifecycleScope.launch {
            val inicio = System.currentTimeMillis()
            val token  = session.token.first()

            val transcurrido = System.currentTimeMillis() - inicio
            val minimo = 600L
            if (transcurrido < minimo) {
                kotlinx.coroutines.delay(minimo - transcurrido)
            }

            binding.txtSplashEstado.text = "Cargando..."
            val intent = if (!token.isNullOrEmpty())
                Intent(this@SplashActivity, MainActivity::class.java)
            else
                Intent(this@SplashActivity, LoginActivity::class.java)
            startActivity(intent)
            finish()
        }
    }
}