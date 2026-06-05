package com.enetfiber.tecnico.data

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.enetfiber.tecnico.data.local.*
import com.enetfiber.tecnico.data.remote.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val api:           ApiService,
    private val configDao:     ConfigOfflineDao,
    private val fotoDao:       FotoPendienteDao,
    private val completarDao:  CompletarPendienteDao,
    private val iniciarDao:    IniciarPendienteDao,
    private val aceptarDao:    AceptarPendienteDao,
    private val consumoDao:    ConsumoPendienteDao,
    private val inventarioDao: InventarioDao,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val tag = "SyncWorker"
        return try {
            var huboFallo = false

            // ── PASO -1: aceptaciones offline ─────────────────────────
            val aceptacionesPendientes = aceptarDao.getPendientes()
            android.util.Log.d(tag, "${aceptacionesPendientes.size} aceptación(es) pendiente(s)")
            for (acep in aceptacionesPendientes) {
                runCatching {
                    api.aceptarOrden(acep.ordenId, AceptarRequest(acep.creadoEn.aIso8601()))
                }
                aceptarDao.marcarSincronizado(acep.ordenId)
                android.util.Log.d(tag, "  ✓ aceptación: ${acep.ordenId}")
            }

            // ── PASO 0: inicios offline ───────────────────────────────
            val iniciosPendientes = iniciarDao.getPendientes()
            android.util.Log.d(tag, "${iniciosPendientes.size} inicio(s) pendiente(s)")
            val iniciosFallidos = mutableSetOf<String>()
            for (ini in iniciosPendientes) {
                val res = runCatching {
                    api.iniciarInstalacion(
                        ini.ordenId,
                        IniciarInstalacionRequest(
                            ini.latitud, ini.longitud, ini.direccionGps, ini.instalacionId
                        )
                    )
                }.getOrNull()
                if (res?.isSuccessful == true) {
                    iniciarDao.marcarSincronizado(ini.instalacionId)
                    android.util.Log.d(tag, "  ✓ inicio: ${ini.instalacionId}")
                } else {
                    iniciosFallidos.add(ini.instalacionId)
                    huboFallo = true
                    android.util.Log.e(tag, "  ✗ inicio falló — ${res?.code()}")
                }
            }

            // ── PASO 1-3: instalaciones ───────────────────────────────
            val pendientes = completarDao.getPendientes()
            android.util.Log.d(tag, "${pendientes.size} instalación(es) a completar")

            for (item in pendientes) {
                val instId = item.instalacionId
                if (instId in iniciosFallidos) { huboFallo = true; continue }

                var instalacionOk = true

                // Config ONU
                for (cfg in configDao.getTodasPendientes().filter { c -> c.instalacionId == instId }) {
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
                    if (res?.isSuccessful == true) configDao.marcarSincronizado(instId)
                    else { instalacionOk = false; huboFallo = true }
                }

                // Fotos
                for (foto in fotoDao.getTodasPendientes().filter { f -> f.instalacionId == instId }) {
                    val file = File(foto.rutaLocal)
                    if (!file.exists() || file.length() == 0L) {
                        fotoDao.marcarSincronizado(foto.id); continue
                    }
                    val part = MultipartBody.Part.createFormData(
                        "fotos", file.name, file.asRequestBody("image/jpeg".toMediaType())
                    )
                    val tipoPart = foto.tipo.toRequestBody("text/plain".toMediaType())
                    val res = runCatching { api.subirFoto(instId, part, tipoPart) }.getOrNull()
                    if (res?.isSuccessful == true) fotoDao.marcarSincronizado(foto.id)
                    else { instalacionOk = false; huboFallo = true }
                }

                // Completar
                if (instalacionOk) {
                    val res = runCatching {
                        api.completarInstalacion(
                            instId,
                            CompletarRequest(item.observaciones, item.fechaFin.aIso8601())
                        )
                    }.getOrNull()
                    if (res?.isSuccessful == true) completarDao.marcarSincronizado(instId)
                    else huboFallo = true
                } else {
                    huboFallo = true
                }
            }

            // ── PASO 4: consumos y retiros offline ────────────────────
            val todosPendientes = consumoDao.getPendientes()
            android.util.Log.d(tag, "${todosPendientes.size} consumo(s)/retiro(s) pendiente(s)")

            if (todosPendientes.isNotEmpty()) {
                val consumosNormales = todosPendientes.filter { c -> c.motivo != "RETIRO" }
                val retirosLista     = todosPendientes.filter { r -> r.motivo == "RETIRO" }

                // Enviar consumos normales
                if (consumosNormales.isNotEmpty()) {
                    val itemsConsumo = consumosNormales.map { c ->
                        ConsumoItemRequest(productoId = c.productoId, cantidad = c.cantidad)
                    }
                    val res = runCatching {
                        api.registrarConsumo(
                            RegistrarConsumoRequest(
                                items       = itemsConsumo,
                                motivo      = "SERVICIO",
                                descripcion = "Sincronización offline"
                            )
                        )
                    }.getOrNull()
                    if (res?.isSuccessful == true) {
                        for (c in consumosNormales) consumoDao.marcarSincronizado(c.id)
                        android.util.Log.d(tag, "  ✓ ${consumosNormales.size} consumo(s) sincronizados")
                    } else {
                        huboFallo = true
                        android.util.Log.e(tag, "  ✗ consumos fallaron — ${res?.code()}")
                    }
                }

                // Enviar retiros
                if (retirosLista.isNotEmpty()) {
                    val itemsRetiro = retirosLista.map { r ->
                        RetiroItemRequest(productoId = r.productoId, cantidad = -r.cantidad)
                    }
                    val res = runCatching {
                        api.registrarRetiro(
                            RegistrarRetiroRequest(
                                items       = itemsRetiro,
                                descripcion = "Retiro sincronizado offline"
                            )
                        )
                    }.getOrNull()
                    if (res?.isSuccessful == true) {
                        for (r in retirosLista) consumoDao.marcarSincronizado(r.id)
                        android.util.Log.d(tag, "  ✓ ${retirosLista.size} retiro(s) sincronizados")
                    } else {
                        huboFallo = true
                        android.util.Log.e(tag, "  ✗ retiros fallaron — ${res?.code()}")
                    }
                }

                // Refrescar inventario desde servidor si todo fue bien
                if (!huboFallo) {
                    runCatching { api.getMiInventario() }.getOrNull()?.body()?.let { body ->
                        inventarioDao.clearItems()
                        inventarioDao.insertItems(body.items.map { item -> item.toEntity() })
                        inventarioDao.clearOnus()
                        inventarioDao.insertOnus(body.onus.map { onu -> onu.toEntity() })
                        android.util.Log.d(tag, "  ✓ inventario actualizado")
                    }
                }
            }

            if (huboFallo) Result.retry() else Result.success()

        } catch (e: Exception) {
            android.util.Log.e("SyncWorker", "Excepción: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        const val NOMBRE_UNICO     = "sync_instalaciones"
        const val NOMBRE_PERIODICO = "sync_instalaciones_periodico"
    }
}