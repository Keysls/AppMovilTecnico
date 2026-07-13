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
import com.enetfiber.tecnico.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val retiroDao:     RetiroPendienteDao,
    private val configTipoOrdenDao: com.enetfiber.tecnico.data.local.ConfigTipoOrdenDao,

    ) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val tag = "SyncWorker"
        return try {
            var huboFallo = false

            // ── PASO 0a: sincronizar tipos de orden ───────────────────
            runCatching {
                val res = api.getTiposOrden()
                if (res.isSuccessful) {
                    val tipos = (res.body()?.tipos ?: emptyList()).map {
                        com.enetfiber.tecnico.data.local.ConfigTipoOrdenEntity(
                            codigo         = it.codigo,
                            label          = it.label,
                            servicio       = it.servicio,
                            flujo          = it.flujo,
                            requiereWan    = it.requiereWan,
                            autorizaOlt    = it.autorizaOlt,
                            esRetiro       = it.esRetiro,
                            esBaja         = it.esBaja,
                            esInstalacion  = it.esInstalacion,
                            esCorte        = it.esCorte,
                            esCambioEquipo = it.esCambioEquipo,
                            activo         = it.activo,
                            orden          = it.orden,
                        )
                    }
                    configTipoOrdenDao.clearAll()
                    configTipoOrdenDao.insertAll(tipos)
                    android.util.Log.d(tag, "✓ ${tipos.size} tipos de orden actualizados")
                }
            }.onFailure { android.util.Log.w(tag, "tipos-orden sync falló (no crítico): ${it.message}") }

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
/*
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

         */

                // Fotos
                for (foto in fotoDao.getTodasPendientes().filter { f -> f.instalacionId == instId }) {
                    val original = File(foto.rutaLocal)
                    if (!original.exists() || original.length() == 0L) {
                        fotoDao.marcarSincronizado(foto.id); continue
                    }
                    val file = if (original.extension.equals("webp", ignoreCase = true)) {
                        original // ya viene comprimida (falló la subida en Repository.subirFoto)
                    } else {
                        withContext(Dispatchers.IO) { ImageUtils.comprimirAWebP(original) }
                    }
                    val part = MultipartBody.Part.createFormData(
                        "fotos", file.name, file.asRequestBody("image/webp".toMediaType())
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

            // ── PASO 4: consumos offline ──────────────────────────────
            val consumosPendientes = consumoDao.getPendientes()
            android.util.Log.d(tag, "${consumosPendientes.size} consumo(s) pendiente(s)")

            if (consumosPendientes.isNotEmpty()) {
                val itemsConsumo = consumosPendientes.map { c ->
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
                    for (c in consumosPendientes) consumoDao.marcarSincronizado(c.id)
                    android.util.Log.d(tag, "  ✓ ${consumosPendientes.size} consumo(s) sincronizados")
                } else {
                    huboFallo = true
                    android.util.Log.e(tag, "  ✗ consumos fallaron — ${res?.code()}")
                }
            }

// ── PASO 5: retiros offline ───────────────────────────────
            val retirosPendientes = retiroDao.getPendientes()
            android.util.Log.d(tag, "${retirosPendientes.size} retiro(s) pendiente(s)")

            if (retirosPendientes.isNotEmpty()) {
                val porOrden = retirosPendientes.groupBy { it.ordenId }
                for ((ordenId, retiros) in porOrden) {
                    val itemsRetiro = retiros.map { r ->
                        RetiroItemRequest(
                            productoId = r.productoId,
                            tipoEquipo = r.tipoEquipo,
                            codigoPon  = r.codigoPon,
                        )
                    }
                    val res = runCatching {
                        api.registrarRetiro(
                            RegistrarRetiroRequest(
                                items   = itemsRetiro,
                                ordenId = ordenId
                            )
                        )
                    }.getOrNull()
                    if (res?.isSuccessful == true) {
                        for (r in retiros) retiroDao.marcarSincronizado(r.id)
                        android.util.Log.d(tag, "  ✓ ${retiros.size} retiro(s) sincronizados — orden: $ordenId")
                    } else {
                        huboFallo = true
                        android.util.Log.e(tag, "  ✗ retiros fallaron — orden: $ordenId — ${res?.code()}")
                    }
                }
            }

// ── Refrescar inventario si todo fue bien ─────────────────
            if (!huboFallo) {
                runCatching { api.getMiInventario() }.getOrNull()?.body()?.let { body ->
                    inventarioDao.clearItems()
                    inventarioDao.insertItems(body.items.map { item -> item.toEntity() })
                    inventarioDao.clearOnus()
                    inventarioDao.insertOnus(body.onus.map { onu -> onu.toEntity() })
                    android.util.Log.d(tag, "  ✓ inventario actualizado")
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