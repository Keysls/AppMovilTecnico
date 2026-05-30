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
    val fechaFin:      Long = System.currentTimeMillis(),   // ← NUEVO: momento real de completado
    val sincronizado:  Boolean = false,
    val creadoEn:      Long = System.currentTimeMillis()
)

@Entity(tableName = "iniciar_pendiente")
data class IniciarPendienteEntity(
    @PrimaryKey val instalacionId: String,   // el UUID que genera la app
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