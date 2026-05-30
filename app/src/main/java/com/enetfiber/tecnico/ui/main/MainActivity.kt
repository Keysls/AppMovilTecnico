package com.enetfiber.tecnico.ui.main

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import com.enetfiber.tecnico.R
import com.enetfiber.tecnico.databinding.ActivityMainBinding
import com.enetfiber.tecnico.ui.DashboardViewModel
import com.enetfiber.tecnico.ui.login.LoginActivity
import com.google.android.material.navigation.NavigationView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import com.enetfiber.tecnico.data.local.SessionEvents
import android.widget.Toast
@Suppress("DEPRECATION")
@AndroidEntryPoint
class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var toggle: ActionBarDrawerToggle
    val vm: DashboardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        toggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar,
            R.string.nav_abrir, R.string.nav_cerrar
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        toggle.drawerArrowDrawable.color = getColor(android.R.color.white)
        binding.navView.setNavigationItemSelectedListener(this)

        actualizarHeader()

        // C3 FIX: si el backend devuelve 401 en cualquier momento, cerrar sesión
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

        if (savedInstanceState == null) {
            cargarFragment(DashboardFragment(), "Dashboard")
            binding.navView.setCheckedItem(R.id.nav_dashboard)
        }
    }

    private fun iniciales(nombre: String?, apellido: String?) = buildString {
        nombre?.firstOrNull()?.let { append(it.uppercaseChar()) }
        apellido?.firstOrNull()?.let { append(it.uppercaseChar()) }
    }

    @SuppressLint("SetTextI18n")
    private fun actualizarHeader() {
        val header      = binding.navView.getHeaderView(0)
        val tvNombre    = header.findViewById<TextView>(R.id.tvNavNombre)
        val tvZona      = header.findViewById<TextView>(R.id.tvNavZona)
        val tvIniciales = header.findViewById<TextView>(R.id.tvNavIniciales)

        // G4: un solo collector combinado para nombre + apellido
        lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(vm.nombre, vm.apellido) { nombre, apellido ->
                nombre to apellido
            }.collect { (nombre, apellido) ->
                tvNombre.text    = "${nombre ?: ""} ${apellido ?: ""}".trim()
                tvIniciales.text = iniciales(nombre, apellido)
            }
        }
        lifecycleScope.launch {
            vm.zona.collect { zona ->
                tvZona.text = zona?.ifEmpty { "Sin zona asignada" } ?: "Sin zona asignada"
            }
        }
    }

    fun cargarFragment(fragment: androidx.fragment.app.Fragment, titulo: String) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.contenedor, fragment)
            .commit()
        supportActionBar?.title = titulo
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_dashboard   -> cargarFragment(DashboardFragment(),   "Dashboard")
            R.id.nav_pendientes  -> {
                cargarFragment(PendientesFragment.newInstance("internet"), "Pendientes Internet")
            }
            R.id.nav_completadas -> cargarFragment(CompletadasFragment(), "Completadas")
            R.id.nav_cable -> {
                cargarFragment(PendientesFragment.newInstance("cable"), "Pendientes Cable")
            }
            R.id.nav_perfil      -> cargarFragment(PerfilFragment(),       "Mi Perfil")
            R.id.nav_salir       -> logout()
        }
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
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
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START))
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        else
            super.onBackPressed()
    }
}