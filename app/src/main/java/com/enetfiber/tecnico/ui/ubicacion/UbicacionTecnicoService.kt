package com.enetfiber.tecnico.ui.ubicacion

import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.enetfiber.tecnico.R
import com.enetfiber.tecnico.data.remote.ApiService
import com.enetfiber.tecnico.data.remote.UbicacionRequest
import com.google.android.gms.location.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Servicio en primer plano que reporta la ubicación del técnico mientras
 * tiene sesión iniciada — tanto con la app abierta (30s) como minimizada (3min).
 *
 * Se inicia en MainActivity al abrir sesión, y se detiene en el logout().
 */
@AndroidEntryPoint
class UbicacionTecnicoService : Service() {

    @Inject lateinit var api: ApiService

    private lateinit var fusedClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var appEnPrimerPlano = true

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> {
                appEnPrimerPlano = true
                reiniciarActualizaciones()
            }
            Lifecycle.Event.ON_STOP -> {
                appEnPrimerPlano = false
                reiniciarActualizaciones()
            }
            else -> {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, crearNotificacion())
        iniciarActualizaciones()
        return START_STICKY
    }

    private fun tienePermisoUbicacion(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun iniciarActualizaciones() {
        if (!tienePermisoUbicacion()) {
            stopSelf()
            return
        }

        val intervaloMs = if (appEnPrimerPlano) INTERVALO_PRIMER_PLANO_MS else INTERVALO_SEGUNDO_PLANO_MS

        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, intervaloMs)
            .setMinUpdateIntervalMillis(intervaloMs / 2)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                enviarUbicacion(loc.latitude, loc.longitude)
            }
        }

        try {
            fusedClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
        } catch (e: SecurityException) {
            // El usuario revocó el permiso justo en este instante (carrera rara,
            // pero posible) — detenemos el servicio en vez de crashear.
            stopSelf()
        }
    }

    private fun reiniciarActualizaciones() {
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        iniciarActualizaciones()
    }

    private fun enviarUbicacion(lat: Double, lng: Double) {
        scope.launch {
            try {
                api.reportarUbicacion(UbicacionRequest(lat = lat, lng = lng))
            } catch (_: Exception) {
                // Falla silenciosa — se reintentará en el próximo ciclo.
                // No es crítico perder un reporte puntual de ubicación.
            }
        }
    }

    private fun crearNotificacion(): Notification {
        val channelId = "ubicacion_tecnico"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                channelId, "Ubicación en tiempo real", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Se usa para que el NOC pueda ubicarte mientras trabajas"
            }
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Compartiendo tu ubicación")
            .setContentText("Enet Fiber Técnico está activo")
            .setSmallIcon(R.drawable.icon_logo_e)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
    }

    companion object {
        private const val NOTIF_ID = 5001
        private const val INTERVALO_PRIMER_PLANO_MS  = 30_000L   // 30 segundos
        private const val INTERVALO_SEGUNDO_PLANO_MS = 180_000L  // 3 minutos

        fun iniciar(context: android.content.Context) {
            val intent = Intent(context, UbicacionTecnicoService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun detener(context: android.content.Context) {
            context.stopService(Intent(context, UbicacionTecnicoService::class.java))
        }
    }
}