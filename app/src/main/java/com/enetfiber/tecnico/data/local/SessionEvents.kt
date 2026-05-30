package com.enetfiber.tecnico.data.local

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Canal global para eventos de sesión.
 * Cuando el backend responde 401, el interceptor emite aquí
 * y la Activity que esté en pantalla redirige al login.
 */
object SessionEvents {
    private val _unauthorized = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val unauthorized: SharedFlow<Unit> = _unauthorized

    fun notifyUnauthorized() {
        _unauthorized.tryEmit(Unit)
    }
}