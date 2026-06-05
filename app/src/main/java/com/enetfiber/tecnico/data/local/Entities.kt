package com.enetfiber.tecnico.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ordenes")
data class OrdenEntity(
    @PrimaryKey val id:              String,
    val nServicio:                   String,
    val tipoOrden:                   String,
    val estado:                      String,
    val fechaServicio:               String?,
    val abonado:                     String,
    val dni:                         String?,
    val direccion:                   String,
    val referencia:                  String?,
    val sector:                      String?,
    val celular:                     String,
    val observacion:                 String?,
    val contrato:                    String?,
    val ipWan:                       String?,
    val mascara:                     String?,
    val gateway:                     String?,
    val fechaAceptacion:             String?,
    val tiempoInstalacion:           Int?,
    val instalacionId:               String?,
    val instalacionCompletada:       Boolean = false,
    val cachedAt:                    Long = System.currentTimeMillis()
)

@Entity(tableName = "config_offline")
data class ConfigOfflineEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val instalacionId:    String,
    val ssid:             String?,
    val ssidPassword:     String?,
    val ssid5ghz:         String?,
    val ssidPassword5ghz: String?,
    val serialNumber:     String?,
    val potenciaRx:       Float?,
    val potenciaTx:       Float?,
    val temperatura:      Float?,
    val pppoeUser:        String?,
    val pppoePassword:    String?,
    val sincronizado:     Boolean = false,
    val creadoEn:         Long = System.currentTimeMillis()
)

@Entity(tableName = "fotos_pendientes")
data class FotoPendienteEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val instalacionId: String,
    val tipo:          String,
    val rutaLocal:     String,
    val sincronizado:  Boolean = false,
    val creadoEn:      Long = System.currentTimeMillis()
)

@Entity(tableName = "completar_pendiente")
data class CompletarPendienteEntity(
    @PrimaryKey val instalacionId: String,
    val observaciones: String?,
    val fechaFin:      Long = System.currentTimeMillis(),
    val sincronizado:  Boolean = false,
    val creadoEn:      Long = System.currentTimeMillis()
)

@Entity(tableName = "iniciar_pendiente")
data class IniciarPendienteEntity(
    @PrimaryKey val instalacionId: String,
    val ordenId:      String,
    val latitud:      Double?,
    val longitud:     Double?,
    val direccionGps: String?,
    val sincronizado: Boolean = false,
    val creadoEn:     Long = System.currentTimeMillis()
)

@Entity(tableName = "aceptar_pendiente")
data class AceptarPendienteEntity(
    @PrimaryKey val ordenId: String,
    val sincronizado: Boolean = false,
    val creadoEn:     Long = System.currentTimeMillis()
)

// ── INVENTARIO ────────────────────────────────────────────────

/**
 * Caché local del inventario asignado al técnico.
 * Se refresca desde GET /api/stock/mi-inventario cuando hay señal.
 */
@Entity(tableName = "inventario_items")
data class InventarioItemEntity(
    @PrimaryKey val productoId: Int,
    val nombre:      String,
    val codigo:      String,
    val categoria:   String,
    val unidad:      String,
    val asignado:    Double,   // total asignado por el admin
    val utilizado:   Double,   // consumido según el servidor
    val disponible:  Double,   // asignado - utilizado
    val sinStock:    Boolean,
    val cachedAt:    Long = System.currentTimeMillis()
)

/**
 * ONUs asignadas al técnico (caché local).
 */
@Entity(tableName = "inventario_onus")
data class InventarioOnuEntity(
    @PrimaryKey val id: Int,
    val codigoPon: String?,
    val producto:  String,
    val codigo:    String?,
    val cachedAt:  Long = System.currentTimeMillis()
)

/**
 * Material gastado pendiente de sincronizar con el servidor.
 * Permite registrar consumo sin conexión.
 */
@Entity(tableName = "consumo_pendiente")
data class ConsumoPendienteEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val productoId:  Int,
    val tecnicoId:   String  = "",
    val nombre:      String,   // guardamos el nombre para mostrar offline
    val cantidad:    Double,
    val motivo:      String = "SERVICIO",
    val descripcion: String?,  // ej: "Orden: ORD-001" o texto libre
    val ordenId:     String?,  // opcional, si viene de una orden
    val sincronizado: Boolean = false,
    val creadoEn:    Long = System.currentTimeMillis()
)