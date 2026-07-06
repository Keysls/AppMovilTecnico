package com.enetfiber.tecnico.ui.main

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.enetfiber.tecnico.R
import com.enetfiber.tecnico.databinding.ActivityMainBinding
import com.enetfiber.tecnico.ui.DashboardViewModel
import com.enetfiber.tecnico.ui.login.LoginActivity
import com.enetfiber.tecnico.data.local.SessionEvents
import dagger.hilt.android.AndroidEntryPoint
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.enetfiber.tecnico.ui.plantaexterna.PlantaExternaFragment
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    val vm: DashboardViewModel by viewModels()

    private var ultimaActividad = System.currentTimeMillis()
    private val TIMEOUT_MS = 8 * 60 * 60 * 1000L // 8 horas

    override fun onUserInteraction() {
        super.onUserInteraction()
        ultimaActividad = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        val inactivo = System.currentTimeMillis() - ultimaActividad > TIMEOUT_MS
        if (inactivo) {
            Toast.makeText(this, "Sesión cerrada por inactividad", Toast.LENGTH_LONG).show()
            logout()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Barra navegación sistema — blanca con iconos oscuros
        window.navigationBarColor = android.graphics.Color.WHITE
        WindowInsetsControllerCompat(window, window.decorView)
            .isAppearanceLightNavigationBars = true

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        // Inflar toolbar custom con título + avatar
        val toolbarView = LayoutInflater.from(this)
            .inflate(R.layout.toolbar_custom, binding.toolbar, false)
        binding.toolbar.addView(toolbarView)

        setupAvatar(toolbarView)
        setupBottomNav()
        observarSesion()

        if (savedInstanceState == null) {
            cargarFragment(DashboardFragment(), "Inicio")
            binding.bottomNav.selectedItemId = R.id.nav_dashboard
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setupAvatar(toolbarView: android.view.View) {
        val tvAvatar = toolbarView.findViewById<TextView>(R.id.tvToolbarAvatar)

        lifecycleScope.launch {
            combine(vm.nombre, vm.apellido) { n, a -> Pair(n, a) }
                .collect { (nombre, apellido) ->
                    val iniciales = buildString {
                        nombre?.firstOrNull()?.let { append(it.uppercaseChar()) }
                        apellido?.firstOrNull()?.let { append(it.uppercaseChar()) }
                    }.ifEmpty { "?" }
                    tvAvatar.text = iniciales
                }
        }

        tvAvatar.setOnClickListener { view ->
            val popup = PopupMenu(this, view, Gravity.END)
            popup.menu.add(0, 1, 0, "👤  Ver mi perfil")
            popup.menu.add(0, 2, 1, "🚪  Cerrar sesión")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        cargarFragment(PerfilFragment(), "Mi Perfil")
                        // No cambiar el bottom nav seleccionado
                    }
                    2 -> logout()
                }
                true
            }
            popup.show()
        }
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard   -> { cargarFragment(DashboardFragment(),          "Inicio");     true }
                R.id.nav_pendientes  -> { cargarFragment(PendientesTabsFragment(),     "Pendientes"); true }
                R.id.nav_inventario  -> { cargarFragment(InventarioFragment.newInstance(), "Mi Inventario"); true }
                R.id.nav_completadas -> { cargarFragment(CompletadasFragment(),        "Historial");  true }
                R.id.nav_planta_externa -> { cargarFragment(PlantaExternaFragment.newInstance(), "Proyectos"); true }                    else -> false
            }
        }
    }

    private fun observarSesion() {
        lifecycleScope.launch {
            SessionEvents.unauthorized.collect {
                Toast.makeText(
                    this@MainActivity,
                    "Tu sesión expiró, vuelve a iniciar sesión",
                    Toast.LENGTH_LONG
                ).show()
                vm.logout()
                startActivity(Intent(this@MainActivity, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }
        }
    }

    fun cargarFragment(fragment: androidx.fragment.app.Fragment, titulo: String) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.contenedor, fragment)
            .commit()
        supportActionBar?.title = titulo
    }

    private fun logout() {
        vm.logout()
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

}

