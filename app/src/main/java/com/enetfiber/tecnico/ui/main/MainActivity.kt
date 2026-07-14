package com.enetfiber.tecnico.ui.main

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
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
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.enetfiber.tecnico.ui.ubicacion.UbicacionTecnicoService


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

    private val permisoUbicacionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permisos ->
        val concedido = permisos[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permisos[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (concedido) {
            // En Android 10+ (API 29+), el permiso de segundo plano se pide
            // en un paso SEPARADO — el sistema no permite pedirlo junto al
            // de primer plano (lo ignoraría silenciosamente).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                pedirPermisoSegundoPlano()
            } else {
                iniciarServicioUbicacion()
            }
        }
        // Si no concedió, simplemente no se inicia el tracking — no bloqueamos
        // el uso normal de la app por esto.
    }

    private val permisoSegundoPlanoLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Concedido o no, igual iniciamos el servicio — funcionará al menos
        // mientras la app esté en primer plano si rechazó el de background.
        iniciarServicioUbicacion()
    }

    private val permisoNotificacionesLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op — solo para que la notificación del servicio sea visible */ }

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

        pedirPermisosYArrancarUbicacion()
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
            mostrarMenuAvatar(view)
        }
    }

    private fun mostrarMenuAvatar(anchor: android.view.View) {
        val popupView = LayoutInflater.from(this)
            .inflate(R.layout.popup_menu_avatar, null)

        val popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 8f
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        popupView.findViewById<LinearLayout>(R.id.itemVerPerfil).setOnClickListener { itemView ->
            itemView.postDelayed({
                popupWindow.dismiss()
                cargarFragment(PerfilFragment(), "Mi Perfil")
            }, 150)
        }

        popupView.findViewById<LinearLayout>(R.id.itemCerrarSesion).setOnClickListener { itemView ->
            itemView.postDelayed({
                popupWindow.dismiss()
                logout()
            }, 150)
        }

        popupWindow.showAsDropDown(anchor, -160, 8)
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
                UbicacionTecnicoService.detener(this@MainActivity)
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
        UbicacionTecnicoService.detener(this)
        vm.logout()
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun tienePermisoUbicacion(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun tienePermisoSegundoPlano(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun pedirPermisoSegundoPlano() {
        permisoSegundoPlanoLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    private fun pedirPermisosYArrancarUbicacion() {
        // Notificaciones (Android 13+) — para que se vea la notificación del servicio
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permisoNotificacionesLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        when {
            tienePermisoUbicacion() && tienePermisoSegundoPlano() -> iniciarServicioUbicacion()
            tienePermisoUbicacion() -> pedirPermisoSegundoPlano()
            else -> permisoUbicacionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    private fun iniciarServicioUbicacion() {
        UbicacionTecnicoService.iniciar(this)
    }


    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

}

