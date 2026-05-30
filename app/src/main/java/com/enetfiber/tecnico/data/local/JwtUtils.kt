package com.enetfiber.tecnico.data.local

import android.util.Base64
import org.json.JSONObject

/**
 * Utilidades para leer el JWT del lado del cliente.
 * No valida la firma (eso lo hace el backend) — solo lee el payload
 * para saber si el token ya expiró, y evitar arrancar con sesión muerta.
 */
object JwtUtils {

    /** true si el token está vencido o es inválido. */
    fun estaExpirado(token: String?): Boolean {
        if (token.isNullOrBlank()) return true
        return try {
            val partes = token.split(".")
            if (partes.size != 3) return true

            val payloadJson = String(
                Base64.decode(partes[1], Base64.URL_SAFE or Base64.NO_WRAP)
            )
            val exp = JSONObject(payloadJson).optLong("exp", 0L)
            if (exp == 0L) return false   // sin exp → no podemos saber, asumimos válido

            // exp viene en segundos; comparar con ahora
            val ahoraSegundos = System.currentTimeMillis() / 1000
            ahoraSegundos >= exp
        } catch (e: Exception) {
            true   // si no se puede parsear, tratarlo como inválido
        }
    }
}