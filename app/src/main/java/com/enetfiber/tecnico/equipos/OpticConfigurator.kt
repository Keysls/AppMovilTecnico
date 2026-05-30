package com.enetfiber.tecnico.equipos
//FUNCIONAL 10/10

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * OPTIC MODELO ZK9004W
 */

// ─────────────────────────────────────────────────────────────────────────────
//  OPTIC XPON — equivalente exacto del JS doLogin_OPTIC / doWAN_OPTIC / etc.
// ─────────────────────────────────────────────────────────────────────────────
class OpticConfigurator(
    private val onuIp: String = "192.168.1.1"
) {
    companion object {
        private const val TAG = "OpticConfigurator"
        private const val TIMEOUT_MS = 12_000
    }

    private var sessionKey = ""
    private val cookieStore = mutableMapOf<String, String>()

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private fun get(path: String): String {
        val url = "http://$onuIp/cgi-bin/$path"
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout    = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val cookie = cookieStore.entries.joinToString("; ") { "${it.key}=${it.value}" }
            if (cookie.isNotBlank()) conn.setRequestProperty("Cookie", cookie)
            conn.instanceFollowRedirects = false
            conn.connect()
            saveCookies(conn)
            if (conn.responseCode in 300..399) return ""
            conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } catch (e: IOException) {
            throw RuntimeException("GET $path → ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    private fun post(path: String, params: Map<String, String>): String {
        val url = "http://$onuIp/cgi-bin/$path"
        val body = params.entries.joinToString("&") {
            "${urlEnc(it.key)}=${urlEnc(it.value)}"
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod  = "POST"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout    = TIMEOUT_MS
            conn.doOutput       = true
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val cookie = cookieStore.entries.joinToString("; ") { "${it.key}=${it.value}" }
            if (cookie.isNotBlank()) conn.setRequestProperty("Cookie", cookie)
            conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
            saveCookies(conn)
            if (conn.responseCode in 300..399) return ""
            conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } catch (e: IOException) {
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

    private fun b64(s: String) = Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    // ── Extrae sessionkey del HTML ────────────────────────────────────────────
    private fun extractKey(html: String): String? {
        val patterns = listOf(
            Regex("""gcsessionkey\s*=\s*["']([a-zA-Z0-9]{20,50})["']"""),
            Regex("""var\s+\w*[Ss]ession[Kk]ey\w*\s*=\s*["']([a-zA-Z0-9]{20,50})["']""")
        )
        for (p in patterns) {
            val m = p.find(html)
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    private fun refreshSessionKey(page: String) {
        val html = get(page)
        val key  = extractKey(html)
        if (key != null) {
            sessionKey = key
            Log.d(TAG, "sessionKey desde $page: ${key.take(8)}...")
        } else {
            Log.w(TAG, "WARN: no se encontró sessionkey en $page")
        }
    }

    // ── Parsers PON y Device ──────────────────────────────────────────────────
    private fun parsePonHtml(html: String): Map<String, String> {
        fun getById(id: String): String? {
            val m = Regex("""id="$id"[^>]*>([^<]*)<""").find(html)
            return m?.groupValues?.get(1)?.trim()
        }
        return mapOf(
            "rx"      to (getById("poninfo_rxpower")     ?: ""),
            "tx"      to (getById("poninfo_txpower")     ?: ""),
            "voltage" to (getById("poninfo_voltage")     ?: ""),
            "bias"    to (getById("poninfo_biascurrent") ?: ""),
            "temp"    to (getById("poninfo_worktemp")    ?: "")
        )
    }

    private fun parseDeviceInfo(html: String): Map<String, String> {
        fun getById(id: String): String? {
            val m = Regex("""id="$id"[^>]*>\s*([^<]+?)\s*<""").find(html)
            return m?.groupValues?.get(1)?.trim()
        }
        return mapOf(
            "model"  to (getById("devinceinfo_modelname") ?: ""),
            "gponsn" to (getById("devinceinfo_gponsn")    ?: ""),
            "sn"     to (getById("devinceinfo_sn")        ?: "")
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  LOGIN
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun login(): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            get("login.cgi")
            Log.d(TAG, "1. GET login.cgi OK")

            post("login.cgi", mapOf(
                "onSubmit"       to "1",
                "login_language" to "English",
                "encryPassword"  to "54b53072540eeeb8f8e9343e71f28176",
                "encryUsername"  to "21232f297a57a5a743894a0e4a801fc3"
            ))
            Log.d(TAG, "2. POST login.cgi OK")

            val html3 = get("index.cgi")
            Log.d(TAG, "3. index.cgi len=${html3.length}")

            val pages = listOf("index.cgi", "wanpon_edit.cgi", "dhcpgateway.cgi", "wlantop.cgi")
            for (page in pages) {
                val html = if (page == "index.cgi") html3 else get(page)
                val key  = extractKey(html)
                if (key != null) {
                    sessionKey = key
                    Log.d(TAG, "SessionKey OK: ${key.take(10)}... desde $page")
                    return@withContext ZteResult.Success(Unit)
                }
            }
            ZteResult.Error("No se encontró sessionkey. Verifica login OPTIC.")
        } catch (e: Exception) {
            ZteResult.Error("Login OPTIC: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  WAN
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun applyWan(
        ip: String, mask: String, gw: String, vlan: String = "100"
    ): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            for (idx in listOf("1", "2")) {
                try {
                    refreshSessionKey("wanpon_edit.cgi")
                    get("wanpon_edit.cgi?sessionkey=$sessionKey&pvceditindex=$idx&child_index=1&encapmode=IPoE&operate=delpvc&onSubmit=2")
                    Log.d(TAG, "Perfil $idx borrado")
                    delay(800)
                } catch (e: Exception) {
                    Log.d(TAG, "Perfil $idx no existía")
                }
            }
            delay(1000)
            refreshSessionKey("wanpon_edit.cgi")

            post("wanpon_edit.cgi", mapOf(
                "wanpon_connect_type"                  to "",
                "pvcindex"                             to "-1",
                "child_index"                          to "-1",
                "pvcenable"                            to "1",
                "pvcdhcpenable"                        to "1",
                "encapmode"                            to "IPoE",
                "pvcipprotocol"                        to "1",
                "pppauthtype"                          to "",
                "pppconntrigger"                       to "",
                "ipacqmode"                            to "Static",
                "ipnat"                                to "1",
                "sessionkey"                           to sessionKey,
                "onSubmit"                             to "1",
                "ipv6getmodeid"                        to "",
                "ipv6staticguaid"                      to "",
                "ipv6staticguagwid"                    to "",
                "ipv6staticdnsid"                      to "",
                "prefixdelegateid"                     to "",
                "X_8021pvalue"                         to "0",
                "wanpon_operation"                     to "add",
                "dslite_enable"                        to "0",
                "dslite_addressmode"                   to "DHCPv6",
                "laninterface"                         to "InternetGatewayDevice.LANDevice.1.LANEthernetInterfaceConfig.1,InternetGatewayDevice.LANDevice.1.LANEthernetInterfaceConfig.2,InternetGatewayDevice.LANDevice.1.LANEthernetInterfaceConfig.3,InternetGatewayDevice.LANDevice.1.LANEthernetInterfaceConfig.4,InternetGatewayDevice.WiFi.SSID.9,InternetGatewayDevice.WiFi.SSID.1",
                "nptv6_enable"                         to "0",
                "wanpon_connectionType"                to "IP_Routed",
                "dscp_enable"                          to "0",
                "pppoe_proxyenable"                    to "0",
                "passthrough_enable"                   to "0",
                "servicetype"                          to "INTERNET",
                "wanponedit_option60_enable"           to "",
                "wanponedit_option125_enable"          to "",
                "page_action"                          to "",
                "wanpon_connectname"                   to "9",
                "wanpon_encapsulatemode"               to "IPoE",
                "wanpon_protocol"                      to "1",
                "wanponedit_servicetype"               to "INTERNET",
                "wanponedit_vlanmode"                  to "2",
                "wanponedit_vlanid"                    to vlan,
                "wanponedit_8021p"                     to "0",
                "wanponedit_ppptranstype"              to "PPPoE",
                "wanponedit_pppusername"               to "",
                "wanponedit_ppppassword"               to "",
                "wanponedit_dmsname"                   to "",
                "wanponedit_authtype"                  to "Auto",
                "wanponedit_ppptrigger"                to "AlwaysOn",
                "wanponedit_idletime"                  to "",
                "wanponedit_Ipv4getmode"               to "Static",
                "wanponedit_staticip"                  to ip,
                "wanponedit_staticmask"                to mask,
                "wanponedit_staticgateway"             to gw,
                "wanponedit_staticdns1"                to "8.8.8.8",
                "wanponedit_staticdns2"                to "8.8.4.4",
                "wanponedit_staticdns3"                to "1.1.1.1",
                "ipv6getmode"                          to "Manual",
                "wanponedit_ipv6staticgua"             to "None",
                "wanponedit_ipv6staticguaipaddress"    to "",
                "wanponedit_ipv6staticguaiplen"        to "",
                "wanponedit_ipv6staticguaipgwaddress"  to "",
                "wanponedit_ipv6staticdns1"            to "",
                "wanponedit_ipv6staticdns2"            to "",
                "wanponedit_ipv6staticdns3"            to "",
                "wanponedit_ipv6staticpdipprefix"      to "",
                "wanponedit_ipv6staticpdiplen"         to "",
                "wanponedit_ipv6staticdsliteipaddress" to "",
                "wanponedit_nat"                       to "on",
                "wanponedit_mtu"                       to "1480",
                "wanponedit_multicastvlan"             to "",
                "wanponedit_option60username"          to "",
                "wanponedit_option60password"          to "",
                "wanponedit_option125username"         to ""
            ))
            Log.d(TAG, "Perfil WAN OPTIC creado OK")
            delay(1000)
            ZteResult.Success(Unit)
        } catch (e: Exception) {
            ZteResult.Error("WAN OPTIC: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  LAN (valores fijos — legacy)
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun applyLan(dns1: String, dns2: String): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            refreshSessionKey("dhcpgateway.cgi")
            post("dhcpgateway.cgi", mapOf(
                "sessionkey"          to sessionKey,
                "onSubmit"            to "1",
                "dhcpserverenable"    to "1",
                "dhcpipchanged"       to "",
                "lanispdns"           to "0",
                "dhcpsv_ispleasetime" to "86400",
                "dhcpgw_ipaddress"    to "192.168.1.1",
                "dhcpgw_subnetmask"   to "255.255.255.0",
                "dhcpsv_dhcpenable"   to "on",
                "dhcpsv_startip"      to "192.168.1.2",
                "dhcpsv_endip"        to "192.168.1.254",
                "dhcpsv_dns1"         to dns1,
                "dhcpsv_dns2"         to dns2
            ))
            delay(800)
            ZteResult.Success(Unit)
        } catch (e: Exception) {
            ZteResult.Error("LAN OPTIC: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  LAN EXTENDED (IP LAN y DHCP editables)
    // ─────────────────────────────────────────────────────────────────────────
    private suspend fun applyLanExtended(
        lanIp: String, dhcpStart: String, dhcpEnd: String, dns1: String, dns2: String
    ): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            refreshSessionKey("dhcpgateway.cgi")
            post("dhcpgateway.cgi", mapOf(
                "sessionkey"          to sessionKey,
                "onSubmit"            to "1",
                "dhcpserverenable"    to "1",
                "dhcpipchanged"       to "",
                "lanispdns"           to "0",
                "dhcpsv_ispleasetime" to "86400",
                "dhcpgw_ipaddress"    to lanIp,
                "dhcpgw_subnetmask"   to "255.255.255.0",
                "dhcpsv_dhcpenable"   to "on",
                "dhcpsv_startip"      to dhcpStart,
                "dhcpsv_endip"        to dhcpEnd,
                "dhcpsv_dns1"         to dns1,
                "dhcpsv_dns2"         to dns2
            ))
            delay(800)
            ZteResult.Success(Unit)
        } catch (e: Exception) {
            ZteResult.Error("LAN OPTIC: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ACL
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun applyAcl(): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            refreshSessionKey("acl.cgi")
            post("acl.cgi", mapOf(
                "onSubmit"          to "1",
                "sessionkey"        to sessionKey,
                "lan_http_enable"   to "1",
                "lan_https_enable"  to "1",
                "lan_telnet_enable" to "1",
                "lan_ssh_enable"    to "1",
                "wifi_web_enable"   to "1",
                "wifi_webs_enable"  to "1",
                "wan_http_enable"   to "1",
                "wan_https_enable"  to "1",
                "wan_telnet_enable" to "1",
                "wan_icmp_enable"   to "1"
            ))
            delay(500)
            ZteResult.Success(Unit)
        } catch (e: Exception) {
            ZteResult.Error("ACL OPTIC: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  UPnP
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun applyUpnp(): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            refreshSessionKey("upnp.cgi")
            post("upnp.cgi", mapOf(
                "onSubmit"    to "1",
                "sessionkey"  to sessionKey,
                "upnpenable"  to "1",
                "upnp_enable" to "on"
            ))
            delay(400)
            ZteResult.Success(Unit)
        } catch (e: Exception) {
            ZteResult.Error("UPnP OPTIC: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  WiFi — band: "2.4" o "5", idx: 1=2.4GHz, 5=5GHz
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun applyWifi(
        ssid: String, pass: String, band: String
    ): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            refreshSessionKey("wlantop.cgi")
            val is5g = band == "5" || band == "5g"
            val passB64   = b64(pass)
            val bw        = if (is5g) "160MHz" else "40MHz"
            val std       = if (is5g) "a,n,ac,ax" else "b,g,n,ax"
            val idx  = if (is5g) "5" else "1"

            post("wlantop.cgi", mapOf(
                "sessionkey"                to sessionKey,
                "onSubmit"                  to "1",
                "Enable"                    to "1",
                "RadioEnabled"              to "1",
                "ModeEnabled"               to "WPA-WPA2-Personal",
                "wep_authmode"              to "",
                "WEPEncryptionLevel"        to "",
                "SSIDAdvertisementEnabled"  to "1",
                "Channel"                   to "",
                "AutoChannelEnable"         to "1",
                "OperatingChannelBandwidth" to bw,
                "TransmitPower"             to "100",
                "external_idx"              to idx,
                "SSID"                      to ssid,
                "X_GC_HT_GuardInterval"     to "0",
                "WmmEnable"                 to "1",
                "backup_external_idx"       to "",
                "Backup_Enable"             to "0",
                "Backup_SSID"               to "${ssid}Wifi5",
                "BandEnable"                to "1",
                "KeyPassphrase_input"       to passB64,
                "WEPKey"                    to b64("12345"),
                "SAEPassphrase_input"       to passB64,
                "RadioEnabled_bt"           to "on",
                "BandEnable_bt"             to "on",
                "country_region"            to "PE",
                "standard"                  to std,
                "bw"                        to bw,
                "channel"                   to "0",
                "SGIEnabled_bt"             to "on",
                "BeaconPeriod"              to "100",
                "transmitpower"             to "100",
                "DTIMPeriod"                to "3",
                "wlan_twt"                  to "1",
                "external_idx_sel"          to idx,
                "Enable_bt"                 to "on",
                "MaxAllowedAssociations"    to "16",
                "SSID_input"                to ssid,
                "authmode"                  to "WPA-WPA2-Personal",
                "radius_server"             to "",
                "radius_port"               to "0",
                "radius_key"                to "",
                "wpa3_encryptionmode"       to "AES",
                "wpa_encryptionmode"        to "TKIP+AES",
                "wep_encryption_level"      to "40-bit",
                "WMM_Enable"                to "on"
            ))
            delay(800)
            ZteResult.Success(Unit)
        } catch (e: Exception) {
            ZteResult.Error("WiFi OPTIC ${band}GHz: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  READ CONFIG
    // ─────────────────────────────────────────────────────────────────────────
    data class OpticConfig(
        val wanIp: String = "", val wanMask: String = "255.255.255.0",
        val wanGw: String = "", val wanVlan: String = "100",
        val lanIp: String = "192.168.1.1",
        val dhcpStart: String = "192.168.1.2", val dhcpEnd: String = "192.168.1.254",
        val dns1: String = "8.8.8.8", val dns2: String = "8.8.4.4",
        val ssid24: String = "", val pass24: String = "",
        val ssid5: String = "", val pass5: String = ""
    )

    suspend fun readConfig(): ZteResult<OpticConfig> = withContext(Dispatchers.IO) {
        try {
            if (sessionKey.isBlank()) {
                val loginResult = login()
                if (loginResult is ZteResult.Error) return@withContext ZteResult.Error(loginResult.message)
            }

            // ── WAN ──
            var wanIp = ""; var wanMask = "255.255.255.0"; var wanGw = ""; var wanVlan = "100"
            try {
                val wanListHtml = get("wanpon_edit.cgi")
                val pvcMatch = Regex("""var\s+ifinfo\s*=\s*['"]([^'"]+)['"]""").find(wanListHtml)
                var wanHtml = wanListHtml
                if (pvcMatch != null) {
                    val first = pvcMatch.groupValues[1].split("#")[0].split("/")[0]
                    val parts = first.split("_")
                    if (parts.size >= 3) {
                        val pvcIdx = parts[0]; val childIdx = parts[1]; val encap = parts[2]
                        wanHtml = get("wanpon_edit.cgi?pvceditindex=$pvcIdx&child_index=$childIdx&encapmode=$encap&operate=modify&onSubmit=2")
                    }
                }
                fun jsVar(name: String) = Regex("""var\s+$name\s*=\s*["']([^"']*)["']""").find(wanHtml)?.groupValues?.get(1)
                fun inputVal(name: String) =
                    Regex("""name\s*=\s*["']$name["'][^>]*value\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(wanHtml)?.groupValues?.get(1)
                        ?: Regex("""value\s*=\s*["']([^"']+)["'][^>]*name\s*=\s*["']$name["']""", RegexOption.IGNORE_CASE).find(wanHtml)?.groupValues?.get(1)

                var vlan = jsVar("vlanid") ?: ""
                if (vlan.isBlank()) {
                    val wl = jsVar("wannamelist") ?: ""
                    vlan = Regex("""_VID_(\d+)""").find(wl)?.groupValues?.get(1) ?: ""
                    if (vlan.isBlank()) {
                        vlan = Regex("""var\s+ifinfo\s*=\s*['"][^'"]*_VID_(\d+)""").find(wanHtml)?.groupValues?.get(1) ?: "100"
                    }
                }
                wanIp   = inputVal("wanponedit_staticip")      ?: ""
                wanMask = inputVal("wanponedit_staticmask")    ?: "255.255.255.0"
                wanGw   = inputVal("wanponedit_staticgateway") ?: ""
                wanVlan = vlan.ifBlank { "100" }
            } catch (e: Exception) { Log.w(TAG, "readConfig WAN: ${e.message}") }

            // ── LAN ──
            var lanIp = "192.168.1.1"; var dhcpStart = "192.168.1.2"; var dhcpEnd = "192.168.1.254"
            var dns1 = "8.8.8.8"; var dns2 = "8.8.4.4"
            try {
                val lanHtml = get("dhcpgateway.cgi")
                fun getInput(name: String) =
                    Regex("""name\s*=\s*["']$name["'][^>]*value\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(lanHtml)?.groupValues?.get(1)
                        ?: Regex("""value\s*=\s*["']([^"']*)["'][^>]*name\s*=\s*["']$name["']""", RegexOption.IGNORE_CASE).find(lanHtml)?.groupValues?.get(1)
                lanIp     = getInput("dhcpgw_ipaddress") ?: "192.168.1.1"
                dhcpStart = getInput("dhcpsv_startip")   ?: "192.168.1.2"
                dhcpEnd   = getInput("dhcpsv_endip")     ?: "192.168.1.254"
                dns1      = getInput("dhcpsv_dns1")      ?: "8.8.8.8"
                dns2      = getInput("dhcpsv_dns2")      ?: "8.8.4.4"
            } catch (e: Exception) { Log.w(TAG, "readConfig LAN: ${e.message}") }

            // ── WiFi ──
            var ssid24 = ""; var pass24 = ""; var ssid5 = ""; var pass5 = ""
            try {
                val html24 = post("wlantop.cgi", mapOf("sessionkey" to sessionKey, "onSubmit" to "0", "external_idx" to "1", "external_idx_sel" to "1"))
                val html5  = post("wlantop.cgi", mapOf("sessionkey" to sessionKey, "onSubmit" to "0", "external_idx" to "5", "external_idx_sel" to "5"))
                fun getSSID(h: String) =
                    Regex("""name\s*=\s*["']SSID_input["'][^>]*value\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(h)?.groupValues?.get(1)
                        ?: Regex("""value\s*=\s*["']([^"']*)["'][^>]*name\s*=\s*["']SSID_input["']""", RegexOption.IGNORE_CASE).find(h)?.groupValues?.get(1)
                fun getPSK(h: String) =
                    Regex("""name\s*=\s*["']KeyPassphrase_input["'][^>]*value\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(h)?.groupValues?.get(1)
                        ?: Regex("""value\s*=\s*["']([^"']*)["'][^>]*name\s*=\s*["']KeyPassphrase_input["']""", RegexOption.IGNORE_CASE).find(h)?.groupValues?.get(1)
                ssid24 = getSSID(html24) ?: ""
                pass24 = getPSK(html24)  ?: ""
                ssid5  = getSSID(html5)  ?: ""
                pass5  = getPSK(html5)   ?: ""
            } catch (e: Exception) { Log.w(TAG, "readConfig WiFi: ${e.message}") }

            ZteResult.Success(OpticConfig(wanIp, wanMask, wanGw, wanVlan, lanIp, dhcpStart, dhcpEnd, dns1, dns2, ssid24, pass24, ssid5, pass5))
        } catch (e: Exception) {
            ZteResult.Error("readConfig OPTIC: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  PON y Device Info
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun readPon(): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            parsePonHtml(get("poninfo.cgi"))
        } catch (e: Exception) {
            Log.w(TAG, "PON error: ${e.message}")
            emptyMap()
        }
    }

    suspend fun readDeviceInfo(): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            parseDeviceInfo(get("deviceinfo.cgi"))
        } catch (e: Exception) {
            Log.w(TAG, "DeviceInfo error: ${e.message}")
            emptyMap()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  REBOOT — secuencia de 4 pasos igual que el Python del proxy
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun reboot(): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            refreshSessionKey("reboot.cgi")
            val html1 = get("reboot.cgi")
            val key1  = extractKey(html1)
                ?: return@withContext ZteResult.Error("Sin key en reboot.cgi")

            post("reboot.cgi", mapOf(
                "onSubmit"   to "loading",
                "sessionkey" to key1
            ))
            delay(1000)

            val html3 = get("loading.cgi?url=reboot.cgi&waittime=60&operation=docmd")
            val key3  = extractKey(html3)
                ?: return@withContext ZteResult.Error("Sin key en loading.cgi")
            delay(1000)

            post("reboot.cgi", mapOf(
                "onSubmit"   to "docmd",
                "sessionkey" to key3
            ))
            ZteResult.Success(Unit)
        } catch (e: Exception) {
            ZteResult.Success(Unit) // Conexión cortada = reboot OK
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  APPLY ALL EXTENDED
    //  Login → WAN → LAN → ACL → UPnP → WiFi5 → WiFi2.4
    //  Usa lanIp, dhcpStart, dhcpEnd y ssid/pass independientes por banda
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun applyAllExtended(
        ip: String, mask: String, gw: String,
        vlan: String      = "100",
        dns1: String      = "8.8.8.8",
        dns2: String      = "8.8.4.4",
        lanIp: String     = "192.168.1.1",
        dhcpStart: String = "192.168.1.2",
        dhcpEnd: String   = "192.168.1.254",
        ssid24: String, pass24: String,
        ssid5g: String,  pass5g: String,
        wifi5g: Boolean   = true,
        onProgress: suspend (step: Int, total: Int, desc: String, ok: Boolean) -> Unit = { _, _, _, _ -> }
    ): ZteResult<Map<String, String>> {
        val total  = if (wifi5g) 7 else 6
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
        step(4, "ACL")         { applyAcl() }
        step(5, "UPnP")        { applyUpnp() }
        if (wifi5g) {
            step(6, "WiFi 5 GHz") { applyWifi(ssid5g, pass5g, "5") }
        }

        // Agrega:
        delay(500)
        val pon = readPon()
        val dev = readDeviceInfo()

        step(if (wifi5g) 7 else 6, "WiFi 2.4 GHz") { applyWifi(ssid24, pass24, "2.4") }


        reboot()

        return if (errors.isEmpty()) ZteResult.Success(pon + dev)
        else ZteResult.Error(errors.joinToString("\n"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  APPLY ALL (legacy — mismo SSID/pass para ambas bandas, LAN fija)
    //
    //  FIX: readPon + readDeviceInfo se mueven ANTES del WiFi — sesión estable
    //  FIX: reboot() dentro del flujo, ya estaba presente
    //
    //  ORDEN CORRECTO:
    //  Login → WAN → LAN → ACL → UPnP → [readPon+readDeviceInfo] → WiFi5 → WiFi2.4 → Reboot
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun applyAll(
        ip: String, mask: String, gw: String,
        vlan: String    = "100",
        dns1: String    = "8.8.8.8",
        dns2: String    = "8.8.4.4",
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

        step(1, "Login")     { login() }
        step(2, "WAN")       { applyWan(ip, mask, gw, vlan) }
        step(3, "LAN / DNS") { applyLan(dns1, dns2) }
        step(4, "ACL")       { applyAcl() }
        step(5, "UPnP")      { applyUpnp() }

        // FIX: leer PON y Device AQUÍ — sesión estable, ANTES del WiFi
        step(6, "Señal Óptica") { ZteResult.Success(Unit) }
        val pon = readPon()
        val dev = readDeviceInfo()
        Log.d(TAG, "Prefetch → rx=${pon["rx"]} tx=${pon["tx"]} gponsn=${dev["gponsn"]}")

        if (wifi5g) {
            step(7, "WiFi 5 GHz") { applyWifi(ssid, pass, "5") }
        }
        step(if (wifi5g) 8 else 7, "WiFi 2.4 GHz") { applyWifi(ssid, pass, "2.4") }

        reboot()

        return if (errors.isEmpty()) ZteResult.Success(pon + dev)
        else ZteResult.Error(errors.joinToString("\n"))
    }
}