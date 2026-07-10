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
import androidx.work.*
import com.enetfiber.tecnico.R
import com.enetfiber.tecnico.data.remote.ApiService
import com.enetfiber.tecnico.data.remote.UbicacionTecnicoRequest
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import com.google.android.gms.location.*

/**
 * Servicio en primer plano que reporta la ubicación del técnico.
 * Solo activo dentro de la jornada laboral:
 *   - 8:00 am – 1:00 pm
 *   - 3:00 pm – 6:00 pm
 * No opera los domingos. Fuera de ese rango el servicio se detiene por
 * completo (sin notificación, sin GPS). Se reactiva automáticamente
 * cada mañana vía WorkManager (excepto domingo).
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
            Lifecycle.Event.ON_START -> { appEnPrimerPlano = true; reiniciarActualizaciones() }
            Lifecycle.Event.ON_STOP  -> { appEnPrimerPlano = false; reiniciarActualizaciones() }
            else -> {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // SIEMPRE se debe llamar startForeground() primero — es obligatorio en
        // Android cuando se inicia con startForegroundService(), incluso si
        // luego decidimos detener el servicio de inmediato.
        startForeground(NOTIF_ID, crearNotificacion())

        if (!estaDentroDeJornada()) {
            programarReinicioManana()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        iniciarActualizaciones()
        programarReinicioManana()
        return START_STICKY
    }

    private fun tienePermisoUbicacion(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun iniciarActualizaciones() {
        if (!tienePermisoUbicacion()) { stopSelf(); return }

        val intervaloMs = if (appEnPrimerPlano) INTERVALO_PRIMER_PLANO_MS else INTERVALO_SEGUNDO_PLANO_MS

        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, intervaloMs)
            .setMinUpdateIntervalMillis(intervaloMs / 2)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return

                // Revisar en cada ciclo si ya terminó la jornada — si es así,
                // detener el servicio por completo (oculta la notificación).
                if (!estaDentroDeJornada()) {
                    detenerPorFinDeJornada()
                    return
                }

                if (!estaEnHorarioLaboral()) return // hueco de almuerzo: pausa sin detener
                enviarUbicacion(loc.latitude, loc.longitude)
            }
        }

        try {
            fusedClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private fun reiniciarActualizaciones() {
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        if (estaDentroDeJornada()) iniciarActualizaciones()
    }

    private fun enviarUbicacion(lat: Double, lng: Double) {
        scope.launch {
            try {
                api.reportarUbicacion(UbicacionTecnicoRequest(lat = lat, lng = lng))
            } catch (_: Exception) { /* falla silenciosa */ }
        }
    }

    // ── Horarios ──────────────────────────────────────────────
    private fun minutosDelDia(): Int {
        val cal = java.util.Calendar.getInstance()
        return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
    }

    private fun esDomingo(): Boolean {
        val cal = java.util.Calendar.getInstance()
        return cal.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.SUNDAY
    }

    /** true si está dentro del día laboral completo (8:00am – 6:00pm), incluyendo el almuerzo. Falso siempre en domingo. */
    private fun estaDentroDeJornada(): Boolean {
        if (esDomingo()) return false
        val m = minutosDelDia()
        return m in (8 * 60 + 10)..(18 * 60)
    }

    private fun estaEnHorarioLaboral(): Boolean {
        if (esDomingo()) return false
        val m = minutosDelDia()
        val turnoManana = m in (8 * 60 + 10)..(13 * 60)   // 8:10 am – 1:00 pm
        val turnoTarde  = m in (15 * 60)..(18 * 60)        // 3:00 pm – 6:00 pm

        return turnoManana || turnoTarde
    }

    private fun detenerPorFinDeJornada() {
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        stopForeground(STOP_FOREGROUND_REMOVE) // quita la notificación
        stopSelf()
    }

    // ── Reinicio automático al día siguiente ─────────────────
    private fun programarReinicioManana() {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 8)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            if (before(java.util.Calendar.getInstance())) {
                add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
            // Si cae domingo, saltar directo al lunes
            if (get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.SUNDAY) {
                add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
        }
        val delay = cal.timeInMillis - System.currentTimeMillis()
        val work = OneTimeWorkRequestBuilder<ReinicioUbicacionWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork("reinicio_ubicacion", ExistingWorkPolicy.REPLACE, work)
    }

    private fun crearNotificacion(): Notification {
        val channelId = "ubicacion_tecnico"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                channelId, "Ubicación en tiempo real", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Se usa para que el NOC pueda ubicarte mientras trabajas" }
            manager.createNotificationChannel(channel)
        }

        val enHorario = estaEnHorarioLaboral()
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(if (enHorario) "Compartiendo tu ubicación" else "Ubicación en pausa")
            .setContentText(if (enHorario) "Enet Fiber Técnico está activo" else "Fuera de horario — retoma pronto")
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
        private const val INTERVALO_PRIMER_PLANO_MS  = 30_000L
        private const val INTERVALO_SEGUNDO_PLANO_MS = 180_000L

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
            WorkManager.getInstance(context).cancelUniqueWork("reinicio_ubicacion")
        }
    }
}

/**
 * Worker que reinicia el servicio de ubicación cada mañana a las 8:00 am,
 * sin necesidad de que el técnico abra la app manualmente.
 */
class ReinicioUbicacionWorker(
    context: android.content.Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        UbicacionTecnicoService.iniciar(applicationContext)
        return Result.success()
    }
}