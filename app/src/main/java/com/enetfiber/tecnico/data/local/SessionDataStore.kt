package com.enetfiber.tecnico.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "session")

@Singleton
class SessionDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        val KEY_TOKEN      = stringPreferencesKey("token")
        val KEY_NOMBRE     = stringPreferencesKey("nombre")
        val KEY_APELLIDO   = stringPreferencesKey("apellido")
        val KEY_EMAIL      = stringPreferencesKey("email")
        val KEY_ZONA       = stringPreferencesKey("zona")
        val KEY_TECNICO_ID = stringPreferencesKey("tecnico_id")
        // ✅ Nuevas keys
        val KEY_TELEFONO       = stringPreferencesKey("telefono")
        val KEY_DNI            = stringPreferencesKey("dni")
        val KEY_VEHICULO       = stringPreferencesKey("vehiculo")
        val KEY_ACTIVO         = booleanPreferencesKey("activo")
        val KEY_ORDENES_ACTIVAS = intPreferencesKey("ordenes_activas")
    }

    val token      : Flow<String?>  = context.dataStore.data.map { it[KEY_TOKEN] }
    val nombre     : Flow<String?>  = context.dataStore.data.map { it[KEY_NOMBRE] }
    val apellido   : Flow<String?>  = context.dataStore.data.map { it[KEY_APELLIDO] }
    val zona       : Flow<String?>  = context.dataStore.data.map { it[KEY_ZONA] }
    val tecnicoId  : Flow<String?>  = context.dataStore.data.map { it[KEY_TECNICO_ID] }   // ← NUEVO
    val isLoggedIn: Flow<Boolean> = token.map { t ->
        !t.isNullOrEmpty() && !JwtUtils.estaExpirado(t)
    }    // ✅ Nuevos flows
    val email          : Flow<String>  = context.dataStore.data.map { it[KEY_EMAIL]          ?: "" }
    val telefono       : Flow<String>  = context.dataStore.data.map { it[KEY_TELEFONO]       ?: "" }
    val dni            : Flow<String>  = context.dataStore.data.map { it[KEY_DNI]            ?: "" }
    val vehiculo       : Flow<String>  = context.dataStore.data.map { it[KEY_VEHICULO]       ?: "" }
    val activo         : Flow<Boolean> = context.dataStore.data.map { it[KEY_ACTIVO]         ?: true }
    val ordenesActivas : Flow<Int>     = context.dataStore.data.map { it[KEY_ORDENES_ACTIVAS] ?: 0 }

    suspend fun guardarSesion(
        token: String,
        nombre: String,
        apellido: String,
        email: String,
        tecnicoId: String?,
        zona: String?,
        // ✅ Nuevos parámetros
        telefono: String?       = null,
        dni: String?            = null,
        vehiculo: String?       = null,
        activo: Boolean         = true,
        ordenesActivas: Int     = 0
    ) {
        context.dataStore.edit { prefs ->
            prefs[KEY_TOKEN]    = token
            prefs[KEY_NOMBRE]   = nombre
            prefs[KEY_APELLIDO] = apellido
            prefs[KEY_EMAIL]    = email
            if (tecnicoId != null) prefs[KEY_TECNICO_ID] = tecnicoId
            if (zona      != null) prefs[KEY_ZONA]       = zona
            // ✅ Guardar nuevos campos
            prefs[KEY_TELEFONO]        = telefono       ?: ""
            prefs[KEY_DNI]             = dni            ?: ""
            prefs[KEY_VEHICULO]        = vehiculo       ?: ""
            prefs[KEY_ACTIVO]          = activo
            prefs[KEY_ORDENES_ACTIVAS] = ordenesActivas
        }
    }

    suspend fun cerrarSesion() {
        context.dataStore.edit { it.clear() }
    }
}