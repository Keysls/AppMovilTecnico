package com.enetfiber.tecnico.ui

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import androidx.lifecycle.*
import com.enetfiber.tecnico.TipoOrden
import com.enetfiber.tecnico.data.*
import com.enetfiber.tecnico.data.local.OrdenEntity
import com.enetfiber.tecnico.data.local.SessionDataStore
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
            if (reset) {
                _paginaActual = 0
                _hayMas = true
            }

            _cargandoMas.value = true

            val lista = repo.getCompletadasFiltradas(
                tipo     = _tipoFiltro.value ?: "TODOS",
                busqueda = _busqueda.value?.trim().orEmpty(),
                limit    = PAGE_SIZE,
                offset   = _paginaActual * PAGE_SIZE
            )

            _completadas.value = if (reset) lista
            else (_completadas.value ?: emptyList()) + lista

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

data class FotoTomada(
    val tipo:      String,
    val rutaLocal: String,
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
    fun agregarFoto(tipo: String, ruta: String) {
        val lista = _fotos.value?.toMutableList() ?: mutableListOf()
        lista.removeAll { it.tipo == tipo }
        lista.add(FotoTomada(tipo, ruta, subida = false))
        _fotos.value = lista
        // NO subir aquí — se suben al completar
    }

    fun cantidadFotos() = _fotos.value?.size ?: 0
    fun tieneFotos() = cantidadFotos() >= 1


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