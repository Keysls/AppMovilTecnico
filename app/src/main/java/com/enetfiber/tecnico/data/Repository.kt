package com.enetfiber.tecnico.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.enetfiber.tecnico.TipoOrden
import com.enetfiber.tecnico.data.local.*
import com.enetfiber.tecnico.data.remote.*
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import androidx.work.*
import com.enetfiber.tecnico.data.local.CompletarPendienteDao
import com.enetfiber.tecnico.data.local.CompletarPendienteEntity
import java.util.concurrent.TimeUnit
import java.util.UUID
sealed class Resultado<out T> {
    data class Exito<T>(val data: T) : Resultado<T>()
    data class Error(val mensaje: String) : Resultado<Nothing>()
}

// Convierte un timestamp (millis) a ISO-8601 UTC, como espera el backend
fun Long.aIso8601(): String {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return sdf.format(java.util.Date(this))
}

// Mensajes de resultado que la UI interpreta — centralizados para no desincronizar
object MsgResultado {
    const val GUARDADO_OFFLINE  = "Guardado offline"
    const val PENDIENTE_OFFLINE = "PENDIENTE_OFFLINE"
}

@Singleton
class Repository @Inject constructor(
    private val api:       ApiService,
    private val ordenDao:  OrdenDao,
    private val configDao: ConfigOfflineDao,
    private val fotoDao:   FotoPendienteDao,
    private val completarDao: CompletarPendienteDao,
    private val iniciarDao:   IniciarPendienteDao,
    private val aceptarDao:   AceptarPendienteDao,
    private val session:   SessionDataStore,
    @ApplicationContext private val ctx: Context
) {

    fun isOnline(): Boolean {
        val cm  = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cap = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    // ── Auth ──────────────────────────────────────────────────
    suspend fun login(email: String, password: String): Resultado<LoginResponse> {
        return try {
            val res = api.login(LoginRequest(email, password))
            if (res.isSuccessful) {
                val body = res.body()!!

                // I1 FIX: solo los TÉCNICOS pueden usar esta app
                if (body.usuario.rol != "TECNICO") {
                    return Resultado.Error("Esta aplicación es solo para técnicos")
                }

                // I2 FIX: un TECNICO sin registro de técnico no puede operar
                val tecnico = body.usuario.tecnico
                    ?: return Resultado.Error("Tu usuario no tiene perfil de técnico asignado. Contactá al administrador.")

                // Validar que el usuario esté activo
                if (body.usuario.activo == false) {
                    return Resultado.Error("Tu cuenta está desactivada. Contactá al administrador.")
                }

                session.guardarSesion(
                    token     = body.token,
                    nombre    = body.usuario.nombre,
                    apellido  = body.usuario.apellido,
                    email     = body.usuario.email,
                    tecnicoId = tecnico.id,
                    zona      = tecnico.zonaAsignada,
                    telefono       = body.usuario.telefono,
                    dni            = tecnico.dni,
                    vehiculo       = tecnico.vehiculo,
                    activo         = body.usuario.activo ?: true,
                    ordenesActivas = tecnico._count?.ordenes ?: 0
                )
                Resultado.Exito(body)
            } else if (res.code() == 401) {
                Resultado.Error("Credenciales incorrectas")
            } else {
                Resultado.Error("Error al iniciar sesión")
            }
        } catch (e: Exception) {
            Resultado.Error("Sin conexión al servidor")
        }
    }

    suspend fun logout() {
        try { api.logout() } catch (_: Exception) {}
        session.cerrarSesion()
    }

    // ── Órdenes ───────────────────────────────────────────────
    fun getPendientesInternet() = ordenDao.getPendientesInternet(TipoOrden.INTERNET)
    fun getPendientesCable()    = ordenDao.getPendientesCable(TipoOrden.CABLE)
    fun getCompletadas()        = ordenDao.getCompletadas()

    suspend fun sincronizarOrdenes(): Resultado<Unit> {
        return try {
            val res = api.getOrdenes(limit = 100)
            if (res.isSuccessful) {
                val lista = res.body()?.data ?: emptyList()
                val idsRemotos = lista.map { it.id }

                // instalacionIds con trabajo offline sin sincronizar
                val idsProtegidos = (
                        configDao.instalacionIdsPendientes() +
                                fotoDao.instalacionIdsPendientes() +
                                completarDao.getPendientes().map { it.instalacionId }
                        ).distinct()

                // FIX: ordenIds con completar pendiente → NO pisar su estado local.
                // Mantienen su COMPLETADA local hasta que el SyncWorker confirme
                // la instalación en el backend.
                val ordenIdsProtegidos = ordenDao.ordenIdsConCompletarPendiente()
                val listaParaInsertar = lista.filter { it.id !in ordenIdsProtegidos }

                ordenDao.insertAll(listaParaInsertar.map { it.toEntity() })
                ordenDao.deleteObsoletas(idsRemotos, idsProtegidos)

                Resultado.Exito(Unit)
            } else Resultado.Error("Error al obtener órdenes")
        } catch (e: Exception) {
            Resultado.Error("Sin internet — mostrando datos locales")
        }
    }

    suspend fun countPorCategoria(): Triple<Int, Int, Int> {
        return Triple(
            ordenDao.countPendientesInternet(TipoOrden.INTERNET),
            ordenDao.countPendientesCable(TipoOrden.CABLE),
            ordenDao.countCompletadas()
        )
    }

    suspend fun getOrden(id: String): Resultado<OrdenDto> {
        return try {
            val res = api.getOrden(id)
            if (res.isSuccessful) {
                val orden = res.body()!!
                ordenDao.insert(orden.toEntity())
                Resultado.Exito(orden)
            } else Resultado.Error("Orden no encontrada")
        } catch (e: Exception) {
            val cache = ordenDao.getById(id)
            if (cache != null) Resultado.Exito(cache.toDto())
            else Resultado.Error("Sin conexión")
        }
    }

    /**
     * Aceptar con respaldo offline.
     * - Marca la orden ACEPTADA local siempre (el técnico puede seguir).
     * - Si hay señal → lo confirma en el backend.
     * - Si no → encola en aceptar_pendiente; el SyncWorker lo reintenta.
     */
    suspend fun aceptarOrden(id: String): Resultado<Unit> {
        ordenDao.updateEstado(id, "ACEPTADA")
        val ahoraIso = System.currentTimeMillis().aIso8601()

        return try {
            val res = api.aceptarOrden(id, AceptarRequest(ahoraIso))
            if (res.isSuccessful) {
                Resultado.Exito(Unit)
            } else {
                aceptarDao.insert(AceptarPendienteEntity(ordenId = id))
                programarSync()
                Resultado.Exito(Unit)
            }
        } catch (e: Exception) {
            aceptarDao.insert(AceptarPendienteEntity(ordenId = id))
            programarSync()
            Resultado.Exito(Unit)
        }
    }
    /**
     * Opción B: iniciar con respaldo offline.
     * - La app SIEMPRE genera el instalacionId (UUID) ella misma.
     * - Si hay señal → lo manda al backend, que lo usa como id.
     * - Si NO hay señal → encola en iniciar_pendiente; el SyncWorker
     *   lo registrará en el backend cuando vuelva la red.
     * Devuelve el instalacionId — la app trabaja con él en ambos casos.
     */
    suspend fun iniciarInstalacion(
        ordenId: String, lat: Double?, lng: Double?, dir: String?
    ): Resultado<String> {
        // Si la orden ya tenía instalacionId (reabierta), lo reusamos. Si no, generamos uno.
        val existente = ordenDao.getById(ordenId)?.instalacionId
        val instId = existente ?: UUID.randomUUID().toString()

        // Marcar la orden EN_PROCESO local + asociar el id (haya o no señal)
        ordenDao.updateEstado(ordenId, "EN_PROCESO")
        ordenDao.updateInstalacionId(ordenId, instId)

        // Función interna para encolar el inicio offline
        suspend fun encolar() {
            iniciarDao.insert(IniciarPendienteEntity(
                instalacionId = instId,
                ordenId       = ordenId,
                latitud       = lat,
                longitud      = lng,
                direccionGps  = dir
            ))
            programarSync()
        }

        if (!isOnline()) {
            encolar()
            return Resultado.Exito(instId)
        }

        return try {
            val res = api.iniciarInstalacion(
                ordenId,
                IniciarInstalacionRequest(lat, lng, dir, instId)   // ← manda el uuid
            )
            if (res.isSuccessful) {
                Resultado.Exito(instId)
            } else {
                encolar()                  // backend falló → el Worker reintenta
                Resultado.Exito(instId)
            }
        } catch (e: Exception) {
            encolar()                      // sin conexión real → el Worker reintenta
            Resultado.Exito(instId)
        }
    }
    suspend fun subirFoto(instalacionId: String, ruta: String, tipo: String): Resultado<Unit> {
        return try {
            val file = File(ruta)
            if (!file.exists() || file.length() == 0L) {
                return Resultado.Error("Archivo no encontrado o vacío")
            }
            val part = MultipartBody.Part.createFormData(
                "fotos",        // ← plural, igual que el backend: .array('fotos', 10)
                file.name,
                file.asRequestBody("image/jpeg".toMediaType())
            )
            // ← "tipos" en plural, como espera el backend
            val tipoPart = tipo.toRequestBody("text/plain".toMediaType())
            val res = api.subirFoto(instalacionId, part, tipoPart)
            if (res.isSuccessful) Resultado.Exito(Unit)
            else {
                val error = res.errorBody()?.string() ?: "Error desconocido"
                android.util.Log.e("REPO", "Error subir foto: $error")
                fotoDao.insert(FotoPendienteEntity(instalacionId = instalacionId, tipo = tipo, rutaLocal = ruta))
                Resultado.Error("Foto guardada offline: $error")
            }
        } catch (e: Exception) {
            fotoDao.insert(FotoPendienteEntity(instalacionId = instalacionId, tipo = tipo, rutaLocal = ruta))
            Resultado.Error("Sin conexión — foto guardada offline")
        }
    }
    suspend fun guardarConfigOnu(instalacionId: String, config: ConfigOnuRequest): Resultado<Unit> {
        if (!isOnline()) {
            configDao.insert(ConfigOfflineEntity(
                instalacionId    = instalacionId,
                ssid             = config.ssid,
                ssidPassword     = config.ssidPassword,
                ssid5ghz         = config.ssid5ghz,
                ssidPassword5ghz = config.ssidPassword5ghz,
                serialNumber     = config.serialNumber,
                potenciaRx       = config.potenciaRx,
                potenciaTx       = config.potenciaTx,
                temperatura      = config.temperatura,
                pppoeUser        = config.pppoeUser,
                pppoePassword    = config.pppoePassword
            ))
            return Resultado.Error(MsgResultado.GUARDADO_OFFLINE)        }
        return try {
            val res = api.guardarConfigOnu(instalacionId, config)
            if (res.isSuccessful) Resultado.Exito(Unit)
            else Resultado.Error("Error al guardar config")
        } catch (e: Exception) {
            configDao.insert(ConfigOfflineEntity(
                instalacionId    = instalacionId,
                ssid             = config.ssid,
                ssidPassword     = config.ssidPassword,
                ssid5ghz         = config.ssid5ghz,
                ssidPassword5ghz = config.ssidPassword5ghz,
                serialNumber     = config.serialNumber,
                potenciaRx       = config.potenciaRx,
                potenciaTx       = config.potenciaTx,
                temperatura      = config.temperatura,
                pppoeUser        = config.pppoeUser,
                pppoePassword    = config.pppoePassword
            ))
            return Resultado.Error(MsgResultado.GUARDADO_OFFLINE)
        }
    }

    /**
     * C10 + C11 FIX: completar con respaldo offline.
     * - Si hay señal y el backend confirma → completa directo.
     * - Si no hay señal o el backend falla → encola en completar_pendiente
     *   y programa el SyncWorker para reintentar cuando vuelva la red.
     */
    suspend fun completarInstalacion(
        instalacionId: String,
        ordenId: String,                    // ← NUEVO
        obs: String?,
        fotosOk: Boolean
    ): Resultado<Unit> {
        if (!fotosOk || !isOnline()) {
            encolarCompletar(instalacionId, ordenId, obs)
            return Resultado.Error("PENDIENTE_OFFLINE")
        }
        return try {
            val res = api.completarInstalacion(
                instalacionId,
                CompletarRequest(obs, System.currentTimeMillis().aIso8601())
            )
            if (res.isSuccessful) {
                ordenDao.updateEstado(ordenId, "COMPLETADA")   // ← marcar local
                Resultado.Exito(Unit)
            } else {
                encolarCompletar(instalacionId, ordenId, obs)
                Resultado.Error("PENDIENTE_OFFLINE")
            }
        } catch (e: Exception) {
            encolarCompletar(instalacionId, ordenId, obs)
            Resultado.Error("PENDIENTE_OFFLINE")
        }
    }

    private suspend fun encolarCompletar(instalacionId: String, ordenId: String, obs: String?) {
        completarDao.insert(
            CompletarPendienteEntity(
                instalacionId = instalacionId,
                observaciones = obs,
                fechaFin      = System.currentTimeMillis()   // ← momento real de completado
            )
        )
        ordenDao.updateEstado(ordenId, "COMPLETADA")
        programarSync()
    }

    /** Programa el SyncWorker para que corra apenas haya red. */
    fun programarSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val work = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            SyncWorker.NOMBRE_UNICO,
            ExistingWorkPolicy.REPLACE,
            work
        )
    }

    suspend fun hayCompletarPendientes(): Int = completarDao.countPendientes()

    suspend fun getCompletadasFiltradas(
        tipo: String, busqueda: String, limit: Int, offset: Int
    ): List<OrdenEntity> {
        val (filtrar, tipos) = when (tipo) {
            "INTERNET" -> true  to TipoOrden.INTERNET
            "CABLE"    -> true  to TipoOrden.CABLE
            else       -> false to emptyList()      // "TODOS"
        }
        return ordenDao.getCompletadasFiltradas(filtrar, tipos, busqueda, limit, offset)
    }
}

// ── Mappers ───────────────────────────────────────────────────
fun OrdenDto.toEntity() = OrdenEntity(
    id = id, nServicio = nServicio, tipoOrden = tipoOrden, estado = estado,
    fechaServicio = fechaServicio, abonado = abonado, dni = dni,
    direccion = direccion, referencia = referencia, sector = sector,
    celular = celular, observacion = observacion, contrato = contrato,
    ipWan = ipWan, mascara = mascara, gateway = gateway,
    fechaAceptacion = fechaAceptacion, tiempoInstalacion = tiempoInstalacion,
    instalacionId = instalacion?.id,
    instalacionCompletada = instalacion?.completada ?: false
)

fun OrdenEntity.toDto() = OrdenDto(
    id = id, nServicio = nServicio, tipoOrden = tipoOrden, estado = estado,
    fechaServicio = fechaServicio, contrato = contrato, abonado = abonado,
    dni = dni, direccion = direccion, referencia = referencia, sector = sector,
    celular = celular, observacion = observacion,
    ipWan = ipWan, mascara = mascara, gateway = gateway,
    fechaAceptacion = fechaAceptacion, fechaInicio = null, fechaFin = null,
    tiempoInstalacion = tiempoInstalacion, tecnico = null,
    instalacion = instalacionId?.let { InstalacionResumenDto(it, instalacionCompletada) }
)