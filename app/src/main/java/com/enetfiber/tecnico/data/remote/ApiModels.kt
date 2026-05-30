package com.enetfiber.tecnico.data.remote

data class LoginRequest(
    val email:       String,
    val password:    String,
    val dispositivo: String = "App Android Técnico"
)

data class LoginResponse(
    val token:   String,
    val usuario: UsuarioDto
)

data class UsuarioDto(
    val id:       String,
    val nombre:   String,
    val apellido: String,
    val email:    String,
    val rol:      String,
    val telefono: String?,
    val activo:   Boolean?,   // ✅ agregado
    val tecnico:  TecnicoDto?
)

data class TecnicoDto(
    val id:           String,
    val dni:          String,
    val zonaAsignada: String?,
    val vehiculo:     String?,
    val _count:       TecnicoCountDto?   // ✅ agregado
)

// ✅ Nuevo data class para el contador
data class TecnicoCountDto(
    val ordenes: Int
)

data class OrdenDto(
    val id:               String,
    val nServicio:        String,
    val tipoOrden:        String,
    val estado:           String,
    val fechaServicio:    String?,
    val contrato:         String?,
    val abonado:          String,
    val dni:              String?,
    val direccion:        String,
    val referencia:       String?,
    val sector:           String?,
    val celular:          String,
    val observacion:      String?,
    val ipWan:            String?,
    val mascara:          String?,
    val gateway:          String?,
    val fechaAceptacion:  String?,
    val fechaInicio:      String?,
    val fechaFin:         String?,
    val tiempoInstalacion:Int?,
    val tecnico:          TecnicoOrdenDto?,
    val instalacion:      InstalacionResumenDto?
)

data class TecnicoOrdenDto(
    val id:      String,
    val usuario: UsuarioResumenDto
)

data class UsuarioResumenDto(
    val nombre:   String,
    val apellido: String,
    val telefono: String?
)

data class InstalacionResumenDto(
    val id:        String,
    val completada:Boolean
)

data class OrdenesResponse(
    val data: List<OrdenDto>,
    val meta: MetaDto
)

data class MetaDto(
    val total: Int,
    val page:  Int,
    val limit: Int,
    val pages: Int
)

data class IniciarInstalacionRequest(
    val latitud:       Double?,
    val longitud:      Double?,
    val direccionGps:  String?,
    val instalacionId: String? = null    // ← Opción B: la app manda su UUID
)

data class IniciarInstalacionResponse(
    val instalacion: InstalacionDto,   // ← es InstalacionDto, no InstalacionResumenDto
    val mensaje:     String
)

data class InstalacionDto(
    val id:         String,
    val ordenId:    String,
    val latitud:    Double?,
    val longitud:   Double?,
    val completada: Boolean,
    val configOnu:  ConfigOnuDto?,
    val fotos:      List<FotoDto>?
)

data class ConfigOnuRequest(
    val ssid:            String?,
    val ssidPassword:    String?,
    val ssid5ghz:        String?,
    val ssidPassword5ghz:String?,
    val serialNumber:    String?,
    val mac:             String?,
    val potenciaRx:      Float?,
    val potenciaTx:      Float?,
    val temperatura:     Float?,
    val estado:          String?,
    val pppoeUser:       String?,
    val pppoePassword:   String?,

    val vlan:            String? = null,
    val offline:         Boolean = false
)

data class ConfigOnuDto(
    val id:              String,
    val ipWan:           String?,
    val mascara:         String?,
    val gateway:         String?,
    val ssid:            String?,
    val ssidPassword:    String?,
    val ssid5ghz:        String?,
    val ssidPassword5ghz:String?,
    val serialNumber:    String?,
    val potenciaRx:      Float?,
    val potenciaTx:      Float?,
    val temperatura:     Float?,
    val estado:          String?,
    val pppoeUser:       String?,
    val pppoePassword:   String?,
    val wanPrecargada:   Boolean
)

data class FotoDto(
    val id:   String,
    val tipo: String,
    val url:  String
)

data class ModeloOnuDto(
    val id:          String,
    val marca:       String,
    val modelo:      String,
    val ipDefault:   String,
    val userDefault: String,
    val passDefault: String
)

data class CompletarRequest(
    val observaciones: String?,
    val fechaFin:      String? = null   // ISO-8601, null = el backend usa "ahora"
)

data class MaterialUsado(
    val nombre:   String,
    val cantidad: Int
)

data class AceptarRequest(
    val fechaAceptacion: String?   // ISO-8601, null = el backend usa "ahora"
)