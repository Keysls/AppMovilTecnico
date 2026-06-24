package com.enetfiber.tecnico.ui.instalacion

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.enetfiber.tecnico.R
import com.enetfiber.tecnico.databinding.ActivityConfigOnuBinding
import com.enetfiber.tecnico.equipos.*
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pantalla de configuración de ONU — extraída de InstalacionActivity.
 *
 * Flujo interno:  Seleccionar equipo → Cargar config → Editar → Aplicar → Overlay → Listo
 *
 * Recibe via Intent:
 *   EXTRA_IP_WAN / EXTRA_MASCARA / EXTRA_GATEWAY  — datos WAN de la orden (NOC)
 *
 * Devuelve via setResult:
 *   RESULT_EQUIPO, RESULT_SN, RESULT_RX, RESULT_TX, RESULT_VLAN,
 *   RESULT_SSID, RESULT_PASS, RESULT_SSID5, RESULT_PASS5, RESULT_CONFIGURED
 */
@AndroidEntryPoint
class ConfigOnuActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IP_WAN   = "ip_wan"
        const val EXTRA_MASCARA  = "mascara"
        const val EXTRA_GATEWAY  = "gateway"

        const val RESULT_EQUIPO     = "equipo_activo"
        const val RESULT_SN         = "result_sn"
        const val RESULT_RX         = "result_rx"
        const val RESULT_TX         = "result_tx"
        const val RESULT_VLAN       = "result_vlan"
        const val RESULT_SSID       = "result_ssid"
        const val RESULT_PASS       = "result_pass"
        const val RESULT_SSID5      = "result_ssid5"
        const val RESULT_PASS5      = "result_pass5"
        const val RESULT_CONFIGURED = "config_applied"
    }

    private lateinit var binding: ActivityConfigOnuBinding

    private var pasoActual   = 1
    private var equipoActivo  = ""
    private var gatewayEquipo = ""
    private var isUpdating    = false
    private var configApplied = false

    // Resultados de la configuración
    private var resultSn    = ""
    private var resultRx    = ""
    private var resultTx    = ""
    private var resultVlan  = "100"
    private var resultSsid  = ""
    private var resultPass  = ""
    private var resultSsid5 = ""
    private var resultPass5 = ""
    private var resultLanIp     = ""
    private var resultDhcpStart = ""
    private var resultDhcpEnd   = ""
    private var resultLanDns    = ""

    // WAN de la orden (recibidos por intent)
    private var ipWan   = ""
    private var mascara = ""
    private var gateway = ""

    // WifiLock
    private var wifiLock: WifiManager.WifiLock? = null

    // Reboot countdown
    private val REBOOT_SECS = 5
    private var rebootHandler: Handler? = null
    private var rebootRunnable: Runnable? = null

    // Overlay views
    private lateinit var overlayConfiguracion: FrameLayout
    private lateinit var overlayPanel: LinearLayout
    private lateinit var ovSubtitulo: TextView
    private lateinit var ovBtnCerrar: TextView
    private lateinit var ovBtnNuevaConfig: MaterialButton
    private lateinit var ovWanIp: TextView
    private lateinit var ovWanGw: TextView
    private lateinit var ovWanMask: TextView
    private lateinit var ovDns: TextView
    private lateinit var ovSsid24: TextView
    private lateinit var ovSsid5g: TextView
    private lateinit var ovSsid5gRow: LinearLayout
    private lateinit var ovWifiPass: TextView
    private lateinit var ovWifiPass5g: TextView
    private lateinit var ovRx: TextView
    private lateinit var ovRxBar: View
    private lateinit var ovTx: TextView
    private lateinit var ovTxBar: View
    private lateinit var ovPonNaRow: LinearLayout
    private lateinit var ovPonNaTxt: TextView
    private lateinit var ovSn: TextView

    // Config fields
    private lateinit var Txt_ip: EditText
    private lateinit var Txt_mascara: EditText
    private lateinit var Txt_gateway: EditText
    private lateinit var Txt_dns1: EditText
    private lateinit var Txt_dns2: EditText
    private lateinit var Txt_vlan: EditText
    private lateinit var Txt_ssid_24: EditText
    private lateinit var Txt_pass_24: EditText
    private lateinit var Txt_ssid_5g: EditText
    private lateinit var Txt_pass_5g: EditText

    // Equipment cards
    private lateinit var cardOptic: CardView
    private lateinit var cardZte: CardView
    private lateinit var cardZteF6600p: CardView
    private lateinit var cardMct: CardView
    private lateinit var cardLanly: CardView
    private lateinit var cardBenmundo: CardView
    private lateinit var cardQualtek: CardView
    private lateinit var cardDixon130: CardView
    private lateinit var cardDixonAX: CardView
    private lateinit var cardDixon110: CardView
    private lateinit var cardDesconocido: CardView

    private val todasLasCards get() = listOf(
        cardOptic, cardLanly, cardZte, cardBenmundo, cardMct,
        cardDixon130, cardDixonAX, cardDixon110,
        cardQualtek, cardZteF6600p, cardDesconocido
    )

    // ═══════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfigOnuBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Seleccionar equipo"
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        // Datos WAN de la orden
        ipWan   = intent.getStringExtra(EXTRA_IP_WAN) ?: ""
        mascara = intent.getStringExtra(EXTRA_MASCARA) ?: ""
        gateway = intent.getStringExtra(EXTRA_GATEWAY) ?: ""

        bindOverlayViews()
        bindConfigViews()
        bindEquipoCards()

        setupOverlay()
        setupEyeToggle(R.id.btn_eye_24, R.id.Txt_pass_24)
        setupEyeToggle(R.id.btn_eye_5g, R.id.Txt_pass_5g)
        setupColapsables()
        setupBotonConfigurar()
        setupBotonCargarConfig()
        setupBotonListo()

        if (ipWan.isNotBlank()) {
            val tv = findViewById<TextView>(R.id.tvWanInfo)
            val card = findViewById<CardView>(R.id.cardWanInfo)
            tv?.text = "WAN: $ipWan / $mascara → $gateway"
            card?.visibility = View.VISIBLE
        }

        mostrarPaso(1)
        obtenerInfoRed()
    }

    // ═══════════════════════════════════════════════════════
    //  PASOS INTERNOS (1 = seleccionar, 2 = configurar)
    // ═══════════════════════════════════════════════════════
    private fun mostrarPaso(paso: Int) {
        pasoActual = paso
        binding.layoutSeleccionar.visibility = if (paso == 1) View.VISIBLE else View.GONE
        binding.layoutConfigurar.visibility  = if (paso == 2) View.VISIBLE else View.GONE

        fun activo(tv: TextView) {
            tv.setTextColor(android.graphics.Color.WHITE)
            tv.setBackgroundResource(R.drawable.circle_active)
        }
        fun inactivo(tv: TextView) {
            tv.setTextColor(getColor(R.color.txt_hint))
            tv.setBackgroundResource(R.drawable.circle_inactive)
        }
        activo(binding.paso1Circulo)
        if (paso >= 2) activo(binding.paso2Circulo) else inactivo(binding.paso2Circulo)

        supportActionBar?.title = if (paso == 1) "Seleccionar equipo" else "Configurar $equipoActivo"
        if (paso == 1) obtenerInfoRed()
    }

    // ═══════════════════════════════════════════════════════
    //  SELECCIONAR EQUIPO
    // ═══════════════════════════════════════════════════════
    private fun bindEquipoCards() {
        cardOptic       = findViewById(R.id.card_sel_optic)
        cardZte         = findViewById(R.id.card_sel_zte)
        cardZteF6600p   = findViewById(R.id.card_sel_ztef6600p)
        cardLanly       = findViewById(R.id.card_sel_lanly)
        cardMct         = findViewById(R.id.card_sel_mct)
        cardBenmundo    = findViewById(R.id.card_sel_benmundo)
        cardQualtek     = findViewById(R.id.card_sel_qualtek)
        cardDixon130    = findViewById(R.id.card_sel_dixon130)
        cardDixonAX     = findViewById(R.id.card_sel_dixonax)
        cardDixon110    = findViewById(R.id.card_sel_dixon110)
        cardDesconocido = findViewById(R.id.card_sel_desconocido)

        cardOptic.setOnClickListener       { seleccionarEquipo("OPTIC", cardOptic) }
        cardLanly.setOnClickListener       { seleccionarEquipo("LANLY", cardLanly) }
        cardZte.setOnClickListener         { seleccionarEquipo("ZTE F6201B", cardZte) }
        cardZteF6600p.setOnClickListener   { seleccionarEquipo("ZTE F6600P", cardZteF6600p) }
        cardBenmundo.setOnClickListener    { seleccionarEquipo("BENMUNDO", cardBenmundo) }
        cardMct.setOnClickListener         { seleccionarEquipo("MCT AX3000", cardMct) }
        cardQualtek.setOnClickListener     { seleccionarEquipo("QUALTEK", cardQualtek) }
        cardDixon130.setOnClickListener    { seleccionarEquipo("DIXON D130GW", cardDixon130) }
        cardDixonAX.setOnClickListener     { seleccionarEquipo("DIXON D580GW-AX", cardDixonAX) }
        cardDixon110.setOnClickListener    { seleccionarEquipo("DIXON D110GWC", cardDixon110) }
        cardDesconocido.setOnClickListener {
            todasLasCards.forEach { it.setCardBackgroundColor(0xFFFFFFFF.toInt()) }
            cardDesconocido.setCardBackgroundColor(0xFFFEF3C7.toInt())
            equipoActivo = "DESCONOCIDO"
            configApplied = false
            devolverResultado()
        }
    }

    private fun seleccionarEquipo(nombre: String, cardSeleccionada: CardView) {
        equipoActivo = nombre
        todasLasCards.forEach { card ->
            val colorNormal = if (card.id == R.id.card_sel_desconocido) 0xFFFFFBEB.toInt() else 0xFFFFFFFF.toInt()
            card.setCardBackgroundColor(if (card == cardSeleccionada) 0xFFEEF3FC.toInt() else colorNormal)
        }
        val btn = findViewById<MaterialButton>(R.id.btn_sel_cargar_config)
        btn.visibility = View.VISIBLE
        btn.isEnabled  = true
        btn.text       = "↓  Cargar configuración del equipo"
    }

    private fun setupBotonCargarConfig() {
        val btn = findViewById<MaterialButton>(R.id.btn_sel_cargar_config) ?: return
        btn.setOnClickListener {
            if (equipoActivo.isEmpty()) return@setOnClickListener
            if (gatewayEquipo.isBlank()) {
                Toast.makeText(this, "⚠ Sin WiFi — conecta al equipo ONU", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            btn.isEnabled = false
            btn.text = "Cargando config..."
            adquirirWifiLock("cargar-$equipoActivo")
            when (equipoActivo) {
                "OPTIC"            -> cargarConfigOptic(btn)
                "LANLY"            -> cargarConfigLanly(btn)
                "ZTE F6201B"       -> cargarConfigZte(btn)
                "ZTE F6600P"       -> cargarConfigZteF6600P(btn)
                "BENMUNDO"         -> cargarConfigBenmundo(btn)
                "MCT AX3000"       -> cargarConfigMct(btn)
                "QUALTEK"          -> cargarConfigQualTek(btn)
                "DIXON D130GW"     -> cargarConfigDixon(btn, DixonConfigurator.DixonModelo.D130GW)
                "DIXON D580GW-AX"  -> cargarConfigDixon(btn, DixonConfigurator.DixonModelo.D580GW_AX)
                "DIXON D110GWC"    -> cargarConfigDixon(btn, DixonConfigurator.DixonModelo.D110GWC)
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    //  IR AL PASO 2 (CONFIGURAR)
    // ═══════════════════════════════════════════════════════
    private fun irAConfigurar() {
        if (ipWan.isNotBlank()) {
            isUpdating = true
            Txt_ip.setText(ipWan)
            Txt_mascara.setText(mascara.ifBlank { "255.255.255.0" })
            Txt_gateway.setText(gateway)
            isUpdating = false
        }
        listOf(Txt_ip, Txt_mascara, Txt_gateway).forEach {
            it.isEnabled = false; it.isFocusable = false; it.isFocusableInTouchMode = false; it.alpha = 0.55f
        }
        listOf(Txt_dns1, Txt_dns2).forEach {
            it.isEnabled = true; it.isFocusable = true; it.isFocusableInTouchMode = true; it.alpha = 1f
        }
        findViewById<TextView>(R.id.txt_equipo_header)?.text = equipoActivo
        findViewById<TextView>(R.id.txt_ip_red)?.text = gatewayEquipo

        val layoutConfig = findViewById<LinearLayout>(R.id.layout_config)
        val arrowConfig  = findViewById<ImageView>(R.id.img_arrow)
        if (layoutConfig?.visibility != View.VISIBLE) toggleSeccion(layoutConfig, arrowConfig)
        val layoutWifi = findViewById<LinearLayout>(R.id.layout_wifi)
        val arrowWifi  = findViewById<ImageView>(R.id.img_arrow_wifi)
        if (layoutWifi?.visibility != View.VISIBLE) toggleSeccion(layoutWifi, arrowWifi)

        mostrarPaso(2)
    }

    // ═══════════════════════════════════════════════════════
    //  CONFIG VIEWS
    // ═══════════════════════════════════════════════════════
    private fun bindConfigViews() {
        Txt_ip       = findViewById(R.id.Txt_ip)
        Txt_mascara  = findViewById(R.id.Txt_mascara)
        Txt_gateway  = findViewById(R.id.Txt_gateway)
        Txt_dns1     = findViewById(R.id.Txt_dns1)
        Txt_dns2     = findViewById(R.id.Txt_dns2)
        Txt_vlan     = findViewById(R.id.Txt_vlan)
        Txt_ssid_24  = findViewById(R.id.Txt_ssid_24)
        Txt_pass_24  = findViewById(R.id.Txt_pass_24)
        Txt_ssid_5g  = findViewById(R.id.Txt_ssid_5g)
        Txt_pass_5g  = findViewById(R.id.Txt_pass_5g)
    }

    private fun rellenarCamposConfig(wanIp: String, wanMask: String, wanGw: String, vlan: String, dns1: String, dns2: String, ssid24: String, pass24: String, ssid5: String, pass5: String) {
        isUpdating = true
        if (wanIp.isNotBlank())   Txt_ip.setText(wanIp)
        if (wanMask.isNotBlank()) Txt_mascara.setText(wanMask)
        if (wanGw.isNotBlank())   Txt_gateway.setText(wanGw)
        if (vlan.isNotBlank())    Txt_vlan.setText(vlan)
        Txt_dns1.setText("8.8.8.8")
        Txt_dns2.setText("8.8.4.4")
        if (ssid24.isNotBlank())  Txt_ssid_24.setText(ssid24)
        if (pass24.isNotBlank())  Txt_pass_24.setText(pass24)
        if (ssid5.isNotBlank())   Txt_ssid_5g.setText(ssid5)
        if (pass5.isNotBlank())   Txt_pass_5g.setText(pass5)
        isUpdating = false
    }

    // ═══════════════════════════════════════════════════════
    //  BOTÓN CONFIGURAR
    // ═══════════════════════════════════════════════════════
    private fun setupBotonConfigurar() {
        val btn = findViewById<MaterialButton>(R.id.btn_configurar_equipo)
        btn.setOnClickListener {
            if (equipoActivo.isEmpty()) { Toast.makeText(this, "⚠ No hay equipo seleccionado", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val ip     = Txt_ip.text.toString().trim()
            val mask   = Txt_mascara.text.toString().trim()
            val gw     = Txt_gateway.text.toString().trim()
            val dns1   = Txt_dns1.text.toString().trim()
            val dns2   = Txt_dns2.text.toString().trim()
            val vlan   = Txt_vlan.text.toString().trim().ifBlank { "100" }
            resultVlan = vlan
            val ssid24 = Txt_ssid_24.text.toString().trim()
            val pass24 = Txt_pass_24.text.toString().trim()
            val ssid5g = Txt_ssid_5g.text.toString().trim()
            val pass5g = Txt_pass_5g.text.toString().trim().ifBlank { pass24 }
            val con5g  = ssid5g.isNotBlank()
            if (ip.isBlank() || gw.isBlank()) { Toast.makeText(this, "⚠ Completa IP y Gateway WAN", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (ssid24.isBlank()) { Toast.makeText(this, "⚠ SSID 2.4 GHz es obligatorio", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (pass24.length < 8) { Toast.makeText(this, "⚠ Contraseña mínimo 8 caracteres", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            when (equipoActivo) {
                "OPTIC"            -> configurarOptic(btn, ip, mask, gw, vlan, dns1, dns2, ssid24, pass24, ssid5g, pass5g, con5g)
                "LANLY"            -> configurarLanly(btn, ip, mask, gw, vlan, dns1, dns2, ssid24, pass24, ssid5g, pass5g, con5g)
                "ZTE F6201B"       -> configurarZte(btn, ip, mask, gw, dns1, dns2, vlan, ssid24, pass24, ssid5g, pass5g, con5g)
                "ZTE F6600P"       -> configurarZteF6600P(btn, ip, mask, gw, dns1, dns2, vlan, ssid24, pass24, ssid5g, pass5g, con5g)
                "BENMUNDO"         -> configurarBenmundo(btn, ip, mask, gw, vlan, dns1, dns2, ssid24, pass24, ssid5g, pass5g, con5g)
                "MCT AX3000"       -> configurarMct(btn, ip, mask, gw, vlan, dns1, dns2, ssid24, pass24, ssid5g, pass5g, con5g)
                "QUALTEK"          -> configurarQualTek(btn, ip, mask, gw, vlan, dns1, dns2, ssid24, pass24, ssid5g, pass5g, con5g)
                "DIXON D130GW"     -> configurarDixon(btn, ip, mask, gw, vlan, dns1, dns2, ssid24, pass24, ssid5g, pass5g, con5g, DixonConfigurator.DixonModelo.D130GW)
                "DIXON D580GW-AX"  -> configurarDixon(btn, ip, mask, gw, vlan, dns1, dns2, ssid24, pass24, ssid5g, pass5g, con5g, DixonConfigurator.DixonModelo.D580GW_AX)
                "DIXON D110GWC"    -> configurarDixon(btn, ip, mask, gw, vlan, dns1, dns2, ssid24, pass24, ssid5g, pass5g, con5g, DixonConfigurator.DixonModelo.D110GWC)
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    //  BOTÓN LISTO (DEVOLVER RESULTADO)
    // ═══════════════════════════════════════════════════════
    private fun setupBotonListo() {
        findViewById<MaterialButton>(R.id.btnListo)?.setOnClickListener {
            devolverResultado()
        }
    }

    private fun devolverResultado() {
        val data = android.content.Intent().apply {
            putExtra(RESULT_EQUIPO, equipoActivo)
            putExtra(RESULT_SN, resultSn)
            putExtra(RESULT_RX, resultRx)
            putExtra(RESULT_TX, resultTx)
            putExtra(RESULT_VLAN, resultVlan)
            putExtra(RESULT_SSID, resultSsid)
            putExtra(RESULT_PASS, resultPass)
            putExtra(RESULT_SSID5, resultSsid5)
            putExtra(RESULT_PASS5, resultPass5)
            putExtra(RESULT_CONFIGURED, configApplied)
        }
        setResult(RESULT_OK, data)
        finish()
    }

    // ═══════════════════════════════════════════════════════
    //  HELPERS DE CONFIGURACIÓN
    // ═══════════════════════════════════════════════════════
    private fun btnInicio(btn: MaterialButton) {
        btn.isEnabled = false; btn.text = "Conectando..."
        adquirirWifiLock(equipoActivo)
    }
    private fun btnFin(btn: MaterialButton) {
        liberarWifiLock()
        Handler(Looper.getMainLooper()).postDelayed({ btn.text = "Configurar equipo"; btn.isEnabled = true }, 3000)
    }
    private suspend fun actualizarProgreso(btn: MaterialButton, step: Int, total: Int, desc: String, ok: Boolean) {
        withContext(Dispatchers.Main) { btn.text = "[${(step * 100) / total}%] ${if (ok) "✓" else "✗"} $desc" }
    }
    private fun mostrarResultado(btn: MaterialButton, ok: Boolean, errorMsg: String, ip: String, mask: String, gw: String, dns1: String, dns2: String, ssid24: String, ssid5g: String, pass: String, pass5g: String, con5g: Boolean, rx: String, tx: String, sn: String) {
        btn.text = if (ok) "✓  CONFIGURADO" else "⚠  CON ERRORES"
        if (!ok && errorMsg.isNotBlank()) Toast.makeText(this, "Errores:\n$errorMsg", Toast.LENGTH_LONG).show()
        resultSn = sn; resultRx = rx; resultTx = tx
        resultSsid = ssid24; resultPass = pass; resultSsid5 = ssid5g; resultPass5 = pass5g
        if (ok) configApplied = true
        mostrarOverlay(ip, mask, gw, dns1, dns2, ssid24, ssid5g, pass, pass5g, con5g, rx, tx, sn)
        btnFin(btn)
    }

    @SuppressLint("SetTextI18n")
    private fun mostrarLanInfo() {
        val card = findViewById<CardView>(R.id.cardLanInfo) ?: return
        if (resultLanIp.isBlank()) { card.visibility = View.GONE; return }
        card.visibility = View.VISIBLE
        findViewById<TextView>(R.id.tvLanIp)?.text     = resultLanIp
        findViewById<TextView>(R.id.tvDhcpStart)?.text = resultDhcpStart
        findViewById<TextView>(R.id.tvDhcpEnd)?.text   = resultDhcpEnd
        findViewById<TextView>(R.id.tvLanDns)?.text    = resultLanDns
    }

    // ═══════════════════════════════════════════════════════
    //  CARGAR CONFIG POR EQUIPO
    // ═══════════════════════════════════════════════════════
    private fun cargarConfigOptic(btn: MaterialButton) {
        lifecycleScope.launch {
            val result = OpticConfigurator(gatewayEquipo).readConfig()
            withContext(Dispatchers.Main) {
                liberarWifiLock()
                when (result) {
                    is ZteResult.Success -> { val c = result.data; resultLanIp = c.lanIp; resultDhcpStart = c.dhcpStart; resultDhcpEnd = c.dhcpEnd; resultLanDns = "${c.dns1} / ${c.dns2}"; rellenarCamposConfig(c.wanIp, c.wanMask, c.wanGw, c.wanVlan, c.dns1, c.dns2, c.ssid24, c.pass24, c.ssid5, c.pass5); mostrarLanInfo(); irAConfigurar() }
                    is ZteResult.Error -> { Toast.makeText(this@ConfigOnuActivity, "⚠ ${result.message}", Toast.LENGTH_LONG).show(); btn.isEnabled = true; btn.text = "↓  Cargar configuración del equipo" }
                }
            }
        }
    }
    private fun cargarConfigZte(btn: MaterialButton) {
        val zte = ZteConfigurator(gatewayEquipo)
        lifecycleScope.launch {
            val login = zte.login("admin", "Web@0063")
            if (login is ZteResult.Error) { withContext(Dispatchers.Main) { liberarWifiLock(); Toast.makeText(this@ConfigOnuActivity, "⚠ Login ZTE: ${login.message}", Toast.LENGTH_LONG).show(); btn.isEnabled = true; btn.text = "↓  Cargar configuración del equipo" }; return@launch }
            val result = zte.readConfig()
            withContext(Dispatchers.Main) {
                liberarWifiLock()
                when (result) {
                    is ZteResult.Success -> { val c = result.data; resultLanIp = c.lan.ip; resultDhcpStart = c.lan.dhcpStart; resultDhcpEnd = c.lan.dhcpEnd; resultLanDns = "${c.lan.dns1} / ${c.lan.dns2}"; rellenarCamposConfig(c.wan.ip, c.wan.mask, c.wan.gw, c.wan.vlan.ifBlank { "100" }, c.wan.dns1, c.wan.dns2, c.wifi24.ssid, c.wifi24.pass, c.wifi5.ssid, c.wifi5.pass); mostrarLanInfo(); irAConfigurar() }
                    is ZteResult.Error -> { Toast.makeText(this@ConfigOnuActivity, "⚠ ${result.message}", Toast.LENGTH_LONG).show(); btn.isEnabled = true; btn.text = "↓  Cargar configuración del equipo" }
                }
            }
        }
    }
    private fun cargarConfigZteF6600P(btn: MaterialButton) {
        val zte = ZteConfigurator(onuIp = gatewayEquipo, modelo = ZteModelo.F6600P)
        lifecycleScope.launch {
            val login = zte.login("admin", "Web@0063")
            if (login is ZteResult.Error) { withContext(Dispatchers.Main) { liberarWifiLock(); Toast.makeText(this@ConfigOnuActivity, "⚠ Login ZTE F6600P: ${login.message}", Toast.LENGTH_LONG).show(); btn.isEnabled = true; btn.text = "↓  Cargar configuración del equipo" }; return@launch }
            val result = zte.readConfig()
            withContext(Dispatchers.Main) {
                liberarWifiLock()
                when (result) {
                    is ZteResult.Success -> { val c = result.data; resultLanIp = c.lan.ip; resultDhcpStart = c.lan.dhcpStart; resultDhcpEnd = c.lan.dhcpEnd; resultLanDns = "${c.lan.dns1} / ${c.lan.dns2}"; rellenarCamposConfig(c.wan.ip, c.wan.mask, c.wan.gw, c.wan.vlan.ifBlank { "100" }, c.wan.dns1, c.wan.dns2, c.wifi24.ssid, c.wifi24.pass, c.wifi5.ssid, c.wifi5.pass); mostrarLanInfo(); irAConfigurar() }
                    is ZteResult.Error -> { Toast.makeText(this@ConfigOnuActivity, "⚠ ${result.message}", Toast.LENGTH_LONG).show(); btn.isEnabled = true; btn.text = "↓  Cargar configuración del equipo" }
                }
            }
        }
    }
    private fun cargarConfigLanly(btn: MaterialButton) {
        lifecycleScope.launch {
            val result = LanlyConfigurator(gatewayEquipo).readConfig()
            withContext(Dispatchers.Main) {
                liberarWifiLock()
                when (result) {
                    is ZteResult.Success -> { val c = result.data; resultLanIp = c.lanIp; resultDhcpStart = c.dhcpStart; resultDhcpEnd = c.dhcpEnd; resultLanDns = "${c.dns1} / ${c.dns2}"; rellenarCamposConfig(c.wanIp, c.wanMask, c.wanGw, c.wanVlan, c.dns1, c.dns2, c.ssid24, c.pass24, c.ssid5, c.pass5); mostrarLanInfo(); irAConfigurar() }
                    is ZteResult.Error -> { Toast.makeText(this@ConfigOnuActivity, "⚠ ${result.message}", Toast.LENGTH_LONG).show(); btn.isEnabled = true; btn.text = "↓  Cargar configuración del equipo" }
                }
            }
        }
    }
    private fun cargarConfigBenmundo(btn: MaterialButton) {
        lifecycleScope.launch {
            val result = BenmundoConfigurator(gatewayEquipo).readConfig()
            withContext(Dispatchers.Main) {
                liberarWifiLock()
                when (result) {
                    is ZteResult.Success -> { val c = result.data; resultLanIp = c.lanIp; resultDhcpStart = c.dhcpStart; resultDhcpEnd = c.dhcpEnd; resultLanDns = "${c.dns1} / ${c.dns2}"; rellenarCamposConfig(c.wanIp, c.wanMask, c.wanGw, c.wanVlan, c.dns1, c.dns2, c.ssid24, c.pass24, c.ssid5, c.pass5); mostrarLanInfo(); irAConfigurar() }
                    is ZteResult.Error -> { Toast.makeText(this@ConfigOnuActivity, "⚠ ${result.message}", Toast.LENGTH_LONG).show(); btn.isEnabled = true; btn.text = "↓  Cargar configuración del equipo" }
                }
            }
        }
    }
    private fun cargarConfigMct(btn: MaterialButton) {
        val mct = MctConfigurator(gatewayEquipo)
        lifecycleScope.launch {
            val loginResult = mct.login()
            if (loginResult is ZteResult.Error) { withContext(Dispatchers.Main) { liberarWifiLock(); Toast.makeText(this@ConfigOnuActivity, "⚠ Login MCT: ${loginResult.message}", Toast.LENGTH_LONG).show(); btn.isEnabled = true; btn.text = "↓  Cargar configuración del equipo" }; return@launch }
            val result = mct.readConfig()
            withContext(Dispatchers.Main) {
                liberarWifiLock()
                when (result) {
                    is ZteResult.Success -> { val c = result.data; resultLanIp = c.lanIp; resultDhcpStart = c.dhcpStart; resultDhcpEnd = c.dhcpEnd; resultLanDns = "${c.lanDns1} / ${c.lanDns2}"; rellenarCamposConfig(c.wanIp, c.wanMask, c.wanGw, c.wanVlan, c.wanDns1, c.wanDns2, c.ssid24, c.pass24, c.ssid5, c.pass5); mostrarLanInfo(); irAConfigurar() }
                    is ZteResult.Error -> { Toast.makeText(this@ConfigOnuActivity, "⚠ ${result.message}", Toast.LENGTH_LONG).show(); btn.isEnabled = true; btn.text = "↓  Cargar configuración del equipo" }
                }
            }
        }
    }
    private fun cargarConfigQualTek(btn: MaterialButton) {
        lifecycleScope.launch {
            val result = QualTekConfigurator(gatewayEquipo).readConfig()
            withContext(Dispatchers.Main) {
                liberarWifiLock()
                when (result) {
                    is ZteResult.Success -> { val c = result.data; resultLanIp = c.lanIp; resultDhcpStart = c.dhcpStart; resultDhcpEnd = c.dhcpEnd; resultLanDns = "${c.dns1} / ${c.dns2}"; rellenarCamposConfig(c.wanIp, c.wanMask, c.wanGw, c.wanVlan, c.dns1, c.dns2, c.ssid24, c.pass24, c.ssid5, c.pass5); mostrarLanInfo(); irAConfigurar() }
                    is ZteResult.Error -> { Toast.makeText(this@ConfigOnuActivity, "⚠ ${result.message}", Toast.LENGTH_LONG).show(); btn.isEnabled = true; btn.text = "↓  Cargar configuración del equipo" }
                }
            }
        }
    }
    private fun cargarConfigDixon(btn: MaterialButton, modelo: DixonConfigurator.DixonModelo) {
        lifecycleScope.launch {
            val result = DixonConfigurator(gatewayEquipo, modelo).readConfig()
            withContext(Dispatchers.Main) {
                liberarWifiLock()
                when (result) {
                    is ZteResult.Success -> { val c = result.data; resultLanIp = c.lanIp; resultDhcpStart = c.dhcpStart; resultDhcpEnd = c.dhcpEnd; resultLanDns = "${c.dns1} / ${c.dns2}"; rellenarCamposConfig(c.wanIp, c.wanMask, c.wanGw, c.wanVlan, c.dns1, c.dns2, c.ssid24, c.pass24, c.ssid5, c.pass5); mostrarLanInfo(); irAConfigurar() }
                    is ZteResult.Error -> { Toast.makeText(this@ConfigOnuActivity, "⚠ ${result.message}", Toast.LENGTH_LONG).show(); btn.isEnabled = true; btn.text = "↓  Cargar configuración del equipo" }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    //  CONFIGURAR POR EQUIPO
    // ═══════════════════════════════════════════════════════
    private fun configurarOptic(btn: MaterialButton, ip: String, mask: String, gw: String, vlan: String, dns1: String, dns2: String, ssid24: String, pass24: String, ssid5g: String, pass5g: String, con5g: Boolean) {
        btnInicio(btn)
        lifecycleScope.launch {
            val result = OpticConfigurator(gatewayEquipo.ifBlank { "192.168.1.1" }).applyAllExtended(ip = ip, mask = mask.ifBlank { "255.255.255.0" }, gw = gw, vlan = vlan, dns1 = dns1.ifBlank { "8.8.8.8" }, dns2 = dns2.ifBlank { "8.8.4.4" }, ssid24 = ssid24, pass24 = pass24, ssid5g = ssid5g.ifBlank { ssid24 }, pass5g = pass5g.ifBlank { pass24 }, wifi5g = con5g) { step, total, desc, ok -> actualizarProgreso(btn, step, total, desc, ok) }
            withContext(Dispatchers.Main) { val data = if (result is ZteResult.Success) result.data else emptyMap(); mostrarResultado(btn, result is ZteResult.Success, if (result is ZteResult.Error) result.message else "", ip, mask.ifBlank { "255.255.255.0" }, gw, dns1, dns2, ssid24, if (con5g) ssid5g else "", pass24, pass5g, con5g, data["rx"] ?: "", data["tx"] ?: "", data["gponsn"] ?: data["sn"] ?: "") }
        }
    }
    private fun configurarLanly(btn: MaterialButton, ip: String, mask: String, gw: String, vlan: String, dns1: String, dns2: String, ssid24: String, pass24: String, ssid5g: String, pass5g: String, con5g: Boolean) {
        btnInicio(btn)
        lifecycleScope.launch {
            val result = LanlyConfigurator(gatewayEquipo.ifBlank { "192.168.1.1" }).applyAllExtended(ip = ip, mask = mask.ifBlank { "255.255.255.0" }, gw = gw, vlan = vlan, dns1 = dns1.ifBlank { "8.8.8.8" }, dns2 = dns2.ifBlank { "8.8.4.4" }, ssid24 = ssid24, pass24 = pass24, ssid5g = ssid5g.ifBlank { ssid24 }, pass5g = pass5g.ifBlank { pass24 }, wifi5g = con5g) { step, total, desc, ok -> actualizarProgreso(btn, step, total, desc, ok) }
            withContext(Dispatchers.Main) { val data = if (result is ZteResult.Success) result.data else emptyMap(); mostrarResultado(btn, result is ZteResult.Success, if (result is ZteResult.Error) result.message else "", ip, mask.ifBlank { "255.255.255.0" }, gw, dns1, dns2, ssid24, if (con5g) ssid5g else "", pass24, pass5g, con5g, data["rx"] ?: "", data["tx"] ?: "", data["sn"] ?: data["gponsn"] ?: "") }
        }
    }
    private fun configurarZte(btn: MaterialButton, ip: String, mask: String, gw: String, dns1: String, dns2: String, vlan: String, ssid24: String, pass24: String, ssid5g: String, pass5g: String, con5g: Boolean) {
        btnInicio(btn)
        val zte = ZteConfigurator(gatewayEquipo.ifBlank { "192.168.1.1" })
        lifecycleScope.launch {
            val login = zte.login("admin", "Web@0063")
            if (login is ZteResult.Error) { withContext(Dispatchers.Main) { liberarWifiLock(); Toast.makeText(this@ConfigOnuActivity, "❌ Login ZTE: ${login.message}", Toast.LENGTH_LONG).show(); btn.text = "Configurar equipo"; btn.isEnabled = true }; return@launch }
            val result = zte.applyAll(ip = ip, mask = mask.ifBlank { "255.255.255.0" }, gw = gw, dns1 = dns1.ifBlank { "8.8.8.8" }, dns2 = dns2.ifBlank { "8.8.4.4" }, vlan = vlan.ifBlank { "100" }, lanIp = resultLanIp.ifBlank { "192.168.1.1" }, dhcpStart = resultDhcpStart.ifBlank { "192.168.1.2" }, dhcpEnd = resultDhcpEnd.ifBlank { "192.168.1.254" }, lanDns1 = dns1.ifBlank { "8.8.8.8" }, lanDns2 = dns2.ifBlank { "8.8.4.4" }, ssid24 = ssid24, pass24 = pass24, wep24 = ZteWifiBand(), ssid5 = ssid5g.ifBlank { ssid24 }, pass5 = pass5g.ifBlank { pass24 }, wep5 = ZteWifiBand()) { step, total, desc, ok -> actualizarProgreso(btn, step, total, desc, ok) }
            withContext(Dispatchers.Main) { val dev = if (result is ZteResult.Success) result.data else ZteDeviceInfo(); mostrarResultado(btn, result is ZteResult.Success, if (result is ZteResult.Error) result.message else "", ip, mask.ifBlank { "255.255.255.0" }, gw, dns1, dns2, ssid24, ssid5g, pass24, pass5g, con5g, dev.rxPower, dev.txPower, dev.serial); if (result is ZteResult.Success) iniciarCuentaRegresivaReboot() }
            if (result is ZteResult.Success) zte.reboot()
        }
    }
    @SuppressLint("SetTextI18n")
    private fun configurarZteF6600P(btn: MaterialButton, ip: String, mask: String, gw: String, dns1: String, dns2: String, vlan: String, ssid24: String, pass24: String, ssid5g: String, pass5g: String, con5g: Boolean) {
        btnInicio(btn)
        val zte = ZteConfigurator(onuIp = gatewayEquipo.ifBlank { "192.168.1.1" }, modelo = ZteModelo.F6600P)
        lifecycleScope.launch {
            val login = zte.login("admin", "Web@0063")
            if (login is ZteResult.Error) { withContext(Dispatchers.Main) { liberarWifiLock(); Toast.makeText(this@ConfigOnuActivity, "❌ Login ZTE F6600P: ${login.message}", Toast.LENGTH_LONG).show(); btn.text = "Configurar equipo"; btn.isEnabled = true }; return@launch }
            val result = zte.applyAll(ip = ip, mask = mask.ifBlank { "255.255.255.0" }, gw = gw, dns1 = dns1.ifBlank { "8.8.8.8" }, dns2 = dns2.ifBlank { "8.8.4.4" }, vlan = vlan.ifBlank { "100" }, lanIp = resultLanIp.ifBlank { "192.168.1.1" }, dhcpStart = resultDhcpStart.ifBlank { "192.168.1.2" }, dhcpEnd = resultDhcpEnd.ifBlank { "192.168.1.254" }, lanDns1 = dns1.ifBlank { "8.8.8.8" }, lanDns2 = dns2.ifBlank { "8.8.4.4" }, ssid24 = ssid24, pass24 = pass24, wep24 = ZteWifiBand(), ssid5 = ssid5g.ifBlank { ssid24 }, pass5 = pass5g.ifBlank { pass24 }, wep5 = ZteWifiBand()) { step, total, desc, ok -> actualizarProgreso(btn, step, total, desc, ok) }
            withContext(Dispatchers.Main) { val dev = if (result is ZteResult.Success) result.data else ZteDeviceInfo(); mostrarResultado(btn, result is ZteResult.Success, if (result is ZteResult.Error) result.message else "", ip, mask.ifBlank { "255.255.255.0" }, gw, dns1, dns2, ssid24, ssid5g, pass24, pass5g, con5g, dev.rxPower, dev.txPower, dev.serial); if (result is ZteResult.Success) iniciarCuentaRegresivaReboot() }
            if (result is ZteResult.Success) zte.reboot()
        }
    }
    private fun configurarBenmundo(btn: MaterialButton, ip: String, mask: String, gw: String, vlan: String, dns1: String, dns2: String, ssid24: String, pass24: String, ssid5g: String, pass5g: String, con5g: Boolean) {
        btnInicio(btn)
        lifecycleScope.launch {
            val result = BenmundoConfigurator(gatewayEquipo.ifBlank { "192.168.101.1" }).applyAll(ip = ip, mask = mask.ifBlank { "255.255.255.0" }, gw = gw, vlan = vlan, dns1 = dns1.ifBlank { "8.8.8.8" }, dns2 = dns2.ifBlank { "8.8.4.4" }, lanIp = resultLanIp.ifBlank { "192.168.101.1" }, dhcpStart = resultDhcpStart.ifBlank { "192.168.101.2" }, dhcpEnd = resultDhcpEnd.ifBlank { "192.168.101.254" }, ssid24 = ssid24, pass24 = pass24, ssid5 = ssid5g.ifBlank { ssid24 }, pass5 = pass5g.ifBlank { pass24 }) { step, total, desc, ok -> actualizarProgreso(btn, step, total, desc, ok) }
            withContext(Dispatchers.Main) { val data = if (result is ZteResult.Success) result.data else emptyMap(); mostrarResultado(btn, result is ZteResult.Success, if (result is ZteResult.Error) result.message else "", ip, mask.ifBlank { "255.255.255.0" }, gw, dns1, dns2, ssid24, if (con5g) ssid5g else "", pass24, pass5g, con5g, data["rx"] ?: "", data["tx"] ?: "", data["gponsn"] ?: data["sn"] ?: "") }
        }
    }
    private fun configurarMct(btn: MaterialButton, ip: String, mask: String, gw: String, vlan: String, dns1: String, dns2: String, ssid24: String, pass24: String, ssid5g: String, pass5g: String, con5g: Boolean) {
        btnInicio(btn)
        val mct = MctConfigurator(gatewayEquipo.ifBlank { "192.168.2.1" })
        lifecycleScope.launch {
            val loginResult = mct.login()
            if (loginResult is ZteResult.Error) { withContext(Dispatchers.Main) { liberarWifiLock(); Toast.makeText(this@ConfigOnuActivity, "❌ Login MCT: ${loginResult.message}", Toast.LENGTH_LONG).show(); btn.text = "Configurar equipo"; btn.isEnabled = true }; return@launch }
            val configResult = mct.readConfig()
            val wanOid: String; val isNew: Boolean
            when (configResult) { is ZteResult.Success -> { wanOid = configResult.data.wanOid; isNew = configResult.data.wanIp.isEmpty() }; is ZteResult.Error -> { wanOid = "MDMOID_WAN_IP_CONN{1-1-1}"; isNew = true } }
            val result = mct.applyAll(ip = ip, mask = mask.ifBlank { "255.255.255.0" }, gw = gw, vlan = vlan, dns1 = dns1.ifBlank { "8.8.8.8" }, dns2 = dns2.ifBlank { "8.8.4.4" }, lanDns1 = dns1.ifBlank { "8.8.8.8" }, lanDns2 = dns2.ifBlank { "8.8.4.4" }, ssid24 = ssid24, pass24 = pass24, ssid5 = ssid5g.ifBlank { ssid24 }, pass5 = pass5g.ifBlank { pass24 }, isNew = isNew, wanOid = wanOid) { step, total, desc, ok -> actualizarProgreso(btn, step, total, desc, ok) }
            withContext(Dispatchers.Main) { val data = if (result is ZteResult.Success) result.data else emptyMap(); mostrarResultado(btn, result is ZteResult.Success, if (result is ZteResult.Error) result.message else "", ip, mask.ifBlank { "255.255.255.0" }, gw, dns1, dns2, ssid24, if (con5g) ssid5g else "", pass24, pass5g, con5g, data["rx"] ?: "", data["tx"] ?: "", data["sn"] ?: "") }
        }
    }
    private fun configurarQualTek(btn: MaterialButton, ip: String, mask: String, gw: String, vlan: String, dns1: String, dns2: String, ssid24: String, pass24: String, ssid5g: String, pass5g: String, con5g: Boolean) {
        btnInicio(btn)
        lifecycleScope.launch {
            val result = QualTekConfigurator(onuIp = gatewayEquipo.ifBlank { "192.168.18.1" }).applyAll(ip = ip, mask = mask.ifBlank { "255.255.255.0" }, gw = gw, vlan = vlan, dns1 = dns1.ifBlank { "8.8.8.8" }, dns2 = dns2.ifBlank { "8.8.4.4" }, lanIp = resultLanIp.ifBlank { "192.168.18.1" }, dhcpStart = resultDhcpStart.ifBlank { "192.168.18.2" }, dhcpEnd = resultDhcpEnd.ifBlank { "192.168.18.254" }, ssid24 = ssid24, pass24 = pass24, ssid5 = ssid5g.ifBlank { ssid24 }, pass5 = pass5g.ifBlank { pass24 }, onProgress = { step, total, desc, ok -> actualizarProgreso(btn, step, total, desc, ok) })
            withContext(Dispatchers.Main) { val data = if (result is ZteResult.Success) result.data else emptyMap(); mostrarResultado(btn, result is ZteResult.Success, if (result is ZteResult.Error) result.message else "", ip, mask.ifBlank { "255.255.255.0" }, gw, dns1, dns2, ssid24, if (con5g) ssid5g else "", pass24, pass5g, con5g, data["rx"] ?: "", data["tx"] ?: "", data["sn"] ?: "") }
        }
    }
    private fun configurarDixon(btn: MaterialButton, ip: String, mask: String, gw: String, vlan: String, dns1: String, dns2: String, ssid24: String, pass24: String, ssid5g: String, pass5g: String, con5g: Boolean, modelo: DixonConfigurator.DixonModelo) {
        btnInicio(btn)
        lifecycleScope.launch {
            val result = DixonConfigurator(onuIp = gatewayEquipo.ifBlank { "192.168.101.1" }, modelo = modelo).applyAll(ip = ip, mask = mask.ifBlank { "255.255.255.0" }, gw = gw, vlan = vlan, dns1 = dns1.ifBlank { "8.8.8.8" }, dns2 = dns2.ifBlank { "8.8.4.4" }, lanIp = resultLanIp.ifBlank { "192.168.101.1" }, dhcpStart = resultDhcpStart.ifBlank { "192.168.101.2" }, dhcpEnd = resultDhcpEnd.ifBlank { "192.168.101.254" }, ssid24 = ssid24, pass24 = pass24, ssid5 = ssid5g.ifBlank { ssid24 }, pass5 = pass5g.ifBlank { pass24 }, onProgress = { step, total, desc, ok -> actualizarProgreso(btn, step, total, desc, ok) })
            withContext(Dispatchers.Main) { val data = if (result is ZteResult.Success) result.data else emptyMap(); val dixon = DixonConfigurator(modelo = modelo); mostrarResultado(btn, result is ZteResult.Success, if (result is ZteResult.Error) result.message else "", ip, mask.ifBlank { "255.255.255.0" }, gw, dns1, dns2, ssid24, if (con5g && !dixon.solo24g) ssid5g else "", pass24, pass5g, con5g && !dixon.solo24g, data["rx"] ?: "", data["tx"] ?: "", data["sn"] ?: "") }
        }
    }

    // ═══════════════════════════════════════════════════════
    //  REBOOT COUNTDOWN
    // ═══════════════════════════════════════════════════════
    @SuppressLint("SetTextI18n")
    private fun iniciarCuentaRegresivaReboot() {
        ovBtnCerrar.isClickable = false; ovBtnCerrar.alpha = 0.3f
        ovBtnNuevaConfig.isEnabled = false
        ovBtnNuevaConfig.text = "⏳ ONU reiniciando... ${REBOOT_SECS}s"
        ovBtnNuevaConfig.alpha = 0.7f
        val subtituloOriginal = ovSubtitulo.text.toString()
        ovSubtitulo.text = "🔄 Reiniciando ONU... espera"
        var secsLeft = REBOOT_SECS
        rebootHandler = Handler(Looper.getMainLooper())
        rebootRunnable = object : Runnable {
            override fun run() {
                secsLeft--
                if (secsLeft > 0) { ovBtnNuevaConfig.text = "⏳ ONU reiniciando... ${secsLeft}s"; rebootHandler?.postDelayed(this, 1000) }
                else { ovBtnCerrar.isClickable = true; ovBtnCerrar.alpha = 1f; ovBtnNuevaConfig.isEnabled = true; ovBtnNuevaConfig.alpha = 1f; ovBtnNuevaConfig.text = "COMPARTIR"; ovSubtitulo.text = "✅ ONU lista · $subtituloOriginal"; Toast.makeText(this@ConfigOnuActivity, "✅ ONU reiniciada correctamente", Toast.LENGTH_SHORT).show() }
            }
        }
        rebootHandler?.postDelayed(rebootRunnable!!, 1000)
    }

    // ═══════════════════════════════════════════════════════
    //  OVERLAY
    // ═══════════════════════════════════════════════════════
    private fun bindOverlayViews() {
        overlayConfiguracion = findViewById(R.id.overlay_configuracion)
        overlayPanel = findViewById(R.id.overlay_panel)
        ovSubtitulo = findViewById(R.id.ov_subtitulo)
        ovBtnCerrar = findViewById(R.id.ov_btn_cerrar)
        ovBtnNuevaConfig = findViewById(R.id.ov_btn_nueva_config)
        ovWanIp = findViewById(R.id.ov_wan_ip); ovWanGw = findViewById(R.id.ov_wan_gw)
        ovWanMask = findViewById(R.id.ov_wan_mask); ovDns = findViewById(R.id.ov_dns)
        ovSsid24 = findViewById(R.id.ov_ssid_24); ovSsid5g = findViewById(R.id.ov_ssid_5g)
        ovSsid5gRow = findViewById(R.id.ov_ssid5_row)
        ovWifiPass = findViewById(R.id.ov_wifi_pass); ovWifiPass5g = findViewById(R.id.ov_wifi_pass_5g)
        ovRx = findViewById(R.id.ov_rx); ovRxBar = findViewById(R.id.ov_rx_bar)
        ovTx = findViewById(R.id.ov_tx); ovTxBar = findViewById(R.id.ov_tx_bar)
        ovPonNaRow = findViewById(R.id.ov_pon_na_row); ovPonNaTxt = findViewById(R.id.ov_pon_na_txt)
        ovSn = findViewById(R.id.ov_sn)
    }

    private fun setupOverlay() {
        val ovBtnContinuar = findViewById<MaterialButton>(R.id.ov_btn_continuar)
        ovBtnCerrar.setOnClickListener { cerrarOverlay() }
        ovBtnNuevaConfig.setOnClickListener { compartirOverlay() }
        // "Continuar" en el overlay = mostrar botón "Listo"
        ovBtnContinuar?.setOnClickListener {
            cerrarOverlay()
            findViewById<MaterialButton>(R.id.btnListo)?.visibility = View.VISIBLE
        }
        overlayConfiguracion.setOnClickListener { }
        overlayPanel.setOnClickListener { }
    }

    @SuppressLint("SetTextI18n")
    private fun mostrarOverlay(ip: String, mask: String, gw: String, dns1: String, dns2: String, ssid24: String, ssid5g: String, pass: String, pass5g: String, con5g: Boolean, rx: String, tx: String, sn: String) {
        ovWanIp.text = ip.ifBlank { "—" }; ovWanGw.text = gw.ifBlank { "—" }; ovWanMask.text = mask.ifBlank { "—" }
        ovDns.text = buildString { append(dns1.ifBlank { "—" }); if (dns2.isNotBlank()) append("\n$dns2") }
        ovSsid24.text = ssid24.ifBlank { "—" }; ovWifiPass.text = pass.ifBlank { "—" }
        if (con5g && ssid5g.isNotBlank()) { ovSsid5g.text = ssid5g; ovSsid5gRow.visibility = View.VISIBLE; ovWifiPass5g.text = pass5g.ifBlank { pass } } else { ovSsid5gRow.visibility = View.GONE }
        ovRx.text = if (rx.isNotBlank()) "$rx dBm" else "—"
        ovTx.text = if (tx.isNotBlank()) "$tx dBm" else "—"
        if (sn.isNotBlank()) { ovSn.text = sn; ovSn.visibility = View.VISIBLE } else { ovSn.visibility = View.GONE }
        ovSubtitulo.text = "$equipoActivo · $ip"
        val rxVal = rx.toDoubleOrNull(); val txVal = tx.toDoubleOrNull()
        if (rxVal == null && txVal == null) { ovPonNaRow.visibility = View.VISIBLE; ovPonNaTxt.text = "Señal óptica no disponible" } else { ovPonNaRow.visibility = View.GONE }
        overlayPanel.translationY = 80f; overlayPanel.scaleX = 0.95f; overlayPanel.scaleY = 0.95f
        overlayConfiguracion.alpha = 0f; overlayConfiguracion.visibility = View.VISIBLE
        overlayConfiguracion.animate().alpha(1f).setDuration(250).setInterpolator(DecelerateInterpolator()).start()
        overlayPanel.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun cerrarOverlay() {
        overlayConfiguracion.animate().alpha(0f).setDuration(200).withEndAction { overlayConfiguracion.visibility = View.GONE }.start()
        overlayPanel.animate().translationY(40f).scaleX(0.96f).scaleY(0.96f).setDuration(200).start()
    }

    private fun compartirOverlay() {
        ovBtnCerrar.visibility = View.INVISIBLE; ovBtnNuevaConfig.visibility = View.INVISIBLE
        overlayPanel.post {
            try {
                overlayPanel.measure(View.MeasureSpec.makeMeasureSpec(overlayPanel.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                val bitmap = Bitmap.createBitmap(overlayPanel.measuredWidth, overlayPanel.measuredHeight, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.parseColor("#08111E"))
                overlayPanel.layout(0, 0, overlayPanel.measuredWidth, overlayPanel.measuredHeight)
                overlayPanel.draw(canvas)
                ovBtnCerrar.visibility = View.VISIBLE; ovBtnNuevaConfig.visibility = View.VISIBLE
                val file = java.io.File(java.io.File(cacheDir, "images").also { it.mkdirs() }, "config_${System.currentTimeMillis()}.png")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.provider", file)
                startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "image/png"; putExtra(android.content.Intent.EXTRA_STREAM, uri); putExtra(android.content.Intent.EXTRA_TEXT, "Config ONT — $equipoActivo · ${ovWanIp.text}"); addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Compartir configuración"))
            } catch (e: Exception) { ovBtnCerrar.visibility = View.VISIBLE; ovBtnNuevaConfig.visibility = View.VISIBLE; Toast.makeText(this, "Error al compartir: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
    }

    // ═══════════════════════════════════════════════════════
    //  UTILIDADES
    // ═══════════════════════════════════════════════════════
    @SuppressLint("SetTextI18n")
    private fun obtenerInfoRed() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 99); return
        }
        try {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val dhcp = wifiManager.dhcpInfo
            if (dhcp.ipAddress == 0) { findViewById<TextView>(R.id.txt_sel_ip_red)?.text = "Sin WiFi"; findViewById<TextView>(R.id.txt_sel_gateway)?.text = "--"; return }
            gatewayEquipo = Formatter.formatIpAddress(dhcp.gateway)
            findViewById<TextView>(R.id.txt_sel_ip_red)?.text  = Formatter.formatIpAddress(dhcp.ipAddress)
            findViewById<TextView>(R.id.txt_sel_gateway)?.text = gatewayEquipo
        } catch (_: Exception) { }
    }

    private fun adquirirWifiLock(tag: String) {
        if (wifiLock?.isHeld == true) return
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "enetfiber:$tag").apply { acquire() }
        } catch (_: Exception) { }
    }

    private fun liberarWifiLock() {
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) { }
        wifiLock = null
    }

    private fun setupColapsables() {
        val toggleConfig = findViewById<LinearLayout>(R.id.btn_toggle_config)
        val layoutConfig = findViewById<LinearLayout>(R.id.layout_config)
        val arrowConfig  = findViewById<ImageView>(R.id.img_arrow)
        val toggleWifi   = findViewById<LinearLayout>(R.id.btn_toggle_wifi)
        val layoutWifi   = findViewById<LinearLayout>(R.id.layout_wifi)
        val arrowWifi    = findViewById<ImageView>(R.id.img_arrow_wifi)
        toggleConfig?.setOnClickListener { toggleSeccion(layoutConfig, arrowConfig) }
        toggleWifi?.setOnClickListener   { toggleSeccion(layoutWifi, arrowWifi) }
    }

    private fun toggleSeccion(layout: LinearLayout?, arrow: ImageView?) {
        layout ?: return
        if (layout.visibility == View.VISIBLE) {
            layout.animate().alpha(0f).setDuration(150).withEndAction { layout.visibility = View.GONE }.start()
            arrow?.animate()?.rotation(0f)?.setDuration(200)?.start()
        } else {
            layout.visibility = View.VISIBLE; layout.alpha = 0f
            layout.animate().alpha(1f).setDuration(200).start()
            arrow?.animate()?.rotation(180f)?.setDuration(200)?.start()
        }
    }

    private fun setupEyeToggle(eyeBtnId: Int, editTextId: Int) {
        val btn  = findViewById<TextView>(eyeBtnId)  ?: return
        val edit = findViewById<EditText>(editTextId) ?: return
        var visible = false
        btn.setOnClickListener {
            visible = !visible
            val cursor = edit.selectionEnd
            edit.inputType = if (visible) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            edit.setSelection(cursor.coerceAtMost(edit.text.length))
            btn.alpha = if (visible) 1f else 0.4f
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 99 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) obtenerInfoRed()
    }

    override fun onDestroy() {
        super.onDestroy()
        liberarWifiLock()
        rebootRunnable?.let { rebootHandler?.removeCallbacks(it) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            overlayConfiguracion.visibility == View.VISIBLE -> { /* overlay bloquea back */ }
            pasoActual > 1 -> mostrarPaso(1)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }
}