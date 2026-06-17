package com.enetfiber.tecnico.data.remote

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @POST("auth/logout")
    suspend fun logout(): Response<Unit>

    @PATCH("auth/cambiar-password")
    suspend fun cambiarPassword(@Body request: CambiarPasswordRequest): Response<Unit>

    @GET("ordenes")
    suspend fun getOrdenes(
        @Query("limit") limit: Int = 50
    ): Response<OrdenesResponse>

    @GET("ordenes/{id}")
    suspend fun getOrden(@Path("id") id: String): Response<OrdenDto>

    @PATCH("ordenes/{id}/aceptar")
    suspend fun aceptarOrden(
        @Path("id") id: String,
        @Body request: AceptarRequest
    ): Response<Unit>

    @POST("instalaciones/iniciar/{ordenId}")
    suspend fun iniciarInstalacion(
        @Path("ordenId") ordenId: String,
        @Body request: IniciarInstalacionRequest
    ): Response<IniciarInstalacionResponse>

    @Multipart
    @POST("instalaciones/{id}/fotos")
    suspend fun subirFoto(
        @Path("id") instalacionId: String,
        @Part foto: MultipartBody.Part,
        @Part("tipos") tipos: RequestBody  // ← "tipos" en plural
    ): Response<Unit>

    @POST("instalaciones/{id}/config-onu")
    suspend fun guardarConfigOnu(
        @Path("id") instalacionId: String,
        @Body request: ConfigOnuRequest
    ): Response<ConfigOnuDto>

    @POST("instalaciones/{id}/completar")
    suspend fun completarInstalacion(
        @Path("id") instalacionId: String,
        @Body request: CompletarRequest
    ): Response<InstalacionDto>

    @GET("instalaciones/{id}")
    suspend fun obtenerInstalacion(
        @Path("id") instalacionId: String
    ): Response<InstalacionDto>

    @POST("instalaciones/{id}/autorizar-olt")
    suspend fun autorizarOlt(
        @Path("id") instalacionId: String,
        @Body request: AutorizarOltRequest
    ): Response<AutorizarOltResponse>


    @PATCH("contratos/{numero}/ubicacion")
    suspend fun actualizarUbicacionContrato(
        @Path("numero") numero: String,
        @Body request: UbicacionRequest
    ): Response<Unit>

    @PATCH("contratos/{numero}/precinto")
    suspend fun actualizarPrecinto(
        @Path("numero") numero: String,
        @Body request: PrecintoRequest
    ): Response<Unit>

    @GET("stock/mi-inventario")
    suspend fun getMiInventario(): Response<MiInventarioResponse>

    @POST("stock/mi-consumo")
    suspend fun registrarConsumo(
        @Body request: RegistrarConsumoRequest
    ): Response<RegistrarConsumoResponse>

    @GET("stock/catalogo")
    suspend fun getCatalogoTecnico(): retrofit2.Response<List<com.enetfiber.tecnico.data.remote.CatalogoProductoDto>>

    @GET("tipos-orden")
    suspend fun getTiposOrden(): retrofit2.Response<TiposOrdenResponse>

    @POST("stock/mi-retiro")
    suspend fun registrarRetiro(
        @Body request: RegistrarRetiroRequest
    ): Response<RegistrarRetiroResponse>

    @POST("stock/mi-devolucion")
    suspend fun registrarDevolucion(
        @Body request: RegistrarDevolucionRequest
    ): Response<RegistrarDevolucionResponse>

    @GET("stock/mis-devoluciones")
    suspend fun getMisDevoluciones(): Response<List<DevolucionDto>>

}