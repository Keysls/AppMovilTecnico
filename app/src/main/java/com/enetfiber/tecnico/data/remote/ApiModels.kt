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

// Ubicación GPS del contrato (casa del cliente)
data class ContratoRefDto(
    val numero:   String,
    val latitud:  Double?,
    val longitud: Double?,
    val precinto: String? = null
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
    val instalacion:      InstalacionResumenDto?,
    val contratoRef:      ContratoRefDto? = null
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
data class UbicacionRequest(
    val latitud:  Double,
    val longitud: Double
)

data class PrecintoRequest(
    val precinto: String?
)

data class InventarioMetricas(
    val totalAsignados:   Double,
    val totalUtilizados:  Double,
    val totalDisponibles: Double,
    val totalSinStock:    Int
)

data class InventarioItemDto(
    val productoId:       Int,
    val nombre:           String,
    val codigo:           String,
    val categoria:        String,
    val unidad:           String,
    val asignado:         Double,
    val utilizado:        Double,
    val disponible:       Double,
    val sinStock:         Boolean,
    val fecha:            String?,
    // Campos para productos medibles (ej: rollos de fibra)
    val esMedible:        Boolean = false,
    val metrosPorUnidad:  Int?    = null,
    val disponibleMetros: Double? = null,
    val asignadoMetros:   Double? = null,
    val utilizadoMetros:  Double? = null
)

data class InventarioOnuDto(
    val id:        Int,
    val codigoPon: String?,
    val producto:  String,
    val codigo:    String?
)

data class ConsumoHistorialDto(
    val productoId:  Int,
    val nombre:      String,
    val cantidad:    Double,
    val motivo:      String?,
    val descripcion: String?,
    val fecha:       String?,
    // Datos de la orden asociada (null si fue consumo manual)
    val nServicio:   String? = null,
    val abonado:     String? = null,
    val contrato:    String? = null
)

data class EntregaHistorialDto(
    val nombre:   String,
    val codigo:   String?,
    val cantidad: Int,
    val fecha:    String?
)

data class MiInventarioResponse(
    val tecnicoId:         String,
    val metricas:          InventarioMetricas,
    val items:             List<InventarioItemDto>,
    val onus:              List<InventarioOnuDto>,
    val historialConsumos: List<ConsumoHistorialDto>,
    val historialEntregas: List<EntregaHistorialDto>,
    val recojos:           List<RecojoDto> = emptyList()
)

// Request para registrar material gastado
data class ConsumoItemRequest(
    val productoId: Int,
    val cantidad:   Double,
    val recojoId:   Int? = null   // ← NUEVO: si viene de un recojo
)

data class DevolucionRecojoRequest(
    val recojoId: Int
)

data class RegistrarConsumoRequest(
    val items:       List<ConsumoItemRequest>,
    val motivo:      String = "SERVICIO",
    val descripcion: String? = null,
    val ordenId:     String? = null
)

data class RegistrarConsumoResponse(
    val ok:          Boolean,
    val registrados: Int
)

// Catálogo global para retiros
data class CatalogoProductoDto(
    val id:        Int,
    val nombre:    String,
    val codigo:    String?,
    val categoria: String?,
    val unidad:    String?,
)

// Un item de retiro = un equipo individual (una card en la UI)
data class RetiroItemRequest(
    val productoId:  Int?,
    val tipoEquipo:  String  = "EQUIPO",
    val codigoPon:   String? = null,   // solo para ONUs
    val cliente:     String? = null,
)

data class RegistrarRetiroRequest(
    val items:   List<RetiroItemRequest>,
    val ordenId: String? = null,
)

// Recojo devuelto por mi-inventario
data class RecojoDto(
    val id:             Int,
    val tipoEquipo:     String,
    val codigoPon:      String?,
    val productoId:     Int?,
    val nombreProducto: String?,
    val estado:         String,
    val cliente:        String?,
    val comentario:     String?,
    val grupoOrden:     String?,
    val nServicio:      String? = null,  // ← NUEVO
    val abonado:        String? = null,  // ← NUEVO
    val contrato:       String? = null,  // ← NUEVO
    val fecha:          String?,
)

data class RegistrarRetiroResponse(
    val ok:          Boolean,
    val registrados: Int
)

data class CambiarPasswordRequest(
    val passwordActual: String,
    val passwordNueva:  String
)

data class DevolucionItemRequest(
    val productoId: Int,
    val cantidad:   Double
)

data class RegistrarDevolucionRequest(
    val items:      List<DevolucionItemRequest>,
    val recojos:    List<DevolucionRecojoRequest> = emptyList(),  // ← NUEVO
    val comentario: String? = null
)

data class DevolucionDetalleDto(
    val productoId: Int,
    val nombre:     String,
    val unidad:     String?,
    val cantidad:   Double
)

data class DevolucionRecojoDto(
    val id:             Int,
    val tipoEquipo:     String,
    val codigoPon:      String?,
    val estado:         String,
    val nombreProducto: String? = null,
    val contrato:       String? = null,  // ← reemplaza nServicio
    val abonado:        String? = null,
)

data class DevolucionDto(
    val id:            Int,
    val estado:        String,
    val comentario:    String?,
    val fecha:         String?,
    val fechaRevision: String?,
    val detalles:      List<DevolucionDetalleDto>,
    val recojos:       List<DevolucionRecojoDto> = emptyList()  // ← NUEVO
)

data class RegistrarDevolucionResponse(
    val ok:          Boolean,
    val devolucionId: Int
)