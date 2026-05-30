package com.enetfiber.tecnico.equipos

////FUNCIONAL 10/10

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.io.IOException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import javax.xml.parsers.DocumentBuilderFactory




// ─────────────────────────────────────────────────────────────────────────────
sealed class ZteResult<out T> {
    data class Success<T>(val data: T) : ZteResult<T>()
    data class Error(val message: String) : ZteResult<Nothing>()
}

// ─────────────────────────────────────────────────────────────────────────────
data class ZteWanConfig(
    val ip: String = "",
    val mask: String = "255.255.255.0",
    val gw: String = "",
    val dns1: String = "8.8.8.8",
    val dns2: String = "8.8.4.4",
    val status: String = "",
    val vlan: String = "100",
    val mac: String = ""
)

data class ZteLanConfig(
    val ip: String = "192.168.1.1",
    val mask: String = "255.255.255.0",
    val dhcpStart: String = "192.168.1.2",
    val dhcpEnd: String = "192.168.1.254",
    val dns1: String = "8.8.8.8",
    val dns2: String = "8.8.4.4"
)

data class ZteWifiBand(
    val ssid: String = "",
    val pass: String = "",
    val wep0: String = "",
    val wep1: String = "",
    val wep2: String = "",
    val wep3: String = ""
)

data class ZteDeviceInfo(
    val serial: String = "",
    val model: String = "",
    val firmware: String = "",
    val rxPower: String = "",
    val txPower: String = "",
    val temp: String = ""
)

// ZteFullConfig ahora incluye deviceInfo
data class ZteFullConfig(
    val wan: ZteWanConfig = ZteWanConfig(),
    val lan: ZteLanConfig = ZteLanConfig(),
    val wifi24: ZteWifiBand = ZteWifiBand(),
    val wifi5: ZteWifiBand = ZteWifiBand(),
    val device: ZteDeviceInfo = ZteDeviceInfo()   // ← NUEVO
)

// ─────────────────────────────────────────────────────────────────────────────
enum class ZteModelo { F6201B, F6600P }

class ZteConfigurator(
    private val onuIp: String = "192.168.1.1",
    private val modelo: ZteModelo = ZteModelo.F6201B
) {
    companion object {
        private const val TAG = "ZteConfigurator"
        private const val TIMEOUT_MS = 15_000

        private const val RSA_PUB_F6201B = """MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAodPTerkUVCYmv28SOfRV
7UKHVujx/HjCUTAWy9l0L5H0JV0LfDudTdMNPEKloZsNam3YrtEnq6jqMLJV4ASb
1d6axmIgJ636wyTUS99gj4BKs6bQSTUSE8h/QkUYv4gEIt3saMS0pZpd90y6+B/9
hZxZE/RKU8e+zgRqp1/762TB7vcjtjOwXRDEL0w71Jk9i8VUQ59MR1Uj5E8X3WIc
fYSK5RWBkMhfaTRM6ozS9Bqhi40xlSOb3GBxCmliCifOJNLoO9kFoWgAIw5hkSIb
GH+4Csop9Uy8VvmmB+B3ubFLN35qIa5OG5+SDXn4L7FeAA5lRiGxRi8tsWrtew8w
nwIDAQAB"""

        private const val RSA_PUB_F6600P = """MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAwlo/vZBnSJ2MyJ0dbNcw
DvzPqBN+O/BPvLX93GIJVSZmquJHD9X6Xn6VYeM9mRKzjEbXPlv73Dj/gjjtNj9j
Tq2QVyW2Sd4ZkY9e3h1ALCCCfkbjnmSqedyrcvXriTeW+J65jhBje6lTJbafmC5q
bGiItjt0OeOkT+Vb4S7hYPSWIjeYYBh+7Y/fg25Rt2a+RgC8dahvJ3ttB1LHXADr
oCm6q7G+lpbRAlpC8jjc0rZdS0c6HcBoYgzW8vxjj2fTuFy3CZZTrpPyTv/C8K6B
hjTnjRe6ocgFVyQ0RIYfx2hxSJcuauR57OzfMzlgFQv3RAXguDZtuVUFLO2sAiwL
ELph3Acfy9Eh58SHcswZvsOSXY0JNb0XeRM9gxpntLRfM6TB7f9hYtYTDw5oKdyN
BY+nnEa/IpBUjndGDrSs3Z4BxRbYcJEwkKQZkvw/5TpQYbkD6sTRVSlZPaXSjeCl
0hsLCttqwJqRZcjbWXrINBYFw8PYE14Xr9BCyPgqocdQh7FgvasVgG6u5mLR1PBZ
o4EFF/LdY0yvMG5rl9egBk1XD/UMayhRtmSQEUzYt3eEWLBbqJB6MbVJ2ygcv5EL
ReDY0SWXw1PIEbHeP51A/MyB6kwSgZwdoQW3JiaPnGHMaE0NqfAYPNiGJLMsmvT/
rNUI/8iSCW+WvSzx9tByUxsCAwEAAQ=="""
    }

    private var sessionToken = ""
    private var lanToken = ""
    private var wifiToken = ""
    private var sessionPassword = ""
    private var sessionUser = ""
    private val cookieStore = mutableMapOf<String, String>()

    private val rsaPublicKey: PublicKey by lazy {
        val pem = if (modelo == ZteModelo.F6600P) RSA_PUB_F6600P else RSA_PUB_F6201B
        val pemClean = pem.replace("\n", "").replace("\r", "").trim()
        val decoded = Base64.decode(pemClean, Base64.DEFAULT)
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(decoded))
    }

    // ─── Crypto ──────────────────────────────────────────────────────────────

    private fun sha256hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun sha256bytes(text: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

    private fun rsaB64(text: String): String {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, rsaPublicKey)
        return Base64.encodeToString(cipher.doFinal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun aesEnc(value: String, ck: String, ci: String): String {
        val key = sha256bytes(ck)
        val iv  = sha256bytes(ci).take(16).toByteArray()
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val bytes = value.toByteArray(Charsets.UTF_8)
        val pad = 16 - (bytes.size % 16)
        return Base64.encodeToString(cipher.doFinal(bytes + ByteArray(pad) { 0 }), Base64.NO_WRAP)
    }

    private fun aesDec(b64: String, token: String): String {
        if (b64.isBlank()) return b64
        return try {
            val key = sha256bytes(token)
            val iv  = sha256bytes(token.reversed()).take(16).toByteArray()
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val result = cipher.doFinal(Base64.decode(b64, Base64.DEFAULT))
                .toString(Charsets.UTF_8).trimEnd('\u0000').trim()
            if (result.all { it.code in 32..126 }) result else b64
        } catch (e: Exception) { b64 }
    }

    private fun rand16(): String = (1..16).map { SecureRandom().nextInt(10) }.joinToString("")

    // ─── HTTP ─────────────────────────────────────────────────────────────────

    private fun get(path: String, referer: String = "/"): String {
        val conn = URL("http://$onuIp$path").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout    = TIMEOUT_MS
            conn.setRequestProperty("Referer", "http://$onuIp$referer")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val ch = cookieStore.entries.joinToString("; ") { "${it.key}=${it.value}" }
            if (ch.isNotBlank()) conn.setRequestProperty("Cookie", ch)
            conn.connect()
            saveCookies(conn)
            if (conn.responseCode == 404) return ""
            conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } catch (e: IOException) {
            throw RuntimeException("GET $path → ${e.message}")
        } finally { conn.disconnect() }
    }

    private fun post(path: String, body: String, check: String, referer: String = "/"): String {
        val conn = URL("http://$onuIp$path").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod  = "POST"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout    = TIMEOUT_MS
            conn.doOutput       = true
            conn.setRequestProperty("Content-Type",      "application/x-www-form-urlencoded; charset=UTF-8")
            conn.setRequestProperty("Referer",           "http://$onuIp$referer")
            conn.setRequestProperty("User-Agent",        "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            conn.setRequestProperty("X-Requested-With",  "XMLHttpRequest")
            conn.setRequestProperty("Origin",            "http://$onuIp")
            conn.setRequestProperty("Check",             check)
            val ch = cookieStore.entries.joinToString("; ") { "${it.key}=${it.value}" }
            if (ch.isNotBlank()) conn.setRequestProperty("Cookie", ch)
            conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
            saveCookies(conn)
            val code = conn.responseCode
            if (code >= 400) throw RuntimeException("POST $path → HTTP $code: ${conn.errorStream?.bufferedReader()?.readText()?.take(100)}")
            conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } catch (e: IOException) {
            throw RuntimeException("POST $path → ${e.message}")
        } finally { conn.disconnect() }
    }

    private fun saveCookies(conn: HttpURLConnection) {
        conn.headerFields["Set-Cookie"]?.forEach { h ->
            val p = h.split(";")[0].split("=", limit = 2)
            if (p.size == 2) cookieStore[p[0].trim()] = p[1].trim()
        }
    }

    // ─── Build body ───────────────────────────────────────────────────────────

    private fun buildBody(
        params: Map<String, String>, tok: String, encFields: List<String> = emptyList()
    ): Pair<String, String> {
        val ck = rand16(); val ci = rand16()
        val parts = params.map { (k, v) ->
            "${urlEnc(k)}=${urlEnc(if (k in encFields) aesEnc(v, ck, ci) else v)}"
        }
        var body = parts.joinToString("&")
        if (encFields.isNotEmpty()) body += "&encode=${urlEnc(rsaB64("$ck+$ci"))}"
        val bodyTok = "$body&_sessionTOKEN=${urlEnc(tok)}"
        return bodyTok to rsaB64(sha256hex(bodyTok))
    }

    private fun urlEnc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ─── Parsers ──────────────────────────────────────────────────────────────

    private fun getTok(html: String): String {
        val m = Regex("""_sessionTmpToken\s*=\s*"((?:\\x[0-9a-fA-F]{2}|[^"\\])+)"""").find(html) ?: return ""
        return try { decodeUnicodeEscapes(m.groupValues[1]) } catch (e: Exception) { m.groupValues[1] }
    }

    private fun decodeUnicodeEscapes(s: String): String {
        val sb = StringBuilder(); var i = 0
        while (i < s.length) {
            if (i + 3 < s.length && s[i] == '\\' && s[i+1] == 'x') {
                sb.append(s.substring(i+2, i+4).toInt(16).toChar()); i += 4
            } else { sb.append(s[i]); i++ }
        }
        return sb.toString()
    }

    /**
     * Recolecta TODOS los ParaName/ParaValue de TODAS las instancias en un dict plano.
     * Equivalente al fix del proxy: itera root.iter("Instance").
     */
    private fun parseAllInstancesToMap(xml: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val doc = parseXml(xml) ?: return result
            val instances = doc.documentElement.getElementsByTagName("Instance")
            for (i in 0 until instances.length) {
                val inst = instances.item(i) as? Element ?: continue
                var cur: String? = null
                for (j in 0 until inst.childNodes.length) {
                    val c = inst.childNodes.item(j) as? Element ?: continue
                    when (c.tagName) {
                        "ParaName"  -> cur = c.textContent.trim()
                        "ParaValue" -> if (cur != null) {
                            val v = c.textContent.trim()
                            if (v.isNotEmpty()) result[cur!!] = v
                            cur = null
                        }
                    }
                }
            }
            // Fallback: campos directos del root
            if (result.isEmpty()) {
                val root = doc.documentElement
                for (i in 0 until root.childNodes.length) {
                    val child = root.childNodes.item(i) as? Element ?: continue
                    if (child.tagName in listOf("IF_ERRORSTR","IF_ERRORPARAM","IF_ERRORTYPE","IF_ERRORID")) continue
                    val v = child.textContent.trim()
                    if (v.isNotEmpty()) result[child.tagName] = v
                }
            }
        } catch (e: Exception) { Log.w(TAG, "parseAllInstancesToMap: ${e.message}") }
        return result
    }

    /** Parse kv plano (para endopoints que no usan Instance) */
    private fun parseKv(xml: String): Map<String, String> = parseAllInstancesToMap(xml)

    /** SSIDs — solo nodos OBJ_WLANAP_ID */
    private fun parseSsids(xml: String): List<Map<String, String>> {
        val out = mutableListOf<Map<String, String>>()
        try {
            val doc = parseXml(xml) ?: return out
            // Intentar OBJ_WLANAP_ID primero
            val apNodes = doc.documentElement.getElementsByTagName("OBJ_WLANAP_ID")
            val sourceNode = if (apNodes.length > 0) apNodes else doc.documentElement.getElementsByTagName("Instance").let { null }

            fun extractFromParent(parent: org.w3c.dom.Node) {
                val insts = (parent as? Element)?.getElementsByTagName("Instance") ?: return
                for (i in 0 until insts.length) {
                    val inst = insts.item(i) as? Element ?: continue
                    val o = mutableMapOf<String, String>(); var cur: String? = null
                    for (j in 0 until inst.childNodes.length) {
                        val c = inst.childNodes.item(j) as? Element ?: continue
                        when (c.tagName) {
                            "ParaName"  -> cur = c.textContent.trim()
                            "ParaValue" -> if (cur != null) { o[cur!!] = c.textContent.trim(); cur = null }
                        }
                    }
                    if (o.isNotEmpty()) out.add(o)
                }
            }

            if (apNodes.length > 0) {
                for (i in 0 until apNodes.length) extractFromParent(apNodes.item(i))
            } else {
                // Fallback: todas las instancias
                val allInsts = doc.documentElement.getElementsByTagName("Instance")
                for (i in 0 until allInsts.length) {
                    val inst = allInsts.item(i) as? Element ?: continue
                    val o = mutableMapOf<String, String>(); var cur: String? = null
                    for (j in 0 until inst.childNodes.length) {
                        val c = inst.childNodes.item(j) as? Element ?: continue
                        when (c.tagName) {
                            "ParaName"  -> cur = c.textContent.trim()
                            "ParaValue" -> if (cur != null) { o[cur!!] = c.textContent.trim(); cur = null }
                        }
                    }
                    if (o.isNotEmpty()) out.add(o)
                }
            }
        } catch (e: Exception) { Log.w(TAG, "parseSsids: ${e.message}") }
        return out
    }

    /**
     * *** FIX PSK ***
     * Equivalente a parse_psk_map del proxy.
     * Lee OBJ_WLANPSK_ID y retorna {instID_ap: passphrase}.
     * "DEV.WIFI.AP1.PSK1" → clave del mapa es "DEV.WIFI.AP1"
     */
    private fun parsePskMap(xml: String, tok: String): Map<String, String> {
        val psk = mutableMapOf<String, String>()
        try {
            val doc = parseXml(xml) ?: return psk
            val pskNodes = doc.documentElement.getElementsByTagName("OBJ_WLANPSK_ID")
            for (n in 0 until pskNodes.length) {
                val pskNode = pskNodes.item(n) as? Element ?: continue
                val instances = pskNode.getElementsByTagName("Instance")
                for (i in 0 until instances.length) {
                    val inst = instances.item(i) as? Element ?: continue
                    var instId = ""; var key = ""; var cur: String? = null
                    for (j in 0 until inst.childNodes.length) {
                        val c = inst.childNodes.item(j) as? Element ?: continue
                        when (c.tagName) {
                            "ParaName"  -> cur = c.textContent.trim()
                            "ParaValue" -> if (cur != null) {
                                val v = c.textContent.trim()
                                when (cur) {
                                    "_InstID"        -> instId = v
                                    "KeyPassphrase"  -> key    = v
                                }
                                cur = null
                            }
                        }
                    }
                    if (instId.isNotEmpty() && key.isNotEmpty()) {
                        val dec  = aesDec(key, tok)
                        val safe = if (dec.isNotEmpty() && dec.all { it.code in 32..126 }) dec else ""
                        val ap   = instId.substringBeforeLast(".PSK", instId)  // "DEV.WIFI.AP1.PSK1" → "DEV.WIFI.AP1"
                        psk[ap]  = safe
                        Log.d(TAG, "[PSK] ap=$ap key=${safe.take(4)}***")
                    }
                }
            }
        } catch (e: Exception) { Log.w(TAG, "parsePskMap: ${e.message}") }
        return psk
    }

    private fun parseWanInstances(xml: String): List<Map<String, String>> {
        val out = mutableListOf<Map<String, String>>()
        try {
            val doc = parseXml(xml) ?: return out
            val wanNodes = doc.documentElement.getElementsByTagName("ID_WAN_COMFIG")
            for (w in 0 until wanNodes.length) {
                val wanNode = wanNodes.item(w) as? Element ?: continue
                val instances = wanNode.getElementsByTagName("Instance")
                for (i in 0 until instances.length) {
                    val inst = instances.item(i) as? Element ?: continue
                    val o = mutableMapOf<String, String>(); var cur: String? = null
                    for (j in 0 until inst.childNodes.length) {
                        val c = inst.childNodes.item(j) as? Element ?: continue
                        when (c.tagName) {
                            "ParaName"  -> cur = c.textContent.trim()
                            "ParaValue" -> if (cur != null) { o[cur!!] = c.textContent.trim(); cur = null }
                        }
                    }
                    if (o.isNotEmpty()) out.add(o)
                }
            }
        } catch (e: Exception) { Log.w(TAG, "parseWanInstances: ${e.message}") }
        return out
    }

    private fun parseAclInstances(xml: String): List<Map<String, String>> {
        val out = mutableListOf<Map<String, String>>()
        try {
            val doc = parseXml(xml) ?: return out
            val aclNodes = doc.documentElement.getElementsByTagName("OBJ_FWSC_ID")
            for (a in 0 until aclNodes.length) {
                val aclNode = aclNodes.item(a) as? Element ?: continue
                val instances = aclNode.getElementsByTagName("Instance")
                for (i in 0 until instances.length) {
                    val inst = instances.item(i) as? Element ?: continue
                    val o = mutableMapOf<String, String>(); var cur: String? = null
                    for (j in 0 until inst.childNodes.length) {
                        val c = inst.childNodes.item(j) as? Element ?: continue
                        when (c.tagName) {
                            "ParaName"  -> cur = c.textContent.trim()
                            "ParaValue" -> if (cur != null) { o[cur!!] = c.textContent.trim(); cur = null }
                        }
                    }
                    if (o.isNotEmpty() && !(o["Name"] ?: "").startsWith("_SC_")) out.add(o)
                }
            }
        } catch (e: Exception) { Log.w(TAG, "parseAclInstances: ${e.message}") }
        return out
    }

    private fun parseXml(xml: String): Document? = try {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    } catch (e: Exception) { null }

    private fun isValidIp(v: String) = v.isNotBlank() && Regex("""^\d+\.\d+\.\d+\.\d+$""").matches(v)

    /** Retorna el primer valor que sea una IP válida */
    private fun firstValidIp(vararg vals: String?): String {
        for (v in vals) if (!v.isNullOrBlank() && isValidIp(v)) return v
        return ""
    }

    private fun ipOct(ip: String, i: Int) = ip.split(".").getOrElse(i) { "0" }

    private fun chk(xml: String, label: String) {
        val err = Regex("<IF_ERRORSTR>([^<]+)</IF_ERRORSTR>").find(xml)?.groupValues?.getOrNull(1) ?: ""
        if (err.isNotBlank() && err !in listOf("SUCC", "SessionTimeout"))
            throw RuntimeException("$label: $err")
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  API pública
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun login(user: String, pass: String): ZteResult<String> = withContext(Dispatchers.IO) {
        try {
            val r0    = get("/?_type=loginData&_tag=login_entry")
            val tok0  = Regex(""""sess_token"\s*:\s*"([^"]+)"""").find(r0)?.groupValues?.get(1) ?: ""
            val rn    = get("/?_type=loginData&_tag=login_token")
            val nonce = parseXml(rn)?.documentElement?.textContent?.trim() ?: ""
            val hsh   = sha256hex(pass + nonce)

            val loginBody = "action=login&Username=${urlEnc(user)}&Password=${urlEnc(hsh)}&_sessionTOKEN=${urlEnc(tok0)}"
            val conn = URL("http://$onuIp/?_type=loginData&_tag=login_entry").openConnection() as HttpURLConnection
            val loginResp = try {
                conn.requestMethod = "POST"; conn.connectTimeout = TIMEOUT_MS; conn.readTimeout = TIMEOUT_MS
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                val ch = cookieStore.entries.joinToString("; ") { "${it.key}=${it.value}" }
                if (ch.isNotBlank()) conn.setRequestProperty("Cookie", ch)
                conn.outputStream.write(loginBody.toByteArray())
                saveCookies(conn)
                conn.inputStream.bufferedReader().readText()
            } finally { conn.disconnect() }

            if (Regex(""""loginErrMsg"\s*:\s*"([^"]+)"""").find(loginResp)?.groupValues?.get(1)?.isNotBlank() == true)
                return@withContext ZteResult.Error("Credenciales incorrectas")

            // Token real de la home page
            val home = get("/")
            val tok  = getTok(home).ifBlank {
                Regex(""""sess_token"\s*:\s*"([^"]+)"""").find(loginResp)?.groupValues?.get(1) ?: tok0
            }
            Log.d(TAG, "Login OK, tok=${tok.take(16)}...")
            sessionToken = tok; lanToken = tok; wifiToken = tok
            sessionPassword = pass; sessionUser = user
            ZteResult.Success(tok.take(8) + "...")
        } catch (e: Exception) {
            Log.e(TAG, "login error: ${e.message}")
            ZteResult.Error(e.message ?: "Error desconocido")
        }
    }

    /**
     * *** readConfig CORREGIDO ***
     * 1. WAN: visita la menuView antes de pedir datos (evita SessionTimeout)
     * 2. WiFi: usa parsePskMap para obtener la clave real
     * 3. Device: llama a readDeviceInfo y lo incluye en ZteFullConfig
     */
    private fun relogin() {
        // Re-login silencioso para renovar sesión (F6600P expira rápido)
        try {
            cookieStore.clear()
            val r0    = get("/?_type=loginData&_tag=login_entry")
            val tok0  = Regex(""""sess_token"\s*:\s*"([^"]+)"""").find(r0)?.groupValues?.get(1) ?: ""
            val rn    = get("/?_type=loginData&_tag=login_token")
            val nonce = parseXml(rn)?.documentElement?.textContent?.trim() ?: ""
            val hsh   = sha256hex(sessionPassword + nonce)
            val loginBody = "action=login&Username=${urlEnc(sessionUser)}&Password=${urlEnc(hsh)}&_sessionTOKEN=${urlEnc(tok0)}"
            val conn = URL("http://$onuIp/?_type=loginData&_tag=login_entry").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"; conn.connectTimeout = TIMEOUT_MS; conn.readTimeout = TIMEOUT_MS
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                val ch = cookieStore.entries.joinToString("; ") { "${it.key}=${it.value}" }
                if (ch.isNotBlank()) conn.setRequestProperty("Cookie", ch)
                conn.outputStream.write(loginBody.toByteArray())
                saveCookies(conn)
                conn.inputStream.bufferedReader().readText()
            } finally { conn.disconnect() }
            val home = get("/")
            val tok  = getTok(home).ifBlank {
                Regex(""""sess_token"\s*:\s*"([^"]+)"""").find(r0)?.groupValues?.get(1) ?: tok0
            }
            sessionToken = tok; lanToken = tok; wifiToken = tok
            Log.d(TAG, "[relogin] OK tok=${tok.take(16)}...")
        } catch (e: Exception) {
            Log.w(TAG, "[relogin] error: ${e.message}")
        }
    }

    suspend fun readConfig(): ZteResult<ZteFullConfig> = withContext(Dispatchers.IO) {
        try {
            // ── WAN ──────────────────────────────────────────────────────────
            val wan = try {
                if (modelo == ZteModelo.F6600P) {
                    // F6600P: leer WAN desde wan_internet_lua.lua (endpoint de config)
                    // wan_internetstatus_lua.lua siempre devuelve SessionTimeout en este modelo
                    get("/?_type=menuView&_tag=ethWanConfig&Menu3Location=0")
                    val xml = get("/?_type=menuData&_tag=wan_internet_lua.lua&TypeUplink=2&pageType=0")
                    Log.d(TAG, "[WAN F6600P raw] ${xml.take(500)}")
                    // Parsear instancias ID_WAN_COMFIG
                    val instances = parseWanInstances(xml)
                    Log.d(TAG, "[WAN F6600P] instancias: ${instances.map { it["_InstID"] }}")
                    val d = instances.firstOrNull { it["linkMode"]?.uppercase() == "IP" }
                        ?: instances.firstOrNull()
                        ?: emptyMap()
                    Log.d(TAG, "[WAN F6600P fields] ${d.keys}")
                    Log.d(TAG, "[WAN F6600P values] ip=${d["IPAddress"]} mask=${d["SubnetMask"]} gw=${d["GateWay"]}")
                    // F6600P devuelve IP en octetos: IPAddress0..3, SubnetMask0..3, GateWay0..3, DNS10..13
                    fun octets(prefix: String) = listOf(0,1,2,3)
                        .map { d["$prefix$it"] ?: "0" }
                        .joinToString(".")
                        .let { if (isValidIp(it) && it != "0.0.0.0") it else "" }

                    val wanIp   = firstValidIp(octets("IPAddress"),  d["IPAddress"],  d["WanIPAddr"])
                    val wanMask = firstValidIp(octets("SubnetMask"), d["SubnetMask"]).ifBlank { "255.255.255.0" }
                    val wanGw   = firstValidIp(octets("GateWay"),    d["GateWay"],    d["Gateway"])
                    val wanDns1 = firstValidIp(octets("DNS1"),       d["DNS1"],       d["DNSServer1"]).ifBlank { "8.8.8.8" }
                    val wanDns2 = firstValidIp(octets("DNS2"),       d["DNS2"],       d["DNSServer2"]).ifBlank { "8.8.4.4" }
                    Log.d(TAG, "[WAN F6600P parsed] ip=$wanIp mask=$wanMask gw=$wanGw dns1=$wanDns1")

                    ZteWanConfig(
                        ip     = wanIp,
                        mask   = wanMask,
                        gw     = wanGw,
                        dns1   = wanDns1,
                        dns2   = wanDns2,
                        status = d["ConnStatus"] ?: d["Status"] ?: "",
                        vlan   = d["VLANID"] ?: "100",
                        mac    = d["WorkIFMac"] ?: d["MACAddress"] ?: ""
                    )
                } else {
                    // F6201B: endpoint de status original
                    get("/?_type=menuView&_tag=ethWanStatus&Menu3Location=0")
                    var xml = get("/?_type=menuData&_tag=wan_internetstatus_lua.lua&TypeUplink=2&pageType=1")
                    if ("SessionTimeout" in xml) {
                        get("/?_type=menuView&_tag=wanBasic&Menu3Location=0")
                        xml = get("/?_type=menuData&_tag=wan_internetstatus_lua.lua&TypeUplink=2&pageType=1")
                    }
                    Log.d(TAG, "[WAN raw] ${xml.take(400)}")
                    val d = parseAllInstancesToMap(xml)
                    Log.d(TAG, "[WAN fields] ${d.keys}")
                    Log.d(TAG, "[WAN values] ip=${d["IPAddress"]} mask=${d["SubnetMask"]} gw=${d["GateWay"]}")
                    ZteWanConfig(
                        ip     = firstValidIp(d["IPAddress"], d["ExternalIPAddress"], d["WanIPAddr"], d["IP"]),
                        mask   = firstValidIp(d["SubnetMask"], d["ExternalSubnetMask"], d["WanSubMask"], d["Netmask"]).ifBlank { "255.255.255.0" },
                        gw     = firstValidIp(d["GateWay"], d["Gateway"], d["DefaultGateway"], d["GW"]),
                        dns1   = firstValidIp(d["DNS1"], d["DNSServer1"], d["PrimaryDNS"]).ifBlank { "8.8.8.8" },
                        dns2   = firstValidIp(d["DNS2"], d["DNSServer2"], d["SecondaryDNS"]).ifBlank { "8.8.4.4" },
                        status = d["ConnStatus"] ?: d["Status"] ?: "",
                        vlan   = d["VLANID"] ?: "100",
                        mac    = d["WorkIFMac"] ?: d["MACAddress"] ?: ""
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "WAN read error: ${e.message}")
                ZteWanConfig()
            }

            // ── LAN ──────────────────────────────────────────────────────────
            val lan = try {
                val lh   = get("/?_type=menuView&_tag=lanMgrIpv4&Menu3Location=0")
                val ltok = getTok(lh).ifBlank { sessionToken }
                lanToken = ltok
                val xml  = get("/?_type=menuData&_tag=Localnet_LanMgrIpv4_DHCPBasicCfg_lua.lua")
                val d    = parseKv(xml)
                fun decIp(raw: String?) = aesDec(raw ?: "", ltok).let { if (isValidIp(it)) it else "" }
                ZteLanConfig(
                    ip        = decIp(d["IPAddr"])     .ifBlank { "192.168.1.1" },
                    dhcpStart = decIp(d["MinAddress"]) .ifBlank { "192.168.1.10" },
                    dhcpEnd   = decIp(d["MaxAddress"]) .ifBlank { "192.168.1.254" },
                    dns1      = decIp(d["DNSServer1"]) .ifBlank { "8.8.8.8" },
                    dns2      = decIp(d["DNSServer2"]) .ifBlank { "8.8.4.4" }
                )
            } catch (e: Exception) { ZteLanConfig() }

            // ── WiFi ─────────────────────────────────────────────────────────
            var wifi24 = ZteWifiBand(); var wifi5 = ZteWifiBand()
            try {
                // F6600P: re-login antes de WiFi para asegurar sesión activa
                if (modelo == ZteModelo.F6600P) relogin()
                val wh   = get("/?_type=menuView&_tag=wlanBasic&Menu3Location=0")
                val wtok = getTok(wh).ifBlank { sessionToken }
                wifiToken = wtok
                val xml  = get("/?_type=menuData&_tag=wlan_wlansssidconf_lua.lua")

                // FIX: usar parsePskMap para obtener la clave real
                val pskMap = parsePskMap(xml, wtok)
                Log.d(TAG, "[WiFi] pskMap keys: ${pskMap.keys}")

                val ssids = parseSsids(xml)
                Log.d(TAG, "[WiFi] SSIDs: ${ssids.map { "${it["Alias"]}/${it["ESSID"]}/${it["_InstID"]}" }}")

                for (s in ssids) {
                    val alias   = s["Alias"]  ?: ""
                    val instId  = s["_InstID"] ?: ""

                    // Buscar clave: primero en pskMap por _InstID, luego inline
                    val passFromPsk = pskMap[instId] ?: ""
                    val rawInline   = s["KeyPassphrase"] ?: ""
                    val decInline   = aesDec(rawInline, wtok)
                    val safeInline  = if (decInline.isNotEmpty() && decInline.all { it.code in 32..126 }) decInline else ""
                    val finalPass   = passFromPsk.ifBlank { safeInline }

                    Log.d(TAG, "[WiFi] alias=$alias instId=$instId psk=${passFromPsk.take(4)}*** inline=${safeInline.take(4)}*** final=${finalPass.take(4)}***")

                    val entry = ZteWifiBand(
                        ssid = s["ESSID"]    ?: "",
                        pass = finalPass,
                        wep0 = s["WEPKey00"] ?: "",
                        wep1 = s["WEPKey01"] ?: "",
                        wep2 = s["WEPKey02"] ?: "",
                        wep3 = s["WEPKey03"] ?: ""
                    )
                    when (alias) {
                        "SSID1" -> wifi24 = entry
                        "SSID5" -> wifi5  = entry
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "WiFi read error: ${e.message}") }

            // ── Device info (SN, RX, TX) ──────────────────────────────────────
            val device = try {
                readDeviceInfoInternal()
            } catch (e: Exception) {
                Log.w(TAG, "Device read error: ${e.message}")
                ZteDeviceInfo()
            }

            Log.d(TAG, "[readConfig] WAN ip=${wan.ip} mask=${wan.mask} gw=${wan.gw}")
            Log.d(TAG, "[readConfig] Device serial=${device.serial} rx=${device.rxPower} tx=${device.txPower}")

            ZteResult.Success(ZteFullConfig(wan, lan, wifi24, wifi5, device))
        } catch (e: Exception) {
            ZteResult.Error(e.message ?: "Error leyendo config")
        }
    }

    suspend fun readConfigFull(): ZteResult<ZteFullConfig> = readConfig()

    // ─── Device info interno (sin withContext, ya se llama desde uno) ─────────
    //  FIX: verifica SessionTimeout en cada endpoint y hace relogin si ocurre

    private fun readDeviceInfoInternal(): ZteDeviceInfo {
        var serial = ""; var model = ""; var firmware = ""
        var rx = ""; var tx = ""; var temp = ""

        // ── SN ──────────────────────────────────────────────────────────────────
        // F6600P: poninfo_sn_lua.lua siempre da SessionTimeout → SN viene de statusMgr, skip aquí
        if (modelo != ZteModelo.F6600P) try {
            val wh    = get("/?_type=menuView&_tag=ponSn&Menu3Location=0")
            val tokSn = getTok(wh).ifBlank { sessionToken }
            var xml   = get("/?_type=menuData&_tag=poninfo_sn_lua.lua")
            // FIX: si SessionTimeout → relogin + reintentar
            if ("SessionTimeout" in xml) {
                Log.w(TAG, "[SN] SessionTimeout, relogin + reintento")
                relogin()
                get("/?_type=menuView&_tag=ponSn&Menu3Location=0")
                xml = get("/?_type=menuData&_tag=poninfo_sn_lua.lua")
            }
            Log.d(TAG, "[SN raw xml] ${xml.take(400)}")

            // Usar parseKvDirect que maneja todos los formatos posibles
            val kv = parseKvDirect(xml)
            Log.d(TAG, "[SN kv] keys=${kv.keys} vals=${kv}")

            val snRaw = kv["Sn"] ?: kv["SN"] ?: kv["SerialNumber"] ?: ""
            Log.d(TAG, "[SN] snRaw='$snRaw' tokSn=${tokSn.take(8)}...")

            if (snRaw.isNotBlank()) {
                // Intentar descifrar con token de la vista SN
                val dec1 = aesDec(snRaw, tokSn)
                Log.d(TAG, "[SN] dec1='$dec1'")
                // Si falla, intentar con sessionToken
                val dec2 = aesDec(snRaw, sessionToken)
                Log.d(TAG, "[SN] dec2='$dec2'")

                serial = when {
                    dec1.length >= 4 && dec1.all { it.code in 32..126 } -> dec1
                    dec2.length >= 4 && dec2.all { it.code in 32..126 } -> dec2
                    snRaw.length >= 4 -> snRaw  // usar raw si no se puede descifrar
                    else -> ""
                }
            }
            Log.d(TAG, "[SN] final serial='$serial'")
        } catch (e: Exception) { Log.w(TAG, "[device] SN: ${e.message}") }

        // ── Modelo + Firmware ────────────────────────────────────────────────────
        try {
            get("/?_type=menuView&_tag=statusMgr&Menu3Location=0")
            var xml = get("/?_type=menuData&_tag=devmgr_statusmgr_lua.lua")
            // FIX: SessionTimeout → relogin + reintentar
            if ("SessionTimeout" in xml) {
                Log.w(TAG, "[statusMgr] SessionTimeout, relogin + reintento")
                relogin()
                get("/?_type=menuView&_tag=statusMgr&Menu3Location=0")
                xml = get("/?_type=menuData&_tag=devmgr_statusmgr_lua.lua")
            }
            val kv  = parseKvDirect(xml)
            model    = kv["ModelName"]    ?: ""
            firmware = kv["SoftwareVer"]  ?: ""
            if (serial.isBlank()) serial = kv["SerialNumber"] ?: ""
            Log.d(TAG, "[device] model=$model firmware=$firmware serial=$serial")
        } catch (e: Exception) { Log.w(TAG, "[device] statusMgr: ${e.message}") }

        // ── Señal óptica PON ─────────────────────────────────────────────────────
        try {
            get("/?_type=menuView&_tag=ponopticalinfo&Menu3Location=0")
            var xml = get("/?_type=menuData&_tag=optical_info_lua.lua")
            // FIX: SessionTimeout → relogin + reintentar
            if ("SessionTimeout" in xml) {
                Log.w(TAG, "[optical] SessionTimeout, relogin + reintento")
                relogin()
                get("/?_type=menuView&_tag=ponopticalinfo&Menu3Location=0")
                xml = get("/?_type=menuData&_tag=optical_info_lua.lua")
            }
            val kv  = parseKvDirect(xml)
            rx   = kv["RxPower"] ?: ""
            tx   = kv["TxPower"] ?: ""
            temp = kv["Temp"]    ?: ""
            Log.d(TAG, "[device] rx=$rx tx=$tx temp=$temp")
        } catch (e: Exception) { Log.w(TAG, "[device] optical: ${e.message}") }

        return ZteDeviceInfo(serial, model, firmware, rx, tx, temp)
    }
    suspend fun readDeviceInfo(): ZteResult<ZteDeviceInfo> = withContext(Dispatchers.IO) {
        try { ZteResult.Success(readDeviceInfoInternal()) }
        catch (e: Exception) { ZteResult.Error(e.message ?: "Error dispositivo") }
    }

    // ─── WAN InstID ───────────────────────────────────────────────────────────

    private fun getWanInstId(tok: String): Triple<String, String, String> {
        val xml = get("/?_type=menuData&_tag=wan_internet_lua.lua&TypeUplink=2&pageType=0")
        val instances = parseWanInstances(xml)
        if (instances.isEmpty()) return Triple("-1", "Enet", "0")
        instances.firstOrNull { it["linkMode"]?.uppercase() == "IP" }?.let {
            return Triple(it["_InstID"] ?: "-1", it["WANCName"] ?: "Enet", it["InstHasGot"] ?: "1")
        }
        val inst = instances[0]
        return Triple(inst["_InstID"] ?: "-1", inst["WANCName"] ?: "Enet", inst["InstHasGot"] ?: "1")
    }

    // ─── Apply WAN ────────────────────────────────────────────────────────────

    suspend fun applyWan(ip: String, mask: String, gw: String, dns1: String, dns2: String, vlan: String = "100")
            : ZteResult<Map<String, String>> = withContext(Dispatchers.IO) {
        try {
            val wh  = get("/?_type=menuView&_tag=ethWanConfig&Menu3Location=0")
            val tok = getTok(wh).ifBlank { sessionToken }; sessionToken = tok
            val (instId, wanName, _) = getWanInstId(tok)
            val isNew = instId == "-1"
            val params = linkedMapOf(
                "IF_ACTION" to "Apply", "_InstID" to instId, "uplink" to "2",
                "InstHasGot" to (if (isNew) "0" else "1"), "ControlType" to "1",
                "WANCName" to wanName, "Enable" to "1", "mode" to "route",
                "ServList" to "1", "MTU" to "1480", "linkMode" to "IP",
                "TransType" to "PPPoE", "UserName" to "", "Password" to "",
                "AuthType" to "PAP,CHAP,MS-CHAP", "ConnTrigger" to "AlwaysOn",
                "IdleTime0" to "20", "IdleTime1" to "0", "IpMode" to "IPv4",
                "Addressingtype" to "Static",
                "IPAddress0" to ipOct(ip,0),   "IPAddress1" to ipOct(ip,1),
                "IPAddress2" to ipOct(ip,2),   "IPAddress3" to ipOct(ip,3),
                "SubnetMask0" to ipOct(mask,0),"SubnetMask1" to ipOct(mask,1),
                "SubnetMask2" to ipOct(mask,2),"SubnetMask3" to ipOct(mask,3),
                "GateWay0" to ipOct(gw,0),    "GateWay1" to ipOct(gw,1),
                "GateWay2" to ipOct(gw,2),    "GateWay3" to ipOct(gw,3),
                "DNS10" to ipOct(dns1,0), "DNS11" to ipOct(dns1,1),
                "DNS12" to ipOct(dns1,2), "DNS13" to ipOct(dns1,3),
                "DNS20" to ipOct(dns2,0), "DNS21" to ipOct(dns2,1),
                "DNS22" to ipOct(dns2,2), "DNS23" to ipOct(dns2,3),
                "DNS30" to "1","DNS31" to "1","DNS32" to "1","DNS33" to "1",
                "IsNAT" to "1","IPv6AcquireMode" to "Auto",
                "Gua1" to "","Gua1PrefixLen" to "128","Gateway6" to "","Pd" to "","PdLen" to "",
                "Dns1v6" to "","Dns2v6" to "","Dns3v6" to "",
                "IsPD" to "1","Unnumbered" to "0","IsSLAAC" to "1","IsGUA" to "1","IsPdAddr" to "1",
                "VlanEnable" to "1","VLANID" to vlan.ifBlank { "100" },"Priority" to "0",
                "Btn_cancel_internet" to "","Btn_apply_internet" to ""
            )
            val (body, check) = buildBody(params, tok, listOf("Password"))
            val resp = post("/?_type=menuData&_tag=wan_internet_lua.lua&TypeUplink=2&pageType=0", body, check)
            chk(resp, "WAN")
            ZteResult.Success(mapOf("ok" to "true", "inst_id" to instId))
        } catch (e: Exception) { ZteResult.Error(e.message ?: "Error WAN") }
    }

    // ─── Apply LAN ────────────────────────────────────────────────────────────

    suspend fun applyLan(ip: String, dhcpStart: String, dhcpEnd: String, dns1: String, dns2: String)
            : ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            get("/?_type=loginData&_tag=login_entry")
            val lh  = get("/?_type=menuView&_tag=lanMgrIpv4&Menu3Location=0")
            val tok = getTok(lh).ifBlank { sessionToken }; lanToken = tok
            val mask = "255.255.255.0"
            // F6600P requiere Ipv4DnsOrigin=1 en el mismo POST para guardar los DNS
            val params = if (modelo == ZteModelo.F6600P) linkedMapOf(
                "IF_ACTION" to "Apply","IF_URL_HOST" to onuIp,"_InstID" to "IGD",
                "IPAddr" to ip,"SubMask" to mask,"SubnetMask" to mask,
                "MinAddress" to dhcpStart,"MaxAddress" to dhcpEnd,"IPRouters" to "",
                "DNSServer1" to dns1,"DNSServer2" to dns2,"LeaseTime" to "86400",
                "ServerEnable" to "1","DnsServerSource" to "0","DomainName" to "ehome",
                "Ipv4DnsOrigin" to "1","IPv4AssignLANIP" to "0",
                "Ipv6DnsOrigin" to "0","IPv6AssignLANIP" to "0",
                "Btn_cancel_DHCPBasicCfg" to "","Btn_apply_DHCPBasicCfg" to ""
            ) else linkedMapOf(
                "IF_ACTION" to "Apply","IF_URL_HOST" to onuIp,"_InstID" to "IGD",
                "IPAddr" to ip,"SubMask" to mask,"SubnetMask" to mask,
                "MinAddress" to dhcpStart,"MaxAddress" to dhcpEnd,"IPRouters" to "",
                "DNSServer1" to dns1,"DNSServer2" to dns2,"LeaseTime" to "86400",
                "ServerEnable" to "1","DnsServerSource" to "0","DomainName" to "ehome",
                "Btn_cancel_DHCPBasicCfg" to "","Btn_apply_DHCPBasicCfg" to ""
            )
            val (body, check) = buildBody(params, tok, listOf("IPAddr","MinAddress","MaxAddress","DNSServer1","DNSServer2"))
            val resp = post("/?_type=menuData&_tag=Localnet_LanMgrIpv4_DHCPBasicCfg_lua.lua", body, check)
            chk(resp, "LAN")
            ZteResult.Success(Unit)
        } catch (e: Exception) { ZteResult.Error(e.message ?: "Error LAN") }
    }

    // ─── Apply WiFi ───────────────────────────────────────────────────────────

    suspend fun applyWifi(band: String, ssid: String, pass: String, wep: ZteWifiBand = ZteWifiBand())
            : ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            get("/?_type=loginData&_tag=login_entry")
            val wh  = get("/?_type=menuView&_tag=wlanBasic&Menu3Location=0")
            val tok = getTok(wh).ifBlank { sessionToken }; wifiToken = tok
            val is24 = band == "24"
            val iid  = if (is24) "DEV.WIFI.AP1"      else "DEV.WIFI.AP5"
            val ipsk = if (is24) "DEV.WIFI.AP1.PSK1"  else "DEV.WIFI.AP5.PSK1"
            val ig   = if (is24) "DEV.GuestWifi1"     else "DEV.GuestWifi4"
            val wb   = if (is24) "DEV.WIFI.AP1.WEP"   else "DEV.WIFI.AP5.WEP"
            val wd   = "abcdef1234567890abcdef1234"
            val params = linkedMapOf(
                "IF_ACTION" to "Apply","Enable" to "1","_InstID" to iid,
                "_WEPCONIG" to "N","_PSKCONIG" to "Y","BeaconType" to "11i",
                "WEPAuthMode" to "None","WPAAuthMode" to "PSKAuthentication","11iAuthMode" to "PSKAuthentication",
                "WPAEncryptType" to "TKIPandAESEncryption","11iEncryptType" to "AESEncryption",
                "WPA3AuthMode" to "SAEAuthentication","WPA3EncryptType" to "AESEncryption",
                "_InstID_WEP0" to "${wb}1","_InstID_WEP1" to "${wb}2",
                "_InstID_WEP2" to "${wb}3","_InstID_WEP3" to "${wb}4",
                "_InstID_PSK" to ipsk,
                "MasterAuthServerIp" to "...","BackupAuthServerIp" to "...",
                "MasterAcctServerIp" to "...","BackupAcctServerIp" to "...",
                "_InstID_GUEST" to ig,"_GUEST" to "N",
                "ESSID" to ssid,"ESSIDHideEnable" to "0","EncryptionType" to "WPA2-PSK-AES",
                "KeyPassphrase" to pass,"WEPKeyIndex" to "1","ShowWEPKey" to "0",
                "WEPKey00" to wep.wep0.ifBlank { wd },"WEPKey01" to wep.wep1.ifBlank { wd },
                "WEPKey02" to wep.wep2.ifBlank { wd },"WEPKey03" to wep.wep3.ifBlank { wd },
                "VapIsolationEnable" to "0","MaxUserNum" to "32",
                "Btn_cancel_WLANSSIDConf" to "","Btn_apply_WLANSSIDConf" to ""
            )
            val (body, check) = buildBody(params, tok, listOf("KeyPassphrase","WEPKey00","WEPKey01","WEPKey02","WEPKey03"))
            val resp = post("/?_type=menuData&_tag=wlan_wlansssidconf_lua.lua", body, check)
            chk(resp, "WiFi ${band}GHz")
            ZteResult.Success(Unit)
        } catch (e: Exception) { ZteResult.Error(e.message ?: "Error WiFi") }
    }

    // ─── Port Binding ─────────────────────────────────────────────────────────

    suspend fun applyPortBinding(): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val wh  = get("/?_type=menuView&_tag=portBinding&Menu3Location=0")
            val tok = getTok(wh).ifBlank { sessionToken }; sessionToken = tok
            var wanView = "DEV.IP.IF3"
            try {
                val xml = get("/?_type=menuData&_tag=portbinding_lua.lua")
                val m = Regex("<WANViewName>([^<]+)</WANViewName>").find(xml)
                if (m != null) wanView = m.groupValues[1].trim()
                else wanView = parseKv(xml)["WANViewName"]?.ifBlank { wanView } ?: wanView
            } catch (e: Exception) { Log.w(TAG, "[portbinding] WANViewName: ${e.message}") }
            val lanView = "DEV.BRIDGING.BR1.BRPORT2,DEV.BRIDGING.BR1.BRPORT3," +
                    "DEV.BRIDGING.BR1.BRPORT4,DEV.BRIDGING.BR1.BRPORT5," +
                    "DEV.BRIDGING.BR1.BRPORT6,DEV.BRIDGING.BR1.BRPORT10,"
            val params = linkedMapOf("IF_ACTION" to "Apply","WANViewName" to wanView,
                "LANViewName" to lanView,"Btn_cancel_PORTBindConf" to "","Btn_apply_PORTBindConf" to "")
            val (body, check) = buildBody(params, tok)
            val resp = post("/?_type=menuData&_tag=portbinding_lua.lua", body, check)
            chk(resp, "PortBinding")
            ZteResult.Success(Unit)
        } catch (e: Exception) { ZteResult.Error(e.message ?: "Error PortBinding") }
    }

    // ─── ACL ──────────────────────────────────────────────────────────────────

    suspend fun applyAcl(): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val wh  = get("/?_type=menuView&_tag=localServiceCtrl&Menu3Location=0")
            val tok = getTok(wh).ifBlank { sessionToken }; sessionToken = tok
            var aclInstId = "-1"; var aclName = "Enet"
            try {
                val xml = get("/?_type=menuData&_tag=firewall_ipv4service_lua.lua")
                val instances = parseAclInstances(xml)
                if (instances.isNotEmpty()) {
                    aclInstId = instances[0]["_InstID"] ?: "-1"
                    aclName   = instances[0]["Name"]    ?: "Enet"
                }
            } catch (e: Exception) { Log.w(TAG, "[ACL] ${e.message}") }
            val params = linkedMapOf(
                "IF_ACTION" to "Apply","Enable" to "1","_InstID" to aclInstId,
                "INCName" to "WAN","MinSrcIp" to "0.0.0.0","MaxSrcIp" to "0.0.0.0",
                "ServiceList" to "HTTP,FTP,TELNET,HTTPS,PING","IPMode" to "1",
                "Name" to aclName,"FilterTarget" to "1","INCViewName" to "IGD.WANIF",
                "Btn_cancel_serviceCtl" to "","Btn_apply_serviceCtl" to ""
            )
            val (body, check) = buildBody(params, tok)
            val resp = post("/?_type=menuData&_tag=firewall_ipv4service_lua.lua", body, check)
            chk(resp, "ACL")
            ZteResult.Success(Unit)
        } catch (e: Exception) { ZteResult.Error(e.message ?: "Error ACL") }
    }

    // ─── UPnP ─────────────────────────────────────────────────────────────────

    suspend fun applyUpnp(): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val wh  = try { get("/?_type=menuView&_tag=upnp&Menu3Location=0") }
            catch (e: Exception) { "" }
            val tok = getTok(wh).ifBlank { sessionToken }
            sessionToken = tok
            Log.d(TAG, "[UPNP] tok=${tok.take(16)}...")

            val params = linkedMapOf(
                "IF_ACTION"       to "Apply",
                "_InstID"         to "IGD",
                "EnableUPnPIGD"   to "1",
                "ADPeriod"        to "30",
                "TTL"             to "4",
                "Btn_cancel_upnp" to "",
                "Btn_apply_upnp"  to ""
            )
            val (body, check) = buildBody(params, tok)
            val resp = post("/?_type=menuData&_tag=upnp_upnp_lua.lua", body, check)
            Log.d(TAG, "[UPNP resp] ${resp.take(200)}")
            chk(resp, "UPnP")
            ZteResult.Success(Unit)
        } catch (e: Exception) { ZteResult.Error(e.message ?: "Error UPnP") }
    }


    // ─── Band Steering OFF ────────────────────────────────────────────────────────

    suspend fun applyBandSteering(): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            // Token fresco desde la vista de Band Steering
            val wh  = try { get("/?_type=menuView&_tag=wifibandsteer&Menu3Location=0") }
            catch (e: Exception) { "" }
            val tok = getTok(wh).ifBlank { sessionToken }
            sessionToken = tok; wifiToken = tok
            Log.d(TAG, "[BANDSTEERING] tok=${tok.take(16)}...")

            // Leer _InstID real desde el XML
            var instId = "IGD.WiFi.RD1.BS"  // confirmado por captura
            try {
                val xml = get("/?_type=menuData&_tag=wlan_BandSteering_lua.lua")
                Log.d(TAG, "[BANDSTEERING] xml=${xml.take(200)}")
                val fromRegex = Regex("<_InstID>([^<]+)</_InstID>").find(xml)?.groupValues?.get(1)?.trim()
                    ?: Regex("<INSTIDENTITY>([^<]+)</INSTIDENTITY>").find(xml)?.groupValues?.get(1)?.trim()
                if (!fromRegex.isNullOrBlank()) {
                    instId = fromRegex
                    Log.d(TAG, "[BANDSTEERING] _InstID leído: $instId")
                } else {
                    val kv = parseKv(xml)
                    instId = kv["_InstID"]?.ifBlank { instId } ?: instId
                    Log.d(TAG, "[BANDSTEERING] _InstID via parseKv: $instId")
                }
            } catch (e: Exception) { Log.w(TAG, "[BANDSTEERING] leyendo XML: ${e.message}") }

            val params = linkedMapOf(
                "IF_ACTION"              to "Apply",
                "_InstID"                to instId,
                "BsEnable"               to "0",       // 0 = OFF
                "BsRssiLmt24G"           to "-60",
                "BsRssiLmt5G"            to "-110",
                "BsBounceDwellTimeLmt"   to "1200",
                "BsTPLimit"              to "9999999",
                "Btn_cancel_Mode"        to "",
                "Btn_apply_Mode"         to ""
            )
            val (body, check) = buildBody(params, tok)
            val resp = post("/?_type=menuData&_tag=wlan_BandSteering_lua.lua", body, check)
            Log.d(TAG, "[BANDSTEERING resp] ${resp.take(300)}")
            chk(resp, "BandSteering")
            ZteResult.Success(Unit)
        } catch (e: Exception) { ZteResult.Error(e.message ?: "Error BandSteering") }
    }

// ─── WiFi 5GHz Radio — 160MHz ─────────────────────────────────────────────────

    suspend fun applyWifi5Radio(): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val wh  = try { get("/?_type=menuView&_tag=wlanBasic&Menu3Location=0") }
            catch (e: Exception) { "" }
            val tok = getTok(wh).ifBlank { sessionToken }
            sessionToken = tok; wifiToken = tok
            Log.d(TAG, "[WIFI5RADIO] tok=${tok.take(16)}...")

            val instId = "DEV.WIFI.RD2"  // confirmado por captura real

            val params = linkedMapOf(
                "IF_ACTION"                   to "Apply",
                "_InstID"                     to instId,
                "BasicDataRates"              to "6,9,12,18,24,36,48,54",
                "OpDataRates"                 to "6,9,12,18,24,36,48,54",
                "11nMode"                     to "1",
                "GreenField"                  to "0",
                "AutoChannelEnabled"          to "1",      // con 'd' — confirmado en captura
                "Band"                        to "5GHz",
                "Channel"                     to "NULL",   // NULL cuando auto-canal activo
                "Standard"                    to "a,n,ac,ax",
                "BandWidth"                   to "160MHz", // ← objetivo principal
                "AutoChRange"                 to "",
                "MUMIMOEnable"                to if (modelo == ZteModelo.F6600P) "1" else "",
                "UPLinkOFDMA"                 to "0",
                "SSIDIsolationEnable"         to "0",
                "CountryCode"                 to "CNI",
                "SGIEnabled"                  to "1",
                "BeaconInterval"              to "100",
                "TxPower"                     to "100%",
                "PreambleType"                to "0",
                "Btn_cancel_WLANBasicAdConf"  to "",
                "Btn_apply_WLANBasicAdConf"   to ""
            )
            val (body, check) = buildBody(params, tok)
            val resp = post(
                "/?_type=menuData&_tag=wlan_wlanbasicadconf_lua.lua",
                body, check
            )
            Log.d(TAG, "[WIFI5RADIO resp] ${resp.take(300)}")
            chk(resp, "WiFi5Radio")
            ZteResult.Success(Unit)
        } catch (e: Exception) { ZteResult.Error(e.message ?: "Error WiFi5Radio") }
    }




    // ─────────────────────────────────────────────────────────────────────────
    //  APPLY ALL
    //
    //  FIX: readDeviceInfo() se mueve ANTES del WiFi — sesión más estable.
    //  Para F6600P, relogin() preventivo antes de leer device.
    //  El WiFi NO reinicia el equipo en ZTE, pero la sesión puede expirar
    //  durante los 9 pasos, especialmente en F6600P.
    //
    //  ORDEN CORRECTO:
    //  WAN → LAN → PortBinding → ACL → UPnP → BandSteering → WiFi5Radio
    //  → [relogin F6600P] → [readDeviceInfo] → WiFi5 → WiFi2.4
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun applyAll(
        ip: String, mask: String, gw: String, dns1: String, dns2: String,
        vlan: String = "100",
        lanIp: String = "192.168.1.1", dhcpStart: String = "192.168.1.10",
        dhcpEnd: String = "192.168.1.254", lanDns1: String = "8.8.8.8", lanDns2: String = "8.8.4.4",
        ssid24: String, pass24: String, wep24: ZteWifiBand = ZteWifiBand(),
        ssid5: String, pass5: String, wep5: ZteWifiBand = ZteWifiBand(),
        onProgress: suspend (step: Int, total: Int, desc: String, ok: Boolean) -> Unit = { _,_,_,_ -> }
    ): ZteResult<ZteDeviceInfo> {
        val total = 9
        val errors = mutableListOf<String>()

        suspend fun step(n: Int, desc: String, action: suspend () -> ZteResult<*>) {
            onProgress(n, total, desc, true)
            val r = action()
            if (r is ZteResult.Error) errors.add("$desc: ${r.message}")
            onProgress(n, total, desc, r is ZteResult.Success)
        }

        step(1, "WAN")                { applyWan(ip, mask, gw, dns1, dns2, vlan) }
        step(2, "LAN")                { applyLan(lanIp, dhcpStart, dhcpEnd, lanDns1, lanDns2) }
        step(3, "Port Binding")       { applyPortBinding() }
        step(4, "ACL Firewall")       { applyAcl() }
        step(5, "UPnP")               { applyUpnp() }
        step(6, "Band Steering OFF")  { applyBandSteering() }
        step(7, "WiFi 5G 160MHz")     { applyWifi5Radio() }

        // FIX: leer device AQUÍ — sesión más estable, ANTES del WiFi
        // F6600P expira la sesión rápido: relogin preventivo
        if (modelo == ZteModelo.F6600P) relogin()
        onProgress(8, total, "Señal Óptica", true)
        val deviceResult = readDeviceInfo()
        onProgress(8, total, "Señal Óptica", deviceResult is ZteResult.Success)

        step(9, "WiFi 5 GHz")         { applyWifi("5",  ssid5,  pass5,  wep5) }

        // WiFi 2.4G fuera del conteo de steps (total=9) — notificación manual
        onProgress(9, total, "WiFi 2.4 GHz", true)
        val wifiResult = applyWifi("24", ssid24, pass24, wep24)
        if (wifiResult is ZteResult.Error) errors.add("WiFi 2.4 GHz: ${wifiResult.message}")
        onProgress(9, total, "WiFi 2.4 GHz", wifiResult is ZteResult.Success)

        return if (errors.isEmpty()) deviceResult
        else ZteResult.Error(errors.joinToString("\n"))
    }

    // ─── Fix SN: parser directo sin Instance ──────────────────────────────────────
    private fun parseKvDirect(xml: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val doc = parseXml(xml) ?: return result
            // Intentar primero con Instance (como hace parseAllInstancesToMap)
            val instances = doc.documentElement.getElementsByTagName("Instance")
            if (instances.length > 0) {
                for (i in 0 until instances.length) {
                    val inst = instances.item(i) as? Element ?: continue
                    var cur: String? = null
                    for (j in 0 until inst.childNodes.length) {
                        val c = inst.childNodes.item(j) as? Element ?: continue
                        when (c.tagName) {
                            "ParaName"  -> cur = c.textContent.trim()
                            "ParaValue" -> if (cur != null) {
                                val v = c.textContent.trim()
                                if (v.isNotEmpty()) result[cur!!] = v
                                cur = null
                            }
                        }
                    }
                }
            }
            // Fallback: campos directos del root (algunos endpoints como poninfo_sn)
            if (result.isEmpty()) {
                val root = doc.documentElement
                for (i in 0 until root.childNodes.length) {
                    val child = root.childNodes.item(i) as? Element ?: continue
                    if (child.tagName in listOf("IF_ERRORSTR","IF_ERRORPARAM","IF_ERRORTYPE","IF_ERRORID")) continue
                    val v = child.textContent.trim()
                    if (v.isNotEmpty()) result[child.tagName] = v
                }
            }
            // Fallback 2: buscar ParaName/ParaValue directamente en el root (sin Instance wrapper)
            if (result.isEmpty()) {
                val root = doc.documentElement
                var cur: String? = null
                for (i in 0 until root.childNodes.length) {
                    val child = root.childNodes.item(i) as? Element ?: continue
                    when (child.tagName) {
                        "ParaName"  -> cur = child.textContent.trim()
                        "ParaValue" -> if (cur != null) {
                            val v = child.textContent.trim()
                            if (v.isNotEmpty()) result[cur!!] = v
                            cur = null
                        }
                    }
                }
            }
        } catch (e: Exception) { Log.w(TAG, "parseKvDirect: ${e.message}") }
        return result
    }

    // ─── Reboot ───────────────────────────────────────────────────────────────────
    suspend fun reboot(): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            // Navegar a la vista de Device Management para token fresco
            val wh  = try { get("/?_type=menuView&_tag=rebootAndReset&Menu3Location=0") }
            catch (e: Exception) { "" }
            val tok = getTok(wh).ifBlank { sessionToken }
            sessionToken = tok
            Log.d(TAG, "[REBOOT] tok=${tok.take(16)}...")

            val params = linkedMapOf(
                "IF_ACTION"   to "Restart",
                "Btn_restart" to ""
            )
            val (body, check) = buildBody(params, tok)
            try {
                val resp = post(
                    "/?_type=menuData&_tag=devmgr_restartmgr_lua.lua",
                    body, check
                )
                Log.d(TAG, "[REBOOT resp] ${resp.take(200)}")
                // Solo verificar si hay error real en XML
                chk(resp, "Reboot")
            } catch (e: RuntimeException) {
                // RuntimeException de chk() = error real del router
                throw e
            } catch (e: Exception) {
                // IOException/timeout = ONU cortó la conexión al reiniciarse — NORMAL
                Log.d(TAG, "[REBOOT] conexión cortada por ONU (normal): ${e.message}")
            }
            ZteResult.Success(Unit)
        } catch (e: RuntimeException) {
            ZteResult.Error(e.message ?: "Error reboot")
        } catch (e: Exception) {
            ZteResult.Success(Unit) // Desconexión = reinicio exitoso
        }
    }
}