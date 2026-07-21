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
import com.enetfiber.tecnico.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.work.*
import com.enetfiber.tecnico.data.local.CompletarPendienteDao
import com.enetfiber.tecnico.data.local.CompletarPendienteEntity
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlinx.coroutines.flow.firstOrNull


sealed class Resultado<out T> {
    data class Exito<T>(val data: T) : Resultado<T>()
    data class Error(val mensaje: String) : Resultado<Nothing>()

    fun isExito() = this is Exito
    fun isError() = this is Error
}

fun Long.aIso8601(): String {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return sdf.format(java.util.Date(this))
}

object MsgResultado {
    const val GUARDADO_OFFLINE  = "Guardado offline"
    const val PENDIENTE_OFFLINE = "PENDIENTE_OFFLINE"
}

data class ErrorResponseSimple(val error: String?)

@Singleton
class Repository @Inject constructor(
    val api:              ApiService,
    private val ordenDao:         OrdenDao,
    private val configDao:        ConfigOfflineDao,
    private val fotoDao:          FotoPendienteDao,
    private val completarDao:     CompletarPendienteDao,
    private val iniciarDao:       IniciarPendienteDao,
    private val aceptarDao:       AceptarPendienteDao,
    private val inventarioDao:    InventarioDao,          // ← NUEVO
    private val consumoDao:       ConsumoPendienteDao,
    val catalogoDao:              CatalogoProductoDao,    // ← NUEVO
    private val retiroDao:        RetiroPendienteDao,
    val configTipoOrdenDao:       ConfigTipoOrdenDao,     // ← tipos dinámicos
    private val session:          SessionDataStore,
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

                if (body.usuario.rol != "TECNICO") {
                    return Resultado.Error("Esta aplicación es solo para técnicos")
                }

                val tecnico = body.usuario.tecnico
                    ?: return Resultado.Error("Tu usuario no tiene perfil de técnico asignado. Contactá al administrador.")

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

    suspend fun cambiarPassword(actual: String, nueva: String): Resultado<Unit> {
        return try {
            val res = api.cambiarPassword(CambiarPasswordRequest(actual, nueva))
            if (res.isSuccessful) Resultado.Exito(Unit)
            else if (res.code() == 401) Resultado.Error("Contraseña actual incorrecta")
            else {
                // Leer el mensaje real del backend (ej. requisitos de contraseña)
                // en vez de mostrar siempre el mismo texto genérico sin contexto.
                val mensaje = try {
                    val errorJson = res.errorBody()?.string()
                    com.google.gson.Gson().fromJson(errorJson, ErrorResponseSimple::class.java)?.error
                } catch (e: Exception) { null }
                Resultado.Error(mensaje ?: "No se pudo cambiar la contraseña")
            }
        } catch (e: Exception) {
            Resultado.Error("Sin conexión al servidor")
        }
    }

    suspend fun logout() {
        try { api.logout() } catch (_: Exception) {}
        // Limpiar TODAS las tablas cacheadas — sin esto, las órdenes/inventario
        // del técnico anterior persisten en Room y aparecen al loguear otro usuario
        ordenDao.deleteAll()
        consumoDao.deleteAll()
        retiroDao.deleteAll()
        inventarioDao.clearItems()
        inventarioDao.clearOnus()
        catalogoDao.clearAll()
        configTipoOrdenDao.clearAll()
        session.cerrarSesion()
    }

    // ── Órdenes ───────────────────────────────────────────────
    fun getPendientesInternet() = ordenDao.getPendientesInternet(TipoOrden.INTERNET)
    fun getPendientesCable()    = ordenDao.getPendientesCable(TipoOrden.CABLE)
    fun getPendientesDuo()      = ordenDao.getPendientesCable(TipoOrden.DUO)
    fun getCompletadas()        = ordenDao.getCompletadas()

    suspend fun sincronizarOrdenes(): Resultado<Unit> {
        return try {
            // FIX: sin límite propio, una red lenta deja esto esperando hasta
            // los 60s del timeout global de OkHttp.
            val res = kotlinx.coroutines.withTimeoutOrNull(12_000) { api.getOrdenes(limit = 100) }
                ?: return Resultado.Error("Red lenta — mostrando datos locales")
            if (res.isSuccessful) {
                val lista = res.body()?.data ?: emptyList()
                val idsRemotos = lista.map { it.id }

                val idsProtegidos = (
                        configDao.instalacionIdsPendientes() +
                                fotoDao.instalacionIdsPendientes() +
                                completarDao.getPendientes().map { it.instalacionId }
                        ).distinct()

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

    data class ConteoOrdenes(val internet: Int, val cable: Int, val duo: Int, val completadas: Int)

    suspend fun countPorCategoria(): ConteoOrdenes {
        return ConteoOrdenes(
            internet    = ordenDao.countPendientesInternet(TipoOrden.INTERNET),
            cable       = ordenDao.countPendientesCable(TipoOrden.CABLE),
            duo         = ordenDao.countPendientesCable(TipoOrden.DUO),
            completadas = ordenDao.countCompletadas()
        )
    }

    suspend fun getOrden(id: String): Resultado<OrdenDto> {
        // FIX: red lenta (pero no caída) hacía que la app se quedara esperando
        // hasta 60s en pantalla de carga, ignorando que ya tenía estos mismos
        // datos en caché desde la última sincronización. Ahora: si hay caché,
        // se intenta la red con un timeout corto (8s) solo para refrescar —
        // si tarda más que eso, se devuelve la caché igual y la red sigue
        // actualizando en segundo plano para la próxima vez que se entre.
        val cache = ordenDao.getById(id)

        if (cache != null) {
            try {
                val res = kotlinx.coroutines.withTimeoutOrNull(8_000) { api.getOrden(id) }
                if (res != null && res.isSuccessful) {
                    val orden = res.body()!!
                    ordenDao.insert(orden.toEntity())
                    return Resultado.Exito(orden)
                }
            } catch (_: Exception) { /* red lenta o caída — usamos la caché */ }
            return Resultado.Exito(cache.toDto())
        }

        // Sin caché: sí hay que esperar la red completa, no hay nada más que mostrar.
        return try {
            val res = api.getOrden(id)
            if (res.isSuccessful) {
                val orden = res.body()!!
                ordenDao.insert(orden.toEntity())
                Resultado.Exito(orden)
            } else Resultado.Error("Orden no encontrada")
        } catch (e: Exception) {
            Resultado.Error("Sin conexión")
        }
    }

    suspend fun aceptarOrden(id: String): Resultado<Unit> {
        val estadoAnterior = ordenDao.getById(id)?.estado ?: "PENDIENTE_TECNICO"
        ordenDao.updateEstado(id, "ACEPTADA")
        val ahoraIso = System.currentTimeMillis().aIso8601()

        return try {
            val res = api.aceptarOrden(id, AceptarRequest(ahoraIso))
            if (res.isSuccessful) {
                Resultado.Exito(Unit)
            } else if (res.code() == 409) {
                ordenDao.updateEstado(id, estadoAnterior)
                val mensaje = try {
                    val errorJson = res.errorBody()?.string()
                    com.google.gson.Gson().fromJson(errorJson, ErrorResponseSimple::class.java)?.error
                } catch (_: Exception) { null }
                Resultado.Error(mensaje ?: "Esta orden ya fue tomada por otro técnico")
            } else {
                ordenDao.updateEstado(id, estadoAnterior)
                val mensaje = try {
                    val errorJson = res.errorBody()?.string()
                    com.google.gson.Gson().fromJson(errorJson, ErrorResponseSimple::class.java)?.error
                } catch (_: Exception) { null }
                Resultado.Error(mensaje ?: "No se pudo aceptar la orden")
            }
        } catch (e: Exception) {
            aceptarDao.insert(AceptarPendienteEntity(ordenId = id))
            programarSync()
            Resultado.Exito(Unit)
        }
    }

    suspend fun iniciarInstalacion(
        ordenId: String, lat: Double?, lng: Double?, dir: String?
    ): Resultado<String> {
        val existente = ordenDao.getById(ordenId)?.instalacionId
        val instId = existente ?: UUID.randomUUID().toString()

        ordenDao.updateEstado(ordenId, "EN_PROCESO")
        ordenDao.updateInstalacionId(ordenId, instId)

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
                IniciarInstalacionRequest(lat, lng, dir, instId)
            )
            if (res.isSuccessful) {
                Resultado.Exito(instId)
            } else {
                encolar()
                Resultado.Exito(instId)
            }
        } catch (e: Exception) {
            encolar()
            Resultado.Exito(instId)
        }
    }
/*
    suspend fun subirFoto(instalacionId: String, ruta: String, tipo: String): Resultado<Unit> {
        return try {
            val file = File(ruta)
            if (!file.exists() || file.length() == 0L) {
                return Resultado.Error("Archivo no encontrado o vacío")
            }
            val part = MultipartBody.Part.createFormData(
                "fotos", file.name,
                file.asRequestBody("image/jpeg".toMediaType())
            )
            val tipoPart = tipo.toRequestBody("text/plain".toMediaType())
            val res = api.subirFoto(instalacionId, part, tipoPart)
            if (res.isSuccessful) Resultado.Exito(Unit)
            else {
                val error = res.errorBody()?.string() ?: "Error desconocido"
                fotoDao.insert(FotoPendienteEntity(instalacionId = instalacionId, tipo = tipo, rutaLocal = ruta))
                Resultado.Error("Foto guardada offline: $error")
            }
        } catch (e: Exception) {
            fotoDao.insert(FotoPendienteEntity(instalacionId = instalacionId, tipo = tipo, rutaLocal = ruta))
            Resultado.Error("Sin conexión — foto guardada offline")
        }
    }
*/

    suspend fun subirFoto(instalacionId: String, ruta: String, tipo: String): Resultado<Unit> {
        return try {
            val original = File(ruta)
            if (!original.exists() || original.length() == 0L) {
                return Resultado.Error("Archivo no encontrado o vacío")
            }
            val file = withContext(Dispatchers.IO) { ImageUtils.comprimirAWebP(original) }
            val part = MultipartBody.Part.createFormData(
                "fotos", file.name,
                file.asRequestBody("image/webp".toMediaType())
            )
            val tipoPart = tipo.toRequestBody("text/plain".toMediaType())
            val res = api.subirFoto(instalacionId, part, tipoPart)
            if (res.isSuccessful) Resultado.Exito(Unit)
            else {
                val error = res.errorBody()?.string() ?: "Error desconocido"
                fotoDao.insert(FotoPendienteEntity(instalacionId = instalacionId, tipo = tipo, rutaLocal = file.absolutePath))
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
            return Resultado.Error(MsgResultado.GUARDADO_OFFLINE)
        }
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

    suspend fun completarInstalacion(
        instalacionId: String,
        ordenId: String,
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
                ordenDao.updateEstado(ordenId, "COMPLETADA")
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

    /** Consulta el estado actual de la instalación (usado para polling del estado OLT). */
    suspend fun obtenerInstalacion(instalacionId: String): Resultado<InstalacionDto> {
        return try {
            val res = api.obtenerInstalacion(instalacionId)
            if (res.isSuccessful && res.body() != null) Resultado.Exito(res.body()!!)
            else Resultado.Error("No se pudo consultar la instalación")
        } catch (e: Exception) {
            Resultado.Error("Sin conexión: ${e.message}")
        }
    }

    /**
     * Autoriza manualmente la ONU en la OLT desde Paso 4 de la app.
     * Si serialNumber viene, corrige el SN antes de autorizar (caso de cambio de equipo).
     */
    suspend fun autorizarOlt(
        instalacionId: String,
        serialNumber: String? = null
    ): Resultado<AutorizarOltResponse> {
        if (!isOnline()) return Resultado.Error("Sin conexión a internet")
        return try {
            val res = api.autorizarOlt(
                instalacionId,
                AutorizarOltRequest(serialNumber = serialNumber)
            )
            val body = res.body()
            if (res.isSuccessful && body != null) {
                Resultado.Exito(body)
            } else {
                // El backend devuelve 422 con { ok:false, error, mensaje } cuando queda pendiente
                val errorBody = res.errorBody()?.string()
                val mensaje = try {
                    com.google.gson.Gson().fromJson(errorBody, AutorizarOltResponse::class.java)?.error
                } catch (e: Exception) { null }
                Resultado.Error(mensaje ?: "No se pudo autorizar la ONU en la OLT")
            }
        } catch (e: Exception) {
            Resultado.Error("Sin conexión: ${e.message}")
        }
    }

    private suspend fun encolarCompletar(instalacionId: String, ordenId: String, obs: String?) {
        completarDao.insert(
            CompletarPendienteEntity(
                instalacionId = instalacionId,
                observaciones = obs,
                fechaFin      = System.currentTimeMillis()
            )
        )
        ordenDao.updateEstado(ordenId, "COMPLETADA")
        programarSync()
    }

    suspend fun actualizarPrecinto(numero: String, precinto: String?): Resultado<Unit> {
        return try {
            val res = api.actualizarPrecinto(numero, PrecintoRequest(precinto))
            if (res.isSuccessful) Resultado.Exito(Unit)
            else Resultado.Error("Error al guardar precinto: ${res.code()}")
        } catch (e: Exception) {
            Resultado.Error("Sin conexion: ${e.message}")
        }
    }

    suspend fun actualizarUbicacionContrato(
        numero:   String,
        latitud:  Double,
        longitud: Double
    ): Resultado<Unit> {
        return try {
            val res = api.actualizarUbicacionContrato(numero, UbicacionRequest(latitud, longitud))
            if (res.isSuccessful) Resultado.Exito(Unit)
            else Resultado.Error("Error al guardar ubicación: ${res.code()}")
        } catch (e: Exception) {
            Resultado.Error("Sin conexión: ${e.message}")
        }
    }

    // ── Inventario ────────────────────────────────────────────

    /** LiveData del inventario local (funciona offline) */
    fun getInventarioItems() = inventarioDao.getItems()
    suspend fun contarItems() = inventarioDao.contarItems()
    fun getInventarioOnus()  = inventarioDao.getOnus()
    fun getConsumosPendientes(tecnicoId: String) = consumoDao.getTodos(tecnicoId)

    suspend fun corregirTecnicoIdVacios(tecnicoId: String) {
        if (tecnicoId.isNotBlank()) consumoDao.actualizarTecnicoIdVacios(tecnicoId)
    }
    /** Métricas calculadas desde la caché local */
    suspend fun getMetricasInventario() = InventarioMetricas(
        totalAsignados   = inventarioDao.totalAsignado(),
        totalUtilizados  = inventarioDao.totalUtilizado(),
        totalDisponibles = inventarioDao.totalDisponible(),
        totalSinStock    = inventarioDao.totalSinStock()
    )

    /**
     * Sincroniza inventario desde el servidor y actualiza la caché local.
     * Si no hay internet devuelve los datos cacheados sin error.
     */
    suspend fun sincronizarInventario(): Resultado<Unit> {
        if (!isOnline()) return Resultado.Error("Sin internet — mostrando datos locales")
        // Sincronizar catálogo en paralelo
        try { sincronizarCatalogo() } catch (_: Exception) {}
        return try {
            // FIX: mismo problema — sin límite propio, red lenta deja el indicador
            // de "sincronizando" encendido hasta 60s aunque Room ya muestra datos.
            val res = kotlinx.coroutines.withTimeoutOrNull(12_000) { api.getMiInventario() }
                ?: return Resultado.Error("Red lenta — mostrando datos locales")
            if (res.isSuccessful) {
                val body = res.body()!!
                inventarioDao.reemplazarItems(body.items.map { it.toEntity() })
                inventarioDao.reemplazarOnus(body.onus.map { it.toEntity() })

                // Corregir consumos con tecnicoId vacío (guardados antes del fix)
                val tecnicoIdFix = session.tecnicoId.firstOrNull() ?: ""
                if (tecnicoIdFix.isNotBlank()) {
                    consumoDao.actualizarTecnicoIdVacios(tecnicoIdFix)
                }

                // Solo borrar sincronizados si el servidor devuelve historial
                // Evita borrar consumos locales si el backend no los tiene aún
                if (body.historialConsumos.isNotEmpty()) {
                    // Borrar solo los sincronizados anteriores
                    consumoDao.deleteSincronizados()
                    // Obtener pendientes locales para no duplicar
                    val pendientesLocales = consumoDao.getPendientes()
                    val descPendientes = pendientesLocales.map { it.descripcion }.toSet()
                    for (c in body.historialConsumos) {
                        // Saltar si ya hay un pendiente local con la misma descripcion
                        if (c.descripcion != null && c.descripcion in descPendientes) continue
                        consumoDao.insert(ConsumoPendienteEntity(
                            productoId   = c.productoId,
                            tecnicoId    = tecnicoIdFix,
                            nombre       = c.nombre,
                            cantidad     = c.cantidad,
                            motivo       = c.motivo ?: "SERVICIO",
                            descripcion  = c.descripcion,
                            ordenId      = null,
                            nServicio    = c.nServicio,
                            abonado      = c.abonado,
                            sincronizado = true,
                        ))
                    }
                }

                Resultado.Exito(Unit)
            } else Resultado.Error("Error al obtener inventario")
        } catch (e: Exception) {
            Resultado.Error("Sin internet — mostrando datos locales")
        }
    }
    /**
     * Trae el historial de consumos directo de la API (sin tocar Room) — usado
     * para mostrar "Materiales utilizados" de una orden completada específica,
     * filtrando por nServicio en el cliente. Solo funciona online; las órdenes
     * completadas son históricas, no necesitan estar disponibles offline.
     */

   /* suspend fun getHistorialConsumosPorOrden(nServicio: String): Resultado<List<ConsumoHistorialDto>> {
        if (!isOnline()) return Resultado.Error("Sin conexión")
        return try {
            val res = kotlinx.coroutines.withTimeoutOrNull(10_000) { api.getMiInventario() }
                ?: return Resultado.Error("Red lenta")
            if (res.isSuccessful) {
                val historial = res.body()?.historialConsumos.orEmpty()
                    .filter { it.nServicio == nServicio }
                Resultado.Exito(historial)
            } else Resultado.Error("Error al obtener materiales")
        } catch (e: Exception) {
            Resultado.Error("Sin conexión: ${e.message}")
        }
    }
*/

    suspend fun getHistorialConsumosPorOrden(nServicio: String): Resultado<List<ConsumoHistorialDto>> {
        if (!isOnline()) return Resultado.Error("Sin conexión")
        return try {
            val res = kotlinx.coroutines.withTimeoutOrNull(10_000) { api.getMiInventario() }
                ?: return Resultado.Error("Red lenta")
            if (res.isSuccessful) {
                val body = res.body()

                // El backend guarda consumoTecnico.cantidad en "unidades" para productos
                // medibles (rollos) — igual que ya hace correctamente con "items" (asignado/
                // utilizado/disponible en metros). "historialConsumos" en cambio devuelve la
                // cantidad cruda sin convertir. Como los mismos productos ya vienen con
                // esMedible/metrosPorUnidad dentro de "items" en ESTA misma respuesta,
                // reconvertimos aquí sin necesidad de tocar el backend.
                val metrosPorUnidadPorProducto = body?.items
                    .orEmpty()
                    .filter { it.esMedible && it.metrosPorUnidad != null }
                    .associate { it.productoId to it.metrosPorUnidad!! }

                val historial = body?.historialConsumos.orEmpty()
                    .filter { it.nServicio == nServicio }
                    .map { c ->
                        // Si el backend YA manda unidad (ej. tras subir el fix de
                        // stock.controller.js a producción), no reconvertir de nuevo.
                        val metrosPorUnidad = metrosPorUnidadPorProducto[c.productoId]
                        if (metrosPorUnidad != null && c.unidad == null) {
                            c.copy(cantidad = c.cantidad * metrosPorUnidad, unidad = "m")
                        } else c
                    }
                Resultado.Exito(historial)
            } else Resultado.Error("Error al obtener materiales")
        } catch (e: Exception) {
            Resultado.Error("Sin conexión: ${e.message}")
        }
    }
    /**
     * Registra material gastado.
     * Siempre guarda localmente primero; si hay internet también envía al servidor.
     * Si no hay internet encola para sincronizar después.
     */
    // ── Catálogo offline ─────────────────────────────────────
    fun getCatalogo() = catalogoDao.getAll()

    suspend fun getCatalogoOnce() = catalogoDao.getAllOnce()

    suspend fun sincronizarCatalogo(): Resultado<Unit> {
        if (!isOnline()) return Resultado.Error("Sin internet")
        return try {
            // FIX: mismo límite — se ejecuta encadenada con sincronizarInventario
            // y podía duplicar la espera en redes lentas.
            val res = kotlinx.coroutines.withTimeoutOrNull(12_000) { api.getCatalogoTecnico() }
                ?: return Resultado.Error("Red lenta")
            if (res.isSuccessful) {
                val items = (res.body() ?: emptyList()).map {
                    CatalogoProductoEntity(
                        id        = it.id,
                        nombre    = it.nombre,
                        codigo    = it.codigo,
                        categoria = it.categoria,
                        unidad    = it.unidad,
                    )
                }
                catalogoDao.clearAll()
                catalogoDao.insertAll(items)
                Resultado.Exito(Unit)
            } else {
                Resultado.Error("Error al obtener catálogo")
            }
        } catch (e: Exception) {
            Resultado.Error(e.message ?: "Error")
        }
    }

    // ── Tipos de orden dinámicos ──────────────────────────────
    fun getTiposOrden() = configTipoOrdenDao.getAll()

    suspend fun getTiposOrdenOnce() = configTipoOrdenDao.getAllOnce()

    suspend fun sincronizarTiposOrden(): Resultado<Unit> {
        if (!isOnline()) return Resultado.Error("Sin internet")
        return try {
            val res = api.getTiposOrden()
            if (res.isSuccessful) {
                val tipos = (res.body()?.tipos ?: emptyList()).map {
                    ConfigTipoOrdenEntity(
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
                android.util.Log.d("Repository", "✓ ${tipos.size} tipos de orden sincronizados")
                Resultado.Exito(Unit)
            } else {
                Resultado.Error("Error al obtener tipos de orden")
            }
        } catch (e: Exception) {
            Resultado.Error(e.message ?: "Error")
        }
    }

    suspend fun registrarConsumo(
        items:       List<ConsumoItemRequest>,
        motivo:      String = "SERVICIO",
        descripcion: String? = null,
        ordenId:     String? = null,
        nServicio:   String? = null,
        abonado:     String? = null,
        // Nombres para mostrar offline (el DAO los necesita)
        nombresMap:  Map<Int, String> = emptyMap()
    ): Resultado<Unit> {
        // Guardar offline siempre — obtener tecnicoId de la sesión
        val tecnicoIdActual = session.tecnicoId.firstOrNull() ?: ""
        for (item in items) {
            if (item.cantidad <= 0) continue
            consumoDao.insert(ConsumoPendienteEntity(
                productoId   = item.productoId,
                tecnicoId    = tecnicoIdActual,
                nombre       = nombresMap[item.productoId] ?: "Producto #${item.productoId}",
                cantidad     = item.cantidad,
                motivo       = motivo,
                descripcion  = descripcion,
                ordenId      = ordenId,
                nServicio    = nServicio,
                abonado      = abonado,
            ))
        }

        // Actualizar caché local del inventario (descuento inmediato)
        val itemsActuales = inventarioDao.getItemsOnce()
        val actualizados = itemsActuales.map { inv ->
            val gastado = items.filter { it.productoId == inv.productoId }
                .sumOf { it.cantidad }
            if (gastado > 0) {
                val nuevoUtilizado  = inv.utilizado + gastado
                val nuevoDisponible = maxOf(0.0, inv.asignado - nuevoUtilizado)
                inv.copy(
                    utilizado  = nuevoUtilizado,
                    disponible = nuevoDisponible,
                    sinStock   = nuevoDisponible == 0.0
                )
            } else inv
        }
        inventarioDao.insertItems(actualizados)

        // Intentar sincronizar si hay internet
        if (isOnline()) {
            try {
                val res = api.registrarConsumo(
                    RegistrarConsumoRequest(items, motivo, descripcion, ordenId)
                )
                if (res.isSuccessful) {
                    // Marcar todos como sincronizados
                    val pendientes = consumoDao.getNoSincronizados()
                    for (p in pendientes) consumoDao.marcarSincronizado(p.id)
                    // Refrescar inventario desde servidor
                    sincronizarInventario()
                } else {
                    programarSync()
                }
            } catch (e: Exception) {
                programarSync()
            }
        } else {
            programarSync()
        }

        return Resultado.Exito(Unit)
    }

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
        tipo:       String,
        busqueda:   String,
        fechaDesde: Long?,
        fechaHasta: Long?,
        limit:      Int,
        offset:     Int
    ): List<OrdenEntity> {
        val (filtrar, tipos) = when (tipo) {
            "INTERNET" -> true  to TipoOrden.INTERNET
            "CABLE"    -> true  to TipoOrden.CABLE
            "DUO"      -> true  to TipoOrden.DUO
            else       -> false to emptyList()
        }
        // fechaHasta: sumar 1 día para incluir todo el día seleccionado
        val hastaFin = fechaHasta?.plus(86_400_000L)
        return ordenDao.getCompletadasFiltradas(
            filtrar, tipos, busqueda, fechaDesde, hastaFin, limit, offset
        )
    }

    /**
     * Registra equipos recuperados de una orden de retiro.
     * El stock se suma al inventario del técnico.
     * Offline-first: si no hay internet encola para sincronizar.
     */
    suspend fun registrarRetiro(
        items:   List<RetiroItemRequest>,
        ordenId: String? = null,
    ): Resultado<Unit> {
        val tecnicoIdActual = session.tecnicoId.firstOrNull() ?: ""

        // ── 1. Guardar localmente siempre (offline-first) ──────────
        for (item in items) {
            // Buscar nombre del producto en catálogo local para mostrarlo offline
            val nombreProducto = item.productoId?.let { pid ->
                catalogoDao.getAllOnce().find { it.id == pid }?.nombre
            } ?: "Equipo #${item.productoId}"

            retiroDao.insert(RetiroPendienteEntity(
                productoId  = item.productoId,
                tecnicoId   = tecnicoIdActual,
                nombre      = nombreProducto,
                tipoEquipo  = item.tipoEquipo ?: "EQUIPO",
                codigoPon   = item.codigoPon,
                ordenId     = ordenId,
            ))
        }

        // ── 2. Actualizar inventario local inmediatamente ──────────
        // El técnico puede usar el equipo aunque no haya internet
        val itemsActuales = inventarioDao.getItemsOnce()
        val actualizados = itemsActuales.map { inv ->
            val recuperado = items
                .filter { it.productoId == inv.productoId }
                .size.toDouble()  // cada item del retiro = 1 unidad
            if (recuperado > 0) {
                val nuevoAsignado   = inv.asignado + recuperado
                val nuevoDisponible = maxOf(0.0, nuevoAsignado - inv.utilizado)
                inv.copy(
                    asignado   = nuevoAsignado,
                    disponible = nuevoDisponible,
                    sinStock   = nuevoDisponible == 0.0,
                    // Actualizar metros si es medible
                    asignadoMetros   = if (inv.esMedible && inv.metrosPorUnidad != null)
                        nuevoAsignado * inv.metrosPorUnidad else inv.asignadoMetros,
                    disponibleMetros = if (inv.esMedible && inv.metrosPorUnidad != null)
                        nuevoDisponible * inv.metrosPorUnidad else inv.disponibleMetros,
                )
            } else inv
        }
        inventarioDao.insertItems(actualizados)

        // ── 3. Intentar sincronizar si hay internet ────────────────
        if (isOnline()) {
            try {
                val res = api.registrarRetiro(
                    RegistrarRetiroRequest(items = items, ordenId = ordenId)
                )
                if (res.isSuccessful) {
                    // Marcar todos los recién insertados como sincronizados
                    val pendientes = retiroDao.getPendientes()
                    for (p in pendientes) retiroDao.marcarSincronizado(p.id)
                    // Refrescar inventario desde servidor para confirmar
                    sincronizarInventario()
                } else {
                    programarSync()
                }
            } catch (e: Exception) {
                programarSync()
            }
        } else {
            programarSync()
        }

        return Resultado.Exito(Unit)
    }

    suspend fun registrarDevolucion(
        items:      List<DevolucionItemRequest>,
        recojos:    List<DevolucionRecojoRequest> = emptyList(),
        onuIds:     List<Int> = emptyList(),
        comentario: String? = null
    ): Resultado<Int> {
        if (!isOnline()) return Resultado.Error("Sin conexión — las devoluciones requieren internet")
        return try {
            val res = api.registrarDevolucion(
                RegistrarDevolucionRequest(items, recojos, onuIds, comentario)
            )
            if (res.isSuccessful) {
                val devolucionId = res.body()?.devolucionId ?: 0
                Resultado.Exito(devolucionId)
            } else {
                val error = res.errorBody()?.string() ?: "Error desconocido"
                Resultado.Error(error)
            }
        } catch (e: Exception) {
            Resultado.Error("Sin conexión al servidor")
        }
    }

    suspend fun getMisDevoluciones(): Resultado<List<DevolucionDto>> {
        if (!isOnline()) return Resultado.Error("Sin conexión")
        return try {
            val res = api.getMisDevoluciones()
            if (res.isSuccessful) Resultado.Exito(res.body() ?: emptyList())
            else Resultado.Error("Error al obtener devoluciones")
        } catch (e: Exception) {
            Resultado.Error("Sin conexión al servidor")
        }
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

fun InventarioItemDto.toEntity() = InventarioItemEntity(
    productoId       = productoId,
    nombre           = nombre,
    codigo           = codigo,
    categoria        = categoria,
    unidad           = unidad,
    asignado         = asignado,
    utilizado        = utilizado,
    disponible       = disponible,
    sinStock         = sinStock,
    esMedible        = esMedible,
    metrosPorUnidad  = metrosPorUnidad,
    disponibleMetros = disponibleMetros,
    asignadoMetros   = asignadoMetros,
    utilizadoMetros  = utilizadoMetros,
)

fun InventarioOnuDto.toEntity() = InventarioOnuEntity(
    id         = id,
    codigoPon  = codigoPon,
    producto   = producto,
    codigo     = codigo,
    productoId = productoId  // ← AGREGAR
)