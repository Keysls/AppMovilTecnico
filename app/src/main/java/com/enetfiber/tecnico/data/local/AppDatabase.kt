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
        AceptarPendienteEntity::class          // ← NUEVO
    ],
    version = 5,                               // ← era 3
    exportSchema = false
)

abstract class AppDatabase : RoomDatabase() {
    abstract fun ordenDao(): OrdenDao
    abstract fun configOfflineDao(): ConfigOfflineDao
    abstract fun fotoPendienteDao(): FotoPendienteDao
    abstract fun completarPendienteDao(): CompletarPendienteDao
    abstract fun iniciarPendienteDao(): IniciarPendienteDao
    abstract fun aceptarPendienteDao(): AceptarPendienteDao        // ← NUEVO

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

        // Migración 3→4: tabla aceptar_pendiente
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
    }
}