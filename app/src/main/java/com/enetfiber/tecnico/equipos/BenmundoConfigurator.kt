package com.enetfiber.tecnico.equipos
//FUNCIONAL 10/10
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class BenmundoConfigurator(
    private val onuIp: String = "192.168.101.1"
) {
    companion object {
        private const val TAG        = "BenmundoConfigurator"
        private const val TIMEOUT_MS = 12_000
        private const val USER       = "adminisp"
        private const val PASS       = "adminisp"
        private val CHKPT_ACTIVE = listOf("on","on","","","on","","","","on","","","")
    }

    private var csrfToken   = ""
    private val cookieStore = mutableMapOf<String, String>()

    // ─────────────────────────────────────────────────────────────────────────
    //  HTTP helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun get(path: String, referer: String? = null): String {
        val conn = URL("http://$onuIp/$path").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod           = "GET"
            conn.connectTimeout          = TIMEOUT_MS
            conn.readTimeout             = TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            if (referer != null) conn.setRequestProperty("Referer", "http://$onuIp/$referer")
            val cookie = cookieStore.entries.joinToString("; ") { "${it.key}=${it.value}" }
            if (cookie.isNotBlank()) conn.setRequestProperty("Cookie", cookie)
            conn.connect()
            saveCookies(conn)
            if (conn.responseCode in 300..399) return ""
            conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } catch (e: IOException) {
            throw RuntimeException("GET $path → ${e.message}")
        } finally { conn.disconnect() }
    }

    private fun post(path: String, params: Map<String, String>, referer: String? = null): String {
        val body = params.entries.joinToString("&") { "${urlEnc(it.key)}=${urlEnc(it.value)}" }
        return postRaw(path, body, referer)
    }

    private fun postPairs(path: String, pairs: List<Pair<String, String>>, referer: String? = null): String {
        val body = pairs.joinToString("&") { "${urlEnc(it.first)}=${urlEnc(it.second)}" }
        return postRaw(path, body, referer)
    }

    private fun postRaw(path: String, body: String, referer: String? = null): String {
        val conn = URL("http://$onuIp/$path").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod           = "POST"
            conn.connectTimeout          = TIMEOUT_MS
            conn.readTimeout             = TIMEOUT_MS
            conn.doOutput                = true
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=gb2312")
            conn.setRequestProperty("User-Agent",   "Mozilla/5.0")
            conn.setRequestProperty("Accept",       "text/html,application/xhtml+xml,*/*;q=0.8")
            if (referer != null) conn.setRequestProperty("Referer", "http://$onuIp/$referer")
            val cookie = cookieStore.entries.joinToString("; ") { "${it.key}=${it.value}" }
            if (cookie.isNotBlank()) conn.setRequestProperty("Cookie", cookie)
            conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
            saveCookies(conn)
            if (conn.responseCode in 300..399) return ""
            conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } catch (e: IOException) {
            val msg = e.message?.lowercase() ?: ""
            if (msg.contains("connection reset") || msg.contains("eof")      ||
                msg.contains("broken pipe")      || msg.contains("timeout")   ||
                msg.contains("econnreset")       || msg.contains("remotedisconnected") ||
                msg.contains("software caused")  || msg.contains("connection abort") ||
                msg.contains("failed to connect")|| msg.contains("connect")) {
                Log.d(TAG, "POST $path — conexión cortada (OK)")
                return ""
            }
            throw RuntimeException("POST $path → ${e.message}")
        } finally { conn.disconnect() }
    }

    private fun saveCookies(conn: HttpURLConnection) {
        conn.headerFields["Set-Cookie"]?.forEach { h ->
            val parts = h.split(";")[0].split("=", limit = 2)
            if (parts.size == 2) cookieStore[parts[0].trim()] = parts[1].trim()
        }
    }

    private fun urlEnc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ─────────────────────────────────────────────────────────────────────────
    //  postSecurityFlag — postTableEncrypt (BOA/Realtek)
    // ─────────────────────────────────────────────────────────────────────────
    private fun calcPsf(params: Map<String, String>): String {
        val skip = setOf("postSecurityFlag", "csrftoken")
        fun encVal(v: String): String = urlEnc(v).replace("%20", "+")
        fun encName(n: String): String = n.replace("[", "%5B").replace("]", "%5D")
        val input = params.entries
            .filter { it.key !in skip }
            .joinToString("&") { "${encName(it.key)}=${encVal(it.value)}" }
            .plus("&")
        var csum = 0L
        var i = 0
        while (i < input.length) {
            csum += when {
                i + 4 <= input.length -> {
                    ((input[i].code.toLong()   shl 24) +
                            (input[i+1].code.toLong() shl 16) +
                            (input[i+2].code.toLong() shl  8) +
                            input[i+3].code.toLong()).also { i += 4 }
                }
                else -> {
                    var v = 0L
                    if (i     < input.length) v += input[i  ].code.toLong() shl 24
                    if (i + 1 < input.length) v += input[i+1].code.toLong() shl 16
                    if (i + 2 < input.length) v += input[i+2].code.toLong() shl  8
                    i = input.length
                    v
                }
            }
        }
        csum = (csum and 0xffff) + (csum shr 16)
        csum = csum and 0xffff
        csum = csum.inv() and 0xffff
        return csum.toString()
    }

    private fun buildWanPairs(
        fields: LinkedHashMap<String, String>,
        chkpt: List<String> = CHKPT_ACTIVE
    ): List<Pair<String, String>> {
        fields["postSecurityFlag"] = calcPsf(fields)
        val pairs = fields.entries.map { it.key to it.value }.toMutableList()
        chkpt.forEach { v -> pairs.add("chkpt" to v) }
        return pairs
    }

    private fun postWithPsf(path: String, params: LinkedHashMap<String, String>, referer: String? = null): String {
        params["postSecurityFlag"] = calcPsf(params)
        return post(path, params, referer)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers de parseo HTML
    // ─────────────────────────────────────────────────────────────────────────

    private fun extractCsrf(html: String): String {
        listOf(
            Regex("""name\s*=\s*["']csrftoken["'][^>]*value\s*=\s*["']([a-f0-9]{32})["']""", RegexOption.IGNORE_CASE),
            Regex("""value\s*=\s*["']([a-f0-9]{32})["'][^>]*name\s*=\s*["']csrftoken["']""", RegexOption.IGNORE_CASE),
            Regex("""csrftoken["']?\s*[=:]\s*["']?([a-f0-9]{32})""",                         RegexOption.IGNORE_CASE)
        ).forEach { p -> p.find(html)?.let { return it.groupValues[1] } }
        return ""
    }

    private fun extractPsf(html: String): String {
        return (Regex("""name\s*=\s*["']postSecurityFlag["'][^>]*value\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""value\s*=\s*["']([^"']*)["'][^>]*name\s*=\s*["']postSecurityFlag["']""", RegexOption.IGNORE_CASE).find(html))
            ?.groupValues?.get(1) ?: ""
    }

    private fun extractChallenge(html: String): String {
        return (Regex("""name\s*=\s*["']challenge["'][^>]*value\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""value\s*=\s*["']([^"']*)["'][^>]*name\s*=\s*["']challenge["']""",  RegexOption.IGNORE_CASE).find(html))
            ?.groupValues?.get(1) ?: ""
    }

    private fun getInput(html: String, name: String): String {
        // Con comillas
        val m1 = Regex("""name\s*=\s*["']$name["'][^>]*value\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""value\s*=\s*["']([^"']*)["'][^>]*name\s*=\s*["']$name["']""",  RegexOption.IGNORE_CASE).find(html)
        if (m1 != null) return m1.groupValues[1]
        // Sin comillas (value=192.168.x.x sin quotes) ← EL FIX
        val m2 = Regex("""name\s*=\s*["']$name["'][^>]*value\s*=\s*([^\s>"'][^\s>]*)""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""value\s*=\s*([^\s>"'][^\s>]*)[^>]*name\s*=\s*["']$name["']""",  RegexOption.IGNORE_CASE).find(html)
        return m2?.groupValues?.get(1) ?: ""
    }

    private fun isLoginPage(html: String) =
        html.length < 1000 || html.contains("formLogin") || html.contains("login.asp")

    private fun itVal(html: String, field: String): String =
        Regex("""new\s+it\(["']$field["']\s*,\s*["']([^"']*)["']\)""").find(html)?.groupValues?.get(1) ?: ""

    private fun itNum(html: String, field: String): String =
        Regex("""new\s+it\(["']$field["']\s*,\s*([\d]+)\)""").find(html)?.groupValues?.get(1) ?: ""

    private fun getItfGroup(html: String): String {
        val m = Regex("""new\s+it\s*\(\s*["']itfGroup["']\s*,\s*(\d+)\s*\)""").find(html)
        val v = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return if (v > 0) v.toString() else "275"
    }

    private fun getExistingLst(html: String): String? =
        Regex("""new\s+it_nr\s*\(\s*["'](nas[\w_]+)["']""").find(html)?.groupValues?.get(1)

    // ─────────────────────────────────────────────────────────────────────────
    //  Parseo WiFi
    // ─────────────────────────────────────────────────────────────────────────
    private fun parseSsid(html: String): String {
        val start = html.indexOf("ssid0:")
        if (start != -1) {
            val open  = html.indexOf('{', start)
            val close = html.indexOf('}', open)
            if (open != -1 && close != -1) {
                Regex("""_ssid\s*:\s*['"]([^'"]+)['"]""").find(html.substring(open + 1, close))
                    ?.let { return it.groupValues[1] }
            }
        }
        return Regex("""_ssid\s*:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1) ?: ""
    }

    private fun parsePsk(html: String): String {
        val start = html.indexOf("ssid0:")
        if (start != -1) {
            val open = html.indexOf('{', start)
            val end  = html.indexOf("ssid1:", start).let { if (it != -1) it else html.indexOf('}', open) + 1 }
            if (open != -1 && end > open) {
                Regex("""_wpaPSK\s*:\s*['"]([^'"]+)['"]""").find(html.substring(open, end))
                    ?.let { return it.groupValues[1] }
            }
        }
        return Regex("""_wpaPSK\s*:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1) ?: ""
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Data class resultado
    // ─────────────────────────────────────────────────────────────────────────
    data class BenmundoConfig(
        val wanIp:     String = "",
        val wanMask:   String = "255.255.255.0",
        val wanGw:     String = "",
        val wanVlan:   String = "100",
        val lanIp:     String = "192.168.101.1",
        val dhcpStart: String = "192.168.101.2",
        val dhcpEnd:   String = "192.168.101.254",
        val dns1:      String = "8.8.8.8",
        val dns2:      String = "8.8.4.4",
        val ssid24:    String = "",
        val pass24:    String = "",
        val ssid5:     String = "",
        val pass5:     String = ""
    )

    // ─────────────────────────────────────────────────────────────────────────
    //  LOGIN
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun login(
        user: String = USER,
        pass: String = PASS
    ): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val loginPage = get("admin/login.asp")
            val psf       = extractPsf(loginPage)
            val challenge = extractChallenge(loginPage)
            val csrf      = extractCsrf(loginPage)
            if (csrf.isNotBlank()) csrfToken = csrf
            Log.d(TAG, "login psf=$psf challenge=$challenge csrf=$csrf")
            val params = linkedMapOf(
                "challenge"        to challenge,
                "username"         to user,
                "password"         to pass,
                "save"             to "Login",
                "submit-url"       to "/admin/login.asp",
                "postSecurityFlag" to psf
            )
            if (csrf.isNotBlank()) params["csrftoken"] = csrf
            post("boaform/admin/formLogin", params)
            for (page in listOf("multi_wan_generic.asp", "status_device_basic_info.asp", "admin/wlbasic.asp")) {
                try {
                    val h = get(page)
                    if (h.length > 500 && !h.contains("login.asp") && !h.contains("formLogin")) {
                        val tok2 = extractCsrf(h)
                        if (tok2.isNotBlank()) csrfToken = tok2
                        Log.d(TAG, "Login BENMUNDO OK via $page")
                        return@withContext ZteResult.Success(Unit)
                    }
                } catch (_: Exception) { }
            }
            ZteResult.Error("Credenciales inválidas para BENMUNDO")
        } catch (e: Exception) {
            ZteResult.Error("Login BENMUNDO: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  READ CONFIG
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun readConfig(): ZteResult<BenmundoConfig> = withContext(Dispatchers.IO) {
        try {
            if (cookieStore.isEmpty()) {
                val r = login()
                if (r is ZteResult.Error) return@withContext ZteResult.Error(r.message)
            }

            var wanIp = ""; var wanMask = "255.255.255.0"; var wanGw = ""
            var wanVlan = "100"; var dns1 = "8.8.8.8"; var dns2 = "8.8.4.4"
            try {
                val html = get("multi_wan_generic.asp")
                if (!isLoginPage(html)) {
                    wanIp   = itVal(html, "ipAddr")
                    wanMask = itVal(html, "netMask").ifBlank { "255.255.255.0" }
                    wanGw   = itVal(html, "remoteIpAddr")
                    wanVlan = itNum(html, "vid").ifBlank { "100" }
                    dns1    = itVal(html, "v4dns1").ifBlank { "8.8.8.8" }
                    dns2    = itVal(html, "v4dns2").ifBlank { "8.8.4.4" }
                }
            } catch (e: Exception) { Log.w(TAG, "readConfig WAN: ${e.message}") }

            var lanIp = "192.168.101.1"
            var dhcpStart = "192.168.101.2"; var dhcpEnd = "192.168.101.254"
            try {
                // FIX 2: reloguea si sesión expiró
                var html = get("dhcpd.asp")
                if (isLoginPage(html)) {
                    Log.w(TAG, "readConfig LAN: sesión expirada, relogueando...")
                    login()
                    html = get("dhcpd.asp")
                }
                if (!isLoginPage(html)) {
                    lanIp = getInput(html, "lan_ip")
                        .ifBlank { getInput(html, "ip") }
                        .ifBlank { getInput(html, "uIp") }       // fallback LANLY-style
                        .ifBlank { itVal(html, "lan_ip") }       // fallback new it(...)
                        .ifBlank { itVal(html, "ipAddr") }
                        .ifBlank { "192.168.101.1" }

                    dhcpStart = getInput(html, "dhcpRangeStart")
                        .ifBlank { getInput(html, "dhcpStart") }
                        .ifBlank { itVal(html, "dhcpRangeStart") }
                        .ifBlank { itVal(html, "dhcpStart") }
                        .ifBlank { "192.168.101.2" }

                    dhcpEnd = getInput(html, "dhcpRangeEnd")
                        .ifBlank { getInput(html, "dhcpEnd") }
                        .ifBlank { itVal(html, "dhcpRangeEnd") }
                        .ifBlank { itVal(html, "dhcpEnd") }
                        .ifBlank { "192.168.101.254" }

                    val d1 = getInput(html, "dns1")
                        .ifBlank { getInput(html, "Ipv4Dns1") }
                        .ifBlank { itVal(html, "dns1") }
                    val d2 = getInput(html, "dns2")
                        .ifBlank { getInput(html, "Ipv4Dns2") }
                        .ifBlank { itVal(html, "dns2") }
                    if (d1.isNotBlank()) dns1 = d1
                    if (d2.isNotBlank()) dns2 = d2
                }
            } catch (e: Exception) { Log.w(TAG, "readConfig LAN: ${e.message}") }

            var ssid24 = ""; var pass24 = ""; var ssid5 = ""; var pass5 = ""
            try {
                val html5  = get("wlan_basic_five.asp?wlan_idx=0")
                val html24 = get("wlan_basic_two.asp?wlan_idx=1")
                if (!isLoginPage(html5))  { ssid5  = parseSsid(html5);  pass5  = parsePsk(html5)  }
                if (!isLoginPage(html24)) { ssid24 = parseSsid(html24); pass24 = parsePsk(html24) }
            } catch (e: Exception) { Log.w(TAG, "readConfig WiFi: ${e.message}") }

            Log.d(TAG, "readConfig OK wanIp=$wanIp vlan=$wanVlan lanIp=$lanIp ssid24=$ssid24 ssid5=$ssid5")
            ZteResult.Success(BenmundoConfig(wanIp, wanMask, wanGw, wanVlan, lanIp, dhcpStart, dhcpEnd, dns1, dns2, ssid24, pass24, ssid5, pass5))
        } catch (e: Exception) {
            ZteResult.Error("readConfig BENMUNDO: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  APPLY WAN
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun applyWan(
        ip: String, mask: String, gw: String,
        vlan: String = "100", dns1: String = "8.8.8.8", dns2: String = "8.8.4.4"
    ): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val wanPage     = get("multi_wan_generic.asp")
            val itfGroup    = getItfGroup(wanPage)
            val existingLst = getExistingLst(wanPage)
            Log.d(TAG, "WAN existingLst=$existingLst itfGroup=$itfGroup")
            if (existingLst != null) {
                try {
                    val delFields = linkedMapOf(
                        "lkname"     to existingLst,
                        "lst"        to existingLst,
                        "action"     to "rm",
                        "itfGroup"   to itfGroup,
                        "submit-url" to "/multi_wan_generic.asp"
                    )
                    postPairs("boaform/admin/formWanEth", buildWanPairs(delFields),
                        referer = "multi_wan_generic.asp")
                    delay(1500)
                } catch (e: Exception) { Log.w(TAG, "WAN delete: ${e.message}") }
            }
            val wanFields = linkedMapOf(
                "lkname"               to (existingLst ?: "new"),
                "vlan"                 to "ON",
                "vid"                  to vlan,
                "vprio"                to "1",
                "multicast_vid"        to "",
                "adslConnectionMode"   to "1",
                "brmode"               to "0",
                "naptEnabled"          to "ON",
                "chEnable"             to "1",
                "ctype"                to "2",
                "mtu"                  to "1480",
                "droute"               to "1",
                "IpProtocolType"       to "1",
                "auth"                 to "0",
                "acName"               to "",
                "serviceName"          to "",
                "ipMode"               to "0",
                "ip"                   to ip,
                "remoteIp"             to gw,
                "netmask"              to mask,
                "dnsMode"              to "0",
                "dns1"                 to dns1,
                "dns2"                 to dns2,
                "gwStr"                to "",
                "wanIf"                to "",
                "SixrdBRv4IP"          to "",
                "SixrdIPv4MaskLen"     to "",
                "SixrdPrefix"          to "",
                "SixrdPrefixLen"       to "",
                "AddrMode"             to "1",
                "Ipv6Addr"             to "",
                "Ipv6PrefixLen"        to "",
                "Ipv6Gateway"          to "",
                "iana"                 to "ON",
                "dnsV6Mode"            to "1",
                "dslite_aftr_hostname" to "",
                "submit-url"           to "/multi_wan_generic.asp",
                "lst"                  to (existingLst ?: ""),
                "encodePppUserName"    to "",
                "encodePppPassword"    to "",
                "apply"                to "Apply Changes",
                "itfGroup"             to itfGroup
            )
            postPairs("boaform/admin/formWanEth", buildWanPairs(wanFields),
                referer = "multi_wan_generic.asp")
            delay(2000)
            Log.d(TAG, "WAN BENMUNDO OK ip=$ip vlan=$vlan itfGroup=$itfGroup")
            ZteResult.Success(Unit)
        } catch (e: Exception) {
            ZteResult.Error("WAN BENMUNDO: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  APPLY LAN
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun applyLan(
        lanIp: String     = "192.168.101.1",
        dhcpStart: String = "192.168.101.2",
        dhcpEnd: String   = "192.168.101.254",
        dns1: String      = "8.8.8.8",
        dns2: String      = "8.8.4.4"
    ): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val params = linkedMapOf(
                "lan_ip"         to lanIp,
                "lan_mask"       to "255.255.255.0",
                "dhcpdenable"    to "2",
                "dhcpRangeStart" to dhcpStart,
                "dhcpRangeEnd"   to dhcpEnd,
                "dhcpSubnetMask" to "255.255.255.0",
                "ltime"          to "43200",
                "dname"          to "bbrouter",
                "ip"             to lanIp,
                "dhcpdns"        to "1",
                "dns1"           to dns1,
                "dns2"           to dns2,
                "dns3"           to "1.1.1.1",
                "save"           to "Apply Changes",
                "submit-url"     to "/dhcpd.asp"
            )
            postWithPsf("boaform/formDhcpServer", params, referer = "dhcpd.asp")
            delay(800)
            Log.d(TAG, "LAN BENMUNDO OK ip=$lanIp")
            ZteResult.Success(Unit)
        } catch (e: Exception) {
            ZteResult.Error("LAN BENMUNDO: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  APPLY ACL
    //  FIX 1: default cambiado de onuIp a "192.168.101.1"
    //         applyAll() ahora pasa lanIp real
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun applyAcl(lanIp: String = "192.168.101.1"): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val params = linkedMapOf(
                "lan_ip"        to lanIp,
                "lan_mask"      to "255.255.255.0",
                "aclcap"        to "1",
                "enable"        to "1",
                "interface"     to "1",
                "aclstartIP"    to "0.0.0.0",
                "aclendIP"      to "255.255.255.255",
                "l_telnet_port" to "23",
                "l_ftp_port"    to "21",
                "l_web_port"    to "80",
                "l_https_port"  to "443",
                "l_ssh_port"    to "22",
                "l_icmp"        to "1",
                "w_telnet"      to "1",
                "w_telnet_port" to "23",
                "w_ftp_port"    to "21",
                "w_web"         to "1",
                "w_web_port"    to "80",
                "w_https"       to "1",
                "w_https_port"  to "443",
                "w_ssh_port"    to "22",
                "w_icmp"        to "1",
                "addIP"         to "Add",
                "submit-url"    to "/acl.asp"
            )
            postWithPsf("boaform/admin/formACL", params, referer = "acl.asp")
            delay(500)
            ZteResult.Success(Unit)
        } catch (e: Exception) {
            ZteResult.Error("ACL BENMUNDO: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  APPLY UPnP
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun applyUpnp(): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val upnpPage = try { get("upnp.asp") } catch (_: Exception) { "" }
            val extIf = Regex("""<option[^>]+value\s*=\s*["']?(\d+)["']?""", RegexOption.IGNORE_CASE)
                .findAll(upnpPage)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .firstOrNull { it in 1..65534 }
                ?.toString() ?: "130816"
            Log.d(TAG, "UPnP ext_if=$extIf")
            val params = linkedMapOf(
                "daemon"     to "1",
                "ext_if"     to extIf,
                "submit-url" to "/upnp.asp"
            )
            postWithPsf("boaform/formUpnp", params, referer = "upnp.asp")
            delay(400)
            ZteResult.Success(Unit)
        } catch (e: Exception) {
            ZteResult.Error("UPnP BENMUNDO: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  APPLY WiFi — band: "5" o "24"
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun applyWifi(ssid: String, pass: String, band: String): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val is5       = band == "5"
            val wlanIdx   = if (is5) "0" else "1"
            val submitUrl = if (is5) "/wlan_basic_five.asp" else "/wlan_basic_two.asp"
            val page      = if (is5) "wlan_basic_five.asp?wlan_idx=0" else "wlan_basic_two.asp?wlan_idx=1"
            val html = try { get(page) } catch (_: Exception) { "" }
            Log.d(TAG, "applyWifi band=$band ssid=$ssid page len=${html.length}")
            val params = linkedMapOf(
                "wlanOnOff"         to "0",
                "SSIDindex"         to "0",
                "wlanDisabled"      to "0",
                "hidessid"          to "0",
                "ssid"              to ssid,
                "encrypt"           to "6",
                "wpa2UnicastCipher" to "3",
                "wpaPSK"            to pass,
                "wlan_idx"          to wlanIdx,
                "submit-btn"        to "Apply Changes",
                "submit-url"        to submitUrl
            )
            val body = params.entries.joinToString("&") { "${urlEnc(it.key)}=${urlEnc(it.value)}" }
            val conn = URL("http://$onuIp/boaform/admin/formCdtWlanSetup").openConnection() as java.net.HttpURLConnection
            conn.requestMethod  = "POST"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout    = TIMEOUT_MS
            conn.doOutput       = true
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Content-Type",     "application/x-www-form-urlencoded; charset=gb2312")
            conn.setRequestProperty("User-Agent",       "Mozilla/5.0")
            conn.setRequestProperty("Accept",           "text/html,application/xhtml+xml,*/*;q=0.8")
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            conn.setRequestProperty("Referer",          "http://$onuIp/$page")
            val cookie = cookieStore.entries.joinToString("; ") { "${it.key}=${it.value}" }
            if (cookie.isNotBlank()) conn.setRequestProperty("Cookie", cookie)
            conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
            saveCookies(conn)
            Log.d(TAG, "applyWifi band=$band status=${conn.responseCode}")
            delay(800)
            ZteResult.Success(Unit)
        } catch (e: Exception) {
            val msg = e.message?.lowercase() ?: ""
            val esDesconexion = msg.contains("software caused") || msg.contains("connection abort") ||
                    msg.contains("failed to connect") || msg.contains("connection reset") ||
                    msg.contains("broken pipe") || msg.contains("eof") || msg.contains("timeout")
            if (esDesconexion) {
                Log.d(TAG, "applyWifi band=$band — equipo reiniciando (OK)")
                ZteResult.Success(Unit)
            } else {
                ZteResult.Error("WiFi ${band}GHz BENMUNDO: ${e.message}")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  READ PON
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun readPon(): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            val html = get("status_pon.asp")
            if (isLoginPage(html)) return@withContext emptyMap()
            fun byTh(label: String): String =
                Regex("""<th[^>]*>\s*$label\s*</th>\s*<td[^>]*>\s*([^<]+?)\s*</td>""", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.get(1)?.trim() ?: ""
            fun num(s: String) = s.replace(Regex("[^\\d.\\-]"), "").trim()
            val rxRaw = byTh("Rx\\s*Power")
            val txRaw = byTh("Tx\\s*Power")
            mapOf(
                "rx"   to if (rxRaw.contains("no", ignoreCase = true)) "" else num(rxRaw),
                "tx"   to if (txRaw.contains("no", ignoreCase = true)) "" else num(txRaw),
                "temp" to num(byTh("Temperature")),
                "volt" to num(byTh("Voltage")),
                "bias" to num(byTh("Bias\\s*Current"))
            )
        } catch (e: Exception) {
            Log.w(TAG, "PON error: ${e.message}")
            emptyMap()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  READ DEVICE INFO
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun readDeviceInfo(): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            for (page in listOf("status.asp", "status_device_basic_info.asp")) {
                val html = try { get(page) } catch (_: Exception) { continue }
                if (isLoginPage(html)) continue
                fun byTh(label: String): String =
                    Regex("""<th[^>]*>\s*$label\s*</th>\s*<td[^>]*>\s*([^<]+?)\s*</td>""",
                        RegexOption.IGNORE_CASE)
                        .find(html)?.groupValues?.get(1)?.trim() ?: ""
                val model  = byTh("Device\\s*Name").ifBlank { byTh("Model(?:\\s*Name)?") }
                val sn     = byTh("Serial\\s*Number")
                val gponsn = byTh("PON\\s*SN")
                    .ifBlank { byTh("GPON\\s*SN") }
                    .ifBlank { byTh("GPON\\s+Serial(?:\\s+Number)?") }
                if (model.isNotBlank() || gponsn.isNotBlank() || sn.isNotBlank()) {
                    Log.d(TAG, "DeviceInfo OK [$page] model=$model gponsn=$gponsn sn=$sn")
                    return@withContext mapOf("model" to model, "sn" to sn, "gponsn" to gponsn)
                }
            }
            emptyMap()
        } catch (e: Exception) {
            Log.w(TAG, "DeviceInfo error: ${e.message}")
            emptyMap()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  REBOOT
    //  FIX 3: agrega Referer igual que el proxy Python que sí funciona
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun reboot(): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val params = linkedMapOf("submit-url" to "/mgm_dev_reboot.asp")
            postWithPsf(
                "boaform/admin/formReboot",
                params,
                referer = "mgm_dev_reboot.asp"  // ← FIX 3
            )
            ZteResult.Success(Unit)
        } catch (e: Exception) {
            ZteResult.Success(Unit)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  APPLY ALL
    //  FIX 1: applyAcl(lanIp) — pasa la IP LAN real
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun applyAll(
        ip: String, mask: String, gw: String,
        vlan: String      = "100",
        dns1: String      = "8.8.8.8",
        dns2: String      = "8.8.4.4",
        lanIp: String     = "192.168.101.1",
        dhcpStart: String = "192.168.101.2",
        dhcpEnd: String   = "192.168.101.254",
        ssid24: String, pass24: String,
        ssid5: String,   pass5: String,
        onProgress: suspend (step: Int, total: Int, desc: String, ok: Boolean) -> Unit = { _, _, _, _ -> }
    ): ZteResult<Map<String, String>> {
        val total  = 8
        val errors = mutableListOf<String>()

        suspend fun step(n: Int, desc: String, action: suspend () -> ZteResult<*>) {
            onProgress(n, total, desc, true)
            val r = action()
            if (r is ZteResult.Error) errors.add("$desc: ${r.message}")
            onProgress(n, total, desc, r is ZteResult.Success)
        }

        step(1, "Login") { login() }
        if (errors.isNotEmpty()) return ZteResult.Error(errors.joinToString("\n"))

        step(2, "WAN")  { applyWan(ip, mask, gw, vlan, dns1, dns2) }
        step(3, "LAN")  { applyLan(lanIp, dhcpStart, dhcpEnd, dns1, dns2) }
        step(4, "ACL")  { applyAcl(lanIp) }  // ← FIX 1
        step(5, "UPnP") { applyUpnp() }

        val pon = readPon()
        val dev = readDeviceInfo()
        Log.d(TAG, "Prefetch PON=${pon["rx"]} gponsn=${dev["gponsn"]}")

        val psf24 = calcPsf(linkedMapOf(
            "wlanOnOff"         to "0",
            "SSIDindex"         to "0",
            "wlanDisabled"      to "0",
            "hidessid"          to "0",
            "ssid"              to ssid24,
            "encrypt"           to "6",
            "wpa2UnicastCipher" to "3",
            "wpaPSK"            to pass24,
            "wlan_idx"          to "1",
            "submit-btn"        to "Apply Changes",
            "submit-url"        to "/wlan_basic_two.asp"
        ))
        Log.d(TAG, "PSF 2.4G calculado: $psf24")

        step(6, "WiFi") {
            withContext(Dispatchers.IO) {
                kotlinx.coroutines.coroutineScope {
                    launch { postWifi(ssid24, pass24, "1", "/wlan_basic_two.asp",  "wlan_basic_two.asp?wlan_idx=1") }
                    launch { postWifi(ssid5,  pass5,  "0", "/wlan_basic_five.asp", "wlan_basic_five.asp?wlan_idx=0") }
                }
            }
            ZteResult.Success(Unit)
        }
        delay(1000)
        step(7, "Reinicio") { reboot() }

        val resultMap = pon + dev +
                if (errors.isNotEmpty()) mapOf("errors" to errors.joinToString("\n")) else emptyMap()
        return ZteResult.Success(resultMap)
    }

    private fun postWifi(
        ssid: String, pass: String,
        wlanIdx: String, submitUrl: String, referer: String
    ) {
        try {
            val body = listOf(
                "wlanOnOff"         to "0",
                "SSIDindex"         to "0",
                "wlanDisabled"      to "0",
                "hidessid"          to "0",
                "ssid"              to ssid,
                "encrypt"           to "6",
                "wpa2UnicastCipher" to "3",
                "wpaPSK"            to pass,
                "wlan_idx"          to wlanIdx,
                "submit-btn"        to "Apply Changes",
                "submit-url"        to submitUrl
            ).joinToString("&") { "${urlEnc(it.first)}=${urlEnc(it.second)}" }

            val conn = URL("http://$onuIp/boaform/admin/formCdtWlanSetup")
                .openConnection() as HttpURLConnection
            conn.requestMethod           = "POST"
            conn.connectTimeout          = 5000
            conn.readTimeout             = 5000
            conn.doOutput                = true
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Content-Type",      "application/x-www-form-urlencoded; charset=gb2312")
            conn.setRequestProperty("User-Agent",        "Mozilla/5.0")
            conn.setRequestProperty("X-Requested-With",  "XMLHttpRequest")
            conn.setRequestProperty("Referer",           "http://$onuIp/$referer")
            val cookie = cookieStore.entries.joinToString("; ") { "${it.key}=${it.value}" }
            if (cookie.isNotBlank()) conn.setRequestProperty("Cookie", cookie)
            conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
            Log.d(TAG, "postWifi wlan_idx=$wlanIdx status=${conn.responseCode}")
            conn.disconnect()
        } catch (e: Exception) {
            Log.d(TAG, "postWifi wlan_idx=$wlanIdx — ${e.message}")
        }
    }

    private suspend fun applyWifiConPsf(
        ssid: String, pass: String, psf: String
    ): ZteResult<Unit> = withContext(Dispatchers.IO) {
        val params = linkedMapOf(
            "wlanOnOff"         to "0",
            "SSIDindex"         to "0",
            "wlanDisabled"      to "0",
            "hidessid"          to "0",
            "ssid"              to ssid,
            "encrypt"           to "6",
            "wpa2UnicastCipher" to "3",
            "wpaPSK"            to pass,
            "wlan_idx"          to "1",
            "submit-btn"        to "Apply Changes",
            "submit-url"        to "/wlan_basic_two.asp",
            "postSecurityFlag"  to psf
        )
        val body = params.entries.joinToString("&") { "${urlEnc(it.key)}=${urlEnc(it.value)}" }
        Log.d(TAG, "applyWifiConPsf ssid=$ssid psf=$psf")
        repeat(3) { intento ->
            try {
                val conn = URL("http://$onuIp/boaform/admin/formCdtWlanSetup")
                    .openConnection() as HttpURLConnection
                conn.requestMethod           = "POST"
                conn.connectTimeout          = TIMEOUT_MS
                conn.readTimeout             = TIMEOUT_MS
                conn.doOutput                = true
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("Content-Type",      "application/x-www-form-urlencoded; charset=gb2312")
                conn.setRequestProperty("User-Agent",        "Mozilla/5.0")
                conn.setRequestProperty("Accept",            "text/html,application/xhtml+xml,*/*;q=0.8")
                conn.setRequestProperty("X-Requested-With",  "XMLHttpRequest")
                conn.setRequestProperty("Referer",           "http://$onuIp/wlan_basic_two.asp?wlan_idx=1")
                val cookie = cookieStore.entries.joinToString("; ") { "${it.key}=${it.value}" }
                if (cookie.isNotBlank()) conn.setRequestProperty("Cookie", cookie)
                conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
                saveCookies(conn)
                Log.d(TAG, "applyWifiConPsf intento ${intento+1} status=${conn.responseCode}")
                conn.disconnect()
                delay(800)
                return@withContext ZteResult.Success(Unit)
            } catch (e: Exception) {
                Log.d(TAG, "applyWifiConPsf intento ${intento+1} — ${e.message}")
                if (intento < 2) delay(2000)
            }
        }
        ZteResult.Success(Unit)
    }
}