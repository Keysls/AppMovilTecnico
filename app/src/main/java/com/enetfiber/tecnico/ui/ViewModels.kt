package com.enetfiber.tecnico.ui

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import androidx.lifecycle.*
import com.enetfiber.tecnico.TipoOrden
import com.enetfiber.tecnico.data.*
import com.enetfiber.tecnico.data.local.OrdenEntity
import com.enetfiber.tecnico.data.remote.*
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Locale
import javax.inject.Inject
import androidx.lifecycle.SavedStateHandle
import android.os.SystemClock
import androidx.lifecycle.asLiveData
import com.enetfiber.tecnico.data.local.SessionDataStore
// ═══════════════════════════════════════════════════════════════
// LOGIN
// ═══════════════════════════════════════════════════════════════
sealed class LoginState {
    object Idle    : LoginState()
    object Loading : LoginState()
    object Success : LoginState()
    data class Error(val msg: String) : LoginState()
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val repo:    Repository,
    private val session: SessionDataStore
) : ViewModel() {

    private val _state = MutableStateFlow<LoginState>(LoginState.Idle)
    val state: StateFlow<LoginState> = _state

    val isLoggedIn = session.isLoggedIn

    fun login(email: String, password: String) {
        viewModelScope.launch {
            _state.value = LoginState.Loading
            when (val r = repo.login(email.trim().lowercase(), password)) {
                is Resultado.Exito -> _state.value = LoginState.Success
                is Resultado.Error -> _state.value = LoginState.Error(r.mensaje)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// DASHBOARD
// ═══════════════════════════════════════════════════════════════
data class DashboardEstado(
    val pendientesInternet: Int = 0,
    val pendientesCable:    Int = 0,
    val pendientesDuo:      Int = 0,
    val completadas:        Int = 0,
    val cargando:           Boolean = true
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repo:    Repository,
    private val session: SessionDataStore
) : ViewModel() {

    val nombre   = session.nombre.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val apellido = session.apellido.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val zona     = session.zona.stateIn(viewModelScope, SharingStarted.Eagerly, "")


    // ✅ Nuevos
    val email          = session.email.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val telefono       = session.telefono.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val dni            = session.dni.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val vehiculo       = session.vehiculo.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val activo         = session.activo.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val ordenesActivas = session.ordenesActivas.stateIn(viewModelScope, SharingStarted.Eagerly, 0)


    private val _estado = MutableLiveData(DashboardEstado())
    val estado: LiveData<DashboardEstado> = _estado

    private val _refresco = MutableLiveData(false)
    val refresco: LiveData<Boolean> = _refresco

    private val _mensaje = MutableLiveData<String?>()
    val mensaje: LiveData<String?> = _mensaje

    val isOnline get() = repo.isOnline()

    init { refrescar() }

    fun refrescar() {
        viewModelScope.launch {
            _refresco.value = true
            val r = repo.sincronizarOrdenes()
            if (r is Resultado.Error) _mensaje.value = r.mensaje
            val counts = repo.countPorCategoria()
            _estado.value = DashboardEstado(
                pendientesInternet = counts.internet,
                pendientesCable    = counts.cable,
                pendientesDuo      = counts.duo,
                completadas        = counts.completadas,
                cargando           = false
            )
            _refresco.value = false
        }
    }

    fun logout() { viewModelScope.launch { repo.logout() } }
    fun limpiarMensaje() { _mensaje.value = null }

    fun cambiarPassword(actual: String, nueva: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val r = repo.cambiarPassword(actual, nueva)
            if (r is Resultado.Exito) onResult(true, "Contraseña actualizada correctamente")
            else onResult(false, (r as Resultado.Error).mensaje)
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// ÓRDENES
// ═══════════════════════════════════════════════════════════════
@HiltViewModel
class OrdenesViewModel @Inject constructor(
    private val repo: Repository
) : ViewModel() {

    val pendientesInternet = repo.getPendientesInternet()
    val pendientesCable    = repo.getPendientesCable()
    val pendientesDuo      = repo.getPendientesDuo()

    // ── Tipos de orden dinámicos ──────────────────────────────
    val tiposOrden = repo.getTiposOrden()

    init {
        viewModelScope.launch {
            // Cargar de Room primero (rápido, offline-first)
            val tiposLocales = repo.getTiposOrdenOnce()
            if (tiposLocales.isNotEmpty()) TipoOrden.cargarTiposDinamicos(tiposLocales)

            // Luego sincronizar del backend en background
            repo.sincronizarTiposOrden()
            val tiposFrescos = repo.getTiposOrdenOnce()
            if (tiposFrescos.isNotEmpty()) TipoOrden.cargarTiposDinamicos(tiposFrescos)
        }
    }

    // "Todos" combina los tres tipos
    val pendientesTodas: androidx.lifecycle.LiveData<List<com.enetfiber.tecnico.data.local.OrdenEntity>> =
        androidx.lifecycle.MediatorLiveData<List<com.enetfiber.tecnico.data.local.OrdenEntity>>().also { mediator ->
            val combine = {
                val internet = pendientesInternet.value ?: emptyList()
                val cable    = pendientesCable.value    ?: emptyList()
                val duo      = pendientesDuo.value      ?: emptyList()
                mediator.value = (internet + cable + duo).sortedByDescending { it.cachedAt }
            }
            mediator.addSource(pendientesInternet) { combine() }
            mediator.addSource(pendientesCable)    { combine() }
            mediator.addSource(pendientesDuo)      { combine() }
        }

    companion object {
        const val PAGE_SIZE = 30
    }

    private val _busqueda   = MutableLiveData("")
    private val _tipoFiltro = MutableLiveData("TODOS")
    private var _paginaActual = 0
    private var _hayMas = true
    private var _cargando = false

    private val _completadas = MutableLiveData<List<OrdenEntity>>(emptyList())
    val completadas: LiveData<List<OrdenEntity>> = _completadas

    private val _cargandoMas = MutableLiveData(false)

    private val _fechaDesde = MutableLiveData<Long?>(null)
    private val _fechaHasta = MutableLiveData<Long?>(null)

    fun setFechas(desde: Long?, hasta: Long?) {
        _fechaDesde.value = desde
        _fechaHasta.value = hasta
        cargarPagina(reset = true)
    }


    val cargandoMas: LiveData<Boolean> = _cargandoMas

    init { cargarPagina(reset = true) }

    fun setBusqueda(q: String) {
        _busqueda.value = q
        cargarPagina(reset = true)
    }

    fun setTipoFiltro(t: String) {
        _tipoFiltro.value = t
        cargarPagina(reset = true)
    }

    // Llamar cuando el usuario llega al final de la lista
    fun cargarMas() {
        if (!_hayMas || _cargando) return
        cargarPagina(reset = false)
    }

    private fun cargarPagina(reset: Boolean) {
        if (_cargando) return
        _cargando = true
        viewModelScope.launch {
            if (reset) { _paginaActual = 0; _hayMas = true }
            _cargandoMas.value = true
            val lista = repo.getCompletadasFiltradas(
                tipo      = _tipoFiltro.value ?: "TODOS",
                busqueda  = _busqueda.value?.trim().orEmpty(),
                fechaDesde = _fechaDesde.value,
                fechaHasta = _fechaHasta.value,
                limit     = PAGE_SIZE,
                offset    = _paginaActual * PAGE_SIZE
            )
            _completadas.value = if (reset) lista else (_completadas.value ?: emptyList()) + lista
            _hayMas = lista.size == PAGE_SIZE
            if (lista.isNotEmpty()) _paginaActual++
            _cargandoMas.value = false
            _cargando = false
        }
    }
    private val _refresco = MutableLiveData(false)
    val refresco: LiveData<Boolean> = _refresco

    fun refrescar() {
        viewModelScope.launch {
            _refresco.value = true
            repo.sincronizarOrdenes()
            cargarPagina(reset = true)
            _refresco.value = false
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// INSTALACIÓN
// ═══════════════════════════════════════════════════════════════
sealed class InstalacionState {
    object Idle      : InstalacionState()
    object Cargando  : InstalacionState()
    object Guardando : InstalacionState()
    data class Error(val msg: String) : InstalacionState()
}

const val MAX_FOTOS = 8

data class FotoTomada(
    val tipo:      String,
    val rutaLocal: String,
    val nombre:    String  = "",
    val tamanoMb:  String  = "",
    val origen:    String  = "CAMARA",   // CAMARA | GALERIA
    var subida:    Boolean = false
)

@HiltViewModel
class InstalacionViewModel @Inject constructor(
    private val repo: Repository,
    private val savedState: SavedStateHandle,
    @ApplicationContext private val ctx: Context
) : ViewModel()
{

    private val _state = MutableLiveData<InstalacionState>(InstalacionState.Idle)
    val state: LiveData<InstalacionState> = _state

    private val _orden = MutableLiveData<OrdenDto?>()
    val orden: LiveData<OrdenDto?> = _orden

    // ── Tipos de orden dinámicos ──────────────────────────────
    val tiposOrden = repo.getTiposOrden()

    init {
        viewModelScope.launch {
            val tiposLocales = repo.getTiposOrdenOnce()
            if (tiposLocales.isNotEmpty()) TipoOrden.cargarTiposDinamicos(tiposLocales)
            repo.sincronizarTiposOrden()
            val tiposFrescos = repo.getTiposOrdenOnce()
            if (tiposFrescos.isNotEmpty()) TipoOrden.cargarTiposDinamicos(tiposFrescos)
        }
    }

    private val _fotos = MutableLiveData<List<FotoTomada>>(emptyList())
    val fotos: LiveData<List<FotoTomada>> = _fotos

    private val _gpsListo = MutableLiveData(false)
    val gpsListo: LiveData<Boolean> = _gpsListo

    // Cronómetro — C9 FIX: basado en timestamp real, no en un contador.
    // El instante de inicio se guarda en SavedStateHandle → sobrevive
    // a background y a que el SO mate el proceso.
    private val _segundos = MutableLiveData(0L)
    val segundos: LiveData<Long> = _segundos
    private var cronometroJob: kotlinx.coroutines.Job? = null

    private var inicioCronometro: Long?
        get() = savedState["inicioCronometro"]
        set(v) { savedState["inicioCronometro"] = v }
    // C8 FIX: persistidos en SavedStateHandle — sobreviven a que el SO
    // mate el proceso (ej. cámara abierta en celular de gama baja)
    var instalacionId: String?
        get() = savedState["instalacionId"]
        set(v) { savedState["instalacionId"] = v }

    var latitud: Double?
        get() = savedState["latitud"]
        set(v) { savedState["latitud"] = v }

    var longitud: Double?
        get() = savedState["longitud"]
        set(v) { savedState["longitud"] = v }

    var direccionGps: String?
        get() = savedState["direccionGps"]
        set(v) { savedState["direccionGps"] = v }

    val isOnline get() = repo.isOnline()

    fun cargarOrden(id: String) {
        viewModelScope.launch {
            _state.value = InstalacionState.Cargando
            when (val r = repo.getOrden(id)) {
                is Resultado.Exito -> {
                    _orden.value = r.data
                    _state.value = InstalacionState.Idle
                }
                is Resultado.Error -> _state.value = InstalacionState.Error(r.mensaje)
            }
        }
    }

    fun aceptarOrden(ordenId: String, onExito: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            _state.value = InstalacionState.Cargando
            when (val r = repo.aceptarOrden(ordenId)) {
                is Resultado.Exito -> { _state.value = InstalacionState.Idle; onExito() }
                is Resultado.Error -> { _state.value = InstalacionState.Idle; onError(r.mensaje) }
            }
        }
    }

    // ── Cronómetro ────────────────────────────────────────────
    fun iniciarCronometro() {
        cronometroJob?.cancel()
        // Si ya había un inicio guardado (volvimos de background), lo conservamos.
        // Si no, marcamos ahora como inicio.
        if (inicioCronometro == null) {
            inicioCronometro = SystemClock.elapsedRealtime()
        }
        cronometroJob = viewModelScope.launch {
            while (true) {
                val inicio = inicioCronometro ?: SystemClock.elapsedRealtime()
                val transcurrido = (SystemClock.elapsedRealtime() - inicio) / 1000
                _segundos.value = transcurrido
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    fun detenerCronometro() {
        cronometroJob?.cancel()
        cronometroJob = null
    }

    fun cronometroTexto(): String {
        val s = _segundos.value ?: 0
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%02d:%02d:%02d".format(h, m, sec)
        else "%02d:%02d".format(m, sec)
    }

    // ── GPS ───────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    fun obtenerGps(onExito: (String) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            _state.value = InstalacionState.Cargando
            try {
                val client = LocationServices.getFusedLocationProviderClient(ctx)

                // Intenta primero la última ubicación conocida (instantáneo)
                val lastLoc = client.lastLocation.await()
                if (lastLoc != null) {
                    guardarUbicacion(lastLoc)
                    _state.value = InstalacionState.Idle
                    onExito("${"%.5f".format(lastLoc.latitude)}, ${"%.5f".format(lastLoc.longitude)}")
                    return@launch
                }

                // Si no hay última ubicación, pedir con timeout de 5 segundos
                val cancellationToken = com.google.android.gms.tasks.CancellationTokenSource()
                val loc = kotlinx.coroutines.withTimeoutOrNull(5_000) {
                    client.getCurrentLocation(
                        Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                        cancellationToken.token
                    ).await()
                }

                if (loc != null) {
                    guardarUbicacion(loc)
                    _state.value = InstalacionState.Idle
                    onExito("${"%.5f".format(loc.latitude)}, ${"%.5f".format(loc.longitude)}")
                } else {
                    _state.value = InstalacionState.Idle
                    onError("No se pudo obtener ubicación")
                }
            } catch (e: Exception) {
                _state.value = InstalacionState.Idle
                onError("Error GPS: ${e.message}")
            }
        }
    }

    private suspend fun guardarUbicacion(loc: android.location.Location) {
        latitud  = loc.latitude
        longitud = loc.longitude
        try {
            @Suppress("DEPRECATION")
            val dirs = Geocoder(ctx, Locale("es", "PE"))
                .getFromLocation(loc.latitude, loc.longitude, 1)
            direccionGps = dirs?.firstOrNull()?.getAddressLine(0)
        } catch (_: Exception) {}
        _gpsListo.value = true
    }

    // ── Iniciar instalación ───────────────────────────────────
    fun iniciarInstalacion(ordenId: String, onExito: (String) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            _state.value = InstalacionState.Cargando
            when (val r = repo.iniciarInstalacion(ordenId, latitud, longitud, direccionGps)) {
                is Resultado.Exito -> {
                    instalacionId = r.data           // ← ahora r.data ES el String del id
                    _state.value  = InstalacionState.Idle
                    iniciarCronometro()
                    onExito(r.data)
                }
                is Resultado.Error -> {
                    _state.value = InstalacionState.Idle
                    onError(r.mensaje)
                }
            }
        }
    }

    // ── Fotos ─────────────────────────────────────────────────
    fun agregarFoto(tipo: String, ruta: String, nombre: String = "", tamano: String = "", origen: String = "CAMARA") {
        val lista = _fotos.value?.toMutableList() ?: mutableListOf()
        lista.removeAll { it.tipo == tipo }
        lista.add(FotoTomada(tipo, ruta, nombre, tamano, origen, subida = false))
        _fotos.value = lista
        // NO subir aquí — se suben al completar
    }

    fun cantidadFotos() = _fotos.value?.size ?: 0
    fun tieneFotos() = cantidadFotos() >= 1
    fun puedeAgregarFoto() = cantidadFotos() < MAX_FOTOS

    fun eliminarFoto(rutaLocal: String) {
        val lista = _fotos.value?.toMutableList() ?: return
        lista.removeAll { it.rutaLocal == rutaLocal }
        _fotos.value = lista
        // Borrar archivo local
        try { java.io.File(rutaLocal).delete() } catch (_: Exception) {}
    }


    // ── Config ONU ────────────────────────────────────────────
    fun guardarConfig(config: ConfigOnuRequest, onDone: (Boolean, String) -> Unit) {
        val id = instalacionId ?: return onDone(false, "Sin instalación activa")
        viewModelScope.launch {
            when (val r = repo.guardarConfigOnu(id, config)) {
                is Resultado.Exito -> onDone(true, "Configuración guardada")
                is Resultado.Error -> onDone(r.mensaje == MsgResultado.GUARDADO_OFFLINE, r.mensaje)            }
        }
    }

    // ── Completar ─────────────────────────────────────────────
    fun completar(
        observaciones: String?,
        ordenId: String,                    // ← NUEVO
        fotosOk: Boolean,
        onExito: () -> Unit,
        onPendiente: () -> Unit,
        onError: (String) -> Unit
    ) {
        val id = instalacionId ?: return onError("Sin instalación activa")
        viewModelScope.launch {
            _state.value = InstalacionState.Guardando
            detenerCronometro()
            when (val r = repo.completarInstalacion(id, ordenId, observaciones, fotosOk)) {
                is Resultado.Exito -> { _state.value = InstalacionState.Idle; onExito() }
                is Resultado.Error -> {
                    _state.value = InstalacionState.Idle
                    if (r.mensaje == "PENDIENTE_OFFLINE") onPendiente()
                    else onError(r.mensaje)
                }
            }
        }
    }
    fun reset() {
        detenerCronometro()
        inicioCronometro = null          // ← NUEVO
        instalacionId  = null
        latitud        = null
        longitud       = null
        direccionGps   = null
        _orden.value   = null
        _fotos.value   = emptyList()
        _gpsListo.value = false
        _segundos.value = 0
    }

    fun subirFotosPendientes(onDone: (Boolean) -> Unit) {
        val id = instalacionId ?: return onDone(false)
        viewModelScope.launch {
            val fotosList = _fotos.value?.toMutableList() ?: mutableListOf()
            var todasOk = true
            for (foto in fotosList) {
                if (!foto.subida) {
                    val r = repo.subirFoto(id, foto.rutaLocal, foto.tipo)
                    if (r is Resultado.Exito) {
                        foto.subida = true
                    } else {
                        todasOk = false
                    }
                }
            }
            _fotos.value = fotosList
            onDone(todasOk)
        }
    }

    fun subirFotoInmediata(tipo: String, ruta: String) {
        val id = instalacionId ?: return
        viewModelScope.launch {
            val r = repo.subirFoto(id, ruta, tipo)
            if (r is Resultado.Exito) {
                val lista = _fotos.value?.toMutableList() ?: return@launch
                val idx = lista.indexOfFirst { it.tipo == tipo }
                if (idx >= 0) {
                    lista[idx] = lista[idx].copy(subida = true)
                    _fotos.value = lista
                }
            }
        }
    }

    /** Versión suspend: guarda la config y espera a que termine de escribirse. */
    suspend fun guardarConfigSuspend(config: ConfigOnuRequest) {
        val id = instalacionId ?: return
        repo.guardarConfigOnu(id, config)   // online o guarda en config_offline
    }

    /** Versión suspend: sube las fotos pendientes y devuelve si todas subieron. */
    suspend fun subirFotosPendientesSuspend(): Boolean {
        val id = instalacionId ?: return false
        val fotosList = _fotos.value?.toMutableList() ?: mutableListOf()
        var todasOk = true
        for (foto in fotosList) {
            if (!foto.subida) {
                val r = repo.subirFoto(id, foto.rutaLocal, foto.tipo)
                if (r is Resultado.Exito) foto.subida = true else todasOk = false
            }
        }
        _fotos.value = fotosList
        return todasOk
    }

}

// ═══════════════════════════════════════════════════════════════
// INVENTARIO
// ═══════════════════════════════════════════════════════════════

data class InventarioUiState(
    val cargando:        Boolean = true,
    val sincronizando:   Boolean = false,
    val totalAsignados:  Double  = 0.0,
    val totalUtilizados: Double  = 0.0,
    val totalDisponibles:Double  = 0.0,
    val totalSinStock:   Int     = 0,
    val consumoPendiente:Int     = 0,   // items sin sincronizar
    val mensaje:         String? = null
)

sealed class ConsumoState {
    object Idle     : ConsumoState()
    object Guardando: ConsumoState()
    object Exito    : ConsumoState()
    data class Error(val msg: String) : ConsumoState()
}

@HiltViewModel
class InventarioViewModel @Inject constructor(
    private val repo: Repository,
    private val session: SessionDataStore
) : ViewModel() {

    // LiveData de la BD local — funciona offline
    val items = repo.getInventarioItems()
    val onus  = repo.getInventarioOnus()

    // Recojos: equipos recuperados de clientes (vienen del servidor)
    private val _recojos = MutableLiveData<List<com.enetfiber.tecnico.data.remote.RecojoDto>>(emptyList())
    val recojos: LiveData<List<com.enetfiber.tecnico.data.remote.RecojoDto>> = _recojos
    val consumosPendientes = session.tecnicoId
        .filterNotNull()
        .filter { it.isNotBlank() }
        .flatMapLatest { id ->
            kotlinx.coroutines.flow.flow {
                // Primero corregir registros viejos con tecnicoId vacío
                repo.corregirTecnicoIdVacios(id)
                // Luego emitir el LiveData como Flow
                emitAll(repo.getConsumosPendientes(id).asFlow())
            }
        }
        .asLiveData()

    private val _uiState = MutableLiveData(InventarioUiState())
    val uiState: LiveData<InventarioUiState> = _uiState

    private val _consumoState = MutableLiveData<ConsumoState>(ConsumoState.Idle)
    val consumoState: LiveData<ConsumoState> = _consumoState

    val isOnline get() = repo.isOnline()

    // Catálogo offline desde Room (para retiros)
    val catalogo = repo.getCatalogo()

    fun cargarCatalogo() {
        viewModelScope.launch {
            try {
                // Si no hay catálogo local o hay internet, sincronizar
                val count = repo.catalogoDao.count()
                if (count == 0 || repo.isOnline()) {
                    repo.sincronizarCatalogo()
                }
            } catch (e: Exception) {
                android.util.Log.e("InventarioVM", "Error cargando catálogo: ${e.message}")
            }
        }
    }

    private var ultimaSync: Long = 0L
    fun forzarSync() {
        ultimaSync = 0L
        cargarMetricas(sincronizar = true)
    }
    private val MIN_INTERVALO_SYNC = 30_000L

    init { cargarMetricas() }

    /** Recalcula métricas desde Room y opcionalmente sincroniza con el servidor */
    fun cargarMetricas(sincronizar: Boolean = true) {
        viewModelScope.launch {
            // SIEMPRE cargar Room primero — nunca se saltea
            val metricas = repo.getMetricasInventario()
            _uiState.value = _uiState.value?.copy(
                totalAsignados   = metricas.totalAsignados,
                totalUtilizados  = metricas.totalUtilizados,
                totalDisponibles = metricas.totalDisponibles,
                totalSinStock    = metricas.totalSinStock,
                cargando         = false
            )

            // Sincronizar con servidor solo si corresponde
            val ahora = System.currentTimeMillis()
            val debeSync = sincronizar
                    && repo.isOnline()
                    && (ahora - ultimaSync) > MIN_INTERVALO_SYNC

            if (!debeSync) return@launch  // ← sale pero Room ya está mostrado arriba

            ultimaSync = ahora
            _uiState.value = _uiState.value?.copy(sincronizando = true)
            val r = repo.sincronizarInventario()

            if (r is Resultado.Exito) {
                // Recojos
                try {
                    val inv = repo.api.getMiInventario()
                    if (inv.isSuccessful) {
                        val soloEnMano = (inv.body()?.recojos ?: emptyList())
                            .filter { it.estado == "en_mano" }
                        _recojos.postValue(soloEnMano)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("InventarioVM", "Error recojos: ${e.message}")
                }
                // Métricas frescas post-sync
                val frescas = repo.getMetricasInventario()
                _uiState.value = _uiState.value?.copy(
                    totalAsignados   = frescas.totalAsignados,
                    totalUtilizados  = frescas.totalUtilizados,
                    totalDisponibles = frescas.totalDisponibles,
                    totalSinStock    = frescas.totalSinStock,
                    sincronizando    = false
                )
            } else {
                _uiState.value = _uiState.value?.copy(
                    sincronizando = false,
                    mensaje = (r as? Resultado.Error)?.mensaje
                )
            }
        }
    }

    /** Registra material gastado — offline-first */
    fun registrarConsumo(
        items:       List<ConsumoItemRequest>,
        motivo:      String = "SERVICIO",
        descripcion: String? = null,
        ordenId:     String? = null,
        nServicio:   String? = null,
        abonado:     String? = null,
        nombresMap:  Map<Int, String> = emptyMap()
    ) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            _consumoState.value = ConsumoState.Guardando
            val r = repo.registrarConsumo(items, motivo, descripcion, ordenId, nServicio, abonado, nombresMap)
            if (r is Resultado.Exito) {
                // Refrescar métricas locales después del descuento
                val metricas = repo.getMetricasInventario()
                _uiState.value = _uiState.value?.copy(
                    totalAsignados   = metricas.totalAsignados,
                    totalUtilizados  = metricas.totalUtilizados,
                    totalDisponibles = metricas.totalDisponibles,
                    totalSinStock    = metricas.totalSinStock
                )
                _consumoState.value = ConsumoState.Exito
            } else {
                _consumoState.value = ConsumoState.Error((r as Resultado.Error).mensaje)
            }
        }
    }

    fun limpiarMensaje() { _uiState.value = _uiState.value?.copy(mensaje = null) }
    fun resetConsumoState() { _consumoState.value = ConsumoState.Idle }


    fun registrarRetiro(
        items:   List<com.enetfiber.tecnico.data.remote.RetiroItemRequest>,
        ordenId: String? = null,
    ) {
        viewModelScope.launch {
            android.util.Log.d("InventarioVM", "registrarRetiro: ${items.size} items, ordenId=$ordenId")
            items.forEach { i ->
                android.util.Log.d("InventarioVM", "  item: productoId=${i.productoId} tipo=${i.tipoEquipo} pon=${i.codigoPon}")
            }
            val resultado = repo.registrarRetiro(items, ordenId)
            android.util.Log.d("InventarioVM", "registrarRetiro resultado: $resultado")
            // Refrescar métricas locales
            val metricas = repo.getMetricasInventario()
            _uiState.value = _uiState.value?.copy(
                totalAsignados   = metricas.totalAsignados,
                totalUtilizados  = metricas.totalUtilizados,
                totalDisponibles = metricas.totalDisponibles,
                totalSinStock    = metricas.totalSinStock
            )
        }
    }

    // Estado de devolución
    sealed class DevolucionState {
        object Idle      : DevolucionState()
        object Guardando : DevolucionState()
        object Exito     : DevolucionState()
        data class Error(val msg: String) : DevolucionState()
    }

    // En la clase InventarioViewModel agrega:
    private val _devolucionState = MutableLiveData<DevolucionState>(DevolucionState.Idle)
    val devolucionState: LiveData<DevolucionState> = _devolucionState

    private val _devoluciones = MutableLiveData<List<DevolucionDto>>(emptyList())
    val devoluciones: LiveData<List<DevolucionDto>> = _devoluciones

    fun registrarDevolucion(
        items:      List<DevolucionItemRequest>,
        recojos:    List<DevolucionRecojoRequest> = emptyList(),
        onuIds:     List<Int> = emptyList(),
        comentario: String? = null
    ) {
        viewModelScope.launch {
            _devolucionState.value = DevolucionState.Guardando
            val r = repo.registrarDevolucion(items, recojos, onuIds, comentario)
            if (r is Resultado.Exito) {
                cargarDevoluciones()
                // Refrescar recojos — los que se devolvieron ya no están en_mano
                ultimaSync = 0L
                cargarMetricas(sincronizar = true)
                _devolucionState.value = DevolucionState.Exito
            } else {
                _devolucionState.value = DevolucionState.Error((r as Resultado.Error).mensaje)
            }
        }
    }


    fun cargarDevoluciones() {
        viewModelScope.launch {
            val r = repo.getMisDevoluciones()
            if (r is Resultado.Exito) _devoluciones.value = r.data
            // Refrescar inventario también — puede haber cambios de estado
            ultimaSync = 0L
            cargarMetricas(sincronizar = true)
        }
    }

    fun resetDevolucionState() { _devolucionState.value = DevolucionState.Idle }
}