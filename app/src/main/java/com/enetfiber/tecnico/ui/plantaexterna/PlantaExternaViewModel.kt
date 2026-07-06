package com.enetfiber.tecnico.ui.plantaexterna

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enetfiber.tecnico.data.remote.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class PlantaExternaState {
    object Idle : PlantaExternaState()
    object Cargando : PlantaExternaState()
    data class Exito(val mensaje: String) : PlantaExternaState()
    data class Error(val msg: String) : PlantaExternaState()
}

@HiltViewModel
class PlantaExternaViewModel @Inject constructor(
    private val api: ApiService
) : ViewModel() {

    private val _trabajos = MutableLiveData<List<TrabajoPEDto>>(emptyList())
    val trabajos: LiveData<List<TrabajoPEDto>> = _trabajos

    private val _trabajoDetalle = MutableLiveData<TrabajoPEDto?>()
    val trabajoDetalle: LiveData<TrabajoPEDto?> = _trabajoDetalle

    private val _state = MutableLiveData<PlantaExternaState>(PlantaExternaState.Idle)
    val state: LiveData<PlantaExternaState> = _state

    private val _cargando = MutableLiveData(false)
    val cargando: LiveData<Boolean> = _cargando

    // ── Filtros activos ───────────────────────────────────────
    var filtroTipo: String? = null
    var filtroEstado: String? = null

    fun cargarTrabajos() {
        viewModelScope.launch {
            _cargando.value = true
            try {
                val resp = api.getTrabajosPE(
                    tipo   = filtroTipo,
                    estado = filtroEstado
                )
                if (resp.isSuccessful) {
                    _trabajos.value = resp.body() ?: emptyList()
                } else {
                    _state.value = PlantaExternaState.Error("Error al cargar trabajos")
                }
            } catch (e: Exception) {
                _state.value = PlantaExternaState.Error("Sin conexión")
            } finally {
                _cargando.value = false
            }
        }
    }

    fun cargarDetalle(trabajoId: String) {
        viewModelScope.launch {
            _cargando.value = true
            try {
                val resp = api.getTrabajoPE(trabajoId)
                if (resp.isSuccessful) {
                    _trabajoDetalle.value = resp.body()
                } else {
                    _state.value = PlantaExternaState.Error("No se pudo cargar el trabajo")
                }
            } catch (e: Exception) {
                _state.value = PlantaExternaState.Error("Sin conexión")
            } finally {
                _cargando.value = false
            }
        }
    }

    fun crearTrabajo(
        tipo: String,
        nombre: String,
        descripcion: String?,
        ubicacion: String?,
        fechaInicio: String?,
        tecnicoIds: List<String>
    ) {
        viewModelScope.launch {
            _state.value = PlantaExternaState.Cargando
            try {
                val resp = api.crearTrabajoPE(
                    CrearTrabajoPERequest(
                        tipo        = tipo,
                        nombre      = nombre,
                        descripcion = descripcion,
                        ubicacion   = ubicacion,
                        fechaInicio = fechaInicio,
                        tecnicoIds  = tecnicoIds
                    )
                )
                if (resp.isSuccessful) {
                    _state.value = PlantaExternaState.Exito("Trabajo creado")
                    cargarTrabajos()
                } else {
                    _state.value = PlantaExternaState.Error("Error al crear trabajo")
                }
            } catch (e: Exception) {
                _state.value = PlantaExternaState.Error("Sin conexión")
            }
        }
    }

    fun agregarMaterial(
        trabajoId: String,
        items: List<MaterialPEItem>,
        comentario: String?
    ) {
        viewModelScope.launch {
            _state.value = PlantaExternaState.Cargando
            try {
                val resp = api.agregarMaterialPE(
                    trabajoId,
                    AgregarMaterialPERequest(items = items, comentario = comentario)
                )
                if (resp.isSuccessful) {
                    _state.value = PlantaExternaState.Exito("Material registrado")
                    cargarDetalle(trabajoId)
                } else {
                    _state.value = PlantaExternaState.Error("Error al registrar material")
                }
            } catch (e: Exception) {
                _state.value = PlantaExternaState.Error("Sin conexión")
            }
        }
    }

    fun completarTrabajo(trabajoId: String) {
        viewModelScope.launch {
            _state.value = PlantaExternaState.Cargando
            try {
                val resp = api.completarTrabajoPE(trabajoId)
                if (resp.isSuccessful) {
                    _state.value = PlantaExternaState.Exito("Trabajo completado")
                    cargarDetalle(trabajoId)
                    cargarTrabajos()
                } else {
                    _state.value = PlantaExternaState.Error("Error al completar")
                }
            } catch (e: Exception) {
                _state.value = PlantaExternaState.Error("Sin conexión")
            }
        }
    }

    fun resetState() {
        _state.value = PlantaExternaState.Idle
    }
}