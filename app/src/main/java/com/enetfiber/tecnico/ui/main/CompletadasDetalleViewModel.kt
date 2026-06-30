package com.enetfiber.tecnico.ui.main

import androidx.lifecycle.*
import com.enetfiber.tecnico.data.Repository
import com.enetfiber.tecnico.data.Resultado
import com.enetfiber.tecnico.data.remote.ConfigOnuDto
import com.enetfiber.tecnico.data.remote.ConsumoHistorialDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetalleCompletadaUi(
    val cargando:   Boolean = false,
    val materiales: List<ConsumoHistorialDto> = emptyList(),
    val config:     ConfigOnuDto? = null,
    val error:      String? = null
)

/**
 * ViewModel dedicado a la expansión inline de "Completadas" — trae materiales
 * utilizados y configuración WAN/ONU de UNA orden específica bajo demanda
 * (solo cuando el técnico expande esa fila), sin tocar el flujo offline-first
 * de InventarioViewModel/InstalacionViewModel que ya gestionan otras pantallas.
 */
@HiltViewModel
class CompletadasDetalleViewModel @Inject constructor(
    private val repo: Repository
) : ViewModel() {

    // Cache en memoria por ordenId — evita re-pedir si el técnico colapsa/expande la misma fila
    private val cache = mutableMapOf<String, DetalleCompletadaUi>()

    private val _detalles = MutableLiveData<Map<String, DetalleCompletadaUi>>(emptyMap())
    val detalles: LiveData<Map<String, DetalleCompletadaUi>> = _detalles

    fun cargarDetalle(ordenId: String, instalacionId: String?, nServicio: String) {
        if (cache.containsKey(ordenId)) return // ya cargado, no repetir

        cache[ordenId] = DetalleCompletadaUi(cargando = true)
        _detalles.value = cache.toMap()

        viewModelScope.launch {
            val materialesDeferred = repo.getHistorialConsumosPorOrden(nServicio)
            val configDeferred = if (instalacionId != null) {
                repo.obtenerInstalacion(instalacionId)
            } else null

            val materiales = (materialesDeferred as? Resultado.Exito)?.data ?: emptyList()
            val config = (configDeferred as? Resultado.Exito)?.data?.configOnu

            val error = if (materialesDeferred is Resultado.Error && configDeferred is Resultado.Error?) {
                materialesDeferred.mensaje
            } else null

            cache[ordenId] = DetalleCompletadaUi(
                cargando   = false,
                materiales = materiales,
                config     = config,
                error      = error
            )
            _detalles.value = cache.toMap()
        }
    }
}