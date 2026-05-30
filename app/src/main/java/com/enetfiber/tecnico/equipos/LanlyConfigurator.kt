package com.enetfiber.tecnico.equipos

//FUNCIONAL 10/10

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ─────────────────────────────────────────────────────────────────────────────
//  LANLY BOA CGI — equivalente exacto del JS doLogin_LANLY / doWAN_LANLY / etc.
// ─────────────────────────────────────────────────────────────────────────────
class LanlyConfigurator(
    private val onuIp: String = "192.168.1.1"
) {
    companion object {
        private const val TAG = "LanlyConfigurator"
        private const val TIMEOUT_MS = 12_000

        // Credenciales en orden de prioridad (igual que el JS)
        private val CREDS = listOf(
            Triple("superadmin", "La23n7y", "3"),
            Triple("admin",      "Web@0063", "1")
        )

        // chkpt arrays del JS (CHKPT_SAVE y CHKPT_DEL son iguales)
        private val CHKPT = listOf("on","on","on","on","on","","","","","on","","","","")
    }

    private var csrfToken = ""
    private val cookieStore = mutableMapOf<String, String>()
    // FIX #4: rastreamos el último referer igual que el proxy Python
    private var lastReferer = ""

    // ── postTableEncrypt — PORT EXACTO del common.js (BENMUNDO/CDATA Realtek BOA) ──
    // FIX #1: necesario para el reboot (el proxy Python lo usa en do_reboot_benmundo)
    private fun postTableEncrypt(params: Map<String, String>): Int {
        val skip = setOf("postSecurityFlag", "csrftoken")

        fun encodeVal(v: String): String =
            URLEncoder.encode(v, "UTF-8").replace("+", "%20")

        fun encodeName(n: String): String =
            n.replace("[", "%5B").replace("]", "%5D")

        var inputVal = ""
        for ((name, value) in params) {
            if (name in skip) continue
            inputVal += encodeName(name) + "=" + encodeVal(value) + "&"
        }

        var csum = 0L
        var i = 0
        val L = inputVal.length
        while (i < L) {
            if ((i + 4) > L) {
                if (i < L)     csum += (inputVal[i].code.toLong()   shl 24)
                if (i+1 < L)   csum += (inputVal[i+1].code.toLong() shl 16)
                if (i+2 < L)   csum += (inputVal[i+2].code.toLong() shl 8)
                break
            } else {
                csum += ((inputVal[i].code.toLong()   shl 24) +
                        (inputVal[i+1].code.toLong() shl 16) +
                        (inputVal[i+2].code.toLong() shl 8)  +
                        inputVal[i+3].code.toLong())
                i += 4
            }
        }

        csum = (csum and 0xffff) + (csum shr 16)
        csum = csum and 0xffff
        csum = csum.inv() and 0xffff
        return csum.toInt()
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────
    // LANLY NO usa /cgi-bin/ — las URLs son directas (a diferencia de OPTIC)

    private fun get(path: String): String {
        val url = "http://$onuIp/$path"
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout    = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            conn.setRequestProperty("Accept-Language", "es-ES,es;q=0.9")
            conn.instanceFollowRedirects = false
            val cookie = cookieStore.entries.joinToString("; ") { "${it.key}=${it.value}" }
            if (cookie.isNotBlank()) conn.setRequestProperty("Cookie", cookie)
            // FIX #4: enviar Referer igual que el proxy Python
            if (lastReferer.isNotBlank()) conn.setRequestProperty("Referer", lastReferer)
            conn.connect()
            saveCookies(conn)
            lastReferer = url  // actualizar referer tras cada request
            if (conn.responseCode in 300..399) return ""
            // FIX #5: leer errorStream si hay error HTTP para no perder cookies
            val stream = try { conn.inputStream } catch (e: IOException) { conn.errorStream }
            stream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
        } catch (e: IOException) {
            val err = e.message?.lowercase() ?: ""
            if (listOf("reset", "disconnect", "refused", "broken", "timeout", "timed", "eof", "abort", "software").any { it in err })
                return ""
            throw RuntimeException("GET $path → ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    private fun post(path: String, params: Map<String, String>): String {
        return postRaw(path, params.entries.joinToString("&") {
            "${urlEnc(it.key)}=${urlEnc(it.value)}"
        }, referer = lastReferer)
    }

    // postRaw acepta body ya construido (para el caso de chkpt múltiples)
    // FIX #4: ahora acepta y envía referer
    private fun postRaw(path: String, body: String, referer: String = ""): String {
        val url = "http://$onuIp/$path"
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod  = "POST"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout    = TIMEOUT_MS
            conn.doOutput       = true
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            conn.setRequestProperty("Accept-Language", "es-ES,es;q=0.9")
            // FIX #4: enviar Referer (el proxy Python siempre lo manda)
            val ref = referer.ifBlank { lastReferer }
            if (ref.isNotBlank()) conn.setRequestProperty("Referer", ref)
            val cookie = cookieStore.entries.joinToString("; ") { "${it.key}=${it.value}" }
            if (cookie.isNotBlank()) conn.setRequestProperty("Cookie", cookie)
            conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
            saveCookies(conn)
            lastReferer = url  // actualizar referer tras cada POST
            if (conn.responseCode in 300..399) return ""
            // FIX #5: leer errorStream si hay error HTTP
            val stream = try { conn.inputStream } catch (e: IOException) { conn.errorStream }
            stream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
        } catch (e: IOException) {
            // Conexión cortada en POST = OK (igual que el proxy Python)
            val err = e.message?.lowercase() ?: ""
            if (listOf("reset", "disconnect", "refused", "broken", "timeout", "timed", "eof", "abort", "software").any { it in err }) {
                Log.d(TAG, "  POST conexión cortada (OK): ${e.message}")
                return ""
            }
            throw RuntimeException("POST $path → ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    private fun saveCookies(conn: HttpURLConnection) {
        conn.headerFields["Set-Cookie"]?.forEach { header ->
            val parts = header.split(";")[0].split("=", limit = 2)
            if (parts.size == 2) cookieStore[parts[0].trim()] = parts[1].trim()
        }
    }

    private fun urlEnc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ── CSRF helpers  (= extractCSRF / refreshCSRF del JS) ───────────────────

    private fun extractCsrf(html: String): String? {
        val patterns = listOf(
            Regex("""name\s*=\s*["']csrftoken["'][^>]*value\s*=\s*["']([a-f0-9]{32})["']""", RegexOption.IGNORE_CASE),
            Regex("""value\s*=\s*["']([a-f0-9]{32})["'][^>]*name\s*=\s*["']csrftoken["']""", RegexOption.IGNORE_CASE),
            Regex("""csrftoken["']?\s*[=:]\s*["']?([a-f0-9]{32})""", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            val m = p.find(html)
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    private fun refreshCsrf(page: String): String {
        val html = get(page)
        val tok  = extractCsrf(html)
        if (tok != null) {
            csrfToken = tok
            Log.d(TAG, "csrfToken desde $page: ${tok.take(8)}...")
        } else {
            Log.w(TAG, "WARN: no se encontró csrftoken en $page")
        }
        return html
    }

    // Extrae el campo 'lst' del HTML  (= extractLst del JS)
    // El JS usa: html.match(/new\s+it_nr\s*\(\s*["']([^"']+)["']/)
    private fun extractLst(html: String): String? {
        return Regex("""new\s+it_nr\s*\(\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
    }

    // Detecta si el HTML es una página de login  (= isLoginPage del JS)
    private fun isLoginPage(html: String): Boolean {
        return html.isBlank() || html.length < 1000 ||
                html.contains("formLogin") || html.contains("login.asp")
    }

    // ── buildEthernetBody con múltiples chkpt  (= buildEthernetBody del JS) ──
    private fun buildEthernetBody(
        fields: Map<String, String>,
        chkptValues: List<String>
    ): String {
        val parts = mutableListOf<String>()
        for ((k, v) in fields) {
            parts.add("${urlEnc(k)}=${urlEnc(v)}")
        }
        for (v in chkptValues) {
            parts.add("chkpt=${urlEnc(v)}")
        }
        return parts.joinToString("&")
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  LOGIN  (= doLogin_LANLY del JS)
    //  Prueba superadmin primero, luego admin
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun login(): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            for ((user, pass, sel) in CREDS) {
                val html = get("admin/login.asp")
                val tok  = extractCsrf(html)
                    ?: throw RuntimeException("No se encontró csrftoken en login.asp")
                csrfToken = tok
                Log.d(TAG, "Intentando login: $user")

                post("boaform/admin/formLogin", mapOf(
                    "username1"    to user,
                    "psd1"         to pass,
                    "loginSelinit" to sel,
                    "username"     to user,
                    "psd"          to pass,
                    "sec_lang"     to "0",
                    "ismobile"     to "",
                    "csrftoken"    to csrfToken
                ))

                // Verificar con net_eth_links.asp (igual que el JS)
                val html2 = get("net_eth_links.asp")
                Log.d(TAG, "net_eth_links.asp len=${html2.length}")

                if (html2.length > 3000 && !html2.contains("login.asp") && !html2.contains("formLogin")) {
                    val tok2 = extractCsrf(html2)
                    if (tok2 != null) {
                        csrfToken = tok2
                        Log.d(TAG, "Login OK con $user | csrf: ${tok2.take(8)}...")
                        return@withContext ZteResult.Success(Unit)
                    }
                }
                Log.d(TAG, "Login fallido con $user, probando siguiente...")
            }
            ZteResult.Error("Credenciales inválidas para LANLY (probado superadmin y admin)")
        } catch (e: Exception) {
            ZteResult.Error("Login LANLY: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  WAN  (= doWAN_LANLY del JS)
    //  1. Borrar perfil existente (action=rm)
    //  2. Crear nuevo perfil (action=sv)
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun applyWan(
        ip: String, mask: String, gw: String, vlan: String = "100"
    ): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val html = refreshCsrf("net_eth_links.asp")
            val lst  = extractLst(html)
            Log.d(TAG, "lst actual: $lst")

            // ── Borrar perfil existente — solo si hay lst (igual que el JS: if(lst)) ──
            if (lst != null) {
                val deleteBody = buildEthernetBody(mapOf(
                    "lkname"              to "0",
                    "lkmode"              to "1",
                    "IpProtocolType"      to "1",
                    "ipmode"              to "1",
                    "PPPoEProxyMaxUser"   to "0",
                    "napt"                to "on",
                    "vlan"                to "on",
                    "vid"                 to "100",
                    "vprio"               to "1",
                    "mtu"                 to "1500",
                    "pppUsername"         to "",
                    "pppPassword"         to "",
                    "pppServiceName"      to "",
                    "pppCtype"            to "0",
                    "ipAddr"              to "0.0.0.0",
                    "netMask"             to "255.255.255.0",
                    "remoteIpAddr"        to "0.0.0.0",
                    "v4dns1"              to "8.8.8.8",
                    "v4dns2"              to "8.8.4.4",
                    "applicationtype"     to "1",
                    "dslite_aftr_mode"    to "0",
                    "dslite_aftr_hostname" to "::",
                    "Ipv6Addr"            to "",
                    "Ipv6PrefixLen"       to "",
                    "Ipv6Gateway"         to "",
                    "dnsv6Mode"           to "1",
                    "Ipv6Dns1"            to "",
                    "Ipv6Dns2"            to "",
                    "cmode"               to "1",
                    "ipDhcp"              to "0",
                    "itfGroup"            to "543",
                    "encodePppUserName"   to "",
                    "encodePppPassword"   to "",
                    "lst"                 to lst,
                    "action"              to "rm",
                    "submit-url"          to "http://$onuIp/net_eth_links.asp",
                    "acnameflag"          to "none",
                    "csrftoken"           to csrfToken
                ), CHKPT)
                postRaw("boaform/admin/formEthernet", deleteBody,
                    referer = "http://$onuIp/net_eth_links.asp")
                Log.d(TAG, "Perfil LANLY borrado")
                delay(1500)
                refreshCsrf("net_eth_links.asp")
            } // fin if(lst != null)

            // ── Crear nuevo perfil ──
            // IMPORTANTE: lkname="new" y lst="" (vacío) — igual que el JS
            refreshCsrf("net_eth_links.asp")
            val createBody = buildEthernetBody(mapOf(
                "lkname"              to "new",
                "lkmode"              to "1",
                "IpProtocolType"      to "1",
                "ipmode"              to "1",
                "PPPoEProxyMaxUser"   to "0",
                "napt"                to "on",
                "vlan"                to "on",
                "vid"                 to vlan,
                "vprio"               to "1",
                "mtu"                 to "1500",
                "pppUsername"         to "",
                "pppPassword"         to "",
                "pppServiceName"      to "",
                "pppCtype"            to "0",
                "ipAddr"              to ip,
                "netMask"             to mask,
                "remoteIpAddr"        to gw,
                "dnsMode"             to "0",
                "v4dns1"              to "8.8.8.8",
                "v4dns2"              to "8.8.4.4",
                "applicationtype"     to "1",
                "dslite_aftr_mode"    to "0",
                "dslite_aftr_hostname" to "::",
                "Ipv6Addr"            to "",
                "Ipv6PrefixLen"       to "",
                "Ipv6Gateway"         to "",
                "dnsv6Mode"           to "1",
                "Ipv6Dns1"            to "",
                "Ipv6Dns2"            to "",
                "cmode"               to "1",
                "ipDhcp"              to "0",
                "itfGroup"            to "543",
                "encodePppUserName"   to "",
                "encodePppPassword"   to "",
                "lst"                 to "",
                "action"              to "sv",
                "submit-url"          to "http://$onuIp/net_eth_links.asp",
                "acnameflag"          to "none",
                "csrftoken"           to csrfToken
            ), CHKPT)
            postRaw("boaform/admin/formEthernet", createBody,
                referer = "http://$onuIp/net_eth_links.asp")
            Log.d(TAG, "Perfil LANLY creado OK")

            // Esperar hasta 20s que el equipo confirme la WAN — igual que el JS
            val wanStart = System.currentTimeMillis()
            var wanReady = false
            while (System.currentTimeMillis() - wanStart < 20_000) {
                delay(2000)
                try {
                    val h = get("net_eth_links.asp")
                    if (h.length > 500 && !h.contains("formLogin")) {
                        wanReady = true
                        Log.d(TAG, "WAN lista en ${System.currentTimeMillis() - wanStart}ms")
                        break
                    }
                } catch (e: Exception) { /* sigue esperando */ }
            }
            if (!wanReady) {
                Log.w(TAG, "WAN no confirmada en 20s, esperando 3s más...")
                delay(3000)
            }
            ZteResult.Success(Unit)
        } catch (e: Exception) {
            ZteResult.Error("WAN LANLY: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  LAN  (= doLAN_LANLY del JS)
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun applyLan(dns1: String, dns2: String): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            refreshCsrf("net_dhcpd.asp")
            post("boaform/formDhcpServer", mapOf(
                "uIp"            to "192.168.1.1",
                "uMask"          to "255.255.255.0",
                "uDhcpType"      to "1",
                "dhcpRangeStart" to "192.168.1.33",
                "dhcpRangeEnd"   to "192.168.1.254",
                "ipMaskMode"     to "0",
                "ulTime"         to "86400",
                "ipv4landnsmode" to "1",
                "Ipv4Dns1"       to dns1,
                "Ipv4Dns2"       to dns2,
                "submit-url"     to "http://$onuIp/net_dhcpd.asp",
                "csrftoken"      to csrfToken
            ))
            delay(800)
            ZteResult.Success(Unit)
        } catch (e: Exception) {
            ZteResult.Error("LAN LANLY: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ACL  (= doACL_LANLY del JS)
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun applyAcl(): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            refreshCsrf("rmtacc.asp")
            post("boaform/formSAC", mapOf(
                "l_telnet"      to "1",
                "w_telnet"      to "1",
                "w_telnet_port" to "23",
                "w_telnet_ip"   to "",
                "w_ftp_port"    to "21",
                "w_ftp_ip"      to "",
                "l_web"         to "1",
                "w_web"         to "1",
                "w_web_port"    to "80",
                "w_web_ip"      to "",
                "l_https"       to "1",
                "w_https"       to "1",
                "w_https_port"  to "443",
                "w_https_ip"    to "",
                "w_icmp"        to "1",
                "w_icmp_ip"     to "",
                "set"           to "Aplicar",
                "submit-url"    to "/rmtacc.asp",
                "csrftoken"     to csrfToken
            ))
            delay(500)
            ZteResult.Success(Unit)
        } catch (e: Exception) {
            ZteResult.Error("ACL LANLY: ${e.message}")
        }
    }

    suspend fun applyUpnp(): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val upnpHtml = refreshCsrf("app_upnp.asp")
            val extIf = Regex("""ifIdx\s*=\s*(\d+)""").find(upnpHtml)?.groupValues?.get(1) ?: "130816"
            post("boaform/admin/formUpnp", mapOf(
                "daemon"     to "1",
                "ext_if"     to extIf,
                "save"       to "Guardar",
                "submit-url" to "/app_upnp.asp",
                "csrftoken"  to csrfToken
            ))
            delay(400)
            ZteResult.Success(Unit)
        } catch (e: Exception) {
            ZteResult.Error("UPnP LANLY: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  WiFi  (= doWifi_LANLY del JS)
    //  band: "2.4" o "5"
    //  IMPORTANTE: el WiFi 2.4 dispara un reboot del equipo (igual que en el JS)
    // ─────────────────────────────────────────────────────────────────────────
    // rebootCsrfForWifi24: token pre-obtenido antes del WiFi 2.4 para el reboot
    internal var rebootCsrfForWifi24 = ""

    suspend fun applyWifi(
        ssid: String, pass: String, band: String
    ): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            // FIX #3: un solo refreshCsrf al inicio del método — no duplicar en el else
            refreshCsrf("admin/wlbasic.asp")
            if (band == "2.4") {
                post("boaform/admin/formWlanSetup", mapOf(
                    "band"               to "10",
                    "mode"               to "0",
                    "ssid"               to ssid,
                    "pskFormat"          to "0",
                    "pskValue"           to pass,
                    "wl_wmm_func"        to "ON",
                    "chanwid"            to "1",
                    "chan"               to "0",
                    "txpower"            to "0",
                    "wl_limitstanum"     to "0",
                    "wl_stanum"          to "",
                    "regdomain_demo"     to "13",
                    "submit-url"         to "/admin/wlbasic.asp",
                    "save"               to "Aplicar Cambios",
                    "basicrates"         to "15",
                    "operrates"          to "4095",
                    "wlan_idx"           to "1",
                    "Band2G5GSupport"    to "1",
                    "wlanBand2G5GSelect" to "",
                    "dfs_enable"         to "1",
                    "regDomain"          to "11",
                    "csrftoken"          to csrfToken
                ))
            } else {
                // 5 GHz — FIX #3: NO hacer refreshCsrf de nuevo aquí
                post("boaform/admin/formWlanSetup", mapOf(
                    "band"               to "75",
                    "mode"               to "0",
                    "ssid"               to ssid,
                    "pskFormat"          to "0",
                    "pskValue"           to pass,
                    "wl_wmm_func"        to "ON",
                    "powerincrease"      to "ON",
                    "powersaving"        to "ON",
                    "chanwid"            to "2",
                    "ctlband"            to "0",
                    "chan"               to "153",
                    "txpower"            to "0",
                    "wl_limitstanum"     to "0",
                    "wl_stanum"          to "",
                    "regdomain_demo"     to "13",
                    "submit-url"         to "/admin/wlbasic.asp",
                    "save"               to "Aplicar Cambios",
                    "basicrates"         to "15",
                    "operrates"          to "4095",
                    "wlan_idx"           to "0",
                    "Band2G5GSupport"    to "2",
                    "wlanBand2G5GSelect" to "",
                    "dfs_enable"         to "1",
                    "regDomain"          to "11",
                    "csrftoken"          to csrfToken
                ))
            }
            delay(800)
            ZteResult.Success(Unit)
        } catch (e: Exception) {
            ZteResult.Error("WiFi LANLY ${band}GHz: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  READ CONFIG — lee WAN, LAN y WiFi actuales del equipo
    //  Equivalente a loginAndLoadLANLY() + loadConfigLANLY() del JS
    // ─────────────────────────────────────────────────────────────────────────
    data class LanlyConfig(
        val wanIp: String = "", val wanMask: String = "255.255.255.0",
        val wanGw: String = "", val wanVlan: String = "100",
        val lanIp: String = "192.168.1.1",
        val dhcpStart: String = "192.168.1.33", val dhcpEnd: String = "192.168.1.254",
        val dns1: String = "8.8.8.8", val dns2: String = "8.8.4.4",
        val ssid24: String = "", val pass24: String = "",
        val ssid5: String = "", val pass5: String = ""
    )

    suspend fun readConfig(): ZteResult<LanlyConfig> = withContext(Dispatchers.IO) {
        try {
            // Login si no hay token
            if (csrfToken.isBlank()) {
                val loginResult = login()
                if (loginResult is ZteResult.Error) return@withContext ZteResult.Error(loginResult.message)
            }

            // ── WAN ──
            // net_eth_links.asp almacena los valores como objetos JS: new it('campo', 'valor')
            // NO como <input value=""> — por eso el regex de inputs nunca encontraba nada
            var wanIp = ""; var wanMask = "255.255.255.0"; var wanGw = ""; var wanVlan = "100"
            try {
                val wanHtml = get("net_eth_links.asp")

                // itVal: extrae new it('campo', 'valor')  → para strings
                fun itVal(field: String): String? =
                    Regex("""new\s+it\s*\(\s*["']${Regex.escape(field)}["']\s*,\s*["']([^"']*)["']""")
                        .find(wanHtml)?.groupValues?.get(1)

                // itNum: extrae new it('campo', 123)  → para números (vid, vprio, etc.)
                fun itNum(field: String): String? =
                    Regex("""new\s+it\s*\(\s*["']${Regex.escape(field)}["']\s*,\s*([\d.]+)\)""")
                        .find(wanHtml)?.groupValues?.get(1)

                wanIp   = itVal("ipAddr")       ?: ""
                wanMask = itVal("netMask")       ?: "255.255.255.0"
                wanGw   = itVal("remoteIpAddr")  ?: ""
                wanVlan = itNum("vid")           ?: "100"

                Log.d(TAG, "readConfig WAN → ip=$wanIp mask=$wanMask gw=$wanGw vlan=$wanVlan")
            } catch (e: Exception) { Log.w(TAG, "readConfig WAN: ${e.message}") }

            // ── LAN ──
            var lanIp = "192.168.1.1"; var dhcpStart = "192.168.1.33"; var dhcpEnd = "192.168.1.254"
            var dns1 = "8.8.8.8"; var dns2 = "8.8.4.4"
            try {
                val lanHtml = get("net_dhcpd.asp")
                fun getInput(name: String) =
                    Regex("""name\s*=\s*["']$name["'][^>]*value\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(lanHtml)?.groupValues?.get(1)
                        ?: Regex("""value\s*=\s*["']([^"']*)["'][^>]*name\s*=\s*["']$name["']""", RegexOption.IGNORE_CASE).find(lanHtml)?.groupValues?.get(1)
                        ?: Regex("""name\s*=\s*["']$name["'][^>]*value\s*=\s*([^\s>"'][^\s>]*)""", RegexOption.IGNORE_CASE).find(lanHtml)?.groupValues?.get(1)
                lanIp     = getInput("uIp")            ?: "192.168.1.1"
                dhcpStart = getInput("dhcpRangeStart") ?: "192.168.1.33"
                dhcpEnd   = getInput("dhcpRangeEnd")   ?: "192.168.1.254"
                dns1      = getInput("Ipv4Dns1")       ?: "8.8.8.8"
                dns2      = getInput("Ipv4Dns2")       ?: "8.8.4.4"
            } catch (e: Exception) { Log.w(TAG, "readConfig LAN: ${e.message}") }

            // ── WiFi ──
            var ssid24 = ""; var pass24 = ""; var ssid5 = ""; var pass5 = ""
            try {
                // wlan_idx=0 → 5G,  wlan_idx=1 → 2.4G
                val html5  = get("admin/wlbasic.asp?wlan_idx=0")
                val html24 = get("admin/wlbasic.asp?wlan_idx=1")

                fun jsVar(h: String, name: String) = Regex("""var\s+$name\s*=\s*["']([^"']*)["']""").find(h)?.groupValues?.get(1)
                fun wpaPsk(h: String) = Regex("""[\t ]*_wpaPSK\s*\[\s*0\s*]\s*=\s*['"]([^'"]*)['"]""").find(h)?.groupValues?.get(1)
                fun inputSSID(h: String) =
                    Regex("""name\s*=\s*["']ssid["'][^>]*value\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(h)?.groupValues?.get(1)
                        ?: Regex("""value\s*=\s*["']([^"']*)["'][^>]*name\s*=\s*["']ssid["']""", RegexOption.IGNORE_CASE).find(h)?.groupValues?.get(1)

                ssid24 = jsVar(html24, "ssid_2g") ?: inputSSID(html24) ?: ""
                ssid5  = jsVar(html5,  "ssid_5g") ?: inputSSID(html5)  ?: ""
                pass24 = wpaPsk(html24) ?: ""
                pass5  = wpaPsk(html5)  ?: ""

            } catch (e: Exception) { Log.w(TAG, "readConfig WiFi: ${e.message}") }

            ZteResult.Success(LanlyConfig(wanIp, wanMask, wanGw, wanVlan, lanIp, dhcpStart, dhcpEnd, dns1, dns2, ssid24, pass24, ssid5, pass5))
        } catch (e: Exception) {
            ZteResult.Error("readConfig LANLY: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  PON prefetch — se llama ANTES del WiFi 2.4 porque ese dispara el reboot
    //  (= prefetchLanlyStatus del JS)
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun prefetchPon(): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            val html = get("status_gpon.asp")
            parseLanlyGpon(html)
        } catch (e: Exception) {
            Log.w(TAG, "Prefetch PON error (no crítico): ${e.message}")
            emptyMap()
        }
    }

    suspend fun prefetchDeviceInfo(): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            val html = get("status_device_basic_info.asp")
            parseLanlyDeviceInfo(html)
        } catch (e: Exception) {
            Log.w(TAG, "Prefetch DeviceInfo error: ${e.message}")
            emptyMap()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Parsers  (= parseLanlyGpon / parseLanlyDeviceInfo del JS)
    // ─────────────────────────────────────────────────────────────────────────
    private fun parseLanlyGpon(html: String): Map<String, String> {
        fun getByTh(label: String): String? {
            val re = Regex("""<th[^>]*>\s*$label\s*</th>\s*<td[^>]*>\s*([^<]+?)\s*</td>""", RegexOption.IGNORE_CASE)
            return re.find(html)?.groupValues?.get(1)?.trim()
        }
        fun num(s: String?): String = s?.replace(Regex("""[^\d.\-]"""), "")?.trim() ?: ""

        val tx      = num(getByTh("Potencia\\s+Tx"))
        val rx      = num(getByTh("Potencia\\s+Rx"))
        val temp    = num(getByTh("Temperatura"))
        val voltage = num(getByTh("Voltaje"))
        val bias    = num(getByTh("Corriente\\s+Bias"))

        return mapOf(
            "rx"      to rx,
            "tx"      to tx,
            "temp"    to temp,
            "voltage" to if (voltage.isNotBlank())
                (voltage.toDoubleOrNull()?.let { (it * 1_000_000).toLong().toString() } ?: voltage)
            else "",
            "bias"    to if (bias.isNotBlank())
                (bias.toDoubleOrNull()?.let { (it * 1000).toLong().toString() } ?: bias)
            else ""
        )
    }

    private fun parseLanlyDeviceInfo(html: String): Map<String, String> {
        val h = html.replace("&nbsp;", " ").replace(Regex("&#\\d+;"), "").replace(Regex("&[a-z]+;"), "")
        fun getByTh(label: String): String? {
            val re = Regex("""<th[^>]*>\s*$label\s*</th>\s*<td[^>]*>\s*([^<]+?)\s*</td>""", RegexOption.IGNORE_CASE)
            return re.find(h)?.groupValues?.get(1)?.trim()
        }
        val model  = getByTh("Modelo")                          ?: ""
        val sn     = getByTh("N[úu]mero\\s+Serie")             ?: ""
        // GPON SN: la LANLY usa "Número de serie" (con "de") — distinto a "Número Serie"
        val gponsn = getByTh("N[úu]mero\\s+de\\s+serie")       ?: ""
        val fw     = getByTh("Versi[óo]n\\s+Firmware")         ?: ""
        return mapOf(
            "model"  to model,
            "sn"     to sn,
            "gponsn" to gponsn,  // ← MainActivity busca data["sn"] para LANLY,
            // pero configurarLanly() usa data["sn"] en mostrarResultado.
            // Retornamos gponsn también como "sn" para que lo muestre en overlay
            "fw"     to fw
        ).let {
            // Si gponsn está disponible, usarlo como "sn" para el overlay
            if (gponsn.isNotBlank()) it + mapOf("sn" to gponsn) else it
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  REBOOT  (= do_reboot_benmundo del Python para LANLY BOA)
    //  FIX #1: calcula postSecurityFlag con postTableEncrypt antes de enviar
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun reboot(): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            // LANLY reboot: solo submit-url + csrftoken, SIN postSecurityFlag
            // (postSecurityFlag es solo para BENMUNDO — la LANLY no lo necesita)
            refreshCsrf("mgm_dev_reboot.asp")
            Log.d(TAG, "Reboot LANLY — csrf: ${csrfToken.take(8)}...")
            post("boaform/admin/formReboot", mapOf(
                "submit-url" to "/mgm_dev_reboot.asp",
                "csrftoken"  to csrfToken
            ))
            ZteResult.Success(Unit)
        } catch (e: Exception) {
            // Conexión cortada = reboot OK
            Log.d(TAG, "Reboot LANLY — conexión cortada (OK): ${e.message}")
            ZteResult.Success(Unit)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  APPLY ALL EXTENDED — ssid/pass 5G separados + LAN editable
    //  Orden: Login → WAN → LAN → ACL → UPnP → WiFi5 → prefetchPON → WiFi2.4 (reboot)
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun applyAllExtended(
        ip: String, mask: String, gw: String,
        vlan: String      = "100",
        dns1: String      = "8.8.8.8",
        dns2: String      = "8.8.4.4",
        lanIp: String     = "192.168.1.1",
        dhcpStart: String = "192.168.1.33",
        dhcpEnd: String   = "192.168.1.254",
        ssid24: String, pass24: String,
        ssid5g: String, pass5g: String,
        wifi5g: Boolean = true,
        onProgress: suspend (step: Int, total: Int, desc: String, ok: Boolean) -> Unit = { _, _, _, _ -> }
    ): ZteResult<Map<String, String>> {
        val total = if (wifi5g) 8 else 7
        val errors = mutableListOf<String>()

        suspend fun step(n: Int, desc: String, action: suspend () -> ZteResult<*>) {
            onProgress(n, total, desc, true)
            val r = action()
            if (r is ZteResult.Error) errors.add("$desc: ${r.message}")
            onProgress(n, total, desc, r is ZteResult.Success)
        }

        step(1, "Login")       { login() }
        step(2, "WAN")         { applyWan(ip, mask, gw, vlan) }
        step(3, "LAN / DNS")   { applyLanExtended(lanIp, dhcpStart, dhcpEnd, dns1, dns2) }
        step(4, "UPnP")        { applyUpnp() }
        step(5, "ACL")         { applyAcl()  }
        if (wifi5g) {
            step(6, "WiFi 5 GHz") { applyWifi(ssid5g, pass5g, "5") }
        }
        val pon = prefetchPon()
        val dev = prefetchDeviceInfo()
        step(if (wifi5g) 7 else 6, "Señal Óptica") { ZteResult.Success(Unit) }

        // Pre-obtener el csrfToken del reboot en el hilo IO, antes del WiFi 2.4
        // Guardar token actual — se usará para reboot después del WiFi 2.4
        // NO hacer GET a mgm_dev_reboot.asp porque puede fallar si la red cambia
        rebootCsrfForWifi24 = csrfToken
        Log.d(TAG, "Token guardado para reboot: ${csrfToken.take(8)}...")

        step(if (wifi5g) 8 else 7, "WiFi 2.4 GHz ← reinicia equipo") {
            applyWifi(ssid24, pass24, "2.4")
        }

        return if (errors.isEmpty()) ZteResult.Success(pon + dev)
        else ZteResult.Error(errors.joinToString("\n"))
    }

    /** LAN con IP LAN y rango DHCP editables */
    private suspend fun applyLanExtended(
        lanIp: String, dhcpStart: String, dhcpEnd: String, dns1: String, dns2: String
    ): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            refreshCsrf("net_dhcpd.asp")
            post("boaform/formDhcpServer", mapOf(
                "uIp"            to lanIp,
                "uMask"          to "255.255.255.0",
                "uDhcpType"      to "1",
                "dhcpRangeStart" to dhcpStart,
                "dhcpRangeEnd"   to dhcpEnd,
                "ipMaskMode"     to "0",
                "ulTime"         to "86400",
                "ipv4landnsmode" to "1",
                "Ipv4Dns1"       to dns1,
                "Ipv4Dns2"       to dns2,
                "submit-url"     to "http://$onuIp/net_dhcpd.asp",
                "csrftoken"      to csrfToken
            ))
            delay(800)
            ZteResult.Success(Unit)
        } catch (e: Exception) {
            ZteResult.Error("LAN LANLY: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  APPLY ALL — mismo orden que el JS:
    //  Login → WAN → LAN → ACL → UPnP → WiFi5 → prefetchPON → WiFi2.4 (reboot)
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun applyAll(
        ip: String, mask: String, gw: String,
        vlan: String  = "100",
        dns1: String  = "8.8.8.8",
        dns2: String  = "8.8.4.4",
        ssid: String,
        pass: String,
        wifi5g: Boolean = true,
        onProgress: suspend (step: Int, total: Int, desc: String, ok: Boolean) -> Unit = { _, _, _, _ -> }
    ): ZteResult<Map<String, String>> {
        val total  = if (wifi5g) 8 else 7
        val errors = mutableListOf<String>()

        suspend fun step(n: Int, desc: String, action: suspend () -> ZteResult<*>) {
            onProgress(n, total, desc, true)
            val r = action()
            if (r is ZteResult.Error) errors.add("$desc: ${r.message}")
            onProgress(n, total, desc, r is ZteResult.Success)
        }

        step(1, "Login")       { login() }
        step(2, "WAN")         { applyWan(ip, mask, gw, vlan) }
        step(3, "LAN / DNS")   { applyLan(dns1, dns2) }
        step(4, "UPnP")        { applyUpnp() }
        step(5, "ACL")         { applyAcl()  }
        if (wifi5g) {
            step(6, "WiFi 5 GHz") { applyWifi(ssid, pass, "5") }
        }
        val pon = prefetchPon()
        val dev = prefetchDeviceInfo()
        Log.d(TAG, "Prefetch PON: rx=${pon["rx"]} tx=${pon["tx"]}")

        step(if (wifi5g) 7 else 6, "Señal Óptica") { ZteResult.Success(Unit) }

        rebootCsrfForWifi24 = csrfToken
        Log.d(TAG, "Token guardado para reboot: ${csrfToken.take(8)}...")

        step(if (wifi5g) 8 else 7, "WiFi 2.4 GHz ← reinicia equipo") {
            applyWifi(ssid, pass, "2.4")
        }

        return if (errors.isEmpty()) ZteResult.Success(pon + dev)
        else ZteResult.Error(errors.joinToString("\n"))
    }
}