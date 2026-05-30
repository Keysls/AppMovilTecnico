package com.enetfiber.tecnico.data.local

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mantiene el token JWT en memoria para que el interceptor de OkHttp
 * NO tenga que hacer runBlocking(DataStore) en cada request.
 *
 * - Al crearse, lee el token actual una vez (bloqueante, una sola vez).
 * - Después observa el Flow del DataStore y actualiza el AtomicReference.
 * - El interceptor solo llama current() → lectura atómica instantánea.
 */
@Singleton
class TokenProvider @Inject constructor(
    private val session: SessionDataStore
) {
    private val cached = AtomicReference<String?>(null)
    private val scope  = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Lectura inicial sincrónica (una única vez, al arrancar la app)
        cached.set(runBlocking { session.token.first() })
        // A partir de acá, se actualiza solo de forma reactiva
        scope.launch {
            session.token.collect { cached.set(it) }
        }
    }

    fun current(): String? = cached.get()
}