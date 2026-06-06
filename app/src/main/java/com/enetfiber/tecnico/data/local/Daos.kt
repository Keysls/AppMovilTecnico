package com.enetfiber.tecnico.data.local

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface OrdenDao {

    @Query("""
        SELECT * FROM ordenes 
        WHERE estado IN ('PENDIENTE_TECNICO','ACEPTADA','EN_PROCESO')
        AND tipoOrden IN (:tipos)
        ORDER BY cachedAt DESC
    """)
    fun getPendientesInternet(tipos: List<String>): LiveData<List<OrdenEntity>>

    @Query("""
        SELECT * FROM ordenes 
        WHERE estado IN ('PENDIENTE_TECNICO','ACEPTADA','EN_PROCESO')
        AND tipoOrden IN (:tipos)
        ORDER BY cachedAt DESC
    """)
    fun getPendientesCable(tipos: List<String>): LiveData<List<OrdenEntity>>

    @Query("SELECT * FROM ordenes WHERE estado = 'COMPLETADA' ORDER BY cachedAt DESC")
    fun getCompletadas(): LiveData<List<OrdenEntity>>

    @Query("SELECT * FROM ordenes WHERE id = :id")
    suspend fun getById(id: String): OrdenEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(ordenes: List<OrdenEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(orden: OrdenEntity)

    @Query("UPDATE ordenes SET estado = :estado WHERE id = :id")
    suspend fun updateEstado(id: String, estado: String)

    @Query("UPDATE ordenes SET instalacionId = :instId WHERE id = :ordenId")
    suspend fun updateInstalacionId(ordenId: String, instId: String)

    @Query("""
        SELECT COUNT(*) FROM ordenes 
        WHERE estado IN ('PENDIENTE_TECNICO','ACEPTADA','EN_PROCESO')
        AND tipoOrden IN (:tipos)
    """)
    suspend fun countPendientesInternet(tipos: List<String>): Int

    @Query("""
        SELECT COUNT(*) FROM ordenes 
        WHERE estado IN ('PENDIENTE_TECNICO','ACEPTADA','EN_PROCESO')
        AND tipoOrden IN (:tipos)
    """)
    suspend fun countPendientesCable(tipos: List<String>): Int

    @Query("SELECT COUNT(*) FROM ordenes WHERE estado = 'COMPLETADA'")
    suspend fun countCompletadas(): Int

    @Query("DELETE FROM ordenes")
    suspend fun deleteAll()

    @Query("""
        DELETE FROM ordenes 
        WHERE id NOT IN (:idsRemotos)
        AND estado != 'EN_PROCESO'
        AND (instalacionId IS NULL OR instalacionId NOT IN (:idsProtegidos))
    """)
    suspend fun deleteObsoletas(idsRemotos: List<String>, idsProtegidos: List<String>)

    @Query("""
        SELECT * FROM ordenes 
        WHERE estado = 'COMPLETADA'
        AND (:filtrarTipo = 0 OR tipoOrden IN (:tipos))
        AND (:busqueda = '' OR 
             abonado LIKE '%' || :busqueda || '%' OR 
             nServicio LIKE '%' || :busqueda || '%' OR
             direccion LIKE '%' || :busqueda || '%')
        ORDER BY cachedAt DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getCompletadasFiltradas(
        filtrarTipo: Boolean,
        tipos: List<String>,
        busqueda: String,
        limit: Int,
        offset: Int
    ): List<OrdenEntity>

    @Query("""
        SELECT COUNT(*) FROM ordenes 
        WHERE estado = 'COMPLETADA'
        AND (:filtrarTipo = 0 OR tipoOrden IN (:tipos))
        AND (:busqueda = '' OR 
             abonado LIKE '%' || :busqueda || '%' OR 
             nServicio LIKE '%' || :busqueda || '%' OR
             direccion LIKE '%' || :busqueda || '%')
    """)
    suspend fun countCompletadasFiltradas(
        filtrarTipo: Boolean,
        tipos: List<String>,
        busqueda: String
    ): Int

    @Query("""
        SELECT id FROM ordenes WHERE instalacionId IN (
            SELECT instalacionId FROM completar_pendiente WHERE sincronizado = 0
        )
    """)
    suspend fun ordenIdsConCompletarPendiente(): List<String>
}

@Dao
interface ConfigOfflineDao {

    @Insert
    suspend fun insert(config: ConfigOfflineEntity)

    @Query("SELECT * FROM config_offline WHERE instalacionId = :id AND sincronizado = 0 LIMIT 1")
    suspend fun getPendiente(id: String): ConfigOfflineEntity?

    @Query("UPDATE config_offline SET sincronizado = 1 WHERE instalacionId = :id")
    suspend fun marcarSincronizado(id: String)

    @Query("SELECT DISTINCT instalacionId FROM config_offline WHERE sincronizado = 0")
    suspend fun instalacionIdsPendientes(): List<String>

    @Query("SELECT * FROM config_offline WHERE sincronizado = 0")
    suspend fun getTodasPendientes(): List<ConfigOfflineEntity>
}

@Dao
interface FotoPendienteDao {

    @Insert
    suspend fun insert(foto: FotoPendienteEntity)

    @Query("SELECT * FROM fotos_pendientes WHERE instalacionId = :id AND sincronizado = 0")
    suspend fun getPendientes(id: String): List<FotoPendienteEntity>

    @Query("UPDATE fotos_pendientes SET sincronizado = 1 WHERE id = :id")
    suspend fun marcarSincronizado(id: Int)

    @Query("SELECT DISTINCT instalacionId FROM fotos_pendientes WHERE sincronizado = 0")
    suspend fun instalacionIdsPendientes(): List<String>

    @Query("SELECT * FROM fotos_pendientes WHERE sincronizado = 0")
    suspend fun getTodasPendientes(): List<FotoPendienteEntity>
}

@Dao
interface CompletarPendienteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: CompletarPendienteEntity)

    @Query("SELECT * FROM completar_pendiente WHERE sincronizado = 0 ORDER BY creadoEn ASC")
    suspend fun getPendientes(): List<CompletarPendienteEntity>

    @Query("UPDATE completar_pendiente SET sincronizado = 1 WHERE instalacionId = :id")
    suspend fun marcarSincronizado(id: String)

    @Query("SELECT COUNT(*) FROM completar_pendiente WHERE sincronizado = 0")
    suspend fun countPendientes(): Int
}

@Dao
interface IniciarPendienteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: IniciarPendienteEntity)

    @Query("SELECT * FROM iniciar_pendiente WHERE sincronizado = 0 ORDER BY creadoEn ASC")
    suspend fun getPendientes(): List<IniciarPendienteEntity>

    @Query("UPDATE iniciar_pendiente SET sincronizado = 1 WHERE instalacionId = :id")
    suspend fun marcarSincronizado(id: String)

    @Query("SELECT * FROM iniciar_pendiente WHERE instalacionId = :id LIMIT 1")
    suspend fun getPorInstalacion(id: String): IniciarPendienteEntity?
}

@Dao
interface AceptarPendienteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: AceptarPendienteEntity)

    @Query("SELECT * FROM aceptar_pendiente WHERE sincronizado = 0 ORDER BY creadoEn ASC")
    suspend fun getPendientes(): List<AceptarPendienteEntity>

    @Query("UPDATE aceptar_pendiente SET sincronizado = 1 WHERE ordenId = :id")
    suspend fun marcarSincronizado(id: String)
}

// ── INVENTARIO DAOs ───────────────────────────────────────────

@Dao
interface InventarioDao {

    // Items asignados
    @Query("SELECT * FROM inventario_items ORDER BY nombre ASC")
    fun getItems(): LiveData<List<InventarioItemEntity>>

    @Query("SELECT * FROM inventario_items ORDER BY nombre ASC")
    suspend fun getItemsOnce(): List<InventarioItemEntity>

    @Query("SELECT * FROM inventario_items WHERE sinStock = 1")
    suspend fun getSinStock(): List<InventarioItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<InventarioItemEntity>)

    @Query("DELETE FROM inventario_items")
    suspend fun clearItems()

    // ONUs
    @Query("SELECT * FROM inventario_onus ORDER BY codigoPon ASC")
    fun getOnus(): LiveData<List<InventarioOnuEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOnus(onus: List<InventarioOnuEntity>)

    @Query("DELETE FROM inventario_onus")
    suspend fun clearOnus()

    // Métricas calculadas desde caché local
    @Query("SELECT COALESCE(SUM(asignado), 0)   FROM inventario_items")
    suspend fun totalAsignado(): Double

    @Query("SELECT COALESCE(SUM(utilizado), 0)  FROM inventario_items")
    suspend fun totalUtilizado(): Double

    @Query("SELECT COALESCE(SUM(disponible), 0) FROM inventario_items")
    suspend fun totalDisponible(): Double

    @Query("SELECT COUNT(*) FROM inventario_items WHERE sinStock = 1")
    suspend fun totalSinStock(): Int
}

@Dao
interface ConsumoPendienteDao {

    @Insert
    suspend fun insert(consumo: ConsumoPendienteEntity)

    @Query("SELECT * FROM consumo_pendiente WHERE sincronizado = 0 ORDER BY creadoEn ASC")
    suspend fun getPendientes(): List<ConsumoPendienteEntity>

    @Query("""
        SELECT * FROM consumo_pendiente 
        WHERE (tecnicoId = :tecnicoId OR tecnicoId = '')
        AND NOT (
            sincronizado = 0 
            AND EXISTS (
                SELECT 1 FROM consumo_pendiente c2 
                WHERE c2.productoId = consumo_pendiente.productoId 
                AND c2.ordenId = consumo_pendiente.ordenId
                AND c2.sincronizado = 1
            )
        )
        ORDER BY creadoEn DESC
    """)
    fun getTodos(tecnicoId: String): LiveData<List<ConsumoPendienteEntity>>

    @Query("UPDATE consumo_pendiente SET sincronizado = 1 WHERE id = :id")
    suspend fun marcarSincronizado(id: Int)

    @Query("SELECT COUNT(*) FROM consumo_pendiente WHERE sincronizado = 0")
    suspend fun countPendientes(): Int

    @Query("SELECT * FROM consumo_pendiente WHERE sincronizado = 0 ORDER BY creadoEn ASC")
    suspend fun getNoSincronizados(): List<ConsumoPendienteEntity>

    @Query("DELETE FROM consumo_pendiente WHERE sincronizado = 1")
    suspend fun deleteSincronizados()

    @Query("DELETE FROM consumo_pendiente")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) > 0 FROM consumo_pendiente WHERE productoId = :productoId AND descripcion = :descripcion AND sincronizado = 1")
    suspend fun existePorDescripcionYProducto(productoId: Int, descripcion: String): Boolean

    @Query("DELETE FROM consumo_pendiente WHERE sincronizado = 1")
    suspend fun limpiarSincronizados()
}