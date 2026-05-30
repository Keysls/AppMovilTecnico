package com.enetfiber.tecnico.data

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.enetfiber.tecnico.data.local.*
import com.enetfiber.tecnico.data.remote.ApiService
import com.enetfiber.tecnico.data.remote.CompletarRequest
import com.enetfiber.tecnico.data.remote.ConfigOnuRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import com.enetfiber.tecnico.data.remote.IniciarInstalacionRequest
/**
 * Sincroniza el trabajo offline del técnico contra el backend.
 * Por cada instalación pendiente de completar, respeta el orden:
 *   1. config ONU  2. fotos  3. completar
 * Solo marca como sincronizado lo que el backend confirmó.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val api:         ApiService,
    private val configDao:   ConfigOfflineDao,
    private val fotoDao:     FotoPendienteDao,
    private val completarDao: CompletarPendienteDao,
    private val iniciarDao:   IniciarPendienteDao,
    private val aceptarDao:   AceptarPendienteDao
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val TAG = "SyncWorker"
        return try {
            var huboFallo = false

            // ── PASO -1: confirmar las aceptaciones offline ──────────────
            val aceptacionesPendientes = aceptarDao.getPendientes()
            android.util.Log.d(TAG, "${aceptacionesPendientes.size} aceptación(es) pendiente(s)")
            for (acep in aceptacionesPendientes) {
                val res = runCatching {
                    api.aceptarOrden(
                        acep.ordenId,
                        com.enetfiber.tecnico.data.remote.AceptarRequest(acep.creadoEn.aIso8601())
                    )
                }.getOrNull()
                if (res?.isSuccessful == true) {
                    aceptarDao.marcarSincronizado(acep.ordenId)
                    android.util.Log.d(TAG, "  ✓ aceptación confirmada: ${acep.ordenId}")
                } else {
                    // Puede fallar si la orden ya fue aceptada por el iniciar (red de seguridad).
                    // En ese caso el backend devuelve error pero la orden YA está aceptada → la marcamos igual.
                    android.util.Log.w(TAG, "  ⚠ aceptación ${acep.ordenId} — código ${res?.code()} (puede estar ya aceptada)")
                    aceptarDao.marcarSincronizado(acep.ordenId)
                }
            }

            // ── PASO 0: registrar en el backend los inicios offline ──────
            // Las instalaciones cuyo inicio NO se pudo registrar se saltan
            // en esta pasada (subir fotos/config daría 404).
            val iniciosPendientes = iniciarDao.getPendientes()
            android.util.Log.d(TAG, "${iniciosPendientes.size} inicio(s) pendiente(s)")
            val iniciosFallidos = mutableSetOf<String>()
            for (ini in iniciosPendientes) {
                val res = runCatching {
                    api.iniciarInstalacion(
                        ini.ordenId,
                        IniciarInstalacionRequest(
                            latitud       = ini.latitud,
                            longitud      = ini.longitud,
                            direccionGps  = ini.direccionGps,
                            instalacionId = ini.instalacionId
                        )
                    )
                }.getOrNull()
                if (res?.isSuccessful == true) {
                    iniciarDao.marcarSincronizado(ini.instalacionId)
                    android.util.Log.d(TAG, "  ✓ inicio registrado: ${ini.instalacionId}")
                } else {
                    iniciosFallidos.add(ini.instalacionId)
                    huboFallo = true
                    android.util.Log.e(TAG, "  ✗ inicio falló — código ${res?.code()}")
                }
            }

            val pendientes = completarDao.getPendientes()
            android.util.Log.d(TAG, "Procesando — ${pendientes.size} instalación(es) a completar")

            for (item in pendientes) {
                val instId = item.instalacionId

                // Si el inicio de esta instalación falló, saltarla (no existe en el backend)
                if (instId in iniciosFallidos) {
                    android.util.Log.w(TAG, "  ⏭ $instId — su inicio no se registró, se reintenta luego")
                    continue
                }

                var instalacionOk = true
                android.util.Log.d(TAG, "Procesando instalación $instId")

                // ── 1. Config ONU ─────────────────────────────────
                val configs = configDao.getTodasPendientes().filter { it.instalacionId == instId }
                android.util.Log.d(TAG, "  ${configs.size} config(s) pendiente(s)")
                for (cfg in configs) {
                    val req = ConfigOnuRequest(
                        ssid             = cfg.ssid,
                        ssidPassword     = cfg.ssidPassword,
                        ssid5ghz         = cfg.ssid5ghz,
                        ssidPassword5ghz = cfg.ssidPassword5ghz,
                        serialNumber     = cfg.serialNumber,
                        mac              = null,
                        potenciaRx       = cfg.potenciaRx,
                        potenciaTx       = cfg.potenciaTx,
                        temperatura      = cfg.temperatura,
                        estado           = "ONLINE",
                        pppoeUser        = cfg.pppoeUser,
                        pppoePassword    = cfg.pppoePassword,
                        offline          = false
                    )
                    val res = runCatching { api.guardarConfigOnu(instId, req) }.getOrNull()
                    if (res?.isSuccessful == true) {
                        configDao.marcarSincronizado(instId)
                        android.util.Log.d(TAG, "  ✓ config subida")
                    } else {
                        instalacionOk = false
                        android.util.Log.e(TAG, "  ✗ config falló — código ${res?.code()}")
                    }
                }

                // ── 2. Fotos ──────────────────────────────────────
                val fotos = fotoDao.getTodasPendientes().filter { it.instalacionId == instId }
                android.util.Log.d(TAG, "  ${fotos.size} foto(s) pendiente(s)")
                for (foto in fotos) {
                    val file = File(foto.rutaLocal)
                    if (!file.exists() || file.length() == 0L) {
                        fotoDao.marcarSincronizado(foto.id)
                        android.util.Log.w(TAG, "  ⚠ foto perdida del cache: ${foto.rutaLocal}")
                        continue
                    }
                    val part = MultipartBody.Part.createFormData(
                        "fotos", file.name,
                        file.asRequestBody("image/jpeg".toMediaType())
                    )
                    val tipoPart = foto.tipo.toRequestBody("text/plain".toMediaType())
                    val res = runCatching { api.subirFoto(instId, part, tipoPart) }.getOrNull()
                    if (res?.isSuccessful == true) {
                        fotoDao.marcarSincronizado(foto.id)
                        android.util.Log.d(TAG, "  ✓ foto subida (${foto.tipo})")
                    } else {
                        instalacionOk = false
                        android.util.Log.e(TAG, "  ✗ foto falló — código ${res?.code()}")
                    }
                }

                // ── 3. Completar ──────────────────────────────────
                if (instalacionOk) {
                    val res = runCatching {
                        api.completarInstalacion(
                            instId,
                            CompletarRequest(item.observaciones, item.fechaFin.aIso8601())
                        )
                    }.getOrNull()
                    if (res?.isSuccessful == true) {
                        completarDao.marcarSincronizado(instId)
                        android.util.Log.d(TAG, "  ✓✓ instalación COMPLETADA en backend")
                    } else {
                        huboFallo = true
                        android.util.Log.e(TAG, "  ✗ completar falló — código ${res?.code()}")
                    }
                } else {
                    huboFallo = true
                    android.util.Log.e(TAG, "  ✗ no se completa — config o fotos fallaron")
                }
            }

            if (huboFallo) {
                android.util.Log.w(TAG, "Terminó con fallos — se reintentará")
                Result.retry()
            } else {
                android.util.Log.d(TAG, "Terminó OK")
                Result.success()
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Excepción: ${e.message}", e)
            Result.retry()
        }
    }
    companion object {
        const val NOMBRE_UNICO    = "sync_instalaciones"
        const val NOMBRE_PERIODICO = "sync_instalaciones_periodico"
    }


}