package com.enetfiber.tecnico.ui.instalacion

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.enetfiber.tecnico.R
import com.enetfiber.tecnico.TipoOrden
import com.enetfiber.tecnico.data.remote.ConfigOnuRequest
import com.enetfiber.tecnico.databinding.ActivityInstalacionBinding
import com.enetfiber.tecnico.equipos.BenmundoConfigurator
import com.enetfiber.tecnico.equipos.DixonConfigurator
import com.enetfiber.tecnico.equipos.LanlyConfigurator
import com.enetfiber.tecnico.equipos.MctConfigurator
import com.enetfiber.tecnico.equipos.OpticConfigurator
import com.enetfiber.tecnico.equipos.ZteConfigurator
import com.enetfiber.tecnico.equipos.ZteDeviceInfo
import com.enetfiber.tecnico.equipos.ZteResult
import com.enetfiber.tecnico.equipos.ZteWifiBand
import com.enetfiber.tecnico.ui.InstalacionState
import com.enetfiber.tecnico.ui.InstalacionViewModel
import com.enetfiber.tecnico.ui.main.MainActivity
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.enetfiber.tecnico.equipos.QualTekConfigurator
import com.enetfiber.tecnico.equipos.ZteModelo
import android.net.wifi.WifiManager
import android.os.Build
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import com.enetfiber.tecnico.ui.ubicacion.UbicacionActivity
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import javax.inject.Inject
import com.enetfiber.tecnico.data.Repository
import com.enetfiber.tecnico.data.remote.ConsumoItemRequest
import com.enetfiber.tecnico.ui.InventarioViewModel
import com.google.android.material.textfield.TextInputEditText as TIEditText
import com.enetfiber.tecnico.data.remote.RetiroItemRequest
import android.app.AlertDialog

@AndroidEntryPoint
class InstalacionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInstalacionBinding
    private val vm: InstalacionViewModel by viewModels()

    private val instalacionId by lazy { intent.getStringExtra("instalacion_id") ?: "" }
    private val ordenId       by lazy { intent.getStringExtra("orden_id") ?: "" }

    private var pasoActual     = 1
    private var tipoFotoActual = ""
    private var archivoFoto: File? = null

    private var equipoActivo  = ""
    private var gatewayEquipo = ""
    private var isUpdating    = false

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

    // ── WifiLock ──────────────────────────────────────────────
    private var wifiLock: WifiManager.WifiLock? = null

    // ── Reboot countdown ──────────────────────────────────────
    private val REBOOT_SECS = 5
    private var rebootHandler: Handler? = null
    private var rebootRunnable: Runnable? = null

    // ── Views overlay ─────────────────────────────────────────
    private lateinit var overlayConfiguracion : FrameLayout
    private lateinit var overlayPanel         : LinearLayout
    private lateinit var ovSubtitulo          : TextView
    private lateinit var ovBtnCerrar          : TextView
    private lateinit var ovBtnNuevaConfig     : MaterialButton
    private lateinit var ovWanIp              : TextView
    private lateinit var ovWanGw              : TextView
    private lateinit var ovWanMask            : TextView
    private lateinit var ovDns                : TextView
    private lateinit var ovSsid24             : TextView
    private lateinit var ovSsid5g             : TextView
    private lateinit var ovSsid5gRow          : LinearLayout
    private lateinit var ovWifiPass           : TextView
    private lateinit var ovWifiPass5g         : TextView
    private lateinit var ovRx                 : TextView
    private lateinit var ovRxBar              : View
    private lateinit var ovTx                 : TextView
    private lateinit var ovTxBar              : View
    private lateinit var ovPonNaRow           : LinearLayout
    private lateinit var ovPonNaTxt           : TextView
    private lateinit var ovSn                 : TextView

    // ── Views config paso 3 ───────────────────────────────────
    private lateinit var Txt_ip      : EditText
    private lateinit var Txt_mascara : EditText
    private lateinit var Txt_gateway : EditText
    private lateinit var Txt_dns1    : EditText
    private lateinit var Txt_dns2    : EditText
    private lateinit var Txt_vlan    : EditText
    private lateinit var Txt_ssid_24 : EditText
    private lateinit var Txt_pass_24 : EditText
    private lateinit var Txt_ssid_5g : EditText
    private lateinit var Txt_pass_5g : EditText

    // ── Botón continuar paso 4 ────────────────────────────────
    private lateinit var btnContinuarPaso4 : MaterialButton

    // ── GPS / Ubicación ──────────────────────────────────────────
    @Inject lateinit var repo: Repository
    private var latContrato: Double? = null
    private var lngContrato: Double? = null
    private var mapaPreviewMarker: org.osmdroid.views.overlay.Marker? = null

    private val ubicacionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val lat = result.data?.getDoubleExtra(UbicacionActivity.RESULT_LAT, 0.0) ?: 0.0
            val lng = result.data?.getDoubleExtra(UbicacionActivity.RESULT_LNG, 0.0) ?: 0.0
            if (lat != 0.0 && lng != 0.0) {
                latContrato = lat
                lngContrato = lng
                actualizarCardUbicacion()
            }
        }
    }

    // ── Cards equipos paso 2 ──────────────────────────────────
    private lateinit var cardOptic       : CardView
    private lateinit var cardZte         : CardView
    private lateinit var cardZteF6600p   : CardView
    private lateinit var cardMct         : CardView
    private lateinit var cardLanly       : CardView
    private lateinit var cardBenmundo    : CardView
    private lateinit var cardQualtek     : CardView
    private lateinit var cardDixon130    : CardView
    private lateinit var cardDixonAX     : CardView
    private lateinit var cardDixon110    : CardView
    private lateinit var cardDesconocido : CardView

    private val todasLasCards get() = listOf(
        cardOptic, cardLanly, cardZte, cardBenmundo, cardMct,
        cardDixon130, cardDixonAX, cardDixon110,
        cardQualtek, cardZteF6600p,
        cardDesconocido
    )
    // ── Launchers ─────────────────────────────────────────────
    private val camaraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val archivo = archivoFoto ?: return@registerForActivityResult
        if (archivo.exists() && archivo.length() > 0) {
            val nombre = archivo.name
            val tamano = "%.1f MB".format(archivo.length() / 1_000_000f)
            val tipo   = "FOTO_${(vm.cantidadFotos() + 1)}"
            vm.agregarFoto(tipo, archivo.absolutePath, nombre, tamano, "CAMARA")
            if (vm.isOnline) vm.subirFotoInmediata(tipo, archivo.absolutePath)
            actualizarListaFotos()
        } else {
            Toast.makeText(this, "No se pudo guardar la foto", Toast.LENGTH_SHORT).show()
        }
    }

    private val galeriaLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@registerForActivityResult
        copiarUriAArchivo(uri)?.let { archivo ->
            val nombre = archivo.name
            val tamano = "%.1f MB".format(archivo.length() / 1_000_000f)
            val tipo   = "FOTO_${(vm.cantidadFotos() + 1)}"
            vm.agregarFoto(tipo, archivo.absolutePath, nombre, tamano, "GALERIA")
            if (vm.isOnline) vm.subirFotoInmediata(tipo, archivo.absolutePath)
            actualizarListaFotos()
        }
    }

    private val permisoCamara = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        if (ok) tomarFoto()
        else Toast.makeText(this, "Se necesita permiso de cámara", Toast.LENGTH_SHORT).show()
    }

    private fun esInternet(): Boolean {
        val orden = vm.orden.value ?: return false
        if (orden.tipoOrden == TipoOrden.RETIRO_EQUIPO_I ||
            orden.tipoOrden == TipoOrden.RETIRO_EQUIPO_C) return false
        return TipoOrden.esInternet(orden.tipoOrden)
    }

    // ═════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInstalacionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        btnContinuarPaso4 = findViewById(R.id.btnContinuarPaso4)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        vm.instalacionId = instalacionId
        vm.cargarOrden(ordenId)

        bindOverlayViews()
        bindConfigViews()
        bindEquipoCards()

        setupOverlay()
        setupEyeToggle(R.id.btn_eye_24, R.id.Txt_pass_24)
        setupEyeToggle(R.id.btn_eye_5g, R.id.Txt_pass_5g)
        setupColapsables()
        setupBotonConfigurar()
        setupBotonCargarConfig()
        botonesGenerales()
        observar()
        inicializarOsmdroid()
        setupCardUbicacion()
        setupCardPrecinto()
    }

    // ═════════════════════════════════════════════════════════
    //  PASOS
    // ═════════════════════════════════════════════════════════
    private fun mostrarPaso(paso: Int) {
        pasoActual = paso
        binding.layoutPaso1.visibility = if (paso == 1) View.VISIBLE else View.GONE
        binding.layoutPaso2.visibility = if (paso == 2) View.VISIBLE else View.GONE
        binding.layoutPaso3.visibility = if (paso == 3) View.VISIBLE else View.GONE
        binding.layoutPaso4.visibility = if (paso == 4) View.VISIBLE else View.GONE

        binding.layoutPaso2Indicador.visibility = if (esInternet()) View.VISIBLE else View.GONE
        binding.layoutPaso3Indicador.visibility = if (esInternet()) View.VISIBLE else View.GONE

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
        if (paso >= 3) activo(binding.paso3Circulo) else inactivo(binding.paso3Circulo)
        if (paso >= 4) activo(binding.paso4Circulo) else inactivo(binding.paso4Circulo)

        supportActionBar?.title = when (paso) {
            1    -> "Paso 1 — Fotos"
            2    -> "Paso 2 — Seleccionar equipo"
            3    -> "Paso 3 — Configurar equipo"
            else -> "Paso 4 — Completar"
        }

        if (paso == 2) obtenerInfoRed()
    }

    // ═════════════════════════════════════════════════════════
    //  BOTONES GENERALES
    // ═════════════════════════════════════════════════════════
    // ═════════════════════════════════════════════════════════
    //  UBICACIÓN GPS — PASO 1
    // ═════════════════════════════════════════════════════════
    private fun inicializarOsmdroid() {
        Configuration.getInstance().userAgentValue = packageName
    }

    private fun setupCardUbicacion() {
        // Botón capturar (sin GPS)
        val btnCapturar = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCapturarUbicacion)
        // Botón editar pin (con GPS)
        val btnEditar   = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEditarUbicacion)
        // Botón navegar
        val btnNavegar  = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnVerEnMapa)

        btnCapturar?.setOnClickListener { abrirEditorUbicacion() }
        btnEditar?.setOnClickListener   { abrirEditorUbicacion() }

        btnNavegar?.setOnClickListener {
            val orden = vm.orden.value ?: return@setOnClickListener
            val uri = if (latContrato != null && lngContrato != null) {
                Uri.parse("geo:${'$'}latContrato,${'$'}lngContrato?q=${'$'}latContrato,${'$'}lngContrato(${'$'}{orden.abonado})")
            } else {
                Uri.parse("geo:0,0?q=${'$'}{Uri.encode(orden.direccion)}")
            }
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (_: Exception) {
                Toast.makeText(this, "No se encontró app de mapas", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun abrirEditorUbicacion() {
        val orden = vm.orden.value ?: return
        ubicacionLauncher.launch(
            Intent(this, UbicacionActivity::class.java).apply {
                putExtra(UbicacionActivity.EXTRA_CONTRATO, orden.contrato ?: "")
                putExtra(UbicacionActivity.EXTRA_DIRECCION, orden.direccion)
                putExtra(UbicacionActivity.EXTRA_SECTOR,   orden.sector ?: "")
                latContrato?.let { putExtra(UbicacionActivity.EXTRA_LAT, it) }
                lngContrato?.let { putExtra(UbicacionActivity.EXTRA_LNG, it) }
            }
        )
    }

    private fun actualizarCardUbicacion() {
        val lat = latContrato ?: return
        val lng = lngContrato ?: return
        val orden = vm.orden.value ?: return

        val badge      = findViewById<android.widget.TextView>(R.id.tvUbicacionBadge)
        val mapPreview = findViewById<org.osmdroid.views.MapView>(R.id.mapPreview)
        val tvDir      = findViewById<android.widget.TextView>(R.id.tvUbicacionDir)
        val layoutBtns = findViewById<android.widget.LinearLayout>(R.id.layoutBotonesGps)
        val btnCapturar = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCapturarUbicacion)

        // Badge verde
        badge?.text = "✓ GPS"
        badge?.setTextColor(android.graphics.Color.parseColor("#16A34A"))
        badge?.setBackgroundColor(android.graphics.Color.parseColor("#DCFCE7"))

        // Dirección
        tvDir?.text       = orden.direccion
        tvDir?.visibility = android.view.View.VISIBLE

        // Botones: mostrar editar/navegar, ocultar capturar
        layoutBtns?.visibility  = android.view.View.VISIBLE
        btnCapturar?.visibility = android.view.View.GONE

        // Mapa pequeño
        mapPreview?.let { map ->
            map.visibility = android.view.View.VISIBLE
            map.setTileSource(TileSourceFactory.MAPNIK)
            map.setMultiTouchControls(false)
            map.isClickable = false
            map.controller.setZoom(16.0)
            val punto = GeoPoint(lat, lng)
            map.controller.setCenter(punto)

            if (mapaPreviewMarker == null) {
                mapaPreviewMarker = Marker(map).apply {
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    isDraggable = false
                }
                map.overlays.add(mapaPreviewMarker)
            }
            mapaPreviewMarker?.position = punto
            map.invalidate()

            // Tap en el mapa pequeño → abrir editor
            map.setOnClickListener { abrirEditorUbicacion() }
        }
    }

    private fun cargarUbicacionDesdeOrden() {
        val orden = vm.orden.value ?: return
        // contratoRef viene del backend con la ubicación guardada
        val lat = orden.contratoRef?.latitud
        val lng = orden.contratoRef?.longitud
        if (lat != null && lng != null) {
            latContrato = lat
            lngContrato = lng
            actualizarCardUbicacion()
        } else {
            // Sin GPS: mostrar botón capturar
            val btnCapturar = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCapturarUbicacion)
            btnCapturar?.visibility = android.view.View.VISIBLE
        }
    }

    // ── Precinto ──────────────────────────────────────────────
    private fun setupCardPrecinto() {
        // Pre-cargar el precinto del contrato si existe
        val orden = vm.orden.value
        val precinto = orden?.contratoRef?.precinto
        if (!precinto.isNullOrBlank()) {
            binding.etPrecinto.setText(precinto)
        }
    }

    private fun guardarPrecintoSiCambio() {
        val orden = vm.orden.value ?: return
        val numero = orden.contrato ?: return
        val nuevo = binding.etPrecinto.text.toString().trim()
        val actual = orden.contratoRef?.precinto ?: ""
        if (nuevo == actual) return  // no cambió, no hacer nada

        lifecycleScope.launch {
            repo.actualizarPrecinto(numero, nuevo.ifEmpty { null })
        }
    }

    private fun botonesGenerales() {
        // Cámara
        binding.btnAgregarFotoCamara.setOnClickListener {
            if (!vm.puedeAgregarFoto()) {
                Toast.makeText(this, "Máximo 8 fotos por instalación", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            solicitarFoto()
        }

        // Galería
        binding.btnAgregarFotoGaleria.setOnClickListener {
            if (!vm.puedeAgregarFoto()) {
                Toast.makeText(this, "Máximo 8 fotos por instalación", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            galeriaLauncher.launch("image/*")
        }

        binding.btnSiguienteConfig.setOnClickListener {
            // Quitar el bloque del cantidadFotos == 0
            guardarPrecintoSiCambio()
            if (esInternet()) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("⚠ Atención")
                    .setMessage("Para continuar deberás conectarte al WiFi del equipo ONU.\n\nDurante la configuración perderás la conexión a internet.")
                    .setPositiveButton("Entendido, continuar") { _, _ -> mostrarPaso(2) }
                    .setNegativeButton("Cancelar", null)
                    .show()
            } else {
                guardarPrecintoSiCambio()
                mostrarPaso(4)
            }
        }
        binding.btnCompletar.setOnClickListener { completar() }

        btnContinuarPaso4.setOnClickListener {
            rellenarPaso4()
            mostrarPaso(4)
        }
    }

    // ═════════════════════════════════════════════════════════
    //  PASO 2: SELECCIONAR EQUIPO
    // ═════════════════════════════════════════════════════════
    private fun bindEquipoCards() {
        cardOptic       = findViewById(R.id.card_sel_optic)
        cardZte         = findViewById(R.id.card_sel_zte)
        cardZteF6600p = findViewById(R.id.card_sel_ztef6600p)
        cardLanly       = findViewById(R.id.card_sel_lanly)
        cardMct         = findViewById(R.id.card_sel_mct)
        cardBenmundo    = findViewById(R.id.card_sel_benmundo)
        cardQualtek   = findViewById(R.id.card_sel_qualtek)
        cardDixon130    = findViewById(R.id.card_sel_dixon130)
        cardDixonAX     = findViewById(R.id.card_sel_dixonax)
        cardDixon110    = findViewById(R.id.card_sel_dixon110)
        cardDesconocido = findViewById(R.id.card_sel_desconocido)
        cardOptic.setOnClickListener    { seleccionarEquipo("OPTIC",      cardOptic)    }
        cardLanly.setOnClickListener    { seleccionarEquipo("LANLY",      cardLanly)    }
        cardZte.setOnClickListener      { seleccionarEquipo("ZTE F6201B", cardZte)      }
        cardZteF6600p.setOnClickListener { seleccionarEquipo("ZTE F6600P",   cardZteF6600p) }
        cardBenmundo.setOnClickListener { seleccionarEquipo("BENMUNDO",   cardBenmundo) }
        cardMct.setOnClickListener      { seleccionarEquipo("MCT AX3000", cardMct)      }
        cardQualtek.setOnClickListener  { seleccionarEquipo("QUALTEK",      cardQualtek)   }
        cardDixon130.setOnClickListener { seleccionarEquipo("DIXON D130GW",    cardDixon130) }
        cardDixonAX.setOnClickListener  { seleccionarEquipo("DIXON D580GW-AX", cardDixonAX)  }
        cardDixon110.setOnClickListener { seleccionarEquipo("DIXON D110GWC",   cardDixon110) }
        cardDesconocido.setOnClickListener {
            // Resetear cards normales a blanco
            listOf(cardOptic, cardLanly, cardZte, cardBenmundo, cardMct)
                .forEach { it.setCardBackgroundColor(0xFFFFFFFF.toInt()) }
            // Resaltar desconocido
            cardDesconocido.setCardBackgroundColor(0xFFFEF3C7.toInt())
            equipoActivo = "DESCONOCIDO"
            rellenarPaso4()
            mostrarPaso(4)
        }
    }

    private fun seleccionarEquipo(nombre: String, cardSeleccionada: CardView) {
        equipoActivo = nombre

        // Resalta solo la card elegida, normaliza todas las demás
        todasLasCards.forEach { card ->
            val colorNormal = if (card.id == R.id.card_sel_desconocido) 0xFFFFFBEB.toInt()
            else 0xFFFFFFFF.toInt()
            card.setCardBackgroundColor(
                if (card == cardSeleccionada) 0xFFEEF3FC.toInt() else colorNormal
            )
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
                "OPTIC"      -> cargarConfigOptic(btn)
                "LANLY"      -> cargarConfigLanly(btn)
                "ZTE F6201B" -> cargarConfigZte(btn)
                "ZTE F6600P" -> cargarConfigZteF6600P(btn)
                "BENMUNDO"   -> cargarConfigBenmundo(btn)
                "MCT AX3000" -> cargarConfigMct(btn)
                "QUALTEK"    -> cargarConfigQualTek(btn)
                "DIXON D130GW"    -> cargarConfigDixon(btn, DixonConfigurator.DixonModelo.D130GW)
                "DIXON D580GW-AX" -> cargarConfigDixon(btn, DixonConfigurator.DixonModelo.D580GW_AX)
                "DIXON D110GWC"   -> cargarConfigDixon(btn, DixonConfigurator.DixonModelo.D110GWC)
            }
        }
    }

    // ── Mostrar info LAN ──────────────────────────────────────
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

    // ── Cargar config ─────────────────────────────────────────
    private fun cargarConfigOptic(btn: MaterialButton) {
        lifecycleScope.launch {
            val result = OpticConfigurator(gatewayEquipo).readConfig()
            withContext(Dispatchers.Main) {
                liberarWifiLock()
                when (result) {
                    is ZteResult.Success -> {
                        val c = result.data
                        resultLanIp = c.lanIp; resultDhcpStart = c.dhcpStart
                        resultDhcpEnd = c.dhcpEnd; resultLanDns = "${c.dns1} / ${c.dns2}"
                        rellenarCamposConfig(c.wanIp, c.wanMask, c.wanGw, c.wanVlan, c.dns1, c.dns2, c.ssid24, c.pass24, c.ssid5, c.pass5)
                        mostrarLanInfo(); irAPaso3()
                    }
                    is ZteResult.Error -> { Toast.makeText(this@InstalacionActivity, "⚠ ${result.message}", Toast.LENGTH_LONG).show(); btn.isEnabled = true; btn.text = "↓  Cargar configuración del equipo" }
                }
            }
        }
    }
    private fun cargarConfigZte(btn: MaterialButton) {
        val zte = ZteConfigurator(gatewayEquipo)
        lifecycleScope.launch {
            val login = zte.login("admin", "Web@0063")
            if (login is ZteResult.Error) {
                withContext(Dispatchers.Main) {
                    liberarWifiLock()
                    Toast.makeText(this@InstalacionActivity, "⚠ Login ZTE: ${login.message}", Toast.LENGTH_LONG).show()
                    btn.isEnabled = true
                    btn.text = "↓  Cargar configuración del equipo"
                }
                return@launch
            }
            val result = zte.readConfig()
            withContext(Dispatchers.Main) {
                liberarWifiLock()
                when (result) {
                    is ZteResult.Success -> {
                        val c = result.data
                        resultLanIp = c.lan.ip; resultDhcpStart = c.lan.dhcpStart
                        resultDhcpEnd = c.lan.dhcpEnd; resultLanDns = "${c.lan.dns1} / ${c.lan.dns2}"
                        rellenarCamposConfig(c.wan.ip, c.wan.mask, c.wan.gw, c.wan.vlan.ifBlank { "100" }, c.wan.dns1, c.wan.dns2, c.wifi24.ssid, c.wifi24.pass, c.wifi5.ssid, c.wifi5.pass)
                        mostrarLanInfo(); irAPaso3()
                    }
                    is ZteResult.Error -> { Toast.makeText(this@InstalacionActivity, "⚠ ${result.message}", Toast.LENGTH_LONG).show(); btn.isEnabled = true; btn.text = "↓  Cargar configuración del equipo" }
                }
            }
        }
    }
    // ── ZTE F6600P: leer config ───────────────────────────────────────────
    private fun cargarConfigZteF6600P(btn: MaterialButton) {
        val zte = ZteConfigurator(
            onuIp  = gatewayEquipo,
            modelo = ZteModelo.F6600P
        )
        lifecycleScope.launch {
            val login = zte.login("admin", "Web@0063")
            if (login is ZteResult.Error) {
                withContext(Dispatchers.Main) {
                    liberarWifiLock()
                    Toast.makeText(this@InstalacionActivity, "⚠ Login ZTE F6600P: ${login.message}", Toast.LENGTH_LONG).show()
                    btn.isEnabled = true
                    btn.text = "↓  Cargar configuración del equipo"
                }
                return@launch
            }
            val result = zte.readConfig()
            withContext(Dispatchers.Main) {
                liberarWifiLock()
                when (result) {
                    is ZteResult.Success -> {
                        val c = result.data
                        resultLanIp     = c.lan.ip
                        resultDhcpStart = c.lan.dhcpStart
                        resultDhcpEnd   = c.lan.dhcpEnd
                        resultLanDns    = "${c.lan.dns1} / ${c.lan.dns2}"
                        rellenarCamposConfig(
                            c.wan.ip, c.wan.mask, c.wan.gw,
                            c.wan.vlan.ifBlank { "100" },
                            c.wan.dns1, c.wan.dns2,
                            c.wifi24.ssid, c.wifi24.pass,
                            c.wifi5.ssid, c.wifi5.pass
                        )
                        mostrarLanInfo()
                        irAPaso3()
                    }
                    is ZteResult.Error -> {
                        Toast.makeText(this@InstalacionActivity, "⚠ ${result.message}", Toast.LENGTH_LONG).show()
                        btn.isEnabled = true
                        btn.text = "↓  Cargar configuración del equipo"
                    }
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
                    is ZteResult.Success -> {
                        val c = result.data
                        resultLanIp = c.lanIp; resultDhcpStart = c.dhcpStart
                        resultDhcpEnd = c.dhcpEnd; resultLanDns = "${c.dns1} / ${c.dns2}"
                        rellenarCamposConfig(c.wanIp, c.wanMask, c.wanGw, c.wanVlan, c.dns1, c.dns2, c.ssid24, c.pass24, c.ssid5, c.pass5)
                        mostrarLanInfo(); irAPaso3()
                    }
                    is ZteResult.Error -> { Toast.makeText(this@InstalacionActivity, "⚠ ${result.message}", Toast.LENGTH_LONG).show(); btn.isEnabled = true; btn.text = "↓  Cargar configuración del equipo" }
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
                    is ZteResult.Success -> {
                        val c = result.data
                        resultLanIp = c.lanIp; resultDhcpStart = c.dhcpStart
                        resultDhcpEnd = c.dhcpEnd; resultLanDns = "${c.dns1} / ${c.dns2}"
                        rellenarCamposConfig(c.wanIp, c.wanMask, c.wanGw, c.wanVlan, c.dns1, c.dns2, c.ssid24, c.pass24, c.ssid5, c.pass5)
                        mostrarLanInfo(); irAPaso3()
                    }
                    is ZteResult.Error -> { Toast.makeText(this@InstalacionActivity, "⚠ ${result.message}", Toast.LENGTH_LONG).show(); btn.isEnabled = true; btn.text = "↓  Cargar configuración del equipo" }
                }
            }
        }
    }
    private fun cargarConfigMct(btn: MaterialButton) {
        val mct = MctConfigurator(gatewayEquipo)
        lifecycleScope.launch {
            // Login primero para MCT
            val loginResult = mct.login()
            if (loginResult is ZteResult.Error) {
                withContext(Dispatchers.Main) {
                    liberarWifiLock()
                    Toast.makeText(this@InstalacionActivity, "⚠ Login MCT: ${loginResult.message}", Toast.LENGTH_LONG).show()
                    btn.isEnabled = true
                    btn.text = "↓  Cargar configuración del equipo"
                }
                return@launch
            }
            val result = mct.readConfig()
            withContext(Dispatchers.Main) {
                liberarWifiLock()
                when (result) {
                    is ZteResult.Success -> {
                        val c = result.data
                        resultLanIp = c.lanIp; resultDhcpStart = c.dhcpStart
                        resultDhcpEnd = c.dhcpEnd; resultLanDns = "${c.lanDns1} / ${c.lanDns2}"
                        rellenarCamposConfig(c.wanIp, c.wanMask, c.wanGw, c.wanVlan, c.wanDns1, c.wanDns2, c.ssid24, c.pass24, c.ssid5, c.pass5)
                        mostrarLanInfo(); irAPaso3()
                    }
                    is ZteResult.Error -> { Toast.makeText(this@InstalacionActivity, "⚠ ${result.message}", Toast.LENGTH_LONG).show(); btn.isEnabled = true; btn.text = "↓  Cargar configuración del equipo" }
                }
            }
        }
    }
    // ── QualTek: leer config ──────────────────────────────────────────────
    private fun cargarConfigQualTek(btn: MaterialButton) {
        lifecycleScope.launch {
            val result = QualTekConfigurator(gatewayEquipo).readConfig()
            withContext(Dispatchers.Main) {
                liberarWifiLock()
                when (result) {
                    is ZteResult.Success -> {
                        val c = result.data
                        resultLanIp     = c.lanIp
                        resultDhcpStart = c.dhcpStart
                        resultDhcpEnd   = c.dhcpEnd
                        resultLanDns    = "${c.dns1} / ${c.dns2}"
                        rellenarCamposConfig(
                            c.wanIp, c.wanMask, c.wanGw, c.wanVlan,
                            c.dns1, c.dns2,
                            c.ssid24, c.pass24, c.ssid5, c.pass5
                        )
                        mostrarLanInfo()
                        irAPaso3()
                    }
                    is ZteResult.Error -> {
                        Toast.makeText(this@InstalacionActivity, "⚠ ${result.message}", Toast.LENGTH_LONG).show()
                        btn.isEnabled = true
                        btn.text = "↓  Cargar configuración del equipo"
                    }
                }
            }
        }
    }

    private fun irAPaso3() {
        val orden = vm.orden.value
        if (!orden?.ipWan.isNullOrBlank()) {
            isUpdating = true
            Txt_ip.setText(orden?.ipWan ?: "")
            Txt_mascara.setText(orden?.mascara ?: "255.255.255.0")
            Txt_gateway.setText(orden?.gateway ?: "")
            isUpdating = false
        }
        // Solo bloquear IP, máscara y gateway — el NOC los asigna, no se tocan
        listOf(Txt_ip, Txt_mascara, Txt_gateway).forEach {
            it.isEnabled = false
            it.isFocusable = false
            it.isFocusableInTouchMode = false
            it.alpha = 0.55f
        }

// DNS: habilitados y editables por el técnico
        listOf(Txt_dns1, Txt_dns2).forEach {
            it.isEnabled = true
            it.isFocusable = true
            it.isFocusableInTouchMode = true
            it.alpha = 1f
        }
        findViewById<TextView>(R.id.txt_equipo_header)?.text = equipoActivo
        findViewById<TextView>(R.id.txt_ip_red)?.text = gatewayEquipo
        val layoutConfig = findViewById<LinearLayout>(R.id.layout_config)
        val arrowConfig  = findViewById<ImageView>(R.id.img_arrow)
        if (layoutConfig?.visibility != View.VISIBLE) toggleSeccion(layoutConfig, arrowConfig)
        val layoutWifi = findViewById<LinearLayout>(R.id.layout_wifi)
        val arrowWifi  = findViewById<ImageView>(R.id.img_arrow_wifi)
        if (layoutWifi?.visibility != View.VISIBLE) toggleSeccion(layoutWifi, arrowWifi)
        btnContinuarPaso4.visibility = View.GONE
        mostrarPaso(3)
    }

    // ═════════════════════════════════════════════════════════
    //  PASO 3: CONFIGURAR
    // ═════════════════════════════════════════════════════════
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
    private fun setupBotonConfigurar() {
        val btn = binding.btnConfigurarEquipo
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
                "OPTIC"      -> configurarOptic(btn, ip, mask, gw, vlan, dns1, dns2, ssid24, pass24, ssid5g, pass5g, con5g)
                "LANLY"      -> configurarLanly(btn, ip, mask, gw, vlan, dns1, dns2, ssid24, pass24, ssid5g, pass5g, con5g)
                "ZTE F6201B" -> configurarZte(btn, ip, mask, gw, dns1, dns2, vlan, ssid24, pass24, ssid5g, pass5g, con5g)
                "ZTE F6600P" -> configurarZteF6600P(btn, ip, mask, gw, dns1, dns2, vlan, ssid24, pass24, ssid5g, pass5g, con5g)
                "BENMUNDO"   -> configurarBenmundo(btn, ip, mask, gw, vlan, dns1, dns2, ssid24, pass24, ssid5g, pass5g, con5g)
                "MCT AX3000" -> configurarMct(btn, ip, mask, gw, vlan, dns1, dns2, ssid24, pass24, ssid5g, pass5g, con5g)
                "QUALTEK"    -> configurarQualTek(btn, ip, mask, gw, vlan, dns1, dns2, ssid24, pass24, ssid5g, pass5g, con5g)
                "DIXON D130GW"    -> configurarDixon(btn, ip, mask, gw, vlan, dns1, dns2, ssid24, pass24, ssid5g, pass5g, con5g, DixonConfigurator.DixonModelo.D130GW)
                "DIXON D580GW-AX" -> configurarDixon(btn, ip, mask, gw, vlan, dns1, dns2, ssid24, pass24, ssid5g, pass5g, con5g, DixonConfigurator.DixonModelo.D580GW_AX)
                "DIXON D110GWC"   -> configurarDixon(btn, ip, mask, gw, vlan, dns1, dns2, ssid24, pass24, ssid5g, pass5g, con5g, DixonConfigurator.DixonModelo.D110GWC)

            }
        }
    }

    private fun btnInicio(btn: MaterialButton) {
        btn.isEnabled = false
        btn.text = "Conectando..."
        adquirirWifiLock(equipoActivo)
    }
    private fun btnFin(btn: MaterialButton) {
        liberarWifiLock()
        Handler(Looper.getMainLooper()).postDelayed({
            btn.text = "Configurar equipo"
            btn.isEnabled = true
        }, 3000)
    }
    private suspend fun actualizarProgreso(btn: MaterialButton, step: Int, total: Int, desc: String, ok: Boolean) {
        withContext(Dispatchers.Main) { btn.text = "[${(step * 100) / total}%] ${if (ok) "✓" else "✗"} $desc" }
    }

    private fun mostrarResultado(btn: MaterialButton, ok: Boolean, errorMsg: String, ip: String, mask: String, gw: String, dns1: String, dns2: String, ssid24: String, ssid5g: String, pass: String, pass5g: String, con5g: Boolean, rx: String, tx: String, sn: String) {
        btn.text = if (ok) "✓  CONFIGURADO" else "⚠  CON ERRORES"
        if (!ok && errorMsg.isNotBlank()) Toast.makeText(this, "Errores:\n$errorMsg", Toast.LENGTH_LONG).show()
        resultSn = sn; resultRx = rx; resultTx = tx
        resultSsid = ssid24; resultPass = pass; resultSsid5 = ssid5g; resultPass5 = pass5g
        mostrarOverlay(ip, mask, gw, dns1, dns2, ssid24, ssid5g, pass, pass5g, con5g, rx, tx, sn)
        btnFin(btn)
    }

    // ── Configuradores ────────────────────────────────────────
    private fun configurarOptic(btn: MaterialButton, ip: String, mask: String, gw: String, vlan: String, dns1: String, dns2: String, ssid24: String, pass24: String, ssid5g: String, pass5g: String, con5g: Boolean) {
        btnInicio(btn)
        lifecycleScope.launch {
            val result = OpticConfigurator(gatewayEquipo.ifBlank { "192.168.1.1" }).applyAllExtended(
                ip = ip, mask = mask.ifBlank { "255.255.255.0" }, gw = gw, vlan = vlan,
                dns1 = dns1.ifBlank { "8.8.8.8" }, dns2 = dns2.ifBlank { "8.8.4.4" },
                ssid24 = ssid24, pass24 = pass24,
                ssid5g = ssid5g.ifBlank { ssid24 }, pass5g = pass5g.ifBlank { pass24 },
                wifi5g = con5g
            ) { step, total, desc, ok -> actualizarProgreso(btn, step, total, desc, ok) }
            withContext(Dispatchers.Main) {
                val data = if (result is ZteResult.Success) result.data else emptyMap()
                mostrarResultado(btn, result is ZteResult.Success, if (result is ZteResult.Error) result.message else "",
                    ip, mask.ifBlank { "255.255.255.0" }, gw, dns1, dns2,
                    ssid24, if (con5g) ssid5g else "", pass24, pass5g, con5g,
                    data["rx"] ?: "", data["tx"] ?: "", data["gponsn"] ?: data["sn"] ?: "")
            }
        }
    }

    private fun configurarLanly(btn: MaterialButton, ip: String, mask: String, gw: String, vlan: String, dns1: String, dns2: String, ssid24: String, pass24: String, ssid5g: String, pass5g: String, con5g: Boolean) {
        btnInicio(btn)
        lifecycleScope.launch {
            val result = LanlyConfigurator(gatewayEquipo.ifBlank { "192.168.1.1" }).applyAllExtended(
                ip = ip, mask = mask.ifBlank { "255.255.255.0" }, gw = gw, vlan = vlan,
                dns1 = dns1.ifBlank { "8.8.8.8" }, dns2 = dns2.ifBlank { "8.8.4.4" },
                ssid24 = ssid24, pass24 = pass24,
                ssid5g = ssid5g.ifBlank { ssid24 }, pass5g = pass5g.ifBlank { pass24 },
                wifi5g = con5g
            ) { step, total, desc, ok -> actualizarProgreso(btn, step, total, desc, ok) }
            withContext(Dispatchers.Main) {
                val data = if (result is ZteResult.Success) result.data else emptyMap()
                mostrarResultado(btn, result is ZteResult.Success, if (result is ZteResult.Error) result.message else "",
                    ip, mask.ifBlank { "255.255.255.0" }, gw, dns1, dns2,
                    ssid24, if (con5g) ssid5g else "", pass24, pass5g, con5g,
                    data["rx"] ?: "", data["tx"] ?: "", data["sn"] ?: data["gponsn"] ?: "")
            }
        }
    }

    // ── ZTE F6600P: configurar ────────────────────────────────────────────
    @SuppressLint("SetTextI18n")
    private fun configurarZteF6600P(
        btn: MaterialButton,
        ip: String, mask: String, gw: String,
        dns1: String, dns2: String, vlan: String,
        ssid24: String, pass24: String, ssid5g: String, pass5g: String,
        con5g: Boolean
    ) {
        btnInicio(btn)
        val zte = ZteConfigurator(
            onuIp  = gatewayEquipo.ifBlank { "192.168.1.1" },
            modelo = ZteModelo.F6600P
        )
        lifecycleScope.launch {
            val login = zte.login("admin", "Web@0063")
            if (login is ZteResult.Error) {
                withContext(Dispatchers.Main) {
                    liberarWifiLock()
                    Toast.makeText(this@InstalacionActivity, "❌ Login ZTE F6600P: ${login.message}", Toast.LENGTH_LONG).show()
                    btn.text = "Configurar equipo"
                    btn.isEnabled = true
                }
                return@launch
            }
            val result = zte.applyAll(
                ip        = ip,
                mask      = mask.ifBlank { "255.255.255.0" },
                gw        = gw,
                dns1      = dns1.ifBlank { "8.8.8.8" },
                dns2      = dns2.ifBlank { "8.8.4.4" },
                vlan      = vlan.ifBlank { "100" },
                lanIp     = resultLanIp.ifBlank { "192.168.1.1" },
                dhcpStart = resultDhcpStart.ifBlank { "192.168.1.2" },
                dhcpEnd   = resultDhcpEnd.ifBlank { "192.168.1.254" },
                lanDns1   = dns1.ifBlank { "8.8.8.8" },
                lanDns2   = dns2.ifBlank { "8.8.4.4" },
                ssid24    = ssid24,
                pass24    = pass24,
                wep24     = ZteWifiBand(),
                ssid5     = ssid5g.ifBlank { ssid24 },
                pass5     = pass5g.ifBlank { pass24 },
                wep5      = ZteWifiBand()
            ) { step, total, desc, ok -> actualizarProgreso(btn, step, total, desc, ok) }
            withContext(Dispatchers.Main) {
                val dev = if (result is ZteResult.Success) result.data else ZteDeviceInfo()
                mostrarResultado(
                    btn, result is ZteResult.Success,
                    if (result is ZteResult.Error) result.message else "",
                    ip, mask.ifBlank { "255.255.255.0" }, gw, dns1, dns2,
                    ssid24, ssid5g, pass24, pass5g, con5g,
                    dev.rxPower, dev.txPower, dev.serial
                )
                if (result is ZteResult.Success) iniciarCuentaRegresivaReboot()
            }
            if (result is ZteResult.Success) zte.reboot()
        }
    }

    private fun configurarZte(btn: MaterialButton, ip: String, mask: String, gw: String, dns1: String, dns2: String, vlan: String, ssid24: String, pass24: String, ssid5g: String, pass5g: String, con5g: Boolean) {
        btnInicio(btn)
        val zte = ZteConfigurator(gatewayEquipo.ifBlank { "192.168.1.1" })
        lifecycleScope.launch {
            val login = zte.login("admin", "Web@0063")
            if (login is ZteResult.Error) {
                withContext(Dispatchers.Main) {
                    liberarWifiLock()
                    Toast.makeText(this@InstalacionActivity, "❌ Login ZTE: ${login.message}", Toast.LENGTH_LONG).show()
                    btn.text = "Configurar equipo"
                    btn.isEnabled = true
                }
                return@launch
            }
            val result = zte.applyAll(
                ip        = ip,
                mask      = mask.ifBlank { "255.255.255.0" },
                gw        = gw,
                dns1      = dns1.ifBlank { "8.8.8.8" },
                dns2      = dns2.ifBlank { "8.8.4.4" },
                vlan      = vlan.ifBlank { "100" },
                lanIp     = resultLanIp.ifBlank { "192.168.1.1" },
                dhcpStart = resultDhcpStart.ifBlank { "192.168.1.2" },
                dhcpEnd   = resultDhcpEnd.ifBlank { "192.168.1.254" },
                lanDns1   = dns1.ifBlank { "8.8.8.8" },
                lanDns2   = dns2.ifBlank { "8.8.4.4" },
                ssid24    = ssid24, pass24 = pass24, wep24 = ZteWifiBand(),
                ssid5     = ssid5g.ifBlank { ssid24 }, pass5 = pass5g.ifBlank { pass24 }, wep5 = ZteWifiBand()
            ) { step, total, desc, ok -> actualizarProgreso(btn, step, total, desc, ok) }
            withContext(Dispatchers.Main) {
                val dev = if (result is ZteResult.Success) result.data else ZteDeviceInfo()
                mostrarResultado(btn, result is ZteResult.Success, if (result is ZteResult.Error) result.message else "",
                    ip, mask.ifBlank { "255.255.255.0" }, gw, dns1, dns2,
                    ssid24, ssid5g, pass24, pass5g, con5g,
                    dev.rxPower, dev.txPower, dev.serial)
                if (result is ZteResult.Success) iniciarCuentaRegresivaReboot()
            }
            if (result is ZteResult.Success) zte.reboot()
        }
    }

    private fun configurarBenmundo(btn: MaterialButton, ip: String, mask: String, gw: String, vlan: String, dns1: String, dns2: String, ssid24: String, pass24: String, ssid5g: String, pass5g: String, con5g: Boolean) {
        btnInicio(btn)
        lifecycleScope.launch {
            val result = BenmundoConfigurator(gatewayEquipo.ifBlank { "192.168.101.1" }).applyAll(
                ip = ip,
                mask = mask.ifBlank { "255.255.255.0" },
                gw = gw,
                vlan = vlan,
                dns1 = dns1.ifBlank { "8.8.8.8" },
                dns2 = dns2.ifBlank { "8.8.4.4" },
                lanIp     = resultLanIp.ifBlank { "192.168.101.1" },      // ← AGREGAR
                dhcpStart = resultDhcpStart.ifBlank { "192.168.101.2" },  // ← AGREGAR
                dhcpEnd   = resultDhcpEnd.ifBlank { "192.168.101.254" },  // ← AGREGAR
                ssid24 = ssid24, pass24 = pass24,
                ssid5 = ssid5g.ifBlank { ssid24 }, pass5 = pass5g.ifBlank { pass24 }
            ) { step, total, desc, ok -> actualizarProgreso(btn, step, total, desc, ok) }
            withContext(Dispatchers.Main) {
                val data = if (result is ZteResult.Success) result.data else emptyMap()
                mostrarResultado(btn, result is ZteResult.Success, if (result is ZteResult.Error) result.message else "",
                    ip, mask.ifBlank { "255.255.255.0" }, gw, dns1, dns2,
                    ssid24, if (con5g) ssid5g else "", pass24, pass5g, con5g,
                    data["rx"] ?: "", data["tx"] ?: "", data["gponsn"] ?: data["sn"] ?: "")
            }
        }
    }

    // ── QualTek: configurar ───────────────────────────────────────────────
    private fun configurarQualTek(
        btn: MaterialButton,
        ip: String, mask: String, gw: String, vlan: String,
        dns1: String, dns2: String,
        ssid24: String, pass24: String, ssid5g: String, pass5g: String,
        con5g: Boolean
    ) {
        btnInicio(btn)
        lifecycleScope.launch {
            val result = QualTekConfigurator(
                onuIp = gatewayEquipo.ifBlank { "192.168.18.1" }
            ).applyAll(
                ip        = ip,
                mask      = mask.ifBlank { "255.255.255.0" },
                gw        = gw,
                vlan      = vlan,
                dns1      = dns1.ifBlank { "8.8.8.8" },
                dns2      = dns2.ifBlank { "8.8.4.4" },
                lanIp     = resultLanIp.ifBlank { "192.168.18.1" },
                dhcpStart = resultDhcpStart.ifBlank { "192.168.18.2" },
                dhcpEnd   = resultDhcpEnd.ifBlank { "192.168.18.254" },
                ssid24    = ssid24,
                pass24    = pass24,
                ssid5     = ssid5g.ifBlank { ssid24 },
                pass5     = pass5g.ifBlank { pass24 },
                onProgress = { step, total, desc, ok ->
                    actualizarProgreso(btn, step, total, desc, ok)
                }
            )
            withContext(Dispatchers.Main) {
                val data = if (result is ZteResult.Success) result.data else emptyMap()
                mostrarResultado(
                    btn, result is ZteResult.Success,
                    if (result is ZteResult.Error) result.message else "",
                    ip, mask.ifBlank { "255.255.255.0" }, gw, dns1, dns2,
                    ssid24, if (con5g) ssid5g else "",
                    pass24, pass5g, con5g,
                    data["rx"] ?: "", data["tx"] ?: "", data["sn"] ?: ""
                )
            }
        }
    }

    private fun configurarMct(btn: MaterialButton, ip: String, mask: String, gw: String, vlan: String, dns1: String, dns2: String, ssid24: String, pass24: String, ssid5g: String, pass5g: String, con5g: Boolean) {
        btnInicio(btn)
        val mct = MctConfigurator(gatewayEquipo.ifBlank { "192.168.2.1" })
        lifecycleScope.launch {
            // 1. Login
            val loginResult = mct.login()
            if (loginResult is ZteResult.Error) {
                withContext(Dispatchers.Main) {
                    liberarWifiLock()
                    Toast.makeText(this@InstalacionActivity, "❌ Login MCT: ${loginResult.message}", Toast.LENGTH_LONG).show()
                    btn.text = "Configurar equipo"
                    btn.isEnabled = true
                }
                return@launch
            }
            // 2. ReadConfig → wanOid e isNew
            val configResult = mct.readConfig()
            val wanOid: String
            val isNew: Boolean
            when (configResult) {
                is ZteResult.Success -> { wanOid = configResult.data.wanOid; isNew = configResult.data.wanIp.isEmpty() }
                is ZteResult.Error   -> { wanOid = "MDMOID_WAN_IP_CONN{1-1-1}"; isNew = true }
            }
            // 3. ApplyAll
            val result = mct.applyAll(
                ip = ip,
                mask = mask.ifBlank { "255.255.255.0" },
                gw = gw,
                vlan = vlan,
                dns1 = dns1.ifBlank { "8.8.8.8" },
                dns2 = dns2.ifBlank { "8.8.4.4" },
                lanDns1 = dns1.ifBlank { "8.8.8.8" },
                lanDns2 = dns2.ifBlank { "8.8.4.4" },
                ssid24 = ssid24,
                pass24 = pass24,
                ssid5 = ssid5g.ifBlank { ssid24 }, pass5 = pass5g.ifBlank { pass24 },
                isNew = isNew, wanOid = wanOid
            ) { step, total, desc, ok -> actualizarProgreso(btn, step, total, desc, ok) }
            withContext(Dispatchers.Main) {
                val data = if (result is ZteResult.Success) result.data else emptyMap()
                mostrarResultado(btn, result is ZteResult.Success, if (result is ZteResult.Error) result.message else "",
                    ip, mask.ifBlank { "255.255.255.0" }, gw, dns1, dns2,
                    ssid24, if (con5g) ssid5g else "", pass24, pass5g, con5g,
                    data["rx"] ?: "", data["tx"] ?: "", data["sn"] ?: "")
            }
        }
    }

    private fun cargarConfigDixon(btn: MaterialButton, modelo: DixonConfigurator.DixonModelo) {
        lifecycleScope.launch {
            val result = DixonConfigurator(gatewayEquipo, modelo).readConfig()
            withContext(Dispatchers.Main) {
                liberarWifiLock()
                when (result) {
                    is ZteResult.Success -> {
                        val c = result.data
                        resultLanIp = c.lanIp; resultDhcpStart = c.dhcpStart
                        resultDhcpEnd = c.dhcpEnd; resultLanDns = "${c.dns1} / ${c.dns2}"
                        rellenarCamposConfig(c.wanIp, c.wanMask, c.wanGw, c.wanVlan,
                            c.dns1, c.dns2, c.ssid24, c.pass24, c.ssid5, c.pass5)
                        mostrarLanInfo(); irAPaso3()
                    }
                    is ZteResult.Error -> {
                        Toast.makeText(this@InstalacionActivity, "⚠ ${result.message}", Toast.LENGTH_LONG).show()
                        btn.isEnabled = true; btn.text = "↓  Cargar configuración del equipo"
                    }
                }
            }
        }
    }

    private fun configurarDixon(
        btn: MaterialButton,
        ip: String, mask: String, gw: String, vlan: String,
        dns1: String, dns2: String,
        ssid24: String, pass24: String, ssid5g: String, pass5g: String,
        con5g: Boolean, modelo: DixonConfigurator.DixonModelo
    ) {
        btnInicio(btn)
        lifecycleScope.launch {
            val result = DixonConfigurator(
                onuIp  = gatewayEquipo.ifBlank { "192.168.101.1" },
                modelo = modelo
            ).applyAll(
                ip        = ip,
                mask      = mask.ifBlank { "255.255.255.0" },
                gw        = gw,
                vlan      = vlan,
                dns1      = dns1.ifBlank { "8.8.8.8" },
                dns2      = dns2.ifBlank { "8.8.4.4" },
                lanIp     = resultLanIp.ifBlank { "192.168.101.1" },
                dhcpStart = resultDhcpStart.ifBlank { "192.168.101.2" },
                dhcpEnd   = resultDhcpEnd.ifBlank { "192.168.101.254" },
                ssid24    = ssid24, pass24 = pass24,
                ssid5     = ssid5g.ifBlank { ssid24 },
                pass5     = pass5g.ifBlank { pass24 },
                onProgress = { step, total, desc, ok ->
                    actualizarProgreso(btn, step, total, desc, ok)
                }
            )
            withContext(Dispatchers.Main) {
                val data = if (result is ZteResult.Success) result.data else emptyMap()
                val dixon = DixonConfigurator(modelo = modelo)
                mostrarResultado(
                    btn, result is ZteResult.Success,
                    if (result is ZteResult.Error) result.message else "",
                    ip, mask.ifBlank { "255.255.255.0" }, gw, dns1, dns2,
                    ssid24, if (con5g && !dixon.solo24g) ssid5g else "",
                    pass24, pass5g, con5g && !dixon.solo24g,
                    data["rx"] ?: "", data["tx"] ?: "", data["sn"] ?: ""
                )
            }
        }
    }
    // ═════════════════════════════════════════════════════════
    //  CUENTA REGRESIVA REBOOT (ZTE)
    // ═════════════════════════════════════════════════════════
    @SuppressLint("SetTextI18n")
    private fun iniciarCuentaRegresivaReboot() {
        ovBtnCerrar.isClickable    = false; ovBtnCerrar.alpha = 0.3f
        ovBtnNuevaConfig.isEnabled = false
        ovBtnNuevaConfig.text      = "⏳ ONU reiniciando... ${REBOOT_SECS}s"
        ovBtnNuevaConfig.alpha     = 0.7f
        val subtituloOriginal = ovSubtitulo.text.toString()
        ovSubtitulo.text = "🔄 Reiniciando ONU... espera"
        var secsLeft = REBOOT_SECS
        rebootHandler  = Handler(Looper.getMainLooper())
        rebootRunnable = object : Runnable {
            override fun run() {
                secsLeft--
                if (secsLeft > 0) {
                    ovBtnNuevaConfig.text = "⏳ ONU reiniciando... ${secsLeft}s"
                    rebootHandler?.postDelayed(this, 1000)
                } else {
                    ovBtnCerrar.isClickable    = true;  ovBtnCerrar.alpha = 1f
                    ovBtnNuevaConfig.isEnabled = true;  ovBtnNuevaConfig.alpha = 1f
                    ovBtnNuevaConfig.text      = "COMPARTIR"
                    ovSubtitulo.text           = "✅ ONU lista · $subtituloOriginal"
                    Toast.makeText(this@InstalacionActivity, "✅ ONU reiniciada correctamente", Toast.LENGTH_SHORT).show()
                }
            }
        }
        rebootHandler?.postDelayed(rebootRunnable!!, 1000)
    }

    // ═════════════════════════════════════════════════════════
    //  OVERLAY
    // ═════════════════════════════════════════════════════════
    private fun bindOverlayViews() {
        overlayConfiguracion = findViewById(R.id.overlay_configuracion)
        overlayPanel         = findViewById(R.id.overlay_panel)
        ovSubtitulo          = findViewById(R.id.ov_subtitulo)
        ovBtnCerrar          = findViewById(R.id.ov_btn_cerrar)
        ovBtnNuevaConfig     = findViewById(R.id.ov_btn_nueva_config)
        ovWanIp              = findViewById(R.id.ov_wan_ip)
        ovWanGw              = findViewById(R.id.ov_wan_gw)
        ovWanMask            = findViewById(R.id.ov_wan_mask)
        ovDns                = findViewById(R.id.ov_dns)
        ovSsid24             = findViewById(R.id.ov_ssid_24)
        ovSsid5g             = findViewById(R.id.ov_ssid_5g)
        ovSsid5gRow          = findViewById(R.id.ov_ssid5_row)
        ovWifiPass           = findViewById(R.id.ov_wifi_pass)
        ovWifiPass5g         = findViewById(R.id.ov_wifi_pass_5g)
        ovRx                 = findViewById(R.id.ov_rx)
        ovRxBar              = findViewById(R.id.ov_rx_bar)
        ovTx                 = findViewById(R.id.ov_tx)
        ovTxBar              = findViewById(R.id.ov_tx_bar)
        ovPonNaRow           = findViewById(R.id.ov_pon_na_row)
        ovPonNaTxt           = findViewById(R.id.ov_pon_na_txt)
        ovSn                 = findViewById(R.id.ov_sn)
    }

    private fun setupOverlay() {
        val ovBtnContinuar = findViewById<MaterialButton>(R.id.ov_btn_continuar)
        ovBtnCerrar.setOnClickListener      { cerrarOverlay() }
        ovBtnNuevaConfig.setOnClickListener { compartirOverlay() }
        ovBtnContinuar?.setOnClickListener  { cerrarOverlay(); btnContinuarPaso4.visibility = View.VISIBLE }
        overlayConfiguracion.setOnClickListener { }
        overlayPanel.setOnClickListener         { }
    }

    private fun compartirOverlay() {
        ovBtnCerrar.visibility      = View.INVISIBLE
        ovBtnNuevaConfig.visibility = View.INVISIBLE
        overlayPanel.post {
            try {
                overlayPanel.measure(View.MeasureSpec.makeMeasureSpec(overlayPanel.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                val bitmap = Bitmap.createBitmap(overlayPanel.measuredWidth, overlayPanel.measuredHeight, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.parseColor("#08111E"))
                overlayPanel.layout(0, 0, overlayPanel.measuredWidth, overlayPanel.measuredHeight)
                overlayPanel.draw(canvas)
                ovBtnCerrar.visibility      = View.VISIBLE
                ovBtnNuevaConfig.visibility = View.VISIBLE
                val file = java.io.File(java.io.File(cacheDir, "images").also { it.mkdirs() }, "config_${System.currentTimeMillis()}.png")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.provider", file)
                startActivity(android.content.Intent.createChooser(
                    android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        putExtra(android.content.Intent.EXTRA_TEXT, "Config ONT — $equipoActivo · ${ovWanIp.text}")
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Compartir configuración"))
            } catch (e: Exception) {
                ovBtnCerrar.visibility      = View.VISIBLE
                ovBtnNuevaConfig.visibility = View.VISIBLE
                Toast.makeText(this, "Error al compartir: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun mostrarOverlay(ip: String, mask: String, gw: String, dns1: String, dns2: String, ssid24: String, ssid5g: String, pass: String, pass5g: String, con5g: Boolean, rx: String, tx: String, sn: String) {
        ovWanIp.text   = ip.ifBlank { "—" }
        ovWanGw.text   = gw.ifBlank { "—" }
        ovWanMask.text = mask.ifBlank { "—" }
        ovDns.text     = buildString { append(dns1.ifBlank { "—" }); if (dns2.isNotBlank()) append("\n$dns2") }
        ovSsid24.text  = ssid24.ifBlank { "—" }; ovWifiPass.text = pass.ifBlank { "—" }
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

    // ═════════════════════════════════════════════════════════
    //  PASO 4
    // ═════════════════════════════════════════════════════════
    private fun rellenarPaso4() {
        val cardDatos   = findViewById<CardView>(R.id.cardDatosEquipo)
        val cardResumen = findViewById<CardView>(R.id.cardResumenConfig)
        if (esInternet()) {
            cardDatos?.visibility = View.VISIBLE
            if (resultSsid.isNotBlank())  binding.etSsid.setText(resultSsid)
            if (resultPass.isNotBlank())  binding.etSsidPass.setText(resultPass)
            if (resultSsid5.isNotBlank()) binding.etSsid5ghz.setText(resultSsid5)
            if (resultPass5.isNotBlank()) binding.etSsidPass5ghz.setText(resultPass5)
            if (resultSn.isNotBlank())    binding.etSerial.setText(resultSn)
            val tvSsid = findViewById<TextView>(R.id.tvResumenSsid)
            val tvSn   = findViewById<TextView>(R.id.tvResumenSn)
            if (resultSsid.isNotBlank() || resultSn.isNotBlank()) {
                cardResumen?.visibility = View.VISIBLE
                tvSsid?.text = "WiFi: $resultSsid"
                tvSn?.text   = if (resultSn.isNotBlank()) "SN: $resultSn  ·  RX: $resultRx dBm" else ""
            }
        } else {
            cardDatos?.visibility = View.GONE
            cardResumen?.visibility = View.GONE
        }

        // ── Conectar botón de materiales ──────────────────────
        val btnAgregarMaterial = findViewById<View>(R.id.btnAgregarMaterial)
        btnAgregarMaterial?.setOnClickListener { mostrarDialogMateriales() }
        // Si es retiro, mostrar sección de equipos recuperados
        val ordenActual = vm.orden.value
        if (ordenActual != null && esRetiro(ordenActual.tipoOrden)) {
            mostrarSeccionRetiro()
        }

        // Refrescar lista visual si ya hay materiales
        actualizarListaMateriales()
    }

    // ── Paso 4: materiales gastados ───────────────────────────
    private val inventarioVm: InventarioViewModel by viewModels()
    // Lista de pares (productoId, cantidad) que el técnico registró
    private val materialesGastados = mutableListOf<Pair<Int, Double>>()
    // Mapa id→nombre para mostrar offline
    private val nombresProductos   = mutableMapOf<Int, String>()

    private val equiposRetirados   = mutableListOf<Pair<Int, Double>>()
    private val nombresEquipos     = mutableMapOf<Int, String>()

    private fun esRetiro(tipoOrden: String) =
        tipoOrden in listOf(
            TipoOrden.RETIRO_EQUIPO_I,
            TipoOrden.RETIRO_EQUIPO_C,
            TipoOrden.RETIRO_EQUIPO_D,
            TipoOrden.BAJA_SERVICIO_I,
            TipoOrden.BAJA_SERVICIO_D
        )

    private fun mostrarSeccionRetiro() {
        val cardMateriales = findViewById<com.google.android.material.card.MaterialCardView>(
            R.id.cardMateriales
        ) ?: return

        // Cambiar el título del card a "Equipos recuperados"
        val tvTitulo = cardMateriales.findViewById<TextView>(
            R.id.tvMaterialesContador
        )

        // Cambiar el botón a "Registrar equipo recuperado"
        val btnAgregar = findViewById<android.widget.LinearLayout>(R.id.btnAgregarMaterial)
        val tvBtnTexto = btnAgregar?.getChildAt(1) as? TextView
        tvBtnTexto?.text = "Registrar equipo recuperado"

        btnAgregar?.setOnClickListener { mostrarDialogRetiro() }
    }

    private fun mostrarDialogRetiro() {
        // Mostrar items del catálogo para seleccionar qué equipo se recuperó
        // Usamos el inventario del técnico pero también puede ser algo nuevo
        val items = inventarioVm.items.value ?: emptyList()

        // Para retiro mostramos TODOS los items (incluso sin stock)
        // porque el técnico puede traer algo que no tenía
        if (items.isEmpty()) {
            // Si no hay inventario cargado, pedir producto manualmente
            pedirEquipoManual()
            return
        }

        val opciones = items.map { it.nombre }.toTypedArray()

        var seleccionIdx = -1
        AlertDialog.Builder(this)
            .setTitle("¿Qué equipo recuperaste?")
            .setSingleChoiceItems(opciones, -1) { _, idx -> seleccionIdx = idx }
            .setPositiveButton("Siguiente") { _, _ ->
                if (seleccionIdx < 0) return@setPositiveButton
                val item = items[seleccionIdx]
                pedirCantidadRetiro(item.productoId, item.nombre)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pedirEquipoManual() {
        // Fallback: si no hay inventario local, solo mostrar un mensaje
        Toast.makeText(
            this,
            "Sincroniza tu inventario para poder registrar equipos recuperados",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun pedirCantidadRetiro(productoId: Int, nombre: String) {
        val etCantidad = android.widget.EditText(this).apply {
            hint = "Cantidad recuperada"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(48, 24, 48, 24)
        }

        AlertDialog.Builder(this)
            .setTitle("Cantidad de $nombre")
            .setView(etCantidad)
            .setPositiveButton("Agregar") { _, _ ->
                val cantidad = etCantidad.text.toString().toDoubleOrNull() ?: 0.0
                if (cantidad <= 0) {
                    Toast.makeText(this, "Ingresa una cantidad válida", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val idx = equiposRetirados.indexOfFirst { it.first == productoId }
                if (idx >= 0) {
                    equiposRetirados[idx] = Pair(productoId, equiposRetirados[idx].second + cantidad)
                } else {
                    equiposRetirados.add(Pair(productoId, cantidad))
                    nombresEquipos[productoId] = nombre
                }
                actualizarListaMateriales() // reusa la misma función visual
                Toast.makeText(this, "✅ $nombre x${cantidad.toInt()} agregado", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Muestra el diálogo para seleccionar un item del inventario y su cantidad */
    private fun mostrarDialogMateriales() {
        val items = inventarioVm.items.value?.filter { it.disponible > 0 }
        if (items.isNullOrEmpty()) {
            Toast.makeText(this, "No tienes items disponibles en tu inventario", Toast.LENGTH_SHORT).show()
            return
        }

        val opciones = items.map { "${it.nombre} — disp: ${it.disponible.toInt()} ${it.unidad}" }
            .toTypedArray()

        var seleccionIdx = -1

        AlertDialog.Builder(this)
            .setTitle("Seleccionar material")
            .setSingleChoiceItems(opciones, -1) { _, idx -> seleccionIdx = idx }
            .setPositiveButton("Siguiente") { _, _ ->
                if (seleccionIdx < 0) return@setPositiveButton
                val item = items[seleccionIdx]
                pedirCantidadMaterial(item.productoId, item.nombre, item.disponible)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pedirCantidadMaterial(productoId: Int, nombre: String, maxDisponible: Double) {
        val dialogView = layoutInflater.inflate(
            android.R.layout.simple_list_item_1, null
        )
        // Crear un EditText programáticamente para la cantidad
        val etCantidad = android.widget.EditText(this).apply {
            hint = "Cantidad (máx ${maxDisponible.toInt()})"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(48, 24, 48, 24)
        }

        AlertDialog.Builder(this)
            .setTitle("Cantidad de $nombre")
            .setView(etCantidad)
            .setPositiveButton("Agregar") { _, _ ->
                val cantidad = etCantidad.text.toString().toDoubleOrNull() ?: 0.0
                if (cantidad <= 0) {
                    Toast.makeText(this, "Ingresa una cantidad válida", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (cantidad > maxDisponible) {
                    Toast.makeText(this, "No puedes gastar más de lo disponible", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // Agregar o acumular
                val idx = materialesGastados.indexOfFirst { it.first == productoId }
                if (idx >= 0) {
                    materialesGastados[idx] = Pair(productoId, materialesGastados[idx].second + cantidad)
                } else {
                    materialesGastados.add(Pair(productoId, cantidad))
                    nombresProductos[productoId] = nombre
                }
                actualizarListaMateriales()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun actualizarListaMateriales() {
        val layoutLista   = findViewById<android.widget.LinearLayout>(R.id.layoutListaMateriales) ?: return
        val layoutVacio   = findViewById<android.widget.LinearLayout>(R.id.layoutMaterialesVacio) ?: return
        val tvContador    = findViewById<TextView>(R.id.tvMaterialesContador) ?: return

        layoutLista.removeAllViews()

        if (materialesGastados.isEmpty()) {
            layoutVacio.visibility = View.VISIBLE
            layoutLista.visibility = View.GONE
            tvContador.visibility  = View.GONE
            return
        }

        layoutVacio.visibility = View.GONE
        layoutLista.visibility = View.VISIBLE
        tvContador.visibility  = View.VISIBLE
        tvContador.text        = "${materialesGastados.size} items"

        for ((productoId, cantidad) in materialesGastados) {
            val nombre = nombresProductos[productoId] ?: "Producto #$productoId"
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }
            val tvNombre = TextView(this).apply {
                text = nombre
                textSize = 13f
                setTextColor(android.graphics.Color.parseColor("#0D1B2A"))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                maxLines = 2
            }
            val tvCantidad = TextView(this).apply {
                text = "-${cantidad.toInt()}"
                textSize = 14f
                setTextColor(android.graphics.Color.parseColor("#E67E22"))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val btnQuitar = android.widget.ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_delete)
                setColorFilter(android.graphics.Color.parseColor("#E74C3C"))
                background = null
                setPadding(16, 0, 0, 0)
                setOnClickListener {
                    materialesGastados.removeAll { it.first == productoId }
                    nombresProductos.remove(productoId)
                    actualizarListaMateriales()
                }
            }
            row.addView(tvNombre)
            row.addView(tvCantidad)
            row.addView(btnQuitar)

            // Divisor
            val divider = View(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
                )
                setBackgroundColor(android.graphics.Color.parseColor("#DDE6F0"))
            }
            layoutLista.addView(row)
            layoutLista.addView(divider)
        }
    }


    private fun completar() {
        if (vm.cantidadFotos() == 0) {
            Toast.makeText(this, "Debes tomar al menos una foto", Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnCompletar.isEnabled = false
        binding.progressCompletar.visibility = View.VISIBLE

        val obs = binding.etObservaciones.text.toString().ifBlank { null }

        lifecycleScope.launch {
            // 1. Guardar config ONU
            if (esInternet()) {
                val config = ConfigOnuRequest(
                    ssid             = binding.etSsid.text.toString().ifBlank { null },
                    ssidPassword     = binding.etSsidPass.text.toString().ifBlank { null },
                    ssid5ghz         = binding.etSsid5ghz.text.toString().ifBlank { null },
                    ssidPassword5ghz = binding.etSsidPass5ghz.text.toString().ifBlank { null },
                    serialNumber     = binding.etSerial.text.toString().ifBlank { null },
                    mac              = null,
                    potenciaRx       = resultRx.toFloatOrNull(),
                    potenciaTx       = resultTx.toFloatOrNull(),
                    temperatura      = null,
                    estado           = "ONLINE",
                    pppoeUser        = null,
                    pppoePassword    = null,
                    vlan             = resultVlan,
                    offline          = !vm.isOnline
                )
                vm.guardarConfigSuspend(config)
            }

            // 2. Subir fotos
            val fotosOk = vm.subirFotosPendientesSuspend()

            // 3. Registrar materiales gastados (offline-first — siempre)
            if (materialesGastados.isNotEmpty()) {
                val consumoItems = materialesGastados.map { (productoId, cantidad) ->
                    ConsumoItemRequest(productoId, cantidad)
                }
                inventarioVm.registrarConsumo(
                    items       = consumoItems,
                    motivo      = "SERVICIO",
                    descripcion = "Orden: $ordenId",
                    ordenId     = ordenId,
                    nombresMap  = nombresProductos
                )
                val ordenActual = vm.orden.value
                if (ordenActual != null && esRetiro(ordenActual.tipoOrden) &&
                    equiposRetirados.isNotEmpty()) {
                    val retiroItems = equiposRetirados.map { (productoId, cantidad) ->
                        RetiroItemRequest(productoId, cantidad)
                    }
                    inventarioVm.registrarRetiro(
                        items       = retiroItems,
                        ordenId     = ordenId,
                        descripcion = "Retiro: ${TipoOrden.label(ordenActual.tipoOrden)}"
                    )
                }

            }

            // 4. Completar la instalación
            vm.completar(
                observaciones = obs,
                ordenId       = ordenId,
                fotosOk       = fotosOk,
                onExito = {
                    Toast.makeText(this@InstalacionActivity,
                        "🎉 Servicio completado", Toast.LENGTH_LONG).show()
                    irAMain()
                },
                onPendiente = {
                    Toast.makeText(this@InstalacionActivity,
                        "📡 Sin señal. El servicio se completará automáticamente cuando vuelva la conexión.",
                        Toast.LENGTH_LONG).show()
                    irAMain()
                },
                onError = { msg ->
                    binding.btnCompletar.isEnabled = true
                    binding.progressCompletar.visibility = View.GONE
                    Toast.makeText(this@InstalacionActivity, msg, Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    private fun irAMain() {
        vm.reset()
        startActivity(
            Intent(this@InstalacionActivity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }

    // ═════════════════════════════════════════════════════════
    //  OBSERVAR
    // ═════════════════════════════════════════════════════════
    private fun observar() {
        vm.orden.observe(this) { orden ->
            if (orden == null) return@observe
            mostrarPaso(1)
            cargarUbicacionDesdeOrden()
            setupCardPrecinto()
            if (!orden.ipWan.isNullOrEmpty()) {
                binding.tvWanInfo.visibility = View.VISIBLE
                binding.tvWanInfo.text = "WAN: ${orden.ipWan} / ${orden.mascara} → ${orden.gateway}"
            }
        }
        vm.fotos.observe(this) { _ ->
            actualizarListaFotos()
        }
        vm.state.observe(this) { state ->
            if (state is InstalacionState.Guardando) binding.progressCompletar.visibility = View.VISIBLE
        }
    }

    // actualizarTextosBotonesFotos eliminada — fotos libres

    // ═════════════════════════════════════════════════════════
    //  CÁMARA / WIFI / BACK
    // ═════════════════════════════════════════════════════════
    private fun solicitarFoto() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) tomarFoto()
        else permisoCamara.launch(Manifest.permission.CAMERA)
    }

    private fun tomarFoto() {
        val archivo = File(cacheDir, "foto_${System.currentTimeMillis()}.jpg")
        archivoFoto = archivo
        camaraLauncher.launch(FileProvider.getUriForFile(this, "$packageName.provider", archivo))
    }

    private fun copiarUriAArchivo(uri: android.net.Uri): File? {
        return try {
            val archivo = File(cacheDir, "galeria_${System.currentTimeMillis()}.jpg")
            contentResolver.openInputStream(uri)?.use { input ->
                archivo.outputStream().use { output -> input.copyTo(output) }
            }
            if (archivo.exists() && archivo.length() > 0) archivo else null
        } catch (_: Exception) { null }
    }

    private fun actualizarListaFotos() {
        val fotos   = vm.fotos.value ?: emptyList()
        val layout  = findViewById<android.widget.LinearLayout>(R.id.layoutListaFotos) ?: return
        val vacio   = findViewById<android.widget.LinearLayout>(R.id.layoutFotosVacio) ?: return
        val barProg = findViewById<android.view.View>(R.id.viewFotosProgreso) ?: return

        vacio.visibility  = if (fotos.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        layout.visibility = if (fotos.isEmpty()) android.view.View.GONE    else android.view.View.VISIBLE

        // Barra de progreso
        val maxFotos = com.enetfiber.tecnico.ui.MAX_FOTOS
        val pct = fotos.size.toFloat() / maxFotos
        val params = barProg.layoutParams
        val parentWidth = (barProg.parent as? android.view.View)?.width ?: 0
        params.width = (parentWidth * pct).toInt().coerceAtLeast(0)
        barProg.layoutParams = params

        // Contador
        binding.tvFotosProgreso.text = "${fotos.size} / $maxFotos"
        val colorBg = if (fotos.isEmpty()) "#E8F4FB" else "#DCFCE7"
        val colorTx = if (fotos.isEmpty()) "#3B9FD4" else "#16A34A"
        binding.tvFotosProgreso.setBackgroundColor(android.graphics.Color.parseColor(colorBg))
        binding.tvFotosProgreso.setTextColor(android.graphics.Color.parseColor(colorTx))

        // Limpiar y rellenar lista
        layout.removeAllViews()
        fotos.forEachIndexed { idx, foto ->
            val item = crearItemFoto(idx, foto, layout)
            layout.addView(item)
        }

        binding.btnSiguienteConfig.isEnabled = true
    }

    private fun crearItemFoto(idx: Int, foto: com.enetfiber.tecnico.ui.FotoTomada, parent: android.view.ViewGroup): android.view.View {
        // Construir el item programáticamente para evitar problemas de R.id con layouts inflados
        val ctx = this

        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#F8FAFC"))
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(6) }
        }

        // ── Ícono origen ──────────────────────────────────────
        val iconBg = android.graphics.Color.parseColor(if (foto.origen == "GALERIA") "#EDE9FE" else "#DBEAFE")
        val iconColor = android.graphics.Color.parseColor(if (foto.origen == "GALERIA") "#7C3AED" else "#1D4ED8")

        val iconLayout = android.widget.LinearLayout(ctx).apply {
            setBackgroundColor(iconBg)
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(dpToPx(40), dpToPx(40)).apply {
                marginEnd = dpToPx(10)
            }
        }
        val iconView = android.widget.ImageView(ctx).apply {
            setImageResource(R.drawable.ic_photo)
            setColorFilter(iconColor)
            layoutParams = android.widget.LinearLayout.LayoutParams(dpToPx(20), dpToPx(20))
        }
        iconLayout.addView(iconView)
        root.addView(iconLayout)

        // ── Nombre y origen ───────────────────────────────────
        val textos = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvNombre = android.widget.TextView(ctx).apply {
            text = foto.nombre.ifEmpty { "foto_${String.format("%03d", idx + 1)}.jpg" }
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#0F172A"))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        val tvInfo = android.widget.TextView(ctx).apply {
            text = "${if (foto.origen == "GALERIA") "Galería" else "Cámara"} · ${foto.tamanoMb}"
            textSize = 10f
            setTextColor(android.graphics.Color.parseColor("#94A3B8"))
        }
        textos.addView(tvNombre)
        textos.addView(tvInfo)
        root.addView(textos)

        // ── Botón ver ─────────────────────────────────────────
        val btnVer = android.widget.LinearLayout(ctx).apply {
            setBackgroundColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
            isClickable = true; isFocusable = true
            layoutParams = android.widget.LinearLayout.LayoutParams(dpToPx(28), dpToPx(28)).apply {
                marginEnd = dpToPx(6)
            }
        }
        val ivVer = android.widget.ImageView(ctx).apply {
            setImageResource(R.drawable.ic_eye)
            setColorFilter(android.graphics.Color.parseColor("#64748B"))
            layoutParams = android.widget.LinearLayout.LayoutParams(dpToPx(14), dpToPx(14))
        }
        btnVer.addView(ivVer)
        btnVer.setOnClickListener {
            try {
                val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "$packageName.provider", File(foto.rutaLocal))
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "image/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            } catch (_: Exception) {
                Toast.makeText(ctx, "No se puede abrir la foto", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(btnVer)

        // ── Botón borrar ──────────────────────────────────────
        val btnBorrar = android.widget.LinearLayout(ctx).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#FEF2F2"))
            gravity = android.view.Gravity.CENTER
            isClickable = true; isFocusable = true
            layoutParams = android.widget.LinearLayout.LayoutParams(dpToPx(28), dpToPx(28))
        }
        val ivBorrar = android.widget.ImageView(ctx).apply {
            setImageResource(R.drawable.ic_trash)
            setColorFilter(android.graphics.Color.parseColor("#DC2626"))
            layoutParams = android.widget.LinearLayout.LayoutParams(dpToPx(14), dpToPx(14))
        }
        btnBorrar.addView(ivBorrar)
        btnBorrar.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle("Borrar foto")
                .setMessage("¿Eliminar esta foto?")
                .setPositiveButton("Borrar") { _, _ ->
                    vm.eliminarFoto(foto.rutaLocal)
                    actualizarListaFotos()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        root.addView(btnBorrar)

        return root
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

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

    // ─────────────────────────────────────────────────────────
    //  WIFI LOCK — mantiene WiFi activo durante la operación
    //  Previene que Android apague la radio durante read/apply
    //  (causa #1 de RX/TX/SN vacíos en ZTE F6600P y similares)
    // ─────────────────────────────────────────────────────────
    private fun adquirirWifiLock(tag: String) {
        if (wifiLock?.isHeld == true) return  // ya hay uno activo
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "enetfiber:$tag").apply { acquire() }
            android.util.Log.d("WifiLock", "Adquirido para $tag")
        } catch (e: Exception) {
            android.util.Log.w("WifiLock", "No disponible: ${e.message}")
        }
    }

    private fun liberarWifiLock() {
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
            android.util.Log.d("WifiLock", "Liberado")
        } catch (_: Exception) {}
        wifiLock = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 99 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) obtenerInfoRed()
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

    override fun onResume() {
        super.onResume()
        findViewById<org.osmdroid.views.MapView>(R.id.mapPreview)?.onResume()
    }

    override fun onPause() {
        super.onPause()
        findViewById<org.osmdroid.views.MapView>(R.id.mapPreview)?.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        liberarWifiLock()  // ← NUEVO (por si quedó pegado al salir)
        rebootRunnable?.let { rebootHandler?.removeCallbacks(it) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            overlayConfiguracion.visibility == View.VISIBLE -> { /* overlay bloquea back */ }
            pasoActual > 1 -> mostrarPaso(pasoActual - 1)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }
}