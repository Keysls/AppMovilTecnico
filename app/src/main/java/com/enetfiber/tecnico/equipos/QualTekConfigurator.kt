package com.enetfiber.tecnico.equipos
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * QualTekConfigurator — QTK-GE504GW-DX-XPON
 *
 * Protocolo: API REST JSON (get.json / post.json) con token Bearer.
 * Idéntico al Dixon D580GW-AX, con estas diferencias:
 *   - IP default:   192.168.18.1
 *   - Usuario:      adminisp
 *   - Contraseña:   Admin123!
 *   - WAN visible:  se selecciona por wan.hidden == false
 *   - WAN POST:     envía [ {}, wanPayload ] — {} vacío obligatorio en posición 0
 *   - ACL POST:     envía [ null×N, nuevaRegla1, nuevaRegla2, ... ]
 *                   donde N = cantidad de reglas existentes en el equipo
 *   - Siempre tiene 2.4 GHz + 5 GHz
 */
class QualTekConfigurator(
    private val onuIp: String = "192.168.18.1"
) {
    companion object {
        private const val TAG        = "QualTekConfigurator"
        private const val TIMEOUT_MS = 12_000
        private const val USER       = "adminisp"
        private const val PASS       = "Admin123!"
    }

    val solo24g get() = false

    // ─────────────────────────────────────────────────────────────────────────
    //  Estado interno
    // ─────────────────────────────────────────────────────────────────────────
    private var axToken             = ""
    private var portBindingOriginal : JSONArray? = null

    data class QualTekConfig(
        val wanIp:     String = "",
        val wanMask:   String = "255.255.255.0",
        val wanGw:     String = "",
        val wanVlan:   String = "100",
        val lanIp:     String = "192.168.18.1",
        val dhcpStart: String = "192.168.18.2",
        val dhcpEnd:   String = "192.168.18.254",
        val dns1:      String = "8.8.8.8",
        val dns2:      String = "8.8.4.4",
        val ssid24:    String = "",
        val pass24:    String = "",
        val ssid5:     String = "",
        val pass5:     String = ""
    )

    // ═════════════════════════════════════════════════════════════════════════
    //  readConfig
    // ═════════════════════════════════════════════════════════════════════════
    suspend fun readConfig(): ZteResult<QualTekConfig> = withContext(Dispatchers.IO) {
        try {
            login()

            // ── WAN ──────────────────────────────────────────────────────────
            var wanIp = ""; var wanMask = "255.255.255.0"; var wanGw = ""
            var wanVlan = "100"; var dns1 = "8.8.8.8"; var dns2 = "8.8.4.4"
            try {
                val wanData = axGet("wan_confignew")
                val wanArr  = wanData.optJSONArray("wan")

                // valid_port_binding_list tiene prioridad
                val validPb = wanData.optJSONArray("valid_port_binding_list")
                if (validPb != null && validPb.length() > 0) portBindingOriginal = validPb

                // Buscar primer WAN visible (hidden == false)
                var visibleWan: JSONObject? = null
                if (wanArr != null) {
                    for (i in 0 until wanArr.length()) {
                        val w = wanArr.optJSONObject(i) ?: continue
                        if (w.optInt("hidden", 0) == 0) {
                            visibleWan = w
                            val pb = w.optJSONArray("port_binding")
                            if (portBindingOriginal == null && pb != null && pb.length() > 0)
                                portBindingOriginal = pb
                            break
                        }
                    }
                    if (visibleWan == null && wanArr.length() > 0)
                        visibleWan = wanArr.optJSONObject(0)
                }

                if (visibleWan != null) {
                    val ipv4 = visibleWan.optJSONObject("ipv4")
                    wanIp   = ipv4?.optString("ip_address", "")               ?: ""
                    wanMask = ipv4?.optString("subnet_mask", "255.255.255.0") ?: "255.255.255.0"
                    wanGw   = ipv4?.optString("gateway", "")                  ?: ""
                    wanVlan = visibleWan.optInt("vlanId", 100).toString()
                    val dnsArr = ipv4?.optJSONArray("dns")
                    dns1 = dnsArr?.optString(0, "8.8.8.8") ?: "8.8.8.8"
                    dns2 = dnsArr?.optString(1, "8.8.4.4") ?: "8.8.4.4"
                }
            } catch (e: Exception) { Log.w(TAG, "readConfig WAN: ${e.message}") }

            // ── LAN ──────────────────────────────────────────────────────────
            var lanIp = "192.168.18.1"; var dhcpStart = "192.168.18.2"; var dhcpEnd = "192.168.18.254"
            try {
                val lanData = axGet("ipv4_lan_config")
                lanIp     = lanData.optJSONObject("base_config")?.optString("lan_ip", "192.168.18.1") ?: "192.168.18.1"
                val dhcp  = lanData.optJSONObject("dhcp_config")
                dhcpStart = dhcp?.optString("dhcp_range_start", "192.168.18.2")  ?: "192.168.18.2"
                dhcpEnd   = dhcp?.optString("dhcp_range_end",   "192.168.18.254") ?: "192.168.18.254"
            } catch (e: Exception) { Log.w(TAG, "readConfig LAN: ${e.message}") }

            // ── WiFi ─────────────────────────────────────────────────────────
            var ssid24 = ""; var pass24 = ""; var ssid5 = ""; var pass5 = ""
            try {
                val wifiData = axGet("wlan_config")
                val w24 = wifiData.optJSONObject("wlan")?.optJSONObject("2_4G")?.optJSONArray("SSID")?.optJSONObject(0)
                val w5  = wifiData.optJSONObject("wlan")?.optJSONObject("5G")?.optJSONArray("SSID")?.optJSONObject(0)
                ssid24 = w24?.optString("ssid", "") ?: ""
                pass24 = w24?.optString("wpa_pre_shared_key", "") ?: ""
                ssid5  = w5?.optString("ssid",  "") ?: ""
                pass5  = w5?.optString("wpa_pre_shared_key", "") ?: ""
            } catch (e: Exception) { Log.w(TAG, "readConfig WiFi: ${e.message}") }

            ZteResult.Success(QualTekConfig(
                wanIp, wanMask, wanGw, wanVlan,
                lanIp, dhcpStart, dhcpEnd,
                dns1, dns2,
                ssid24, pass24, ssid5, pass5
            ))
        } catch (e: Exception) {
            ZteResult.Error("readConfig QUALTEK: ${e.message}")
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  applyAll
    // ═════════════════════════════════════════════════════════════════════════
    suspend fun applyAll(
        ip: String, mask: String, gw: String,
        vlan: String      = "100",
        dns1: String      = "8.8.8.8",
        dns2: String      = "8.8.4.4",
        lanIp: String     = "192.168.18.1",
        dhcpStart: String = "192.168.18.2",
        dhcpEnd: String   = "192.168.18.254",
        ssid24: String, pass24: String,
        ssid5: String = "", pass5: String = "",
        onProgress: suspend (step: Int, total: Int, desc: String, ok: Boolean) -> Unit = { _, _, _, _ -> }
    ): ZteResult<Map<String, String>> {
        Log.d(TAG, "applyAll INICIO onuIp=$onuIp")
        val total  = 7
        val errors = mutableListOf<String>()

        suspend fun step(n: Int, desc: String, action: suspend () -> ZteResult<*>) {
            onProgress(n, total, desc, true)
            val r = action()
            Log.d(TAG, "step $n '$desc' result=$r")
            if (r is ZteResult.Error) errors.add("$desc: ${r.message}")
            onProgress(n, total, desc, r is ZteResult.Success)
        }

        return try {

            // ── 1. LOGIN ─────────────────────────────────────────────────────
            step(1, "Login") {
                try { login(); ZteResult.Success(Unit) }
                catch (e: Exception) { ZteResult.Error(e.message ?: "login error") }
            }
            if (errors.isNotEmpty()) return ZteResult.Error(errors.joinToString("\n"))

            // ── 2. WAN ───────────────────────────────────────────────────────
            // CRÍTICO: siempre se envía [ {}, wanPayload ]
            // {} en posición 0 = perfil TR069/gestión oculto, nunca se toca
            // wanPayload en posición 1 = perfil de internet visible
            step(2, "WAN") {
                try {
                    val wanCurrent = axGet("wan_confignew")
                    val wanArr     = wanCurrent.optJSONArray("wan")

                    if (portBindingOriginal == null) {
                        val validPb = wanCurrent.optJSONArray("valid_port_binding_list")
                        if (validPb != null && validPb.length() > 0) portBindingOriginal = validPb
                    }

                    // Buscar WAN visible
                    var visibleWan: JSONObject? = null
                    if (wanArr != null) {
                        for (i in 0 until wanArr.length()) {
                            val w = wanArr.optJSONObject(i) ?: continue
                            if (w.optInt("hidden", 0) == 0) {
                                visibleWan = w
                                if (portBindingOriginal == null) {
                                    val pb = w.optJSONArray("port_binding")
                                    if (pb != null && pb.length() > 0) portBindingOriginal = pb
                                }
                                break
                            }
                        }
                        if (visibleWan == null && wanArr.length() > 0)
                            visibleWan = wanArr.optJSONObject(0)
                    }

                    val portBinding = portBindingOriginal ?: JSONArray(listOf(0, 1, 2, 3, 8, 4))
                    Log.d(TAG, "WAN visibleWan=${visibleWan?.toString()?.take(100)}")
                    Log.d(TAG, "WAN portBinding=$portBinding")

                    val wanPayload: JSONObject = if (visibleWan != null && visibleWan.length() > 1) {
                        // Copiar TODOS los campos del visibleWan (spread {...visibleWan})
                        // y sobreescribir solo los que cambian
                        val ipv4Copy = JSONObject(visibleWan.optJSONObject("ipv4")?.toString() ?: "{}")
                        ipv4Copy.put("dhcp_enable",  0)
                        ipv4Copy.put("ip_address",   ip)
                        ipv4Copy.put("subnet_mask",  mask)
                        ipv4Copy.put("gateway",      gw)
                        ipv4Copy.put("request_dns",  0)
                        ipv4Copy.put("dns",          JSONArray(listOf(dns1, dns2)))

                        val updated = JSONObject(visibleWan.toString())
                        updated.put("enable_vlan",  if ((vlan.toIntOrNull() ?: 0) > 0) 1 else 0)
                        updated.put("vlanId",       vlan.toIntOrNull() ?: 0)
                        updated.put("802_1_mark",   0)
                        updated.put("mtu",          1480)
                        updated.put("ip_protocol",  1)
                        updated.put("enable_napt",  1)
                        updated.put("port_binding", portBinding)
                        updated.put("ipv4",         ipv4Copy)
                        updated
                    } else {
                        // Crear perfil nuevo
                        JSONObject().apply {
                            put("enable_vlan",           if ((vlan.toIntOrNull() ?: 0) > 0) 1 else 0)
                            put("vlanId",                vlan.toIntOrNull() ?: 0)
                            put("802_1_mark",            0)
                            put("multicast_vlan_id",     "")
                            put("service_type",          1)
                            put("enable_qos",            0)
                            put("admin_status",          1)
                            put("connection_type",       2)
                            put("mtu",                   1480)
                            put("ip_protocol",           1)
                            put("enable_napt",           1)
                            put("enable_igmp_mld_proxy", 0)
                            put("port_binding",          portBinding)
                            put("ppp", JSONObject().apply {
                                put("username", ""); put("password", ""); put("type", 0)
                                put("idle_time", 0); put("authentication", 0)
                                put("ac_name", ""); put("service_name", "")
                            })
                            put("ipv4", JSONObject().apply {
                                put("dhcp_enable",  0)
                                put("ip_address",   ip)
                                put("subnet_mask",  mask)
                                put("gateway",      gw)
                                put("request_dns",  0)
                                put("dns",          JSONArray(listOf(dns1, dns2)))
                            })
                            put("ipv6", JSONObject().apply {
                                put("prefix_delegation", "")
                                put("address_mode",      32)
                                put("request_options",   1)
                                put("ip_address",        "")
                                put("ipv6_prefixlen",    "")
                                put("gateway",           "")
                                put("request_dns",       1)
                                put("dns",               JSONArray(listOf("", "")))
                            })
                        }
                    }

                    Log.d(TAG, "WAN payload: ${wanPayload.toString().take(200)}")

                    // Siempre [ {}, wanPayload ] — {} en posición 0
                    axSave("wan_confignew", JSONObject().put("wan",
                        JSONArray().put(JSONObject()).put(wanPayload)
                    ))
                    delay(1000)
                    ZteResult.Success(Unit)
                } catch (e: Exception) { ZteResult.Error(e.message ?: "WAN error") }
            }

            // ── 3. LAN ───────────────────────────────────────────────────────
            step(3, "LAN") {
                try {
                    axSave("ipv4_lan_config", JSONObject().apply {
                        put("base_config", JSONObject().apply {
                            put("lan_ip",     lanIp)
                            put("lan_subnet", "255.255.255.0")
                        })
                        put("dhcp_config", JSONObject().apply {
                            put("dhcp_enable",       1)
                            put("dhcp_range_start",  dhcpStart)
                            put("dhcp_range_end",    dhcpEnd)
                            put("dhcp_SubnetMask",   "255.255.255.0")
                            put("dns_option",        2)
                            put("dhcp_dns",          JSONArray(listOf(dns1, dns2, "")))
                            put("max_lease_time",    86400)
                        })
                    })
                    delay(600)
                    ZteResult.Success(Unit)
                } catch (e: Exception) { ZteResult.Error(e.message ?: "LAN error") }
            }

            // ── 4. ACL ───────────────────────────────────────────────────────
            // CRÍTICO: se envía [ null×N, nuevaRegla1, ..., nuevaRegla4 ]
            // null×N = N posiciones de reglas existentes que el equipo conserva intactas
            // Las 4 reglas WAN nuevas se agregan al final
            step(4, "ACL") {
                try {
                    val aclCurrent  = axGet("acl_config")
                    val aclExisting = aclCurrent.optJSONArray("acl")
                    val existingCount = aclExisting?.length() ?: 0
                    Log.d(TAG, "ACL existingCount=$existingCount")

                    val finalAcl = JSONArray()

                    // null en cada posición de las reglas existentes
                    repeat(existingCount) { finalAcl.put(JSONObject.NULL) }

                    // 4 reglas WAN nuevas al final
                    listOf(
                        Triple(128, "telnet", 1),
                        Triple(128, "https",  1),
                        Triple(128, "http",   1),
                        Triple(128, "ping",   1)
                    ).forEach { (iface, svc, en) ->
                        finalAcl.put(JSONObject().apply {
                            put("interface",      iface)
                            put("servicename",    svc)
                            put("aclstartIP",     "0.0.0.0")
                            put("aclendIP",       "255.255.255.255")
                            put("service_enable", en)
                        })
                    }

                    Log.d(TAG, "ACL finalAcl length=${finalAcl.length()}")
                    axSave("acl_config", JSONObject().put("acl", finalAcl))
                    delay(400)
                    ZteResult.Success(Unit)
                } catch (e: Exception) {
                    Log.w(TAG, "ACL no crítico: ${e.message}")
                    ZteResult.Success(Unit)
                }
            }

            // ── 5. UPnP ──────────────────────────────────────────────────────
            step(5, "UPnP") {
                try {
                    axSave("upnp_config", JSONObject().apply { put("upnp_daemon", 1) })
                    delay(400)
                    ZteResult.Success(Unit)
                } catch (e: Exception) { ZteResult.Success(Unit) }
            }

            // ── Prefetch PON ─────────────────────────────────────────────────
            var rxPower = ""; var txPower = ""; var gponsn = ""
            try {
                val ponData = axGet("pon_info", mapOf("refresh_login_timer" to "0"))
                val devData = axGet("dev_info", mapOf("refresh_login_timer" to "0"))
                fun extractNum(s: String) = Regex("([-\\d.]+)").find(s)?.groupValues?.get(1) ?: ""
                rxPower = extractNum(ponData.optString("rxPower", ""))
                txPower = extractNum(ponData.optString("txPower", ""))
                gponsn  = ponData.optString("gpon_sn", "").ifBlank {
                    devData.optString("sn", "").ifBlank { devData.optString("device_sn", "") }
                }
                Log.d(TAG, "PON → rx=$rxPower tx=$txPower sn=$gponsn")
            } catch (e: Exception) { Log.w(TAG, "PON prefetch: ${e.message}") }

            // ── 6. WiFi 2.4G + 5G ────────────────────────────────────────────
            step(6, "WiFi 2.4G + 5G") {
                try {
                    val wifiData = axGet("wlan_config")
                    val wifiJson = JSONObject(wifiData.toString())
                    val wlan     = wifiJson.optJSONObject("wlan") ?: JSONObject()

                    // 2.4 GHz
                    val w24      = wlan.optJSONObject("2_4G") ?: JSONObject()
                    val arr24    = w24.optJSONArray("SSID") ?: JSONArray()
                    val s24      = if (arr24.length() > 0) JSONObject(arr24.optJSONObject(0)?.toString() ?: "{}") else JSONObject()
                    s24.put("ssid",               ssid24)
                    s24.put("wpa_pre_shared_key", pass24)
                    s24.put("encryption",         4)
                    s24.put("ssid_enable",        1)
                    w24.put("SSID", JSONArray().put(s24))
                    wlan.put("2_4G", w24)

                    // 5 GHz
                    val w5       = wlan.optJSONObject("5G") ?: JSONObject()
                    val arr5     = w5.optJSONArray("SSID") ?: JSONArray()
                    val s5       = if (arr5.length() > 0) JSONObject(arr5.optJSONObject(0)?.toString() ?: "{}") else JSONObject()
                    s5.put("ssid",               ssid5.ifBlank { ssid24 })
                    s5.put("wpa_pre_shared_key", pass5.ifBlank { pass24 })
                    s5.put("encryption",         4)
                    s5.put("ssid_enable",        1)
                    w5.put("SSID", JSONArray().put(s5))
                    wlan.put("5G", w5)

                    wifiJson.put("wlan", wlan)
                    axSave("wlan_config", wifiJson)
                    delay(800)
                    ZteResult.Success(Unit)
                } catch (e: Exception) { ZteResult.Error(e.message ?: "WiFi error") }
            }

            // ── 7. REBOOT ────────────────────────────────────────────────────
            step(7, "Reinicio") {
                try {
                    axPostRaw("post.json", JSONObject().apply {
                        put("module", "dev_config")
                        put("reboot", 1)
                    }.toString())
                    ZteResult.Success(Unit)
                } catch (e: Exception) { ZteResult.Success(Unit) }
            }

            ZteResult.Success(
                mapOf("rx" to rxPower, "tx" to txPower, "sn" to gponsn).let {
                    if (errors.isEmpty()) it else it + mapOf("errors" to errors.joinToString("\n"))
                }
            )
        } catch (e: Exception) {
            ZteResult.Error("applyAll QUALTEK: ${e.message}")
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Login
    // ═════════════════════════════════════════════════════════════════════════
    private suspend fun login() = withContext(Dispatchers.IO) {
        try { axPostRaw("post.json", JSONObject().apply { put("module", "logout") }.toString()) }
        catch (e: Exception) { /* ignorar */ }
        axToken = ""

        val resp = axPostRaw("post.json", JSONObject().apply {
            put("module",        "login")
            put("username",      USER)
            put("encryPassword", md5Hex(PASS))
        }.toString())

        if (resp.isBlank()) throw RuntimeException("Login QUALTEK: sin respuesta")
        val json = try { JSONObject(resp) } catch (e: Exception) {
            throw RuntimeException("Login QUALTEK: respuesta inválida → ${resp.take(80)}")
        }
        val code = json.optInt("code", -1)
        axToken = when {
            code == 0 -> json.optString("token", "").ifBlank {
                json.optString("access_token", "").ifBlank {
                    json.optJSONObject("data")?.optString("token", "") ?: ""
                }
            }
            else -> json.optString("token", "").ifBlank { json.optString("access_token", "") }
        }
        if (axToken.isBlank())
            throw RuntimeException("Login QUALTEK fallido (code=$code): ${resp.take(80)}")
        Log.d(TAG, "login OK token=${axToken.take(20)}...")
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  HTTP helpers
    // ═════════════════════════════════════════════════════════════════════════

    private suspend fun axGet(module: String, extra: Map<String, String> = emptyMap()): JSONObject =
        withContext(Dispatchers.IO) {
            val params = (mapOf("module" to module) + extra)
                .entries.joinToString("&") { "${it.key}=${it.value}" }
            val conn = URL("http://$onuIp/get.json?$params").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"; conn.connectTimeout = TIMEOUT_MS; conn.readTimeout = TIMEOUT_MS
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.setRequestProperty("Referer", "http://$onuIp/")
                conn.setRequestProperty("Cookie", "userLanguage=en")
                if (axToken.isNotBlank()) conn.setRequestProperty("Authorization", axToken)
                val resp = try { conn.inputStream.bufferedReader(Charsets.UTF_8).readText() }
                catch (e: IOException) { conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: "" }
                Log.d(TAG, "axGet $module → ${conn.responseCode} ${resp.take(100)}")
                try { JSONObject(resp) } catch (e: Exception) { JSONObject() }
            } catch (e: IOException) { JSONObject() }
            finally { conn.disconnect() }
        }

    private suspend fun axSave(module: String, payload: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            payload.put("module", module)
            val resp = axPostRaw("post.json", payload.toString())
            Log.d(TAG, "axSave $module → ${resp.take(80)}")
            try { JSONObject(resp) } catch (e: Exception) { JSONObject() }
        }

    private suspend fun axPostRaw(path: String, body: String): String = withContext(Dispatchers.IO) {
        val conn = URL("http://$onuIp/$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"; conn.connectTimeout = TIMEOUT_MS; conn.readTimeout = TIMEOUT_MS
            conn.doOutput = true; conn.instanceFollowRedirects = false
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.setRequestProperty("Referer", "http://$onuIp/")
            conn.setRequestProperty("Cookie", "userLanguage=en")
            if (axToken.isNotBlank()) conn.setRequestProperty("Authorization", axToken)
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).also { it.write(body); it.flush() }
            val resp = try { conn.inputStream.bufferedReader(Charsets.UTF_8).readText() }
            catch (e: IOException) { conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: "" }
            Log.d(TAG, "axPostRaw $path → ${conn.responseCode} body=${body.take(60)} resp=${resp.take(80)}")
            resp
        } catch (e: IOException) {
            val msg = e.message?.lowercase() ?: ""
            if (msg.contains("reset") || msg.contains("eof") || msg.contains("broken") ||
                msg.contains("timeout") || msg.contains("connect")) return@withContext ""
            throw RuntimeException("QUALTEK POST $path → ${e.message}")
        } finally { conn.disconnect() }
    }

    private fun md5Hex(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Suppress("unused")
    private fun urlEnc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}