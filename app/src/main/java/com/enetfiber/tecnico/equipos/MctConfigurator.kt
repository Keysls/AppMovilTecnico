package com.enetfiber.tecnico.equipos
//MCT 10/10 - 28-04-2026

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ─────────────────────────────────────────────────────────────────────────────
//  MCT AX3000 — CGI con autenticación Base64
//  IP default: 192.168.2.1
//  Credenciales: Administrador / mct@dm1n1str@d0r
//
//  FIXES aplicados:
//  1. applyWan — eliminado urlEnc() por campo individual (causaba doble-encode)
//     Los valores van en texto plano dentro del XML; solo el bloque completo
//     se codifica una vez con urlEnc() al asignarlo al parámetro &value=
//  2. applyWan — OID dinámico: ya no se hardcodea {1-1-1}; readConfig detecta
//     el OID real y lo persiste en _wanOid para que applyWan lo use.
//  3. MctConfig — nuevo campo wanOid para transportar el OID entre llamadas.
//  4. applyAll  — pasa wanOid a applyWan.
// ─────────────────────────────────────────────────────────────────────────────
class MctConfigurator(
    private val onuIp: String = "192.168.2.1"
) {
    companion object {
        private const val TAG        = "MctConfigurator"
        private const val TIMEOUT_MS = 12_000
        private const val USER       = "Administrador"
        private const val PASS       = "mct@dm1n1str@d0r"
    }

    private val cookieStore = mutableMapOf<String, String>()

    // OID WAN detectado dinámicamente en readConfig / login
    private var _wanOid: String = "MDMOID_WAN_IP_CONN{1-1-1}"

    // ─────────────────────────────────────────────────────────────────────────
    //  HTTP helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun get(path: String): String {
        val url  = "http://$onuIp/$path"
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod           = "GET"
            conn.connectTimeout          = TIMEOUT_MS
            conn.readTimeout             = TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
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

    private fun saveCookies(conn: HttpURLConnection) {
        conn.headerFields["Set-Cookie"]?.forEach { h ->
            val parts = h.split(";")[0].split("=", limit = 2)
            if (parts.size == 2) cookieStore[parts[0].trim()] = parts[1].trim()
        }
    }

    // FIX: urlEnc() solo se usa para el bloque &value= completo, NUNCA por campo individual
    private fun urlEnc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun b64(s: String)    = Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun loginUrl(user: String, pass: String) =
        "login.cgi?username=${b64(user)}&psd=${b64(pass)}"

    // ─────────────────────────────────────────────────────────────────────────
    //  Parsers de objBuf JS — patrón MCT
    // ─────────────────────────────────────────────────────────────────────────

    private fun jsObjVal(html: String, obj: String, field: String): String {
        val escaped = obj.replace("{", "\\{").replace("}", "\\}")
        val m = Regex("""objBuf\['$escaped'\]\['$field'\]\s*=\s*'([^']*)'""").find(html)
        return m?.groupValues?.get(1) ?: ""
    }

    private fun inputVal(html: String, name: String): String {
        val m = Regex("""name\s*=\s*["']$name["'][^>]*value\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""value\s*=\s*["']([^"']*)["'][^>]*name\s*=\s*["']$name["']""",  RegexOption.IGNORE_CASE).find(html)
        return m?.groupValues?.get(1) ?: ""
    }

    private fun isLoginPage(html: String) =
        html.length < 500 || html.contains("login.html") || html.contains("login.cgi")

    // ─────────────────────────────────────────────────────────────────────────
    //  Data class resultado
    //  FIX: nuevo campo wanOid para transportar el OID dinámico
    // ─────────────────────────────────────────────────────────────────────────
    data class MctConfig(
        val wanIp:     String = "",
        val wanMask:   String = "255.255.255.0",
        val wanGw:     String = "",
        val wanVlan:   String = "100",
        val wanDns1:   String = "8.8.8.8",
        val wanDns2:   String = "8.8.4.4",
        val lanIp:     String = "192.168.2.1",
        val dhcpStart: String = "192.168.2.2",
        val dhcpEnd:   String = "192.168.2.254",
        val lanDns1:   String = "8.8.8.8",
        val lanDns2:   String = "8.8.4.4",
        val ssid24:    String = "",
        val pass24:    String = "",
        val ssid5:     String = "",
        val pass5:     String = "",
        // FIX: OID WAN real detectado del router (antes hardcodeado {1-1-1})
        val wanOid:    String = "MDMOID_WAN_IP_CONN{1-1-1}"
    )

    // ─────────────────────────────────────────────────────────────────────────
    //  LOGIN
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun login(
        user: String = USER,
        pass: String = PASS
    ): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            get(loginUrl(user, pass))
            val main = get("main.html")
            if (isLoginPage(main)) return@withContext ZteResult.Error("Credenciales incorrectas MCT")
            Log.d(TAG, "Login MCT OK")
            ZteResult.Success(Unit)
        } catch (e: Exception) {
            ZteResult.Error("Login MCT: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  READ CONFIG
    //  FIX: guarda el OID detectado en _wanOid y lo incluye en MctConfig
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun readConfig(): ZteResult<MctConfig> = withContext(Dispatchers.IO) {
        try {
            if (cookieStore.isEmpty()) {
                val r = login()
                if (r is ZteResult.Error) return@withContext ZteResult.Error(r.message)
            }

            // ── WAN ──
            var wanIp = ""; var wanMask = "255.255.255.0"; var wanGw = ""
            var wanVlan = "100"; var wanDns1 = "8.8.8.8"; var wanDns2 = "8.8.4.4"
            var detectedOid = "MDMOID_WAN_IP_CONN{1-1-1}"
            try {
                val html = get("x_wancfg.html")
                // FIX: detectar OID dinámico y guardarlo
                val idxMatch = Regex("""objBuf\['MDMOID_WAN_IP_CONN(\{[^']+\})'\]\['ExternalIPAddress'\]""").find(html)
                val idx = idxMatch?.groupValues?.get(1) ?: "{1-1-1}"
                detectedOid = "MDMOID_WAN_IP_CONN$idx"
                _wanOid     = detectedOid
                Log.d(TAG, "WAN OID detectado: $_wanOid")

                wanIp   = jsObjVal(html, detectedOid, "ExternalIPAddress")
                wanMask = jsObjVal(html, detectedOid, "SubnetMask").ifBlank { "255.255.255.0" }
                wanGw   = jsObjVal(html, detectedOid, "DefaultGateway")
                wanVlan = jsObjVal(html, detectedOid, "X_UM_COM_VlanMuxID").ifBlank { "100" }
                val dns = jsObjVal(html, detectedOid, "DNSServers").ifBlank { "8.8.8.8,8.8.4.4" }
                val dnsParts = (dns + ",8.8.4.4").split(",")
                wanDns1 = dnsParts.getOrElse(0) { "8.8.8.8" }.trim()
                wanDns2 = dnsParts.getOrElse(1) { "8.8.4.4" }.trim()
            } catch (e: Exception) { Log.w(TAG, "readConfig WAN: ${e.message}") }

            // ── LAN ──
            var lanIp = "192.168.2.1"
            var dhcpStart = "192.168.2.2"; var dhcpEnd = "192.168.2.254"
            var lanDns1 = "8.8.8.8"; var lanDns2 = "8.8.4.4"
            try {
                val html  = get("ctdhcp.html")
                lanIp     = jsObjVal(html, "MDMOID_LAN_IP_INTF{1-1}", "IPInterfaceIPAddress").ifBlank { "192.168.2.1" }
                dhcpStart = jsObjVal(html, "MDMOID_LAN_HOST_CFG{1}", "MinAddress").ifBlank { "192.168.2.2" }
                dhcpEnd   = jsObjVal(html, "MDMOID_LAN_HOST_CFG{1}", "MaxAddress").ifBlank { "192.168.2.254" }
                val dns   = jsObjVal(html, "MDMOID_LAN_HOST_CFG{1}", "DNSServers").ifBlank { "8.8.8.8,8.8.4.4" }
                val parts = (dns + ",8.8.4.4").split(",")
                lanDns1   = parts.getOrElse(0) { "8.8.8.8" }.trim()
                lanDns2   = parts.getOrElse(1) { "8.8.4.4" }.trim()
            } catch (e: Exception) { Log.w(TAG, "readConfig LAN: ${e.message}") }

            // ── WiFi ──
            var ssid24 = ""; var pass24 = ""; var ssid5 = ""; var pass5 = ""
            try {
                val html24 = get("x_wlssidcfg.html")
                val html5  = get("x_wl5gssidcfg.html")
                ssid24 = jsObjVal(html24, "MDMOID_LAN_WLAN_CT{1-1}", "SSID")
                    .ifBlank { inputVal(html24, "SSID") }
                pass24 = jsObjVal(html24, "MDMOID_LAN_WLAN_CT_PRE_SHARED_KEY{1-1-1}", "KeyPassphrase")
                    .ifBlank { inputVal(html24, "KeyPassphrase") }
                ssid5  = jsObjVal(html5,  "MDMOID_LAN_WLAN_CT{1-5}", "SSID")
                    .ifBlank { inputVal(html5, "SSID") }
                pass5  = jsObjVal(html5,  "MDMOID_LAN_WLAN_CT_PRE_SHARED_KEY{1-5-1}", "KeyPassphrase")
                    .ifBlank { inputVal(html5, "KeyPassphrase") }
            } catch (e: Exception) { Log.w(TAG, "readConfig WiFi: ${e.message}") }

            Log.d(TAG, "readConfig OK wanIp=$wanIp vlan=$wanVlan oid=$detectedOid ssid24=$ssid24 ssid5=$ssid5")
            ZteResult.Success(MctConfig(
                wanIp, wanMask, wanGw, wanVlan, wanDns1, wanDns2,
                lanIp, dhcpStart, dhcpEnd, lanDns1, lanDns2,
                ssid24, pass24, ssid5, pass5,
                wanOid = detectedOid   // FIX: incluir OID real en el resultado
            ))
        } catch (e: Exception) {
            ZteResult.Error("readConfig MCT: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  APPLY WAN
    //
    //  FIX 1 — Sin urlEnc() por campo individual.
    //           Los valores (ip, gw, mask, vlan, dns) van en texto plano dentro
    //           del XML. Solo el bloque completo se codifica UNA vez al final
    //           con urlEnc() al asignarlo al parámetro &value=
    //
    //  FIX 2 — wanOid dinámico: acepta el OID real del router como parámetro
    //           (default = _wanOid detectado en readConfig). Ya no hardcodea {1-1-1}.
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun applyWan(
        ip: String, mask: String, gw: String,
        vlan: String   = "100",
        dns1: String   = "8.8.8.8",
        dns2: String   = "8.8.4.4",
        isNew: Boolean = false,
        wanOid: String = _wanOid   // FIX: OID dinámico, no hardcodeado
    ): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val dns = "$dns1,$dns2"

            // FIX: valores en texto plano, SIN urlEnc() individual
            val fields =
                "UserDefinedMtu=1480" +
                        "&X_CU_MulticastVlan=-1" +
                        "&ConnectionType=IP_Routed" +
                        "&NATEnabled=1" +
                        "&Enable=1" +
                        "&AddressingType=Static" +
                        "&ExternalIPAddress=$ip" +
                        "&DefaultGateway=$gw" +
                        "&DNSServers=$dns" +
                        "&X_CU_ServiceList=INTERNET" +
                        "&X_UM_COM_VlanMuxID=$vlan" +
                        "&X_UM_COM_VlanMux8021p=0" +
                        "&SubnetMask=$mask" +
                        "&X_CU_IPv6IPAddress=" +
                        "&X_CU_DefaultIPv6Gateway=" +
                        "&X_CU_IPv6IPAddressOrigin=AutoConfigured" +
                        "&X_CU_IPv6DNSServers=" +
                        "&X_CU_IPv6Prefix=" +
                        "&X_CU_IPv6PrefixVltime=604800" +
                        "&X_CU_IPv6PrefixPltime=86400" +
                        "&X_CU_IPv6PrefixOrigin=PrefixDelegation" +
                        "&PrefixChildPrefixBits=::/64" +
                        "&X_CU_IPMode=1" +
                        "&X_CU_Dslite_Enable=0" +
                        "&X_CU_AftrMode=0" +
                        "&X_CU_Aftr=" +
                        "&X_CU_LanInterface=" +
                        "InternetGatewayDevice.LANDevice.1.LANEthernetInterfaceConfig.1.," +
                        "InternetGatewayDevice.LANDevice.1.LANEthernetInterfaceConfig.2.," +
                        "InternetGatewayDevice.LANDevice.1.LANEthernetInterfaceConfig.3.," +
                        "InternetGatewayDevice.LANDevice.1.LANEthernetInterfaceConfig.4." +
                        "&X_UM_COM_IPv6Enabled=0" +
                        "&X_UM_COM_IPv4Enabled=1" +
                        "&X_CU_IPForwardModeEnabled=0" +
                        "&X_CU_IPForwardList=" +
                        "&ConnectionStatus=Disconnected"

            val path = if (isNew) {
                // Conexión WAN nueva — action=addMltLv
                val raw = "<MDMOID_WAN_CONN_DEVICE{1}></MDMOID_WAN_CONN_DEVICE{1}>" +
                        "<MDMOID_WAN_IP_CONN>$fields</MDMOID_WAN_IP_CONN>"
                // FIX: urlEnc() solo UNA vez sobre el bloque completo
                "x_wancfg.cgi?type=objOperate&action=addMltLv" +
                        "&id=MDMOID_WAN_CONN_DEVICE{1}|MDMOID_WAN_IP_CONN" +
                        "&value=${urlEnc(raw)}"
            } else {
                // FIX: usa el OID dinámico recibido, no {1-1-1} hardcodeado
                val raw = "<$wanOid>$fields</$wanOid>"
                "x_wancfg.cgi?type=objOperate&action=edit" +
                        "&id=$wanOid" +
                        "&value=${urlEnc(raw)}"
            }

            get(path)
            delay(1000)
            Log.d(TAG, "WAN MCT OK ip=$ip vlan=$vlan isNew=$isNew oid=$wanOid")
            ZteResult.Success(Unit)
        } catch (e: Exception) {
            ZteResult.Error("WAN MCT: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  APPLY LAN
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun applyLan(
        lanIp: String     = "192.168.2.1",
        dhcpStart: String = "192.168.2.2",
        dhcpEnd: String   = "192.168.2.254",
        dns1: String      = "8.8.8.8",
        dns2: String      = "8.8.4.4"
    ): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val valStr =
                "<MDMOID_LAN_IP_INTF{1-1}>" +
                        "IPInterfaceIPAddress=$lanIp" +
                        "&IPInterfaceSubnetMask=255.255.255.0" +
                        "</MDMOID_LAN_IP_INTF{1-1}>" +
                        "<MDMOID_LAN_HOST_CFG{1}>" +
                        "DHCPServerEnable=1" +
                        "&MinAddress=$dhcpStart" +
                        "&MaxAddress=$dhcpEnd" +
                        "&SubnetMask=255.255.255.0" +
                        "&IPRouters=$lanIp" +
                        "&DHCPLeaseTime=86400" +
                        "&DNSOption=2" +
                        "&DNSServers=$dns1,$dns2" +
                        "</MDMOID_LAN_HOST_CFG{1}>"

            val path =
                "ctdhcp.cgi?type=objOperate&action=edit" +
                        "&id=MDMOID_LAN_IP_INTF{1-1}|MDMOID_LAN_HOST_CFG{1}" +
                        "&value=${urlEnc(valStr)}"

            get(path)
            delay(800)
            Log.d(TAG, "LAN MCT OK ip=$lanIp")
            ZteResult.Success(Unit)
        } catch (e: Exception) {
            ZteResult.Error("LAN MCT: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  APPLY ACL
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun applyAcl(): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val valStr =
                "<MDMOID_SSHD_CFG>NetworkAccess=LAN and WAN</MDMOID_SSHD_CFG>" +
                        "<MDMOID_HTTPD_CFG>NetworkAccess=LAN and WAN</MDMOID_HTTPD_CFG>" +
                        "<MDMOID_TELNETD_CFG>NetworkAccess=LAN and WAN</MDMOID_TELNETD_CFG>"
            val path =
                "x_accesscontrol.cgi?type=objOperate&action=edit" +
                        "&id=MDMOID_SSHD_CFG|MDMOID_HTTPD_CFG|MDMOID_TELNETD_CFG" +
                        "&value=${urlEnc(valStr)}"
            get(path)
            delay(500)
            ZteResult.Success(Unit)
        } catch (e: Exception) {
            ZteResult.Error("ACL MCT: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  APPLY UPnP
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun applyUpnp(): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val path =
                "x_upnpcfg.cgi?type=objOperate&action=edit" +
                        "&id=MDMOID_UPNP_CFG" +
                        "&value=${urlEnc("<MDMOID_UPNP_CFG>Enable=1</MDMOID_UPNP_CFG>")}"
            get(path)
            delay(400)
            ZteResult.Success(Unit)
        } catch (e: Exception) {
            ZteResult.Error("UPnP MCT: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  APPLY WiFi — band: "5" o "24"
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun applyWifi(ssid: String, pass: String, band: String): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val is5    = band == "5"
            val idx    = if (is5) "1-5" else "1-1"

            val valStr =
                "<MDMOID_LAN_WLAN_CT{$idx}>" +
                        "SSID=$ssid" +
                        "&Enable=1" +
                        "&SSIDAdvertisementEnabled=0" +
                        "&WMMEnable=1" +
                        "&MaxStaNum=0" +
                        "&BeaconType=WPA2" +
                        "&WPAEncryptionModes=AESEncryption" +
                        "</MDMOID_LAN_WLAN_CT{$idx}>" +
                        "<MDMOID_LAN_WLAN_CT_PRE_SHARED_KEY{$idx-1}>" +
                        "KeyPassphrase=$pass" +
                        "&wlWpaPskShow=0" +
                        "</MDMOID_LAN_WLAN_CT_PRE_SHARED_KEY{$idx-1}>" +
                        "<MDMOID_LAN_WLAN_CT_WEP_KEY{$idx-1}></MDMOID_LAN_WLAN_CT_WEP_KEY{$idx-1}>" +
                        "<MDMOID_LAN_WLAN_CT_WEP_KEY{$idx-2}></MDMOID_LAN_WLAN_CT_WEP_KEY{$idx-2}>" +
                        "<MDMOID_LAN_WLAN_CT_WEP_KEY{$idx-3}></MDMOID_LAN_WLAN_CT_WEP_KEY{$idx-3}>" +
                        "<MDMOID_LAN_WLAN_CT_WEP_KEY{$idx-4}></MDMOID_LAN_WLAN_CT_WEP_KEY{$idx-4}>"

            val cgi    = if (is5) "x_wl5gssidcfg.cgi" else "x_wlssidcfg.cgi"
            val idFull = if (is5)
                "MDMOID_LAN_WLAN_CT{1-5}|MDMOID_LAN_WLAN_CT_PRE_SHARED_KEY{1-5-1}|MDMOID_LAN_WLAN_CT_WEP_KEY{1-5-1}|MDMOID_LAN_WLAN_CT_WEP_KEY{1-5-2}|MDMOID_LAN_WLAN_CT_WEP_KEY{1-5-3}|MDMOID_LAN_WLAN_CT_WEP_KEY{1-5-4}"
            else
                "MDMOID_LAN_WLAN_CT{1-1}|MDMOID_LAN_WLAN_CT_PRE_SHARED_KEY{1-1-1}|MDMOID_LAN_WLAN_CT_WEP_KEY{1-1-1}|MDMOID_LAN_WLAN_CT_WEP_KEY{1-1-2}|MDMOID_LAN_WLAN_CT_WEP_KEY{1-1-3}|MDMOID_LAN_WLAN_CT_WEP_KEY{1-1-4}"

            val path = "$cgi?type=objOperate&action=edit&id=$idFull&value=${urlEnc(valStr)}"
            get(path)
            delay(800)
            Log.d(TAG, "WiFi MCT ${band}GHz OK ssid=$ssid")
            ZteResult.Success(Unit)
        } catch (e: Exception) {
            ZteResult.Error("WiFi ${band}GHz MCT: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  READ PON
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun readPon(): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            val html = get("x_ponStatus.html")
            fun jsVal(obj: String, field: String): String {
                val escaped = obj.replace("{", "\\{").replace("}", "\\}")
                return Regex("""objBuf\['$escaped'\]\['$field'\]\s*=\s*'([^']*)'""")
                    .find(html)?.groupValues?.get(1) ?: ""
            }
            val tempRaw = jsVal("MDMOID_GPON_OPTICAL_XCVR", "Temperature")
            val txRaw   = jsVal("MDMOID_GPON_OPTICAL_XCVR", "TxPower")
            val rxRaw   = jsVal("MDMOID_GPON_OPTICAL_XCVR", "RxPower")
            val sn      = jsVal("MDMOID_GPON_OMCI_STATS", "Sn")
                .ifBlank { jsVal("MDMOID_ONT_G", "SerialNumber") }
            val fw      = jsVal("MDMOID_GPONGLOBAL", "version0")

            val temp = if (tempRaw.isNotBlank()) (tempRaw.toLongOrNull()?.div(256))?.toString() ?: "" else ""
            val txV  = txRaw.toLongOrNull() ?: 0L
            val rxV  = rxRaw.toLongOrNull() ?: 0L
            val tx   = if (txV == 0L) "-40.0" else String.format("%.1f", 10 * Math.log10(txV * 0.0001))
            val rx   = if (rxV == 0L) "-40.0" else String.format("%.1f", 10 * Math.log10(rxV * 0.0001))

            mapOf("rx" to rx, "tx" to tx, "temp" to temp, "sn" to sn, "fw" to fw)
        } catch (e: Exception) {
            Log.w(TAG, "PON error: ${e.message}")
            emptyMap()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  REBOOT
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun reboot(): ZteResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val resetHtml = get("ctreset.html")
            val skMatch   = Regex("""sessionKey\s*=\s*'?(\d+)'?""").find(resetHtml)
            val sk        = skMatch?.groupValues?.get(1) ?: ""
            try { get("ctrebootinfo.cgi?sessionKey=$sk") } catch (e: Exception) { /* OK — desconexión normal */ }
            ZteResult.Success(Unit)
        } catch (e: Exception) {
            ZteResult.Success(Unit) // Desconexión = reboot OK
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  APPLY ALL
    //  Login → WAN → LAN → ACL → UPnP → PON → WiFi5 → WiFi2.4 → Reboot
    //
    //  FIX: pasa wanOid dinámico a applyWan
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun applyAll(
        ip: String, mask: String, gw: String,
        vlan: String      = "100",
        dns1: String      = "8.8.8.8",
        dns2: String      = "8.8.4.4",
        lanIp: String     = "192.168.2.1",
        dhcpStart: String = "192.168.2.2",
        dhcpEnd: String   = "192.168.2.254",
        lanDns1: String   = "8.8.8.8",
        lanDns2: String   = "8.8.4.4",
        ssid24: String,  pass24: String,
        ssid5: String,   pass5: String,
        isNew: Boolean    = false,
        // FIX: acepta el OID WAN (viene de MctConfig.wanOid o de readConfig)
        wanOid: String    = _wanOid,
        onProgress: suspend (step: Int, total: Int, desc: String, ok: Boolean) -> Unit = { _, _, _, _ -> }
    ): ZteResult<Map<String, String>> {
        val total  = 9
        val errors = mutableListOf<String>()

        suspend fun step(n: Int, desc: String, action: suspend () -> ZteResult<*>) {
            onProgress(n, total, desc, true)
            val r = action()
            if (r is ZteResult.Error) errors.add("$desc: ${r.message}")
            onProgress(n, total, desc, r is ZteResult.Success)
        }

        step(1, "Login")        { login() }
        // FIX: pasa wanOid dinámico
        step(2, "WAN")          { applyWan(ip, mask, gw, vlan, dns1, dns2, isNew, wanOid) }
        step(3, "LAN")          { applyLan(lanIp, dhcpStart, dhcpEnd, lanDns1, lanDns2) }
        step(4, "ACL")          { applyAcl() }
        step(5, "UPnP")         { applyUpnp() }

        // PON antes del reboot (igual que la versión web)
        onProgress(6, total, "Señal Óptica", true)
        val pon = readPon()
        onProgress(6, total, "Señal Óptica", true)

        step(7, "WiFi 5 GHz")   { applyWifi(ssid5,  pass5,  "5") }
        step(8, "WiFi 2.4 GHz") { applyWifi(ssid24, pass24, "24") }
        step(9, "Reinicio")     { reboot() }

        return if (errors.isEmpty()) ZteResult.Success(pon)
        else ZteResult.Error(errors.joinToString("\n"))
    }
}