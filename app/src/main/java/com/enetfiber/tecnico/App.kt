package com.enetfiber.tecnico

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class App : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        val constraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
            .build()
        val periodico = androidx.work.PeriodicWorkRequestBuilder<com.enetfiber.tecnico.data.SyncWorker>(
            1, java.util.concurrent.TimeUnit.HOURS
        ).setConstraints(constraints).build()
        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            com.enetfiber.tecnico.data.SyncWorker.NOMBRE_PERIODICO,
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            periodico
        )
    }
}

// ── Tipos de orden ────────────────────────────────────────────
object TipoOrden {
    // Internet
    const val INSTALACION_I       = "INSTALACION_I"
    const val ALTA_SERVICIO_I     = "ALTA_SERVICIO_I"
    const val ATENCION_NOC_I      = "ATENCION_NOC_I"
    const val AVERIA_I            = "AVERIA_I"
    const val BAJA_SERVICIO_I     = "BAJA_SERVICIO_I"
    const val CAMBIO_CONTRASENA_I = "CAMBIO_CONTRASENA_I"
    const val CAMBIO_DOMICILIO_I  = "CAMBIO_DOMICILIO_I"
    const val CAMBIO_EQUIPO_I     = "CAMBIO_EQUIPO_I"
    const val CAMBIO_PLAN_I       = "CAMBIO_PLAN_I"
    const val CAMBIO_TITULAR_I    = "CAMBIO_TITULAR_I"
    const val CORTE_SOLICITUD_I   = "CORTE_SOLICITUD_I"
    const val CORTE_DEUDA_I       = "CORTE_DEUDA_I"
    const val RECONEXION_I        = "RECONEXION_I"
    const val RETIRO_EQUIPO_I     = "RETIRO_EQUIPO_I"
    const val TRASLADO_I          = "TRASLADO_I"

    // Cable
    const val INSTALACION_C       = "INSTALACION_C"
    const val ALTA_SERVICIO_C     = "ALTA_SERVICIO_C"
    const val AVERIA_C            = "AVERIA_C"
    const val CAMBIO_DOMICILIO_C  = "CAMBIO_DOMICILIO_C"
    const val CAMBIO_PLAN_C       = "CAMBIO_PLAN_C"
    const val CAMBIO_TITULAR_C    = "CAMBIO_TITULAR_C"
    const val CORTE_SOLICITUD_C   = "CORTE_SOLICITUD_C"
    const val CORTE_DEUDA_C       = "CORTE_DEUDA_C"
    const val INSTALACION_ANEXO_C = "INSTALACION_ANEXO_C"
    const val MIGRACION_FTTH_C    = "MIGRACION_FTTH_C"
    const val RECONEXION_C        = "RECONEXION_C"
    const val RETIRO_EQUIPO_C     = "RETIRO_EQUIPO_C"
    const val SUPERVISION_C       = "SUPERVISION_C"
    const val TRASLADO_C          = "TRASLADO_C"

    // Dúo (Internet + Cable)
    const val INSTALACION_D       = "INSTALACION_D"
    const val ALTA_SERVICIO_D     = "ALTA_SERVICIO_D"
    const val AVERIA_D            = "AVERIA_D"
    const val BAJA_SERVICIO_D     = "BAJA_SERVICIO_D"
    const val CAMBIO_DOMICILIO_D  = "CAMBIO_DOMICILIO_D"
    const val CAMBIO_EQUIPO_D     = "CAMBIO_EQUIPO_D"
    const val CAMBIO_PLAN_D       = "CAMBIO_PLAN_D"
    const val CAMBIO_TITULAR_D    = "CAMBIO_TITULAR_D"
    const val CORTE_SOLICITUD_D   = "CORTE_SOLICITUD_D"
    const val CORTE_DEUDA_D       = "CORTE_DEUDA_D"
    const val RECONEXION_D        = "RECONEXION_D"
    const val RETIRO_EQUIPO_D     = "RETIRO_EQUIPO_D"
    const val TRASLADO_D          = "TRASLADO_D"

    val INTERNET = listOf(
        INSTALACION_I, ALTA_SERVICIO_I, ATENCION_NOC_I, AVERIA_I,
        BAJA_SERVICIO_I, CAMBIO_CONTRASENA_I, CAMBIO_DOMICILIO_I,
        CAMBIO_EQUIPO_I, CAMBIO_PLAN_I, CAMBIO_TITULAR_I,
        CORTE_SOLICITUD_I, CORTE_DEUDA_I, RECONEXION_I,
        RETIRO_EQUIPO_I, TRASLADO_I
    )

    val CABLE = listOf(
        INSTALACION_C, ALTA_SERVICIO_C, AVERIA_C, CAMBIO_DOMICILIO_C,
        CAMBIO_PLAN_C, CAMBIO_TITULAR_C, CORTE_SOLICITUD_C, CORTE_DEUDA_C,
        INSTALACION_ANEXO_C, MIGRACION_FTTH_C, RECONEXION_C,
        RETIRO_EQUIPO_C, SUPERVISION_C, TRASLADO_C
    )

    val DUO = listOf(
        INSTALACION_D, ALTA_SERVICIO_D, AVERIA_D, BAJA_SERVICIO_D,
        CAMBIO_DOMICILIO_D, CAMBIO_EQUIPO_D, CAMBIO_PLAN_D, CAMBIO_TITULAR_D,
        CORTE_SOLICITUD_D, CORTE_DEUDA_D, RECONEXION_D,
        RETIRO_EQUIPO_D, TRASLADO_D
    )

    fun label(tipo: String) = when (tipo) {
        INSTALACION_I       -> "Instalación Internet"
        ALTA_SERVICIO_I     -> "Alta de Servicio Internet"
        ATENCION_NOC_I      -> "Atención NOC"
        AVERIA_I            -> "Avería Internet"
        BAJA_SERVICIO_I     -> "Baja de Servicio Internet"
        CAMBIO_CONTRASENA_I -> "Cambio de Contraseña"
        CAMBIO_DOMICILIO_I  -> "Cambio de Domicilio Internet"
        CAMBIO_EQUIPO_I     -> "Cambio de Equipo Internet"
        CAMBIO_PLAN_I       -> "Cambio de Plan Internet"
        CAMBIO_TITULAR_I    -> "Cambio de Titular Internet"
        CORTE_SOLICITUD_I   -> "Corte a Solicitud Internet"
        CORTE_DEUDA_I       -> "Corte por Deuda Internet"
        RECONEXION_I        -> "Reconexión Internet"
        RETIRO_EQUIPO_I     -> "Retiro de Equipo Internet"
        TRASLADO_I          -> "Traslado Internet"
        INSTALACION_C       -> "Instalación Cable"
        ALTA_SERVICIO_C     -> "Alta de Servicio Cable"
        AVERIA_C            -> "Avería Cable"
        CAMBIO_DOMICILIO_C  -> "Cambio de Domicilio Cable"
        CAMBIO_PLAN_C       -> "Cambio de Plan Cable"
        CAMBIO_TITULAR_C    -> "Cambio de Titular Cable"
        CORTE_SOLICITUD_C   -> "Corte a Solicitud Cable"
        CORTE_DEUDA_C       -> "Corte por Deuda Cable"
        INSTALACION_ANEXO_C -> "Instalación de Anexo"
        MIGRACION_FTTH_C    -> "Migración FTTH"
        RECONEXION_C        -> "Reconexión Cable"
        RETIRO_EQUIPO_C     -> "Retiro de Equipo Cable"
        SUPERVISION_C       -> "Supervisión Cable"
        TRASLADO_C          -> "Traslado Cable"
        // Dúo
        INSTALACION_D       -> "Instalación Dúo"
        ALTA_SERVICIO_D     -> "Alta de Servicio Dúo"
        AVERIA_D            -> "Avería Dúo"
        BAJA_SERVICIO_D     -> "Baja de Servicio Dúo"
        CAMBIO_DOMICILIO_D  -> "Cambio de Domicilio Dúo"
        CAMBIO_EQUIPO_D     -> "Cambio de Equipo Dúo"
        CAMBIO_PLAN_D       -> "Cambio de Plan Dúo"
        CAMBIO_TITULAR_D    -> "Cambio de Titular Dúo"
        CORTE_SOLICITUD_D   -> "Corte a Solicitud Dúo"
        CORTE_DEUDA_D       -> "Corte por Deuda Dúo"
        RECONEXION_D        -> "Reconexión Dúo"
        RETIRO_EQUIPO_D     -> "Retiro de Equipo Dúo"
        TRASLADO_D          -> "Traslado Dúo"
        else                -> tipo
    }

    fun esInternet(tipo: String) = tipo in INTERNET || tipo in DUO
    fun esCable(tipo: String)    = tipo in CABLE    || tipo in DUO
    fun esDuo(tipo: String)      = tipo in DUO

    // Tipos que van directo a completar sin configurar ONU
    val SIN_CONFIG_ONU = listOf(
        RETIRO_EQUIPO_I, RETIRO_EQUIPO_C, RETIRO_EQUIPO_D,
        CORTE_SOLICITUD_I, CORTE_DEUDA_I,
        CORTE_SOLICITUD_C, CORTE_DEUDA_C,
        CORTE_SOLICITUD_D, CORTE_DEUDA_D,
        CAMBIO_TITULAR_I, CAMBIO_PLAN_I,
        CAMBIO_CONTRASENA_I, ALTA_SERVICIO_I,
        BAJA_SERVICIO_I, ATENCION_NOC_I,
        CAMBIO_TITULAR_D, CAMBIO_PLAN_D,
        ALTA_SERVICIO_D, BAJA_SERVICIO_D
    )

    // Requiere configurar ONU: tipos que tienen componente Internet y no son solo-admin
    fun requiereConfigOnu(tipo: String) = tipo !in SIN_CONFIG_ONU && esInternet(tipo)
}

object EstadoOrden {
    const val PENDIENTE_NOC     = "PENDIENTE_NOC"
    const val PENDIENTE_TECNICO = "PENDIENTE_TECNICO"
    const val ACEPTADA          = "ACEPTADA"
    const val EN_PROCESO        = "EN_PROCESO"
    const val COMPLETADA        = "COMPLETADA"
    const val CANCELADA         = "CANCELADA"
    const val REPROGRAMADA      = "REPROGRAMADA"
}

object TipoFoto {
    const val FOTO_1            = "FOTO_1"
    const val FOTO_2            = "FOTO_2"
    const val FOTO_3            = "FOTO_3"
    const val CAJA_NAP          = "CAJA_NAP"
    const val POTENCIA          = "POTENCIA"
    const val INSTALACION_FINAL = "INSTALACION_FINAL"
    const val OTROS             = "OTROS"

    val VALIDOS = listOf(
        FOTO_1, FOTO_2, FOTO_3, CAJA_NAP, POTENCIA, INSTALACION_FINAL, OTROS
    )
}