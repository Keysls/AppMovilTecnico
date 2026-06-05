package com.enetfiber.tecnico.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        OrdenEntity::class,
        ConfigOfflineEntity::class,
        FotoPendienteEntity::class,
        CompletarPendienteEntity::class,
        IniciarPendienteEntity::class,
        AceptarPendienteEntity::class,
        InventarioItemEntity::class,    // ← NUEVO
        InventarioOnuEntity::class,     // ← NUEVO
        ConsumoPendienteEntity::class,  // ← NUEVO
    ],
    version = 8,                        // ← era 5
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ordenDao(): OrdenDao
    abstract fun configOfflineDao(): ConfigOfflineDao
    abstract fun fotoPendienteDao(): FotoPendienteDao
    abstract fun completarPendienteDao(): CompletarPendienteDao
    abstract fun iniciarPendienteDao(): IniciarPendienteDao
    abstract fun aceptarPendienteDao(): AceptarPendienteDao
    abstract fun inventarioDao(): InventarioDao          // ← NUEVO
    abstract fun consumoPendienteDao(): ConsumoPendienteDao // ← NUEVO

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS completar_pendiente (
                        instalacionId TEXT NOT NULL PRIMARY KEY,
                        observaciones TEXT,
                        sincronizado  INTEGER NOT NULL DEFAULT 0,
                        creadoEn      INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS iniciar_pendiente (
                        instalacionId TEXT NOT NULL PRIMARY KEY,
                        ordenId       TEXT NOT NULL,
                        latitud       REAL,
                        longitud      REAL,
                        direccionGps  TEXT,
                        sincronizado  INTEGER NOT NULL DEFAULT 0,
                        creadoEn      INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS aceptar_pendiente (
                        ordenId      TEXT NOT NULL PRIMARY KEY,
                        sincronizado INTEGER NOT NULL DEFAULT 0,
                        creadoEn     INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE completar_pendiente ADD COLUMN fechaFin INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // Migración 5→6: tablas de inventario
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS inventario_items (
                        productoId  INTEGER NOT NULL PRIMARY KEY,
                        nombre      TEXT    NOT NULL,
                        codigo      TEXT    NOT NULL,
                        categoria   TEXT    NOT NULL,
                        unidad      TEXT    NOT NULL,
                        asignado    REAL    NOT NULL,
                        utilizado   REAL    NOT NULL,
                        disponible  REAL    NOT NULL,
                        sinStock    INTEGER NOT NULL,
                        cachedAt    INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS inventario_onus (
                        id        INTEGER NOT NULL PRIMARY KEY,
                        codigoPon TEXT,
                        producto  TEXT    NOT NULL,
                        codigo    TEXT,
                        cachedAt  INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS consumo_pendiente (
                        id           INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        productoId   INTEGER NOT NULL,
                        nombre       TEXT    NOT NULL,
                        cantidad     REAL    NOT NULL,
                        motivo       TEXT    NOT NULL DEFAULT 'SERVICIO',
                        descripcion  TEXT,
                        ordenId      TEXT,
                        sincronizado INTEGER NOT NULL DEFAULT 0,
                        creadoEn     INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE consumo_pendiente ADD COLUMN tecnicoId TEXT NOT NULL DEFAULT ''"
                )
            }
        }
    }
}