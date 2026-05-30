package com.enetfiber.tecnico.equipos

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
/**
 * DixonConfigurator
 *
 * Soporta tres modelos Dixon:
 *   - D130GW    → BOA clásico (HTTP form), solo 2.4 GHz
 *   - D580GW-AX → API JSON get.json/post.json con token Bearer
 *   - D110GWC   → misma API JSON que AX, solo 2.4 GHz
 *
 * Uso:
 *   val conf = DixonConfigurator(onuIp = "192.168.101.1", modelo = DixonModelo.D130GW)
 *   val result = conf.readConfig()
 *   val result = conf.applyAll(ip, mask, gw, ...)
 */
class DixonConfigurator(
    private val onuIp: String = "192.168.101.1",
    val modelo: DixonModelo = DixonModelo.D130GW
) {
    companion object {
        private const val TAG        = "DixonConfigurator"
        private const val TIMEOUT_MS = 12_000
        private const val USER       = "adminisp"
        private const val PASS       = "adminisp"
    }

    enum class DixonModelo { D130GW, D580GW_AX, D110GWC }

    /** Solo D130GW usa BOA; los otros dos usan API JSON */
    private val esBoa  get() = modelo == DixonModelo.D130GW
    /** Solo D130GW y D110GWC son solo 2.4 GHz */
    val solo24g get() = modelo == DixonModelo.D130GW || modelo == DixonModelo.D110GWC

    // ─────────────────────────────────────────────────────────────────────────
    //  Estado interno
    // ─────────────────────────────────────────────────────────────────────────
    private val cookieStore = mutableMapOf<String, String>()
    private var axToken     = ""   // Bearer token para API AX
    private var csrfToken   = ""   // CSRF para BOA (D130GW)

    data class DixonConfig(
        val wanIp:     String = "",
        val wanMask:   String = "255.255.255.0",
        val wanGw:     String = "",
        val wanVlan:   String = "100",
        val lanIp:     String = "192.168.101.1",
        val dhcpStart: String = "192.168.101.2",
        val dhcpEnd:   String   = "192.168.101.254",
        val dns1:      String = "8.8.8.8",
        val dns2:      String = "8.8.4.4",
        val ssid24:    String = "",
        val pass24:    String = "",
        val ssid5:     String = "",
        val pass5:     String = ""
    )

    // ═════════════════════════════════════════════════════════════════════════
    //  readConfig — lee la configuración actual del equipo
    // ═════════════════════════════════════════════════════════════════════════
    suspend fun readConfig(): ZteResult<DixonConfig> = withContext(Dispatchers.IO) {
        try {
            if (esBoa) readConfigBoa()
            else       readConfigAx()
        } catch (e: Exception) {
            ZteResult.Error("readConfig DIXON ${modelo.name}: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  D130GW — BOA
    // ─────────────────────────────────────────────────────────────────────────
    private suspend fun readConfigBoa(): ZteResult<DixonConfig> {
        loginBoa()

        var wanIp = ""; var wanMask = "255.255.255.0"; var wanGw = ""
        var wanVlan = "100"; var dns1 = "8.8.8.8"; var dns2 = "8.8.4.4"
        try {
            val html = boaGet("net_eth_links.asp")
            wanIp   = itVal(html, "ipAddr")
            wanMask = itVal(html, "netMask").ifBlank { "255.255.255.0" }
            wanGw   = itVal(html, "remoteIpAddr")
            wanVlan = itNum(html, "vid").ifBlank { "100" }
            dns1    = itVal(html, "v4dns1").ifBlank { "8.8.8.8" }
            dns2    = itVal(html, "v4dns2").ifBlank { "8.8.4.4" }
        } catch (e: Exception) { Log.w(TAG, "readConfigBoa WAN: ${e.message}") }

        var lanIp = "192.168.101.1"; var dhcpStart = "192.168.101.2"; var dhcpEnd = "192.168.101.254"
        try {
            val html = boaGet("net_dhcpd.asp")
            lanIp    = getInput(html, "uIp").ifBlank { "192.168.101.1" }
            dhcpStart = getInput(html, "dhcpRangeStart").ifBlank { "192.168.101.2" }
            dhcpEnd   = getInput(html, "dhcpRangeEnd").ifBlank { "192.168.101.254" }
        } catch (e: Exception) { Log.w(TAG, "readConfigBoa LAN: ${e.message}") }

        var ssid24 = ""; var pass24 = ""
        try {
            val basicHtml = boaGet("net_wlan_basic_11n.asp")
            val advHtml   = boaGet("net_wlan_adv.asp")
            ssid24 = Regex("_ssid\\s*\\[\\s*0\\s*\\]\\s*=\\s*['\"]([^'\"]+)['\"]").find(basicHtml)?.groupValues?.get(1)
                ?: getInput(basicHtml, "ssid")
            pass24 = Regex("_wpaPSK\\s*\\[\\s*0\\s*\\]\\s*=\\s*['\"]([^'\"]*)['\"]").find(advHtml)?.groupValues?.get(1) ?: ""
        } catch (e: Exception) { Log.w(TAG, "readConfigBoa WiFi: ${e.message}") }

        return ZteResult.Success(DixonConfig(
            wanIp, wanMask, wanGw, wanVlan, lanIp, dhcpStart, dhcpEnd,
            dns1, dns2, ssid24, pass24, "", ""
        ))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  D580GW-AX / D110GWC — API JSON
    // ─────────────────────────────────────────────────────────────────────────
    private suspend fun readConfigAx(): ZteResult<DixonConfig> {
        loginAx()

        var wanIp = ""; var wanMask = "255.255.255.0"; var wanGw = ""
        var wanVlan = "100"; var dns1 = "8.8.8.8"; var dns2 = "8.8.4.4"
        try {
            val wanData = axGet("wan_confignew")
            val wan = wanData.optJSONArray("wan")?.optJSONObject(0)
            if (wan != null) {
                val ipv4 = wan.optJSONObject("ipv4")
                wanIp   = ipv4?.optString("ip_address", "") ?: ""
                wanMask = ipv4?.optString("subnet_mask", "255.255.255.0") ?: "255.255.255.0"
                wanGw   = ipv4?.optString("gateway", "") ?: ""
                wanVlan = wan.optInt("vlanId", 100).toString()
                val dnsArr = ipv4?.optJSONArray("dns")
                dns1 = dnsArr?.optString(0, "8.8.8.8") ?: "8.8.8.8"
                dns2 = dnsArr?.optString(1, "8.8.4.4") ?: "8.8.4.4"
            }
        } catch (e: Exception) { Log.w(TAG, "readConfigAx WAN: ${e.message}") }

        var lanIp = "192.168.101.1"; var dhcpStart = "192.168.101.2"; var dhcpEnd = "192.168.101.254"
        try {
            val lanData = axGet("ipv4_lan_config")
            lanIp     = lanData.optJSONObject("base_config")?.optString("lan_ip", "192.168.101.1") ?: "192.168.101.1"
            val dhcp  = lanData.optJSONObject("dhcp_config")
            dhcpStart = dhcp?.optString("dhcp_range_start", "192.168.101.2") ?: "192.168.101.2"
            dhcpEnd   = dhcp?.optString("dhcp_range_end", "192.168.101.254") ?: "192.168.101.254"
        } catch (e: Exception) { Log.w(TAG, "readConfigAx LAN: ${e.message}") }

        var ssid24 = ""; var pass24 = ""; var ssid5 = ""; var pass5 = ""
        try {
            val wifiData = axGet("wlan_config")
            val w24 = wifiData.optJSONObject("wlan")?.optJSONObject("2_4G")?.optJSONArray("SSID")?.optJSONObject(0)
            val w5  = wifiData.optJSONObject("wlan")?.optJSONObject("5G")?.optJSONArray("SSID")?.optJSONObject(0)
            ssid24 = w24?.optString("ssid", "") ?: ""
            pass24 = w24?.optString("wpa_pre_shared_key", "") ?: ""
            ssid5  = w5?.optString("ssid", "") ?: ""
            pass5  = w5?.optString("wpa_pre_shared_key", "") ?: ""
        } catch (e: Exception) { Log.w(TAG, "readConfigAx WiFi: ${e.message}") }

        return ZteResult.Success(DixonConfig(
            wanIp, wanMask, wanGw, wanVlan, lanIp, dhcpStart, dhcpEnd,
            dns1, dns2, ssid24, pass24, ssid5, pass5
        ))
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  applyAll — aplica la configuración completa
    // ═════════════════════════════════════════════════════════════════════════
    suspend fun applyAll(
        ip: String, mask: String, gw: String,
        vlan: String      = "100",
        dns1: String      = "8.8.8.8",
        dns2: String      = "8.8.4.4",
        lanIp: String     = "192.168.101.1",
        dhcpStart: String = "192.168.101.2",
        dhcpEnd: String   = "192.168.101.254",
        ssid24: String, pass24: String,
        ssid5: String  = "",  pass5: String  = "",
        onProgress: suspend (step: Int, total: Int, desc: String, ok: Boolean) -> Unit = { _, _, _, _ -> }
    ): ZteResult<Map<String, String>> {
        Log.d(TAG, "applyAll INICIO modelo=$modelo onuIp=$onuIp")  // ← AGREGAR AQUI
        val total = if (esBoa) 6 else 8  // ← AX tiene 8 steps ahora
        val errors = mutableListOf<String>()

        suspend fun step(n: Int, desc: String, action: suspend () -> ZteResult<*>) {
            onProgress(n, total, desc, true)
            val r = action()
            if (r is ZteResult.Error) errors.add("$desc: ${r.message}")
            onProgress(n, total, desc, r is ZteResult.Success)
        }

        return try {
            if (esBoa) applyAllBoa(ip, mask, gw, vlan, dns1, dns2, lanIp, dhcpStart, dhcpEnd, ssid24, pass24, errors, total, onProgress)
            else       applyAllAx(ip, mask, gw, vlan, dns1, dns2, lanIp, dhcpStart, dhcpEnd, ssid24, pass24, ssid5, pass5, errors, total, onProgress)
        } catch (e: Exception) {
            ZteResult.Error("applyAll DIXON ${modelo.name}: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  D130GW apply
    // ─────────────────────────────────────────────────────────────────────────
    private suspend fun applyAllBoa(
        ip: String, mask: String, gw: String, vlan: String,
        dns1: String, dns2: String,
        lanIp: String, dhcpStart: String, dhcpEnd: String,
        ssid24: String, pass24: String,
        errors: MutableList<String>, total: Int,
        onProgress: suspend (Int, Int, String, Boolean) -> Unit
    ): ZteResult<Map<String, String>> {

        suspend fun step(n: Int, desc: String, action: suspend () -> ZteResult<*>) {
            onProgress(n, total, desc, true)
            val r = action()
            if (r is ZteResult.Error) errors.add("$desc: ${r.message}")
            onProgress(n, total, desc, r is ZteResult.Success)
        }

        step(1, "Login") {
            try { loginBoa(); ZteResult.Success(Unit) }
            catch (e: Exception) { ZteResult.Error(e.message ?: "login error") }
        }
        if (errors.isNotEmpty()) return ZteResult.Error(errors.joinToString("\n"))

        // WAN
        step(2, "WAN") {
            try {
                val wanHtml = boaGet("net_eth_links.asp")
                csrfToken = extractCsrf(wanHtml).ifBlank { csrfToken }
                val existingLst = extractLst(wanHtml)
                val isNew = existingLst.isBlank()
                Log.d(TAG, "WAN BOA → isNew=$isNew existingLst=$existingLst")
                val itfGrpM = Regex("new\\s+it\\s*\\(\\s*[\"']itfGroup[\"']\\s*,\\s*(\\d+)").find(wanHtml)
                val itfGroup = if (itfGrpM != null && (itfGrpM.groupValues[1].toIntOrNull() ?: 0) > 0)
                    itfGrpM.groupValues[1] else "19"

                val p = mutableListOf(
                    "lkname"              to (if (isNew) "new" else "0"),
                    "lkmode"              to "1",
                    "IpProtocolType"      to "1",
                    "ipmode"              to "1",
                    "PPPoEProxyMaxUser"   to "0",
                    "napt"                to "on",
                    "vlan"                to (if (vlan.toIntOrNull() ?: 0 > 0) "on" else ""),
                    "vid"                 to vlan,
                    "vprio"               to "1",
                    "mVid"                to "0",
                    "igmpproxy"           to "on",
                    "mtu"                 to "1480",
                    "pppUsername"         to "",
                    "pppPassword"         to "",
                    "pppServiceName"      to "",
                    "pppCtype"            to "0",
                    "ipAddr"              to ip,
                    "netMask"             to mask,
                    "remoteIpAddr"        to gw,
                    "v4dns1"              to dns1,
                    "v4dns2"              to dns2,
                    "applicationtype"     to "1",
                    "dslite_aftr_mode"    to "0",
                    "dslite_aftr_hostname" to "",
                    "Ipv6Addr"            to "",
                    "Ipv6PrefixLen"       to "",
                    "Ipv6Gateway"         to "",
                    "Ipv6Dns1"            to "",
                    "Ipv6Dns2"            to "",
                    "cmode"               to "1",
                    "ipDhcp"              to "0",
                    "itfGroup"            to itfGroup,
                    "encodePppUserName"   to "",
                    "encodePppPassword"   to "",
                    "lst"                 to existingLst,
                    "action"              to "sv",
                    "submit-url"          to "http://$onuIp/net_eth_links.asp",
                    "acnameflag"          to "none",
                    "igmpenable"          to "enabled"
                )
                if (isNew) { p.add("option60Value" to ""); p.add("AddrMode" to "1"); p.add("iapd" to "ON") }
                else        p.add("dgw" to "on")

                val chkpt = listOf("on","on","","","on","","","","","","","","")
                val body = (p.map { "${urlEnc(it.first)}=${urlEnc(it.second)}" } +
                        chkpt.map { "chkpt=${urlEnc(it)}" }).joinToString("&")
                boaPostRaw("boaform/admin/formEthernet", body, referer = "net_eth_links.asp")
                // ← REEMPLAZAR delay(2000) POR ESTO:
                var wanReady = false
                val wanStart = System.currentTimeMillis()
                while (System.currentTimeMillis() - wanStart < 15000) {
                    delay(2000)
                    try {
                        val h = boaGet("net_eth_links.asp")
                        if (h.length > 500 && !isLoginPage(h)) { wanReady = true; break }
                    } catch (e: Exception) { /* continuar */ }
                }
                if (!wanReady) delay(3000)

                ZteResult.Success(Unit)
            } catch (e: Exception) { ZteResult.Error(e.message ?: "WAN error") }
        }

        // LAN
        step(3, "LAN") {
            try {
                var ok = false
                repeat(5) {
                    delay(3000)
                    try {
                        boaPost("boaform/formDhcpServer", mapOf(
                            "uIp"            to lanIp,
                            "uMask"          to "255.255.255.0",
                            "uDhcpType"      to "1",
                            "dhcpRangeStart" to dhcpStart,
                            "dhcpRangeEnd"   to dhcpEnd,
                            "ulTime"         to "86400",
                            "uDhcpDnsType"   to "1",
                            "dns1"           to dns1,
                            "dns2"           to dns2,
                            "dns3"           to "1.1.1.1",
                            "submit-url"     to "http://$onuIp/net_dhcpd.asp"
                        ), referer = "net_dhcpd.asp")
                        ok = true; return@repeat
                    } catch (e: Exception) { Log.w(TAG, "LAN intento ${it+1}: ${e.message}") }
                }
                if (ok) ZteResult.Success(Unit) else ZteResult.Error("timeout LAN")
            } catch (e: Exception) { ZteResult.Error(e.message ?: "LAN error") }
        }

        // UPnP
        step(4, "UPnP") {
            try {
                boaPost("boaform/admin/formUpnp", mapOf(
                    "daemon"     to "1",
                    "ext_if"     to "130816",
                    "save"       to "Save",
                    "submit-url" to "/app_upnp.asp"
                ))
                ZteResult.Success(Unit)
            } catch (e: Exception) { ZteResult.Success(Unit) }
        }

        // ── Prefetch PON — ANTES del WiFi (igual que el HTML) ──
        var rxPower = ""; var txPower = ""; var gponsn = ""
        try {
            val htmlPon = boaGet("status_gpon.asp")
            val htmlDev = boaGet("status_device_basic_info.asp")

            fun byTd(html: String, label: String): String {
                val re = Regex("<td[^>]*>\\s*$label\\s*</td>\\s*<td[^>]*>\\s*([^<]+?)\\s*</td>", RegexOption.IGNORE_CASE)
                return re.find(html)?.groupValues?.get(1)?.trim() ?: ""
            }
            fun num(s: String): String = Regex("([-\\d.]+)").find(s)?.groupValues?.get(1) ?: ""

            rxPower = num(byTd(htmlPon, "RX\\s*Power"))
            txPower = num(byTd(htmlPon, "TX\\s*Power"))
            gponsn  = byTd(htmlDev, "GPON\\s*SN").ifBlank {
                byTd(htmlDev, "Device\\s*SN")
            }
            Log.d(TAG, "PON BOA → rx=$rxPower tx=$txPower sn=$gponsn")
        } catch (e: Exception) { Log.w(TAG, "PON BOA prefetch: ${e.message}") }

        //WIFI 2.4G
        step(5, "WiFi 2.4G") {
            try {
                Log.d(TAG, "WiFi step INICIO ssid24='$ssid24' pass24='$pass24'")

                // Leer campos ocultos de net_wlan_basic_11n.asp
                val basicHtml = boaGet("net_wlan_basic_11n.asp")
                val hiddenFields = mutableMapOf<String, String>()
                Regex("input[^>]+type\\s*=\\s*[\"']hidden[\"'][^>]*", RegexOption.IGNORE_CASE)
                    .findAll(basicHtml).forEach { m ->
                        val name  = Regex("name\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(m.value)?.groupValues?.get(1)
                        val value = Regex("value\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(m.value)?.groupValues?.get(1) ?: ""
                        if (name != null) hiddenFields[name] = value
                    }
                Log.d(TAG, "hidden fields = $hiddenFields")

                // PASO 1: SSID con campos hidden → formWlanBasic
                val basicParams = hiddenFields.toMutableMap()
                basicParams["wlanOnOff"]  = "0"
                basicParams["ssid"]       = ssid24
                basicParams["hidessid"]   = "0"
                basicParams["submit-url"] = "/net_wlan_basic_11n.asp"
                basicParams["save"]       = "Save/Apply"

                try {
                    boaPost("boaform/admin/formWlanBasic", basicParams, referer = "net_wlan_basic_11n.asp")
                    Log.d(TAG, "formWlanBasic OK")
                } catch (e: Exception) { Log.w(TAG, "formWlanBasic: ${e.message}") }

                delay(1000)

                // PASO 2: PSK → formWlEncrypt
                val secBody = listOf(
                    "wlanDisabled"      to "OFF",
                    "isNmode"           to "1",
                    "wpaSSID"           to "0",
                    "security_method"   to "6",
                    "auth_type"         to "both",
                    "wepEnabled"        to "ON",
                    "length0"           to "1",
                    "format0"           to "1",
                    "key0"              to "*****",
                    "wpaAuth"           to "psk",
                    "pskValue"          to pass24,
                    "pskFormat"         to "0",
                    "lst"               to "",
                    "submit-url"        to "/net_wlan_adv.asp",
                    "wlan_idx"          to "0",
                    "wlan_idx2"         to "222",
                    "ciphersuite_t"     to "1",
                    "wpa2ciphersuite_a" to "1"
                ).joinToString("&") { "${urlEnc(it.first)}=${urlEnc(it.second)}" }

                withContext(Dispatchers.IO) {
                    try {
                        val conn = URL("http://$onuIp/boaform/admin/formWlEncrypt").openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"; conn.connectTimeout = 8_000; conn.readTimeout = 5_000
                        conn.doOutput = true; conn.instanceFollowRedirects = false
                        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                        conn.setRequestProperty("Referer", "http://$onuIp/net_wlan_adv.asp")
                        val cookie = cookieStore.entries.joinToString("; ") { "${it.key}=${it.value}" }
                        if (cookie.isNotBlank()) conn.setRequestProperty("Cookie", cookie)
                        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).also { it.write(secBody); it.flush() }
                        Log.d(TAG, "formWlEncrypt enviado psk=$pass24")
                        try { conn.inputStream.bufferedReader(Charsets.UTF_8).readText() } catch (e: Exception) { }
                        conn.disconnect()
                    } catch (e: Exception) { Log.w(TAG, "formWlEncrypt: ${e.message}") }
                }

                ZteResult.Success(Unit)
            } catch (e: Exception) {
                Log.w(TAG, "WiFi step error: ${e.message}")
                ZteResult.Success(Unit)
            }
        }
        // Reboot
        step(6, "Reinicio") {
            try { rebootBoa(); ZteResult.Success(Unit) }
            catch (e: Exception) { ZteResult.Success(Unit) }
        }

        // CORRECTO:
        return ZteResult.Success(
            mapOf("rx" to rxPower, "tx" to txPower, "sn" to gponsn).let {
                if (errors.isEmpty()) it
                else it + mapOf("errors" to errors.joinToString("\n"))
            }
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  D580GW-AX / D110GWC apply
    // ─────────────────────────────────────────────────────────────────────────
    private suspend fun applyAllAx(
        ip: String, mask: String, gw: String, vlan: String,
        dns1: String, dns2: String,
        lanIp: String, dhcpStart: String, dhcpEnd: String,
        ssid24: String, pass24: String,
        ssid5: String, pass5: String,
        errors: MutableList<String>, total: Int,
        onProgress: suspend (Int, Int, String, Boolean) -> Unit
    ): ZteResult<Map<String, String>> {

        suspend fun step(n: Int, desc: String, action: suspend () -> ZteResult<*>) {
            onProgress(n, total, desc, true)
            val r = action()
            Log.d(TAG, "step $n '$desc' result=$r")
            if (r is ZteResult.Error) errors.add("$desc: ${r.message}")
            onProgress(n, total, desc, r is ZteResult.Success)
        }

        step(1, "Login") {
            try {
                Log.d(TAG, "applyAllAx LOGIN INICIO")
                loginAx()
                Log.d(TAG, "applyAllAx LOGIN OK axToken=${axToken.take(10)}")
                ZteResult.Success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "applyAllAx LOGIN ERROR: ${e.message}")
                ZteResult.Error(e.message ?: "login error")
            }
        }
        Log.d(TAG, "applyAllAx después step1 errors=$errors")
        if (errors.isNotEmpty()) return ZteResult.Error(errors.joinToString("\n"))

        // WAN
        step(2, "WAN") {
            try {
                val wanCurrent = axGet("wan_confignew")
                val wanList = wanCurrent.optJSONArray("wan")
                val wanObj = wanList?.optJSONObject(0)
                val payload = if (wanObj != null) {
                    val ipv4Copy = JSONObject(wanObj.optJSONObject("ipv4")?.toString() ?: "{}")
                    ipv4Copy.put("dhcp_enable", 0)
                    ipv4Copy.put("ip_address", ip)
                    ipv4Copy.put("subnet_mask", mask)
                    ipv4Copy.put("gateway", gw)
                    ipv4Copy.put("request_dns", 0)
                    ipv4Copy.put("dns", org.json.JSONArray(listOf(dns1, dns2)))
                    val updated = JSONObject(wanObj.toString())
                    updated.put("enable_vlan", if ((vlan.toIntOrNull() ?: 0) > 0) 1 else 0)
                    updated.put("vlanId", vlan.toIntOrNull() ?: 0)
                    updated.put("802_1_mark", 0)
                    updated.put("mtu", 1480)
                    updated.put("ip_protocol", 1)
                    updated.put("enable_napt", 1)
                    updated.put("ipv4", ipv4Copy)
                    updated
                } else {
                    JSONObject().apply {
                        put("enable_vlan", if ((vlan.toIntOrNull() ?: 0) > 0) 1 else 0)
                        put("vlanId", vlan.toIntOrNull() ?: 0)
                        put("802_1_mark", 0)
                        put("service_type", 1)
                        put("enable_qos", 0)
                        put("admin_status", 1)
                        put("connection_type", 2)
                        put("mtu", 1480)
                        put("ip_protocol", 1)
                        put("enable_napt", 1)
                        put("enable_igmp_mld_proxy", 0)
                        put("multicast_vlan_id", "")
                        put("ipv4", JSONObject().apply {
                            put("dhcp_enable", 0)
                            put("ip_address", ip)
                            put("subnet_mask", mask)
                            put("gateway", gw)
                            put("request_dns", 0)
                            put("dns", org.json.JSONArray(listOf(dns1, dns2)))
                        })
                        put("ipv6", JSONObject().apply {
                            put("address_mode", 32)
                            put("request_options", 1)
                            put("ip_address", "")
                            put("ipv6_prefixlen", "")
                            put("gateway", "")
                            put("request_dns", 1)
                            put("dns", org.json.JSONArray(listOf("", "")))
                        })
                        put("ppp", JSONObject().apply {
                            put("username", ""); put("password", ""); put("type", 0)
                            put("idle_time", 0); put("authentication", 0)
                            put("ac_name", ""); put("service_name", "")
                        })
                        put("port_binding", org.json.JSONArray(listOf(0, 1, 2, 3, 8, 4)))
                    }
                }
                axSave("wan_confignew", JSONObject().put("wan", org.json.JSONArray().put(payload)))
                delay(1000)
                ZteResult.Success(Unit)
            } catch (e: Exception) { ZteResult.Error(e.message ?: "WAN error") }
        }

        // LAN
        step(3, "LAN") {
            try {
                axSave("ipv4_lan_config", JSONObject().apply {
                    put("base_config", JSONObject().apply {
                        put("lan_ip", lanIp)
                        put("lan_subnet", "255.255.255.0")
                    })
                    put("dhcp_config", JSONObject().apply {
                        put("dhcp_enable", 1)
                        put("dhcp_range_start", dhcpStart)
                        put("dhcp_range_end", dhcpEnd)
                        put("dhcp_SubnetMask", "255.255.255.0")
                        put("dns_option", 2)
                        put("dhcp_dns", org.json.JSONArray(listOf(dns1, dns2, "")))
                        put("max_lease_time", 86400)
                    })
                })
                delay(600)
                ZteResult.Success(Unit)
            } catch (e: Exception) { ZteResult.Error(e.message ?: "LAN error") }
        }

        // ACL
        step(4, "ACL") {
            try {
                val aclCheck = axGet("acl_config")
                Log.d("ACL_DEBUG", "acl_config ANTES: $aclCheck")

                val aclList = listOf(
                    Triple(16,  "http",   1), Triple(16,  "ping",  1),
                    Triple(16,  "https",  1), Triple(128, "telnet",1),
                    Triple(16,  "telnet", 1), Triple(128, "https", 0),
                    Triple(128, "ping",   1), Triple(128, "http",  1)
                )
                val arr = org.json.JSONArray()
                aclList.forEach { (iface, svc, en) ->
                    arr.put(JSONObject().apply {
                        put("interface", iface)
                        put("aclstartIP", "0.0.0.0")
                        put("aclendIP", "255.255.255.255")
                        put("servicename", svc)
                        put("service_enable", en)
                    })
                }
                axSave("acl_config", JSONObject().put("acl", arr))
                delay(400)
                ZteResult.Success(Unit)
            } catch (e: Exception) { ZteResult.Success(Unit) }
        }



        // UPnP
        step(5, "UPnP") {
            try {
                axSave("upnp_config", JSONObject().apply { put("upnp_daemon", 1) })
                delay(400)
                ZteResult.Success(Unit)
            } catch (e: Exception) { ZteResult.Success(Unit) }
        }

        // Prefetch PON y Device info
        var rxPower = ""; var txPower = ""; var gponsn = ""
        try {
            val ponData = axGet("pon_info", mapOf("refresh_login_timer" to "0"))
            val devData = axGet("dev_info", mapOf("refresh_login_timer" to "0"))
            fun extractNum(s: String): String =
                Regex("([-\\d.]+)").find(s)?.groupValues?.get(1) ?: ""
            rxPower = extractNum(ponData.optString("rxPower", ""))
            txPower = extractNum(ponData.optString("txPower", ""))
            gponsn  = ponData.optString("gpon_sn", "").ifBlank {
                devData.optString("sn", "").ifBlank {
                    devData.optString("device_sn", "")
                }
            }
            Log.d(TAG, "PON → rx=$rxPower tx=$txPower sn=$gponsn")
        } catch (e: Exception) { Log.w(TAG, "PON prefetch: ${e.message}") }

        // WiFi
        val wifiStepDesc = if (solo24g) "WiFi 2.4G" else "WiFi 2.4G + 5G"
        step(6, wifiStepDesc) {
            try {
                val wifiData = axGet("wlan_config")
                val wifiJson = JSONObject(wifiData.toString())
                val wlan = wifiJson.optJSONObject("wlan") ?: JSONObject()

                val w24 = wlan.optJSONObject("2_4G") ?: JSONObject()
                val ssid24Arr = w24.optJSONArray("SSID") ?: org.json.JSONArray()
                val s24 = if (ssid24Arr.length() > 0) JSONObject(ssid24Arr.optJSONObject(0)?.toString() ?: "{}") else JSONObject()
                s24.put("ssid", ssid24)
                s24.put("wpa_pre_shared_key", pass24)
                s24.put("security_mode", 6)
                w24.put("SSID", org.json.JSONArray().put(s24))
                wlan.put("2_4G", w24)

                if (!solo24g && ssid5.isNotBlank()) {
                    val w5 = wlan.optJSONObject("5G") ?: JSONObject()
                    val ssid5Arr = w5.optJSONArray("SSID") ?: org.json.JSONArray()
                    val s5 = if (ssid5Arr.length() > 0) JSONObject(ssid5Arr.optJSONObject(0)?.toString() ?: "{}") else JSONObject()
                    s5.put("ssid", ssid5)
                    s5.put("wpa_pre_shared_key", pass5)
                    s5.put("security_mode", 6)
                    w5.put("SSID", org.json.JSONArray().put(s5))
                    wlan.put("5G", w5)
                }

                wifiJson.put("wlan", wlan)
                axSave("wlan_config", wifiJson)
                delay(800)
                ZteResult.Success(Unit)
            } catch (e: Exception) { ZteResult.Error(e.message ?: "WiFi error") }
        }



        // Reboot
        step(7, "Reinicio") {
            try { rebootAx(); ZteResult.Success(Unit) }
            catch (e: Exception) { ZteResult.Success(Unit) }
        }

        return ZteResult.Success(
            mapOf("rx" to rxPower, "tx" to txPower, "sn" to gponsn).let {
                if (errors.isEmpty()) it
                else it + mapOf("errors" to errors.joinToString("\n"))
            }
        )
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Login helpers
    // ═════════════════════════════════════════════════════════════════════════

    private suspend fun loginBoa() = withContext(Dispatchers.IO) {
        Log.d(TAG, "loginBoa INICIO onuIp=$onuIp")
        boaGet("admin/login.asp")
        boaPost("boaform/admin/formLogin", mapOf("username" to USER, "psd" to PASS))
        val verPages = listOf("net_eth_links.asp", "status.asp", "app_upnp.asp")
        var ok = false
        for (p in verPages) {
            try {
                val h = boaGet(p)
                Log.d(TAG, "loginBoa verPage=$p len=${h.length} isLogin=${isLoginPage(h)}")
                if (h.length > 500 && !isLoginPage(h)) {
                    csrfToken = extractCsrf(h).ifBlank { csrfToken }
                    ok = true; break
                }
            } catch (e: Exception) {
                Log.e(TAG, "loginBoa verPage=$p error: ${e.message}")
            }
        }
        Log.d(TAG, "loginBoa resultado ok=$ok")
        if (!ok) throw RuntimeException("Login DIXON D130GW falló — credenciales inválidas")
    }
    private suspend fun loginAx() = withContext(Dispatchers.IO) {
        // Cerrar sesión anterior
        try {
            axPostRaw("post.json", JSONObject().apply { put("module", "logout") }.toString())
        } catch (e: Exception) { }
        axToken = ""

        val md5 = md5Hex(PASS)
        val loginBody = JSONObject().apply {
            put("module", "login")
            put("username", USER)
            put("encryPassword", md5)
        }.toString()

        val resp = axPostRaw("post.json", loginBody)
        if (resp.isBlank()) throw RuntimeException("Login DIXON AX: sin respuesta")

        val json = try { JSONObject(resp) } catch (e: Exception) {
            throw RuntimeException("Login DIXON AX: respuesta inválida → ${resp.take(80)}")
        }

        val code = json.optInt("code", -1)
        if (code == 0) {
            axToken = json.optString("token", "").ifBlank {
                json.optString("access_token", "").ifBlank {
                    json.optJSONObject("data")?.optString("token", "") ?: ""
                }
            }
        } else {
            axToken = json.optString("token", "").ifBlank {
                json.optString("access_token", "")
            }
        }

        if (axToken.isBlank())
            throw RuntimeException("Login DIXON AX fallido (code=$code): ${resp.take(80)}")

        Log.d(TAG, "loginAx OK token=${axToken.take(20)}...")
    }
    // ═════════════════════════════════════════════════════════════════════════
    //  Reboot helpers
    // ═════════════════════════════════════════════════════════════════════════

    private suspend fun rebootBoa() = withContext(Dispatchers.IO) {
        try {
            val html = boaGet("reboot.asp")
            val csrf = extractCsrf(html).ifBlank { csrfToken }
            if (csrf.isNotBlank())
                boaPost("boaform/admin/formReboot", mapOf("submit-url" to "/reboot.asp", "csrftoken" to csrf))
            else
                boaPost("boaform/admin/formReboot", mapOf("submit-url" to "/reboot.asp"))
        } catch (e: Exception) { Log.d(TAG, "rebootBoa: ${e.message}") }
    }


    private suspend fun rebootAx() {
        try { axPostRaw("post.json", JSONObject().apply { put("module", "reboot") }.toString()) }
        catch (e: Exception) { Log.d(TAG, "rebootAx: ${e.message}") }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  BOA HTTP helpers (D130GW)
    // ═════════════════════════════════════════════════════════════════════════

    private suspend fun boaGet(path: String): String = withContext(Dispatchers.IO) {
        val conn = URL("http://$onuIp/$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod           = "GET"
            conn.connectTimeout          = TIMEOUT_MS
            conn.readTimeout             = TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.setRequestProperty("Accept", "text/html,*/*;q=0.8")
            val cookie = cookieStore.entries.joinToString("; ") { "${it.key}=${it.value}" }
            if (cookie.isNotBlank()) conn.setRequestProperty("Cookie", cookie)
            conn.connect()
            saveCookies(conn)
            if (conn.responseCode in 300..399) return@withContext ""
            conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } catch (e: IOException) {
            throw RuntimeException("GET $path → ${e.message}")
        } finally { conn.disconnect() }
    }

    private suspend fun boaPost(path: String, params: Map<String, String>, referer: String? = null): String {
        val body = params.entries.joinToString("&") { "${urlEnc(it.key)}=${urlEnc(it.value)}" }
        return boaPostRaw(path, body, referer)
    }

    private suspend fun boaPostRaw(path: String, body: String, referer: String? = null): String = withContext(Dispatchers.IO) {
        val conn = URL("http://$onuIp/$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod           = "POST"
            conn.connectTimeout          = TIMEOUT_MS
            conn.readTimeout             = TIMEOUT_MS
            conn.doOutput                = true
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=gb2312")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (referer != null) conn.setRequestProperty("Referer", "http://$onuIp/$referer")
            val cookie = cookieStore.entries.joinToString("; ") { "${it.key}=${it.value}" }
            if (cookie.isNotBlank()) conn.setRequestProperty("Cookie", cookie)
            val writer = OutputStreamWriter(conn.outputStream, Charsets.UTF_8)
            writer.write(body); writer.flush()
            saveCookies(conn)
            if (conn.responseCode in 300..399) return@withContext ""
            conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } catch (e: IOException) {
            val msg = e.message?.lowercase() ?: ""
            if (msg.contains("reset") || msg.contains("eof") || msg.contains("broken") ||
                msg.contains("timeout") || msg.contains("disconnect") || msg.contains("connect")) {
                return@withContext ""
            }
            throw RuntimeException("POST $path → ${e.message}")
        } finally { conn.disconnect() }
    }
    // ═════════════════════════════════════════════════════════════════════════
    //  AX JSON API helpers (D580GW-AX, D110GWC)
    // ═════════════════════════════════════════════════════════════════════════

    private suspend fun axGet(module: String, extra: Map<String, String> = emptyMap()): JSONObject = withContext(Dispatchers.IO) {
        val params = (mapOf("module" to module) + extra)
            .entries.joinToString("&") { "${it.key}=${it.value}" }
        val conn = URL("http://$onuIp/get.json?$params").openConnection() as HttpURLConnection
        try {
            conn.requestMethod           = "GET"
            conn.connectTimeout          = TIMEOUT_MS
            conn.readTimeout             = TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent",   "Mozilla/5.0")
            conn.setRequestProperty("Referer",       "http://$onuIp/")
            conn.setRequestProperty("Cookie",        "userLanguage=en")
            if (axToken.isNotBlank()) conn.setRequestProperty("Authorization", axToken)
            val resp = try { conn.inputStream.bufferedReader(Charsets.UTF_8).readText() }
            catch (e: IOException) { conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: "" }
            Log.d(TAG, "axGet $module → code=${conn.responseCode} resp=${resp.take(120)}")
            try { JSONObject(resp) } catch (e: Exception) { JSONObject() }
        } catch (e: IOException) { JSONObject() }
        finally { conn.disconnect() }
    }

    private suspend fun axSave(module: String, payload: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        payload.put("module", module)
        val resp = axPostRaw("post.json", payload.toString())
        try { JSONObject(resp) } catch (e: Exception) { JSONObject() }
    }

    private suspend fun axPostRaw(path: String, body: String): String = withContext(Dispatchers.IO) {
        val conn = URL("http://$onuIp/$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod           = "POST"
            conn.connectTimeout          = TIMEOUT_MS
            conn.readTimeout             = TIMEOUT_MS
            conn.doOutput                = true
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.setRequestProperty("Referer", "http://$onuIp/")
            conn.setRequestProperty("Cookie", "userLanguage=en")
            if (axToken.isNotBlank()) conn.setRequestProperty("Authorization", axToken)
            val writer = OutputStreamWriter(conn.outputStream, Charsets.UTF_8)
            writer.write(body); writer.flush()
            val resp = try { conn.inputStream.bufferedReader(Charsets.UTF_8).readText() }
            catch (e: IOException) { conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: "" }
            Log.d(TAG, "axPostRaw $path → code=${conn.responseCode} body=${body.take(60)} resp=${resp.take(120)}")
            resp
        } catch (e: IOException) {
            val msg = e.message?.lowercase() ?: ""
            if (msg.contains("reset") || msg.contains("eof") || msg.contains("broken") ||
                msg.contains("timeout") || msg.contains("connect")) return@withContext ""
            throw RuntimeException("AX POST $path → ${e.message}")
        } finally { conn.disconnect() }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Parsing helpers
    // ═════════════════════════════════════════════════════════════════════════

    private fun saveCookies(conn: HttpURLConnection) {
        conn.headerFields["Set-Cookie"]?.forEach { h ->
            val parts = h.split(";")[0].split("=", limit = 2)
            if (parts.size == 2) cookieStore[parts[0].trim()] = parts[1].trim()
        }
    }

    private fun itVal(html: String, field: String): String {
        val re = Regex("new\\s+it\\([\"']${Regex.escape(field)}[\"']\\s*,\\s*[\"']([^\"']*)[\"']\\)")
        return re.find(html)?.groupValues?.get(1) ?: ""
    }

    private fun itNum(html: String, field: String): String {
        val re = Regex("new\\s+it\\([\"']${Regex.escape(field)}[\"']\\s*,\\s*([\\d.]+)\\)")
        return re.find(html)?.groupValues?.get(1) ?: ""
    }

    private fun getInput(html: String, name: String): String {
        val re1 = Regex("name\\s*=\\s*[\"']${Regex.escape(name)}[\"'][^>]*value\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
        val re2 = Regex("value\\s*=\\s*[\"']([^\"']*)[\"'][^>]*name\\s*=\\s*[\"']${Regex.escape(name)}[\"']", RegexOption.IGNORE_CASE)
        return re1.find(html)?.groupValues?.get(1) ?: re2.find(html)?.groupValues?.get(1) ?: ""
    }

    private fun extractCsrf(html: String): String {
        val re = Regex("name\\s*=\\s*[\"']csrftoken[\"'][^>]*value\\s*=\\s*[\"']([a-f0-9]{32})[\"']", RegexOption.IGNORE_CASE)
        val re2 = Regex("csrftoken[\"']?\\s*[=:]\\s*[\"']?([a-f0-9]{32})", RegexOption.IGNORE_CASE)
        return re.find(html)?.groupValues?.get(1) ?: re2.find(html)?.groupValues?.get(1) ?: ""
    }

    private fun extractLst(html: String): String {
        // Igual que el HTML — captura cualquier nombre, no solo "nas"
        return Regex("new\\s+it_nr\\s*\\(\\s*[\"']([^\"']+)[\"']")
            .find(html)?.groupValues?.get(1) ?: ""
    }

    private fun isLoginPage(html: String) =
        html.lowercase().let { it.contains("formlogin") || it.contains("login.asp") }

    private fun urlEnc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun md5Hex(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}