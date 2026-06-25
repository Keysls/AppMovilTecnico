package com.enetfiber.tecnico.ui.instalacion

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
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
import com.enetfiber.tecnico.ui.InstalacionState
import com.enetfiber.tecnico.ui.EstadoOltUi
import com.enetfiber.tecnico.ui.InstalacionViewModel
import com.enetfiber.tecnico.ui.main.MainActivity
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
import com.enetfiber.tecnico.data.Resultado
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

    private var tipoFotoActual = ""
    private var archivoFoto: File? = null

    private var equipoActivo  = ""

    private var resultSn    = ""
    private var resultRx    = ""
    private var resultTx    = ""
    private var resultVlan  = "100"
    private var resultSsid  = ""
    private var resultPass  = ""
    private var resultSsid5 = ""
    private var resultPass5 = ""
    private var configOnuAplicada = false

    // IDs generados dinámicamente para la card de código PON manual
    // (TRASLADO/CAMBIO_DOMICILIO) — se crean en rellenarPaso4() y se
    // leen en serialActualParaOlt() y actualizarCardOltInactiva().
    private var idEtOltCodigoManual: Int = View.NO_ID
    private var idTvOltCodigoManualConfirmacion: Int = View.NO_ID

    private var resultLanIp     = ""
    private var resultDhcpStart = ""
    private var resultDhcpEnd   = ""
    private var resultLanDns    = ""

    // ── WifiLock ──────────────────────────────────────────────

    // ── GPS / Ubicación ──────────────────────────────────────────
    @Inject lateinit var repo: Repository
    private var latContrato: Double? = null
    private var lngContrato: Double? = null
    private var mapaPreviewMarker: org.osmdroid.views.overlay.Marker? = null

    // ── Resultado de ConfigOnuActivity ────────────────────────
    private val configOnuLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            equipoActivo      = data.getStringExtra(ConfigOnuActivity.RESULT_EQUIPO) ?: ""
            resultSn          = data.getStringExtra(ConfigOnuActivity.RESULT_SN) ?: ""
            resultRx          = data.getStringExtra(ConfigOnuActivity.RESULT_RX) ?: ""
            resultTx          = data.getStringExtra(ConfigOnuActivity.RESULT_TX) ?: ""
            resultVlan        = data.getStringExtra(ConfigOnuActivity.RESULT_VLAN) ?: "100"
            resultSsid        = data.getStringExtra(ConfigOnuActivity.RESULT_SSID) ?: ""
            resultPass        = data.getStringExtra(ConfigOnuActivity.RESULT_PASS) ?: ""
            resultSsid5       = data.getStringExtra(ConfigOnuActivity.RESULT_SSID5) ?: ""
            resultPass5       = data.getStringExtra(ConfigOnuActivity.RESULT_PASS5) ?: ""
            configOnuAplicada = data.getBooleanExtra(ConfigOnuActivity.RESULT_CONFIGURED, false)
            actualizarBadgeConfigOnu()
        }
    }

    private fun abrirConfigOnu() {
        configOnuLauncher.launch(
            android.content.Intent(this, ConfigOnuActivity::class.java).apply {
                val orden = vm.orden.value
                putExtra(ConfigOnuActivity.EXTRA_IP_WAN, orden?.ipWan ?: "")
                putExtra(ConfigOnuActivity.EXTRA_MASCARA, orden?.mascara ?: "")
                putExtra(ConfigOnuActivity.EXTRA_GATEWAY, orden?.gateway ?: "")
            }
        )
    }

    private fun actualizarBadgeConfigOnu() {
        val btnConfig = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnConfigurarOnu) ?: return
        if (configOnuAplicada) {
            btnConfig.text = "✓ ONU configurada ($equipoActivo) — reconfigurar"
            btnConfig.setTextColor(android.graphics.Color.parseColor("#14532D"))
            btnConfig.setBackgroundColor(android.graphics.Color.parseColor("#DCFCE7"))
        } else {
            btnConfig.text = "📡 Configurar ONU"
            btnConfig.setTextColor(android.graphics.Color.WHITE)
            btnConfig.setBackgroundColor(android.graphics.Color.parseColor("#1E3A8A"))
        }
    }

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
        if (TipoOrden.esRetiroDinamico(orden.tipoOrden)) return false
        return TipoOrden.esInternet(orden.tipoOrden)
    }

    /**
     * Solo estos tipos de orden requieren autorización OLT — lista intencionalmente
     * más restringida que esInternet(), que se usa para otras cosas (mostrar pasos de
     * configuración WiFi, cards de equipo) y abarca tipos como AVERIA_I o CAMBIO_PLAN_I
     * que no necesitan autenticar la ONU en la OLT.
     */
    private fun requiereOltDinamico(): Boolean {
        val orden = vm.orden.value ?: return false
        return orden.tipoOrden in listOf(
            TipoOrden.INSTALACION_I, TipoOrden.CAMBIO_EQUIPO_I, TipoOrden.RECONEXION_I, TipoOrden.TRASLADO_I, TipoOrden.CAMBIO_DOMICILIO_I,
            TipoOrden.INSTALACION_D, TipoOrden.CAMBIO_EQUIPO_D, TipoOrden.RECONEXION_D, TipoOrden.TRASLADO_D, TipoOrden.CAMBIO_DOMICILIO_D
        )
    }

    /**
     * TRASLADO/CAMBIO_DOMICILIO: la ONU ya está instalada en casa del cliente desde
     * antes — no está en el inventario del técnico, así que no hay chip que elegir
     * en Materiales. El código PON se escribe a mano.
     *
     * RECONEXION es distinto: normalmente la ONU del cliente sigue ahí y se reutiliza
     * (mismo comportamiento, código manual) — PERO si está averiada, el técnico activa
     * el switch "ONU averiada" y el flujo cambia a elegir una ONU nueva del inventario.
     */
    private fun esTrasladoOCambioDomicilio(): Boolean {
        val orden = vm.orden.value ?: return false
        if (esReconexion(orden.tipoOrden) && onuClienteAveriadaEnReconexion) return false
        return orden.tipoOrden in listOf(
            TipoOrden.TRASLADO_I, TipoOrden.CAMBIO_DOMICILIO_I, TipoOrden.RECONEXION_I,
            TipoOrden.TRASLADO_D, TipoOrden.CAMBIO_DOMICILIO_D, TipoOrden.RECONEXION_D
        )
    }

    /** true si el tipo de orden es RECONEXION (Internet o Dúo). */
    private fun esReconexion(tipoOrden: String) = tipoOrden in listOf(
        TipoOrden.RECONEXION_I, TipoOrden.RECONEXION_D
    )

    /**
     * Solo relevante en RECONEXION: true si el técnico marcó que la ONU del cliente
     * está averiada. Por defecto false (se asume que la ONU sigue sirviendo).
     */
    private var onuClienteAveriadaEnReconexion: Boolean = false

    /** ONU vieja averiada que se registra como retiro al completar (si aplica). */
    private var equipoOnuAveriadaRetirado: com.enetfiber.tecnico.data.remote.RetiroItemRequest? = null

    // ═════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInstalacionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        vm.instalacionId = instalacionId
        vm.cargarOrden(ordenId)
        vm.cargarEstadoOltInicial(instalacionId)

        botonesGenerales()
        bindOltViews()
        observar()
        observarEstadoOlt()
        inicializarOsmdroid()
        setupCardUbicacion()
        setupCardPrecinto()

        // Cargar inventario del técnico al iniciar la activity
        inventarioVm.cargarMetricas(sincronizar = true)

        // Observer global de ONUs — actualiza chips del producto actualmente visible
        inventarioVm.onus.observe(this) { onus ->
            repintarChipsVisibles(onus ?: emptyList())
        }
    }

    /**
     * Repinta los chips de código PON de todas las filas de Materiales que estén
     * mostrando la sección ONU actualmente. Reutilizable desde el observer de
     * inventario y desde "Cambiar equipo", que también cambia onusSeleccionadas
     * sin pasar por un click de chip.
     */
    private fun repintarChipsVisibles(onus: List<com.enetfiber.tecnico.data.local.InventarioOnuEntity>) {
        val layoutLista = findViewById<android.widget.LinearLayout>(R.id.layoutListaMateriales) ?: return
        for (i in 0 until layoutLista.childCount) {
            val row = layoutLista.getChildAt(i) ?: continue
            val spinner = row.findViewById<android.widget.Spinner>(R.id.spinnerItem) ?: continue
            val productoId = spinner.tag as? Int ?: continue
            val chipsRow = row.findViewWithTag<android.widget.LinearLayout>("chips_row") ?: continue
            val onuSection = row.findViewWithTag<android.widget.LinearLayout>("onu_section") ?: continue
            if (onuSection.visibility != android.view.View.VISIBLE) continue

            chipsRow.removeAllViews()
            val filtradas = onus.filter { it.productoId == productoId }
            if (filtradas.isEmpty()) {
                chipsRow.addView(android.widget.TextView(this).apply {
                    text = "Sin ONUs disponibles"; textSize = 12f
                    setTextColor(android.graphics.Color.parseColor("#94A3B8"))
                })
            } else {
                val dp = resources.displayMetrics.density
                val codigosReciclados = inventarioVm.recojos.value
                    ?.filter { it.productoId == productoId && it.estado == "en_mano" }
                    ?.mapNotNull { it.codigoPon }
                    ?.toSet() ?: emptySet()

                filtradas.forEach { onu ->
                    val yaBloqueado = vm.estadoOlt.value is EstadoOltUi.Autorizada
                    val esElSeleccionado = onusSeleccionadas[productoId] == onu.codigoPon
                    val esReciclado = onu.codigoPon != null && codigosReciclados.contains(onu.codigoPon)

                    val chipContainer = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = (8*dp).toInt() }
                    }

                    val chip = android.widget.TextView(this).apply {
                        text = onu.codigoPon ?: "SIN CÓDIGO"; textSize = 12f
                        typeface = android.graphics.Typeface.MONOSPACE
                        if (esElSeleccionado) {
                            setTextColor(android.graphics.Color.WHITE)
                            setBackgroundColor(android.graphics.Color.parseColor("#7C3AED"))
                        } else {
                            setTextColor(android.graphics.Color.parseColor("#1E3A5F"))
                            background = getDrawable(R.drawable.input_bg)
                        }
                        alpha = if (yaBloqueado && !esElSeleccionado) 0.4f else 1f
                        setPadding((10*dp).toInt(),(6*dp).toInt(),(10*dp).toInt(),(6*dp).toInt())
                        isClickable = true; isFocusable = true
                    }

                    chipContainer.addView(chip)

                    if (esReciclado) {
                        chipContainer.addView(android.widget.TextView(this).apply {
                            text = "♻ Reciclado"; textSize = 9f
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            setTextColor(android.graphics.Color.parseColor("#15803D"))
                            gravity = android.view.Gravity.CENTER
                            layoutParams = android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { topMargin = (2*dp).toInt() }
                        })
                    }

                    chip.setOnClickListener {


                        if (vm.estadoOlt.value is EstadoOltUi.Autorizada) {
                            Toast.makeText(
                                this,
                                "Ya autenticaste esta ONU con la OLT. Usa \"Cambiar equipo\" si necesitas usar otra.",
                                Toast.LENGTH_LONG
                            ).show()
                            return@setOnClickListener
                        }
                        for (j in 0 until chipsRow.childCount) {
                            val c = chipsRow.getChildAt(j) as? android.widget.TextView
                            c?.setTextColor(android.graphics.Color.parseColor("#1E3A5F"))
                            c?.background = getDrawable(R.drawable.input_bg)
                        }
                        chip.setTextColor(android.graphics.Color.WHITE)
                        chip.setBackgroundColor(android.graphics.Color.parseColor("#7C3AED"))
                        onu.codigoPon?.let { onusSeleccionadas[productoId] = it }
                        val idx = materialesGastados.indexOfFirst { it.first == productoId }
                        val nombre = itemsInventarioCache.firstOrNull { it.productoId == productoId }?.nombre ?: ""
                        if (idx >= 0) materialesGastados[idx] = Pair(productoId, 1.0)
                        else { materialesGastados.add(Pair(productoId, 1.0)); nombresProductos[productoId] = nombre }
                        actualizarContadorMateriales()
                        actualizarCardOltInactiva()
                    }
                    chipsRow.addView(chipContainer)
                }
            }
        }
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

        binding.btnCompletar.setOnClickListener { completar() }


    }

    // ═════════════════════════════════════════════════════════
    //  PASO 4: AUTORIZACIÓN ONU EN LA OLT
    // ═════════════════════════════════════════════════════════
    private lateinit var layoutOltInactivo:    android.widget.LinearLayout
    private lateinit var layoutOltAutorizando: android.widget.LinearLayout
    private lateinit var layoutOltPendiente:   android.widget.LinearLayout
    private lateinit var layoutOltAutorizada:  android.widget.LinearLayout

    private fun bindOltViews() {
        layoutOltInactivo    = findViewById(R.id.layoutOltInactivo)
        layoutOltAutorizando = findViewById(R.id.layoutOltAutorizando)
        layoutOltPendiente   = findViewById(R.id.layoutOltPendiente)
        layoutOltAutorizada  = findViewById(R.id.layoutOltAutorizada)

        findViewById<MaterialButton>(R.id.btnAutorizarOlt).setOnClickListener {
            iniciarAutorizacionOlt()
        }
        findViewById<MaterialButton>(R.id.btnCambiarEquipoDesdePendiente).setOnClickListener {
            abrirCambiarEquipo()
        }
        findViewById<MaterialButton>(R.id.btnCambiarEquipoDesdeAutorizada).setOnClickListener {
            abrirCambiarEquipo()
        }
        actualizarCardOltInactiva()
    }

    /**
     * Refresca la card "Inactivo" de la OLT. Para órdenes normales muestra el código PON
     * elegido en Materiales (solo lectura). Para TRASLADO/CAMBIO_DOMICILIO muestra el
     * campo de texto manual en su lugar, ya que la ONU no está en el inventario.
     */
    private fun actualizarCardOltInactiva() {
        val sn       = serialActualParaOlt()
        val tvCodigo = findViewById<TextView>(R.id.tvOltCodigoPendiente)
        val btn      = findViewById<MaterialButton>(R.id.btnAutorizarOlt)

        if (sn.isNullOrBlank()) {
            tvCodigo.visibility = View.GONE
        } else {
            tvCodigo.text = "Se autenticará: $sn"
            tvCodigo.visibility = View.VISIBLE
        }
        btn.isEnabled = !sn.isNullOrBlank()
        btn.alpha = if (sn.isNullOrBlank()) 0.5f else 1f
    }

    /** Detecta si un item del catálogo es un producto tipo ONU/ONT (por categoría o nombre). */
    private fun esProductoOnu(item: com.enetfiber.tecnico.data.local.InventarioItemEntity): Boolean {
        return item.categoria?.lowercase()?.let { it.contains("onu") || it.contains("ont") } == true ||
                item.nombre.lowercase().let { it.contains("onu") || it.contains("ont") }
    }

    /**
     * Código PON a usar para la OLT. Para TRASLADO/CAMBIO_DOMICILIO viene del campo
     * manual (la ONU no está en el inventario del técnico); para el resto, del chip
     * elegido en Materiales.
     */
    private fun serialActualParaOlt(): String? {
        if (esTrasladoOCambioDomicilio()) {
            if (idEtOltCodigoManual == View.NO_ID) return null
            return findViewById<android.widget.EditText>(idEtOltCodigoManual)
                ?.text?.toString()?.trim()?.ifBlank { null }
        }
        return onusSeleccionadas.values.firstOrNull()?.ifBlank { null }
    }

    private fun iniciarAutorizacionOlt() {
        val sn = serialActualParaOlt()
        if (sn.isNullOrBlank()) {
            val msg = if (esTrasladoOCambioDomicilio())
                "Escribe el código PON de la ONU antes de autenticar."
            else
                "Primero selecciona el código PON de la ONU en Materiales utilizados."
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            return
        }
        // Persistir la config ONU (con el SN actual) ANTES de autorizar —
        // el backend lee el serialNumber desde ConfigOnu, no desde el request.
        lifecycleScope.launch {
            val config = ConfigOnuRequest(
                ssid             = resultSsid.ifBlank { null },
                ssidPassword     = resultPass.ifBlank { null },
                ssid5ghz         = resultSsid5.ifBlank { null },
                ssidPassword5ghz = resultPass5.ifBlank { null },
                serialNumber     = sn,
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
            vm.autorizarOlt(serialNumber = sn)
        }
    }

    private fun observarEstadoOlt() {
        // Si el backend ya tenía esta ONU autorizada/pendiente de una sesión anterior,
        // reconstruir onusSeleccionadas para que la UI (chips, card OLT) quede coherente.
        vm.serialAutorizadoInicial.observe(this) { serial ->
            if (serial.isNullOrBlank()) return@observe
            if (onusSeleccionadas.containsValue(serial)) return@observe // ya está, no duplicar

            fun intentarRestaurar() {
                val onu = inventarioVm.onus.value?.firstOrNull { it.codigoPon == serial } ?: return
                val pid = onu.productoId ?: return
                onusSeleccionadas[pid] = serial
                nombresProductos[pid] = onu.producto
                actualizarCardOltInactiva()
            }

            if (!inventarioVm.onus.value.isNullOrEmpty()) {
                intentarRestaurar()
            } else {
                // El inventario aún no cargó — observar una vez a que llegue
                inventarioVm.onus.observe(this) { intentarRestaurar() }
            }
        }

        vm.estadoOlt.observe(this) { estado ->
            findViewById<CardView>(R.id.cardAutorizarOlt)?.visibility =
                if (requiereOltDinamico()) View.VISIBLE else View.GONE

            layoutOltInactivo.visibility    = View.GONE
            layoutOltAutorizando.visibility = View.GONE
            layoutOltPendiente.visibility   = View.GONE
            layoutOltAutorizada.visibility  = View.GONE

            when (estado) {
                is EstadoOltUi.Inactivo -> {
                    layoutOltInactivo.visibility = View.VISIBLE
                    actualizarCardOltInactiva()
                    habilitarCompletar(false)
                }
                is EstadoOltUi.Autorizando -> {
                    layoutOltAutorizando.visibility = View.VISIBLE
                    habilitarCompletar(false)
                }
                is EstadoOltUi.Pendiente -> {
                    layoutOltPendiente.visibility = View.VISIBLE
                    habilitarCompletar(false)
                }
                is EstadoOltUi.Autorizada -> {
                    layoutOltAutorizada.visibility = View.VISIBLE
                    val puerto = estado.puertoCompleto ?: "—"
                    val olt    = estado.oltNombre ?: "OLT"
                    val onuId  = estado.onuId?.toString() ?: "—"
                    findViewById<TextView>(R.id.tvOltDetalle).text =
                        "$olt  ·  puerto $puerto  ·  ID $onuId"
                    habilitarCompletar(true)
                }
                else -> { /* no-op */ }
            }
        }
    }

    /** Habilita o bloquea el botón Completar. Solo aplica el bloqueo en órdenes que requieren OLT. */
    private fun habilitarCompletar(autorizada: Boolean) {
        if (!requiereOltDinamico()) {
            binding.btnCompletar.isEnabled = true
            return
        }
        binding.btnCompletar.isEnabled = autorizada
        binding.btnCompletar.alpha = if (autorizada) 1f else 0.5f
    }

    // ── Cambio de equipo (resetea selección, sin mover inventario) ──

    private fun abrirCambiarEquipo() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Cambiar equipo")
            .setMessage("Se quitará la ONU seleccionada de Materiales y deberás volver a elegir el equipo. Ningún material se descuenta de tu inventario hasta que completes el servicio.")
            .setPositiveButton("Sí, cambiar") { _, _ -> ejecutarCambioEquipo() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * Resetea la selección de ONU para volver a elegir equipo desde cero.
     * No mueve inventario (ni retiro ni consumo) — eso solo ocurre al Completar.
     * Borra la fila completa de Materiales que tenía la ONU, igual que si el
     * técnico la hubiera eliminado manualmente con el botón de la fila.
     */
    private fun ejecutarCambioEquipo() {
        // 1. Detener cualquier polling/espera en curso
        vm.cancelarEsperaOlt()

        val snActual = serialActualParaOlt()
        val productoIdActual = onusSeleccionadas.entries
            .find { it.value == snActual }?.key

        // 2. Limpiar el estado local — sin tocar inventario
        if (productoIdActual != null) {
            onusSeleccionadas.remove(productoIdActual)
            materialesGastados.removeAll { it.first == productoIdActual }
            nombresProductos.remove(productoIdActual)
        }

        // 2b. Limpiar también el código PON manual (TRASLADO/CAMBIO_DOMICILIO/RECONEXION) —
        // sin esto, el código viejo queda guardado en ese EditText y serialActualParaOlt()
        // sigue devolviéndolo aunque el técnico ya haya pasado a elegir otra ONU.
        if (idEtOltCodigoManual != View.NO_ID) {
            findViewById<android.widget.EditText>(idEtOltCodigoManual)?.setText("")
        }
        equipoOnuAveriadaRetirado = null

        actualizarContadorMateriales()

        // 3. Eliminar la fila completa de Materiales que tenía esa ONU
        val layoutLista = findViewById<android.widget.LinearLayout>(R.id.layoutListaMateriales)
        val layoutVacio = findViewById<android.widget.LinearLayout>(R.id.layoutMaterialesVacio)
        if (layoutLista != null && productoIdActual != null) {
            for (i in 0 until layoutLista.childCount) {
                val row = layoutLista.getChildAt(i) ?: continue
                val spinner = row.findViewById<android.widget.Spinner>(R.id.spinnerItem) ?: continue
                if (spinner.tag as? Int == productoIdActual) {
                    layoutLista.removeView(row)
                    break
                }
            }
            if (layoutLista.childCount == 0) {
                layoutVacio?.visibility = android.view.View.VISIBLE
                layoutLista.visibility = android.view.View.GONE
            }
        }

        // 4. Volver la card de OLT a su estado inicial (sin código PON elegido)
        actualizarCardOltInactiva()

        Toast.makeText(this, "Vuelve a seleccionar la ONU en Materiales utilizados", Toast.LENGTH_LONG).show()
    }


    // ═════════════════════════════════════════════════════════
    //  PASO 2: SELECCIONAR EQUIPO
    // ═════════════════════════════════════════════════════════
    // ═════════════════════════════════════════════════════════
    //  PASO 4
    // ═════════════════════════════════════════════════════════
    /*   private fun rellenarPaso4() {
           val ordenActual = vm.orden.value
           val esRetiroOrden = ordenActual != null && esRetiro(ordenActual.tipoOrden)

           val cardDatos   = findViewById<CardView>(R.id.cardDatosEquipo)
           val cardResumen = findViewById<CardView>(R.id.cardResumenConfig)

           if (esRetiroOrden) {
               // Retiro: ocultar cards de config ONU
               cardDatos?.visibility   = View.GONE
               cardResumen?.visibility = View.GONE
               // Configurar sección de equipos recogidos
               configurarSeccionRetiro()
           } else {
               if (esInternet()) {
                   cardDatos?.visibility = View.VISIBLE
                   val tvSsid = findViewById<TextView>(R.id.tvResumenSsid)
                   val tvSn   = findViewById<TextView>(R.id.tvResumenSn)
                   if (resultSsid.isNotBlank() || resultSn.isNotBlank()) {
                       cardResumen?.visibility = View.VISIBLE
                       tvSsid?.text = "WiFi: $resultSsid"
                       tvSn?.text   = if (resultSn.isNotBlank()) "SN: $resultSn  ·  RX: $resultRx dBm" else ""
                   }
               } else {
                   cardDatos?.visibility   = View.GONE
                   cardResumen?.visibility = View.GONE
               }

               // ── Botón agregar material (solo para no-retiros) ──
               inventarioVm.items.observe(this) { items ->
                   itemsInventarioCache = items ?: emptyList()
               }
               if (inventarioVm.items.value.isNullOrEmpty()) {
                   inventarioVm.cargarMetricas(sincronizar = true)
               }
               val btnAgregarMaterial = findViewById<View>(R.id.btnAgregarMaterial)
               btnAgregarMaterial?.setOnClickListener {
                   val itemsFrescos = itemsInventarioCache.filter { it.disponible > 0 || (it.esMedible && (it.disponibleMetros ?: 0.0) > 0) }
                   if (itemsFrescos.isEmpty()) {
                       inventarioVm.cargarMetricas(sincronizar = true)
                       Toast.makeText(this, "Cargando inventario, intenta en un momento...", Toast.LENGTH_SHORT).show()
                   } else {
                       agregarFilaMaterial(itemsFrescos)
                   }
               }
           }

           // Solo actualizar lista para instalaciones; los retiros gestionan sus cards directamente
           val tipoActual = vm.orden.value?.tipoOrden ?: ""
           if (!esRetiro(tipoActual)) {
               actualizarListaMateriales()
           } else {
               actualizarContadorMateriales()
           }
       }
   */

    /**
     * Agrega (una sola vez) el switch "ONU del cliente averiada" arriba de la card
     * de Materiales, visible solo en órdenes de RECONEXION. Al activarlo, pide el
     * código PON de la ONU vieja y refresca las cards para comportarse como una
     * instalación normal (elegir ONU del inventario en vez de código manual).
     */
    private fun agregarSwitchOnuAveriada() {
        val layoutPaso4 = findViewById<android.widget.LinearLayout>(R.id.layoutPaso4) ?: return
        if (layoutPaso4.findViewWithTag<View>("switch_onu_averiada") != null) return

        val cardMateriales = layoutPaso4.findViewById<android.view.View>(R.id.cardMateriales)
        val indexMateriales = layoutPaso4.indexOfChild(cardMateriales).coerceAtLeast(0)

        val dp = resources.displayMetrics.density
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            tag = "switch_onu_averiada"
            radius = 14 * dp
            strokeWidth = 1
            strokeColor = android.graphics.Color.parseColor("#FECACA")
            cardElevation = 0f
            setCardBackgroundColor(android.graphics.Color.parseColor("#FEF2F2"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * dp).toInt() }
        }

        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
        }
        val textos = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        val tvTitulo = TextView(this).apply {
            text = "⚠ La ONU del cliente está averiada"
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.parseColor("#991B1B"))
        }
        val tvSub = TextView(this).apply {
            text = "Actívalo para usar una ONU nueva de tu inventario y registrar la vieja como retiro"
            textSize = 11f
            setTextColor(android.graphics.Color.parseColor("#B91C1C"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (2 * dp).toInt() }
        }
        textos.addView(tvTitulo); textos.addView(tvSub)

        val switch = com.google.android.material.switchmaterial.SwitchMaterial(this).apply {
            isChecked = onuClienteAveriadaEnReconexion
        }

        row.addView(textos)
        row.addView(switch)
        card.addView(row)
        layoutPaso4.addView(card, indexMateriales)

        switch.setOnCheckedChangeListener { sw, checked ->
            val swMaterial = sw as com.google.android.material.switchmaterial.SwitchMaterial
            // Si ya se autenticó con la OLT (con el código manual o con un chip), no se
            // puede cambiar de ruta sin pasar primero por "Cambiar equipo" — mismo
            // candado que ya protege los chips de ONU normales.
            if (vm.estadoOlt.value is EstadoOltUi.Autorizada) {
                swMaterial.setOnCheckedChangeListener(null)
                swMaterial.isChecked = onuClienteAveriadaEnReconexion
                agregarSwitchOnuAveriadaListener(swMaterial)
                Toast.makeText(
                    this,
                    "Ya autenticaste una ONU con la OLT. Usa \"Cambiar equipo\" si necesitas cambiar.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnCheckedChangeListener
            }

            onuClienteAveriadaEnReconexion = checked

            if (checked) {
                mostrarDialogoOnuAveriada(swMaterial)
            } else {
                onusSeleccionadas.clear()
                materialesGastados.removeAll { par ->
                    nombresProductos[par.first]?.let { esOnu(it, null) } == true
                }
                equipoOnuAveriadaRetirado = null
            }

            // Quitar la card PON dinámica anterior si existía (se recrea según el nuevo estado)
            findViewById<android.widget.LinearLayout>(R.id.layoutPaso4)
                ?.findViewWithTag<View>("card_pon_manual")?.let {
                    (it.parent as? android.widget.LinearLayout)?.removeView(it)
                }
            idEtOltCodigoManual = View.NO_ID

            rellenarPaso4()
        }
    }

    /** Reinstala el listener del switch tras desactivarlo temporalmente para revertir su valor. */
    private fun agregarSwitchOnuAveriadaListener(switch: com.google.android.material.switchmaterial.SwitchMaterial) {
        switch.setOnCheckedChangeListener { sw, checked ->
            val swMaterial = sw as com.google.android.material.switchmaterial.SwitchMaterial
            if (vm.estadoOlt.value is EstadoOltUi.Autorizada) {
                swMaterial.setOnCheckedChangeListener(null)
                swMaterial.isChecked = onuClienteAveriadaEnReconexion
                agregarSwitchOnuAveriadaListener(swMaterial)
                Toast.makeText(
                    this,
                    "Ya autenticaste una ONU con la OLT. Usa \"Cambiar equipo\" si necesitas cambiar.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnCheckedChangeListener
            }
            onuClienteAveriadaEnReconexion = checked
            if (checked) {
                mostrarDialogoOnuAveriada(swMaterial)
            } else {
                onusSeleccionadas.clear()
                materialesGastados.removeAll { par ->
                    nombresProductos[par.first]?.let { esOnu(it, null) } == true
                }
                equipoOnuAveriadaRetirado = null
            }
            findViewById<android.widget.LinearLayout>(R.id.layoutPaso4)
                ?.findViewWithTag<View>("card_pon_manual")?.let {
                    (it.parent as? android.widget.LinearLayout)?.removeView(it)
                }
            idEtOltCodigoManual = View.NO_ID
            rellenarPaso4()
        }
    }


    /**
     * Diálogo para capturar la ONU vieja averiada: modelo (catálogo, igual que en
     * retiros normales) + código PON. Sin el productoId, el backend no puede sumar
     * correctamente el stock del modelo correspondiente cuando se aprueba el retiro.
     */
    private fun mostrarDialogoOnuAveriada(switch: com.google.android.material.switchmaterial.SwitchMaterial) {
        val dp = resources.displayMetrics.density
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((20*dp).toInt(), (8*dp).toInt(), (20*dp).toInt(), 0)
        }

        val tvLabelModelo = TextView(this).apply {
            text = "Modelo de la ONU *"
            textSize = 11f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.parseColor("#64748B"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (6*dp).toInt() }
        }
        val etBuscarModelo = android.widget.EditText(this).apply {
            hint = "Buscar modelo en catálogo..."
            textSize = 13f
            background = getDrawable(R.drawable.input_bg)
            setPadding((12*dp).toInt(), (10*dp).toInt(), (12*dp).toInt(), (10*dp).toInt())
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val dropdown = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            visibility = android.view.View.GONE
            background = getDrawable(R.drawable.input_bg)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4*dp).toInt() }
        }

        val tvLabelPon = TextView(this).apply {
            text = "Código PON *"
            textSize = 11f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.parseColor("#64748B"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (14*dp).toInt(); bottomMargin = (6*dp).toInt() }
        }
        val etPon = android.widget.EditText(this).apply {
            hint = "Ej: ZTEGC1234567"
            textSize = 13f
            background = getDrawable(R.drawable.input_bg)
            setPadding((12*dp).toInt(), (10*dp).toInt(), (12*dp).toInt(), (10*dp).toInt())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }

        var modeloSelId: Int? = null
        var modeloSelNombre: String? = null

        fun mostrarOpciones(query: String) {
            dropdown.removeAllViews()
            // Solo productos ONU/ONT del catálogo
            val candidatos = catalogoCache.filter { esOnu(it.nombre, it.categoria) }
            val resultados = if (query.isBlank()) candidatos.take(8)
            else candidatos.filter { it.nombre.contains(query, ignoreCase = true) }.take(8)

            if (resultados.isEmpty()) { dropdown.visibility = android.view.View.GONE; return }
            resultados.forEach { prod ->
                val item = TextView(this).apply {
                    text = prod.nombre
                    textSize = 13f
                    setPadding((12*dp).toInt(), (10*dp).toInt(), (12*dp).toInt(), (10*dp).toInt())
                    isClickable = true; isFocusable = true
                    setBackgroundColor(android.graphics.Color.WHITE)
                }
                item.setOnClickListener {
                    modeloSelId = prod.id
                    modeloSelNombre = prod.nombre
                    etBuscarModelo.setText(prod.nombre)
                    etBuscarModelo.clearFocus()
                    dropdown.visibility = android.view.View.GONE
                }
                dropdown.addView(item)
            }
            dropdown.visibility = android.view.View.VISIBLE
        }

        etBuscarModelo.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString() ?: ""
                if (modeloSelNombre != null && q == modeloSelNombre) return
                modeloSelId = null; modeloSelNombre = null
                mostrarOpciones(q)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        etBuscarModelo.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) mostrarOpciones(etBuscarModelo.text.toString())
        }

        container.addView(tvLabelModelo)
        container.addView(etBuscarModelo)
        container.addView(dropdown)
        container.addView(tvLabelPon)
        container.addView(etPon)

        // Asegurar catálogo cargado
        if (catalogoCache.isEmpty()) {
            inventarioVm.catalogo.observe(this) { catalogoCache = it ?: emptyList() }
            inventarioVm.cargarCatalogo()
        }

        AlertDialog.Builder(this)
            .setTitle("ONU averiada a retirar")
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("Confirmar") { _, _ ->
                val codigo = etPon.text.toString().trim().uppercase()
                if (modeloSelId == null) {
                    Toast.makeText(this, "Selecciona el modelo de la ONU", Toast.LENGTH_SHORT).show()
                    switch.isChecked = false
                    return@setPositiveButton
                }
                if (codigo.isBlank()) {
                    Toast.makeText(this, "Debes ingresar el código PON", Toast.LENGTH_SHORT).show()
                    switch.isChecked = false
                    return@setPositiveButton
                }
                equipoOnuAveriadaRetirado = com.enetfiber.tecnico.data.remote.RetiroItemRequest(
                    productoId = modeloSelId,
                    tipoEquipo = "ONU",
                    codigoPon  = codigo,
                )
                Toast.makeText(this, "Se registrará el retiro de $codigo", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar") { _, _ ->
                switch.isChecked = false
            }
            .show()
    }

    private fun rellenarPaso4() {
        val ordenActual = vm.orden.value
        val esRetiroOrden = ordenActual != null && esRetiro(ordenActual.tipoOrden)

        val cardDatos   = findViewById<CardView>(R.id.cardDatosEquipo)
        val cardResumen = findViewById<CardView>(R.id.cardResumenConfig)

        val esCambioEquipoOrden = ordenActual != null && esCambioEquipo(ordenActual.tipoOrden)

        if (esRetiroOrden) {
            cardDatos?.visibility   = View.GONE
            cardResumen?.visibility = View.GONE
            configurarSeccionRetiro()
        } else if (esCambioEquipoOrden) {
            cardDatos?.visibility   = View.GONE
            cardResumen?.visibility = View.GONE

            // Cambiar título del card de materiales a "Equipo nuevo a instalar"
            val cardMateriales = findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardMateriales)
            val tvTituloMateriales = cardMateriales
                ?.findViewById<android.widget.LinearLayout>(android.R.id.content)
                ?: run {
                    // Buscar el TextView del título por texto
                    cardMateriales?.findViewWithTag<TextView>("titulo_materiales")
                }

            // Agregar card de equipo a retirar ANTES del card de materiales
            val layoutPaso4 = findViewById<android.widget.LinearLayout>(R.id.layoutPaso4)
            val cardEquipoViejo = com.google.android.material.card.MaterialCardView(this).apply {
                id = View.generateViewId()
                radius = (14 * resources.displayMetrics.density)
                strokeWidth = 1
                strokeColor = android.graphics.Color.parseColor("#FDE68A")
                cardElevation = 0f
                setCardBackgroundColor(android.graphics.Color.parseColor("#FFFBEB"))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (12 * resources.displayMetrics.density).toInt() }
            }

            val innerLayout = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                val dp14 = (14 * resources.displayMetrics.density).toInt()
                setPadding(dp14, dp14, dp14, dp14)
            }

            // Header del card
            val headerRow = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (12 * resources.displayMetrics.density).toInt() }
            }
            val ivIcono = android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_trash)
                androidx.core.widget.ImageViewCompat.setImageTintList(this,
                    android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#D97706")))
                val sz = (16 * resources.displayMetrics.density).toInt()
                layoutParams = android.widget.LinearLayout.LayoutParams(sz, sz).apply {
                    marginEnd = (7 * resources.displayMetrics.density).toInt()
                }
            }
            val tvTitulo = TextView(this).apply {
                text = "Equipo viejo a retirar"
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(android.graphics.Color.parseColor("#92400E"))
                layoutParams = android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            headerRow.addView(ivIcono)
            headerRow.addView(tvTitulo)

            // Layout vacío
            val layoutVacioRetiro = android.widget.LinearLayout(this).apply {
                id = View.generateViewId()
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                val dp = resources.displayMetrics.density
                setPadding(0, (14*dp).toInt(), 0, (10*dp).toInt())
            }
            val tvVacioRetiro = TextView(this).apply {
                text = "Sin equipos agregados"
                textSize = 11f
                setTextColor(android.graphics.Color.parseColor("#94A3B8"))
            }
            layoutVacioRetiro.addView(tvVacioRetiro)

            // Layout lista retiro
            val layoutListaRetiro = android.widget.LinearLayout(this).apply {
                id = View.generateViewId()
                orientation = android.widget.LinearLayout.VERTICAL
                visibility = View.GONE
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (10 * resources.displayMetrics.density).toInt() }
            }

            // Botón agregar equipo viejo
            val btnAgregarEquipoViejo = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER
                setBackgroundColor(android.graphics.Color.parseColor("#FEF3C7"))
                val dp10 = (10 * resources.displayMetrics.density).toInt()
                setPadding(0, dp10, 0, dp10)
                isClickable = true; isFocusable = true
            }
            val tvBtnAgregar = TextView(this).apply {
                text = "+ Agregar equipo viejo"
                textSize = 12f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(android.graphics.Color.parseColor("#92400E"))
            }
            btnAgregarEquipoViejo.addView(tvBtnAgregar)

            innerLayout.addView(headerRow)
            innerLayout.addView(layoutVacioRetiro)
            innerLayout.addView(layoutListaRetiro)
            innerLayout.addView(btnAgregarEquipoViejo)
            cardEquipoViejo.addView(innerLayout)

            // Insertar antes del cardMateriales
            val indexCard = layoutPaso4?.indexOfChild(
                layoutPaso4.findViewById(R.id.cardMateriales)
            ) ?: 0
            layoutPaso4?.addView(cardEquipoViejo, indexCard)

            // Configurar el catálogo para las cards de retiro
            inventarioVm.catalogo.observe(this) { catalogo ->
                catalogoCache = catalogo ?: emptyList()
            }
            inventarioVm.cargarCatalogo()

            btnAgregarEquipoViejo.setOnClickListener {
                agregarCardEquipoRetiro(layoutListaRetiro, layoutVacioRetiro)
            }

            // Sección de materiales (equipo nuevo) — comportamiento normal
            inventarioVm.items.observe(this) { items ->

                itemsInventarioCache = items ?: emptyList()
            }
            if (inventarioVm.items.value.isNullOrEmpty()) {
                inventarioVm.cargarMetricas(sincronizar = true)
            }
            val btnAgregarMaterial = findViewById<View>(R.id.btnAgregarMaterial)
            btnAgregarMaterial?.setOnClickListener {
                val yaSeleccionados = mutableSetOf<Int>()
                val layoutLista = findViewById<android.widget.LinearLayout>(R.id.layoutListaMateriales)
                if (layoutLista != null) {
                    for (i in 0 until layoutLista.childCount) {
                        val row = layoutLista.getChildAt(i)
                        val spinner = row?.findViewById<android.widget.Spinner>(R.id.spinnerItem)
                        val productoId = spinner?.tag as? Int
                        if (productoId != null) yaSeleccionados.add(productoId)
                    }
                }
                val itemsFrescos = itemsInventarioCache.filter {
                    (it.disponible > 0 || (it.esMedible && (it.disponibleMetros ?: 0.0) > 0))
                            && it.productoId !in yaSeleccionados
                }
                if (itemsFrescos.isEmpty()) {
                    inventarioVm.cargarMetricas(sincronizar = true)
                    Toast.makeText(this, "Sin más ítems disponibles", Toast.LENGTH_SHORT).show()
                } else {
                    agregarFilaMaterial(itemsFrescos)
                }
            }
        } else {
            cardDatos?.visibility   = View.GONE
            cardResumen?.visibility = View.GONE

            // Botón "Configurar ONU" — dentro de cardWan, visible solo para Internet
            val btnConfigOnu = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnConfigurarOnu)
            if (esInternet()) {
                btnConfigOnu?.visibility = View.VISIBLE
                btnConfigOnu?.setOnClickListener { abrirConfigOnu() }
                actualizarBadgeConfigOnu()
                // Si no hay WAN asignada, igual mostrar la card pero sin datos WAN
                val cardWan = findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardWan)
                if (cardWan?.visibility != View.VISIBLE) {
                    cardWan?.visibility = View.VISIBLE
                }
            } else {
                btnConfigOnu?.visibility = View.GONE
            }

            // RECONEXION: switch "ONU del cliente averiada" — visible solo en este tipo.
            if (ordenActual != null && esReconexion(ordenActual.tipoOrden)) {
                agregarSwitchOnuAveriada()
            }

            // Para TRASLADO/CAMBIO_DOMICILIO (o RECONEXION sin marcar averiada): agregar
            // card de ingreso de código PON debajo de la card de Materiales — la ONU ya
            // está en casa del cliente, su código se escribe aquí y se muestra en la
            // card OLT en modo solo lectura.
            if (esTrasladoOCambioDomicilio() &&
                findViewById<android.widget.LinearLayout>(R.id.layoutPaso4)
                    ?.findViewWithTag<View>("card_pon_manual") == null) {
                val layoutPaso4 = findViewById<android.widget.LinearLayout>(R.id.layoutPaso4)
                val cardMateriales = layoutPaso4?.findViewById<android.view.View>(R.id.cardMateriales)
                val indexMateriales = layoutPaso4?.indexOfChild(cardMateriales) ?: -1

                val dp = resources.displayMetrics.density
                val cardPon = com.google.android.material.card.MaterialCardView(this).apply {
                    id = View.generateViewId()
                    tag = "card_pon_manual"
                    radius = (14 * dp)
                    strokeWidth = 1
                    strokeColor = android.graphics.Color.parseColor("#C7D2FE")
                    cardElevation = 0f
                    setCardBackgroundColor(android.graphics.Color.parseColor("#EEF2FF"))
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (12 * dp).toInt() }
                }
                val innerPon = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding((14*dp).toInt(), (14*dp).toInt(), (14*dp).toInt(), (14*dp).toInt())
                }
                val tvPonHeader = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (10*dp).toInt() }
                }
                val tvPonIcon = TextView(this).apply {
                    text = "◈  "; textSize = 14f
                    setTextColor(android.graphics.Color.parseColor("#4F46E5"))
                }
                val tvPonTitle = TextView(this).apply {
                    text = "Ingresa el código PON de la ONU a autenticar"
                    textSize = 13f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(android.graphics.Color.parseColor("#1E1B4B"))
                    layoutParams = android.widget.LinearLayout.LayoutParams(0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                tvPonHeader.addView(tvPonIcon); tvPonHeader.addView(tvPonTitle)

                val etPon = android.widget.EditText(this).apply {
                    idEtOltCodigoManual = View.generateViewId()
                    id = idEtOltCodigoManual
                    hint = "Ej: ZTEGC1234567"; textSize = 13f
                    setTextColor(android.graphics.Color.parseColor("#0F172A"))
                    setHintTextColor(android.graphics.Color.parseColor("#94A3B8"))
                    background = getDrawable(R.drawable.input_bg)
                    setPadding((12*dp).toInt(), (10*dp).toInt(), (12*dp).toInt(), (10*dp).toInt())
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                            android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                    importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (8*dp).toInt() }
                }
                val tvConfirm = TextView(this).apply {
                    idTvOltCodigoManualConfirmacion = View.generateViewId()
                    id = idTvOltCodigoManualConfirmacion
                    textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE
                    setTextColor(android.graphics.Color.parseColor("#1E3A8A"))
                    visibility = View.GONE
                }
                etPon.addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) {
                        val codigo = s?.toString()?.trim() ?: ""
                        if (codigo.isBlank()) {
                            tvConfirm.visibility = View.GONE
                        } else {
                            tvConfirm.text = "Se autenticará: $codigo"
                            tvConfirm.visibility = View.VISIBLE
                        }
                        actualizarCardOltInactiva()
                    }
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                })

                innerPon.addView(tvPonHeader)
                innerPon.addView(etPon)
                innerPon.addView(tvConfirm)
                cardPon.addView(innerPon)

                if (indexMateriales >= 0) {
                    layoutPaso4?.addView(cardPon, indexMateriales + 1)
                } else {
                    layoutPaso4?.addView(cardPon)
                }

                // El campo tiene el mismo id dinámico guardado en idEtOltCodigoManual, así que
                // serialActualParaOlt() lo encuentra correctamente por ese ID.
            }

            // ✅ FUERA del if(esInternet()) — aplica a cable, duo, internet
            inventarioVm.items.observe(this) { items ->
                itemsInventarioCache = items ?: emptyList()
            }
            if (inventarioVm.items.value.isNullOrEmpty()) {
                inventarioVm.cargarMetricas(sincronizar = true)
            }
            val btnAgregarMaterial = findViewById<View>(R.id.btnAgregarMaterial)
            btnAgregarMaterial?.setOnClickListener {
                val layoutLista = findViewById<android.widget.LinearLayout>(R.id.layoutListaMateriales)
                val yaSeleccionados = mutableSetOf<Int>()
                if (layoutLista != null) {
                    for (i in 0 until layoutLista.childCount) {
                        val row     = layoutLista.getChildAt(i)
                        val spinner = row?.findViewById<android.widget.Spinner>(R.id.spinnerItem)
                        val productoId = spinner?.tag as? Int
                        if (productoId != null) {
                            yaSeleccionados.add(productoId)
                        }
                    }
                }
                val itemsFrescos = itemsInventarioCache.filter {
                    (it.disponible > 0 || (it.esMedible && (it.disponibleMetros ?: 0.0) > 0))
                            && it.productoId !in yaSeleccionados
                }
                if (itemsFrescos.isEmpty()) {
                    inventarioVm.cargarMetricas(sincronizar = true)
                    Toast.makeText(this, "Sin más ítems disponibles", Toast.LENGTH_SHORT).show()
                } else {
                    agregarFilaMaterial(itemsFrescos)
                }
            }
        }

        val tipoActual = vm.orden.value?.tipoOrden ?: ""
        if (!esRetiro(tipoActual)) {
            actualizarListaMateriales()
        } else {
            actualizarContadorMateriales()
        }

        // Mostrar/ocultar la card de autorización OLT según el tipo de orden actual
        findViewById<CardView>(R.id.cardAutorizarOlt)?.visibility =
            if (requiereOltDinamico()) View.VISIBLE else View.GONE
        habilitarCompletar(vm.estadoOlt.value is EstadoOltUi.Autorizada)
    }
    // ── Paso 4: materiales gastados ───────────────────────────
    private val inventarioVm: InventarioViewModel by viewModels()
    // Cache local de items — se actualiza cuando el LiveData emite
    private var itemsInventarioCache: List<com.enetfiber.tecnico.data.local.InventarioItemEntity> = emptyList()
    // Lista de pares (productoId, cantidad) que el técnico registró
    private val materialesGastados = mutableListOf<Pair<Int, Double>>()
    // Mapa productoId → codigoPon seleccionado (para ONUs)
    private val onusSeleccionadas = mutableMapOf<Int, String>()
    // Mapa productoId → true si el técnico eligió usar equipo reciclado (no-ONU)
    private val usarRecicladoPorProducto = mutableMapOf<Int, Boolean>()
    // Mapa id→nombre para mostrar offline
    private val nombresProductos   = mutableMapOf<Int, String>()

    // Retiros: una entrada por cada equipo individual (una card en la UI)
    private val equiposRetirados = mutableListOf<com.enetfiber.tecnico.data.remote.RetiroItemRequest>()
    private val nombresEquipos   = mutableMapOf<Int, String>()  // productoId → nombre para display

    private fun esRetiro(tipoOrden: String) = TipoOrden.esRetiroDinamico(tipoOrden)

    private fun esCambioEquipo(tipoOrden: String) = TipoOrden.esCambioEquipoDinamico(tipoOrden)

    // Cache del catálogo global para retiros
    private var catalogoCache: List<com.enetfiber.tecnico.data.local.CatalogoProductoEntity> = emptyList()

    /** Detecta si un producto es ONU por nombre o categoría */
    private fun esOnu(nombre: String, categoria: String?): Boolean {
        val texto = "$nombre ${categoria ?: ""}".lowercase()
        return texto.contains("onu") || texto.contains("ont")
    }

    private fun configurarSeccionRetiro() {
        val btnAgregar = findViewById<android.widget.LinearLayout>(R.id.btnAgregarMaterial)
        val tvBtnTexto = btnAgregar?.getChildAt(1) as? TextView
        tvBtnTexto?.text = "+ Agregar equipos recogidos "

        // Cargar catálogo offline-first
        inventarioVm.catalogo.observe(this) { catalogo ->
            catalogoCache = catalogo ?: emptyList()
        }
        inventarioVm.cargarCatalogo()

        btnAgregar?.setOnClickListener {
            agregarCardEquipoRetiro()
        }
    }

    private val cardsRetiro = mutableListOf<android.view.View>()


    private fun agregarCardEquipoRetiro(
        layoutLista: android.widget.LinearLayout? = null,
        layoutVacio: android.widget.LinearLayout? = null
    ) {
        val lista = layoutLista ?: findViewById<android.widget.LinearLayout>(R.id.layoutListaMateriales) ?: return
        val vacio = layoutVacio ?: findViewById<android.widget.LinearLayout>(R.id.layoutMaterialesVacio) ?: return

        vacio.visibility = android.view.View.GONE
        lista.visibility = android.view.View.VISIBLE

        val numEquipo = cardsRetiro.size + 1
        val dp = resources.displayMetrics.density

        // ── Card contenedor ────────────────────────────────────
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            radius = 12f * dp
            strokeWidth = 1
            strokeColor = android.graphics.Color.parseColor("#E2E8F0")
            cardElevation = 0f
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * dp).toInt() }
        }

        val inner = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((14*dp).toInt(), (12*dp).toInt(), (14*dp).toInt(), (14*dp).toInt())
        }

        // Header EQUIPO #N + eliminar
        val headerRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10*dp).toInt() }
        }
        val tvNumero = TextView(this).apply {
            text = "EQUIPO #$numEquipo"
            textSize = 10f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.parseColor("#94A3B8"))
            letterSpacing = 0.06f
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnElim = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_trash)
            setPadding(8, 8, 8, 8)
            background = getDrawable(R.drawable.bg_btn_delete)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                (32*dp).toInt(), (32*dp).toInt())
        }
        headerRow.addView(tvNumero); headerRow.addView(btnElim)

        // ── Buscador de catálogo ───────────────────────────────
        val tvLabel = TextView(this).apply {
            text = "Producto del catálogo *"
            textSize = 11f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.parseColor("#64748B"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (6*dp).toInt() }
        }
        val searchContainer = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.input_bg)
            setPadding((10*dp).toInt(), 0, (10*dp).toInt(), 0)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (44*dp).toInt())
        }
        val ivLupa = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            val sz = (16*dp).toInt()
            layoutParams = android.widget.LinearLayout.LayoutParams(sz, sz).apply {
                marginEnd = (8*dp).toInt() }
            androidx.core.widget.ImageViewCompat.setImageTintList(this,
                android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#94A3B8")))
        }
        val etBuscar = android.widget.EditText(this).apply {
            hint = "Buscar en catálogo..."; textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#0F172A"))
            setHintTextColor(android.graphics.Color.parseColor("#94A3B8"))
            background = null; importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        searchContainer.addView(ivLupa); searchContainer.addView(etBuscar)

        // Dropdown de resultados
        val dropdown = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            visibility = android.view.View.GONE
            background = getDrawable(R.drawable.input_bg)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4*dp).toInt() }
        }

        // ── Campo PON-SN (oculto hasta seleccionar ONU) ────────
        val ponSection = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            visibility = android.view.View.GONE
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12*dp).toInt() }
        }
        val tvPonLabel = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (6*dp).toInt() }
        }
        val tvPonIcon = TextView(this).apply {
            text = "◈ "; textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#7C3AED"))
        }
        val tvPonTitle = TextView(this).apply {
            text = "Código PON-SN *"; textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.parseColor("#64748B"))
        }
        tvPonLabel.addView(tvPonIcon); tvPonLabel.addView(tvPonTitle)

        val etPon = android.widget.EditText(this).apply {
            hint = "Ej: ZTEGC1234567"; textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#0F172A"))
            setHintTextColor(android.graphics.Color.parseColor("#94A3B8"))
            background = getDrawable(R.drawable.input_bg)
            setPadding((12*dp).toInt(), (10*dp).toInt(), (12*dp).toInt(), (10*dp).toInt())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val tvPonHint = TextView(this).apply {
            text = "Código de la etiqueta trasera de la ONU"
            textSize = 10f; setTextColor(android.graphics.Color.parseColor("#94A3B8"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4*dp).toInt() }
        }
        ponSection.addView(tvPonLabel); ponSection.addView(etPon); ponSection.addView(tvPonHint)

        // Estado del producto seleccionado
        var productoSelId: Int? = null
        var productoSelNombre: String? = null
        var productoSelCat: String? = null
        var indiceEnLista: Int = -1

        fun mostrarDropdown(query: String) {
            dropdown.removeAllViews()
            val resultados = if (query.isBlank()) catalogoCache.take(8)
            else catalogoCache.filter {
                it.nombre.contains(query, ignoreCase = true) ||
                        it.codigo?.contains(query, ignoreCase = true) == true ||
                        it.categoria?.contains(query, ignoreCase = true) == true
            }.take(8)

            if (resultados.isEmpty()) { dropdown.visibility = android.view.View.GONE; return }

            resultados.forEach { prod ->
                val item = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding((12*dp).toInt(), (10*dp).toInt(), (12*dp).toInt(), (10*dp).toInt())
                    isClickable = true; isFocusable = true
                    background = android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE)
                }
                val tn = TextView(this).apply {
                    text = prod.nombre; textSize = 13f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(android.graphics.Color.parseColor("#0F172A"))
                }
                val ts = TextView(this).apply {
                    text = listOfNotNull(prod.codigo, prod.categoria).joinToString(" · ")
                    textSize = 11f; setTextColor(android.graphics.Color.parseColor("#94A3B8"))
                    visibility = if (prod.codigo.isNullOrBlank() && prod.categoria.isNullOrBlank())
                        android.view.View.GONE else android.view.View.VISIBLE
                }
                item.addView(tn); item.addView(ts)
                val div = android.view.View(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).apply { marginStart = (12*dp).toInt() }
                    setBackgroundColor(android.graphics.Color.parseColor("#F1F5F9"))
                }
                item.setOnClickListener {
                    productoSelId     = prod.id
                    productoSelNombre = prod.nombre
                    productoSelCat    = prod.categoria
                    etBuscar.setText(prod.nombre)
                    etBuscar.clearFocus()
                    dropdown.visibility = android.view.View.GONE

                    // Mostrar PON-SN solo para ONUs
                    val esOnu = esOnu(prod.nombre, prod.categoria)
                    ponSection.visibility = if (esOnu) android.view.View.VISIBLE else android.view.View.GONE

                    // Guardar o actualizar en la lista
                    val nuevoItem = com.enetfiber.tecnico.data.remote.RetiroItemRequest(
                        productoId = prod.id,
                        tipoEquipo = if (esOnu) "ONU" else "EQUIPO",
                        codigoPon  = if (esOnu) etPon.text.toString().trim().ifBlank { null } else null,
                    )
                    if (indiceEnLista >= 0 && indiceEnLista < equiposRetirados.size) {
                        equiposRetirados[indiceEnLista] = nuevoItem
                    } else {
                        equiposRetirados.add(nuevoItem)
                        indiceEnLista = equiposRetirados.size - 1
                    }
                    if (prod.id != null) nombresEquipos[prod.id] = prod.nombre
                    actualizarContadorMateriales()
                }
                dropdown.addView(item); dropdown.addView(div)
            }
            dropdown.visibility = android.view.View.VISIBLE
        }

        // Actualizar codigoPon cuando el técnico escribe
        etPon.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                if (indiceEnLista >= 0 && indiceEnLista < equiposRetirados.size) {
                    val actual = equiposRetirados[indiceEnLista]
                    equiposRetirados[indiceEnLista] = actual.copy(
                        codigoPon = s?.toString()?.trim()?.uppercase()?.ifBlank { null }
                    )
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        etBuscar.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString() ?: ""
                if (productoSelNombre != null && q == productoSelNombre) return
                productoSelId = null; productoSelNombre = null
                mostrarDropdown(q)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        etBuscar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && catalogoCache.isNotEmpty()) mostrarDropdown(etBuscar.text.toString())
        }

        btnElim.setOnClickListener {
            if (indiceEnLista >= 0 && indiceEnLista < equiposRetirados.size) {
                equiposRetirados.removeAt(indiceEnLista)
                productoSelId?.let { nombresEquipos.remove(it) }
            }
            cardsRetiro.remove(card)
            lista.removeView(card)
            if (lista.childCount == 0) {
                vacio.visibility = android.view.View.VISIBLE
                lista.visibility = android.view.View.GONE
            }
            actualizarContadorMateriales()
        }

        inner.addView(headerRow); inner.addView(tvLabel)
        inner.addView(searchContainer); inner.addView(dropdown)
        inner.addView(ponSection)
        card.addView(inner)
        cardsRetiro.add(card)
        lista.addView(card)

        etBuscar.requestFocus()
        if (catalogoCache.isNotEmpty()) mostrarDropdown("")
    }

    /** Agrega una fila inline de material (Spinner + Cantidad + Eliminar) */
    private fun mostrarDialogMateriales() {
        val items = itemsInventarioCache.filter { it.disponible > 0 || (it.esMedible && (it.disponibleMetros ?: 0.0) > 0) }.ifEmpty { inventarioVm.items.value?.filter { it.disponible > 0 } ?: emptyList() }
        if (items.isNullOrEmpty()) {
            Toast.makeText(this, "No tienes items disponibles en tu inventario", Toast.LENGTH_SHORT).show()
            return
        }
        agregarFilaMaterial(items)
    }

    private fun agregarFilaMaterial(items: List<com.enetfiber.tecnico.data.local.InventarioItemEntity>) {
        val layoutLista = findViewById<android.widget.LinearLayout>(R.id.layoutListaMateriales) ?: return
        val layoutVacio = findViewById<android.widget.LinearLayout>(R.id.layoutMaterialesVacio) ?: return

        // Mostrar lista y ocultar vacío
        layoutVacio.visibility = android.view.View.GONE
        layoutLista.visibility = android.view.View.VISIBLE

        val rowView = layoutInflater.inflate(R.layout.item_material_row, layoutLista, false)
        val spinner  = rowView.findViewById<android.widget.Spinner>(R.id.spinnerItem)
        val etCant   = rowView.findViewById<android.widget.EditText>(R.id.etCantidad)
        val tvHint   = rowView.findViewById<android.widget.TextView>(R.id.tvHint)
        val btnElim  = rowView.findViewById<android.widget.ImageView>(R.id.btnEliminar)



        // Si ya hay una ONU seleccionada en otra fila, no ofrecer más productos ONU/ONT
        // en este spinner — solo puede haber un código PON activo para autorizar en la OLT.
        // Para TRASLADO/CAMBIO_DOMICILIO, los productos ONU/ONT se excluyen siempre: esa
        // ONU ya está instalada en casa del cliente, no está en el inventario del técnico,
        // y su código se ingresa a mano en la card PON que aparece debajo de Materiales.
        val itemsDisponibles = if (esTrasladoOCambioDomicilio()) {
            items.filter { !esProductoOnu(it) }
        } else if (onusSeleccionadas.isNotEmpty()) {
            items.filter { !esProductoOnu(it) }
        } else {
            items
        }

        // Llenar spinner con "Nombre — disp: X unidad" (o metros si es medible)
        val opciones = listOf("Seleccionar ítem...") +
                itemsDisponibles.map { item ->
                    if (item.esMedible && item.metrosPorUnidad != null && item.disponibleMetros != null) {
                        "${item.nombre} — disp: ${item.disponibleMetros.toInt()} m"
                    } else {
                        "${item.nombre} — disp: ${item.disponible.toInt()} ${item.unidad}"
                    }
                }
        val adapter = object : android.widget.ArrayAdapter<String>(
            this, R.layout.item_spinner_selected, opciones
        ) {
            override fun getView(pos: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val v = (convertView as? android.widget.TextView)
                    ?: layoutInflater.inflate(R.layout.item_spinner_selected, parent, false) as android.widget.TextView
                v.text = opciones[pos]
                v.setTextColor(if (pos == 0) android.graphics.Color.parseColor("#94A3B8")
                else android.graphics.Color.parseColor("#0F172A"))
                return v
            }
            override fun getDropDownView(pos: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val v = (convertView as? android.widget.TextView)
                    ?: layoutInflater.inflate(R.layout.item_spinner_dropdown, parent, false) as android.widget.TextView
                v.text = opciones[pos]
                v.setTextColor(if (pos == 0) android.graphics.Color.parseColor("#94A3B8")
                else android.graphics.Color.parseColor("#0F172A"))
                return v
            }
        }
        spinner.adapter = adapter

        // Cuando selecciona un item, mostrar hint con unidad y máx
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, v: android.view.View?, pos: Int, id: Long) {

                if (pos == 0) { tvHint.visibility = android.view.View.GONE; return }
                val item = itemsDisponibles[pos - 1]
                spinner.tag = item.productoId

// ── Sección ONU: mostrar chips de códigos PON ─────────────
                val dp = resources.displayMetrics.density
                val esOnuProducto = esProductoOnu(item)

// Buscar o crear la onuSection dentro del rowView
                /* var onuSection = rowView.findViewWithTag<android.widget.LinearLayout>("onu_section_${item.productoId}")
                 if (onuSection == null) {
                     onuSection = android.widget.LinearLayout(this@InstalacionActivity).apply {
                         tag = "onu_section_${item.productoId}"
                         orientation = android.widget.LinearLayout.VERTICAL
                         visibility = android.view.View.GONE
                         layoutParams = android.widget.LinearLayout.LayoutParams(
                             android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                             android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                         ).apply { topMargin = (10 * dp).toInt() }
                     }
                     (rowView as? android.widget.LinearLayout)?.addView(onuSection)
                 }*/

                // Buscar o crear la onuSection dentro del rowView — TAG FIJO
                var onuSection = rowView.findViewWithTag<android.widget.LinearLayout>("onu_section")
                if (onuSection == null) {
                    onuSection = android.widget.LinearLayout(this@InstalacionActivity).apply {
                        tag = "onu_section"   // ← sin productoId
                        orientation = android.widget.LinearLayout.VERTICAL
                        visibility = android.view.View.GONE
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = (10 * dp).toInt() }
                    }
                    (rowView as? android.widget.LinearLayout)?.addView(onuSection)
                }

                if (esOnuProducto) {
                    // Ocultar cantidad — no aplica para ONUs
                    etCant.visibility = android.view.View.GONE
                    tvHint.visibility = android.view.View.GONE
                    onuSection.visibility = android.view.View.VISIBLE
                    onuSection.removeAllViews()

                    // Label
                    val tvLabel = android.widget.TextView(this@InstalacionActivity).apply {
                        text = "◈  SELECCIONÁ LA ONU A USAR"
                        textSize = 10f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setTextColor(android.graphics.Color.parseColor("#7C3AED"))
                        letterSpacing = 0.06f
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = (8 * dp).toInt() }
                    }
                    onuSection.addView(tvLabel)

                    // Chips container
                    val chipsRow = android.widget.LinearLayout(this@InstalacionActivity).apply {
                        //tag = "chips_row_${item.productoId}"  // ← AGREGAR esta línea
                        tag = "chips_row"   // ← sin productoId
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    }
                    onuSection.addView(chipsRow)

                    fun poblarChips(onusList: List<com.enetfiber.tecnico.data.local.InventarioOnuEntity>) {
                        val onusDelProducto = onusList.filter { it.productoId == item.productoId }
                        chipsRow.removeAllViews()

                        // Códigos PON reciclados (en_mano) para este producto — para distinguir
                        // visualmente en el chip cuál ONU viene de un retiro/devolución vs.
                        // una entregada nueva desde almacén. Sin esto, dos chips idénticos
                        // (uno reciclado, otro nuevo) son indistinguibles para el técnico.
                        val codigosReciclados = inventarioVm.recojos.value
                            ?.filter { it.productoId == item.productoId && it.estado == "en_mano" }
                            ?.mapNotNull { it.codigoPon }
                            ?.toSet() ?: emptySet()

                        if (onusDelProducto.isEmpty()) {
                            val tvVacio = android.widget.TextView(this@InstalacionActivity).apply {
                                text = "Sin ONUs disponibles"
                                textSize = 12f
                                setTextColor(android.graphics.Color.parseColor("#94A3B8"))
                            }
                            chipsRow.addView(tvVacio)
                        } else {
                            onusDelProducto.forEach { onu ->
                                val yaBloqueado = vm.estadoOlt.value is EstadoOltUi.Autorizada
                                val esElSeleccionado = onusSeleccionadas[item.productoId] == onu.codigoPon
                                val esReciclado = onu.codigoPon != null && codigosReciclados.contains(onu.codigoPon)

                                val chipContainer = android.widget.LinearLayout(this@InstalacionActivity).apply {
                                    orientation = android.widget.LinearLayout.VERTICAL
                                    layoutParams = android.widget.LinearLayout.LayoutParams(
                                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                                    ).apply { marginEnd = (8 * dp).toInt() }
                                }

                                val chip = android.widget.TextView(this@InstalacionActivity).apply {
                                    text = onu.codigoPon ?: "SIN CÓDIGO"
                                    textSize = 12f
                                    typeface = android.graphics.Typeface.MONOSPACE
                                    if (esElSeleccionado) {
                                        setTextColor(android.graphics.Color.WHITE)
                                        setBackgroundColor(android.graphics.Color.parseColor("#7C3AED"))
                                    } else {
                                        setTextColor(android.graphics.Color.parseColor("#1E3A5F"))
                                        background = getDrawable(R.drawable.input_bg)
                                    }
                                    alpha = if (yaBloqueado && !esElSeleccionado) 0.4f else 1f
                                    setPadding(
                                        (10 * dp).toInt(), (6 * dp).toInt(),
                                        (10 * dp).toInt(), (6 * dp).toInt()
                                    )
                                    isClickable = true; isFocusable = true
                                }

                                chipContainer.addView(chip)

                                if (esReciclado) {
                                    val tvBadge = android.widget.TextView(this@InstalacionActivity).apply {
                                        text = "♻ Reciclado"
                                        textSize = 9f
                                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                                        setTextColor(android.graphics.Color.parseColor("#15803D"))
                                        gravity = android.view.Gravity.CENTER
                                        layoutParams = android.widget.LinearLayout.LayoutParams(
                                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                                        ).apply { topMargin = (2 * dp).toInt() }
                                    }
                                    chipContainer.addView(tvBadge)
                                }

                                chip.setOnClickListener {
                                    // Si ya se autenticó con OLT, no se puede cambiar el código PON
                                    // libremente — hay que usar "Cambiar equipo" en la card de OLT.
                                    if (vm.estadoOlt.value is EstadoOltUi.Autorizada) {
                                        Toast.makeText(
                                            this@InstalacionActivity,
                                            "Ya autenticaste esta ONU con la OLT. Usa \"Cambiar equipo\" si necesitas usar otra.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        return@setOnClickListener
                                    }
                                    for (i in 0 until chipsRow.childCount) {
                                        val c = chipsRow.getChildAt(i) as? android.widget.TextView
                                        c?.setTextColor(android.graphics.Color.parseColor("#1E3A5F"))
                                        c?.background = getDrawable(R.drawable.input_bg)
                                    }
                                    chip.setTextColor(android.graphics.Color.WHITE)
                                    chip.setBackgroundColor(android.graphics.Color.parseColor("#7C3AED"))
                                    onu.codigoPon?.let { ponCode ->
                                        onusSeleccionadas[item.productoId] = ponCode
                                    }
                                    val idx = materialesGastados.indexOfFirst { it.first == item.productoId }
                                    if (idx >= 0) materialesGastados[idx] = Pair(item.productoId, 1.0)
                                    else {
                                        materialesGastados.add(Pair(item.productoId, 1.0))
                                        nombresProductos[item.productoId] = item.nombre
                                    }
                                    actualizarContadorMateriales()
                                    actualizarCardOltInactiva()
                                }
                                chipsRow.addView(chipContainer)
                            }
                        }
                    }

// Poblar inmediatamente con cache actual
                    poblarChips(inventarioVm.onus.value ?: emptyList())


                } else {
                    // Producto normal: mostrar cantidad, ocultar ONU
                    etCant.visibility = android.view.View.VISIBLE
                    tvHint.visibility = android.view.View.VISIBLE
                    onuSection.visibility = android.view.View.GONE
                    onusSeleccionadas.remove(item.productoId)
                }





                val maxCant = if (item.esMedible && item.metrosPorUnidad != null && item.disponibleMetros != null) {
                    tvHint.text = "m  ·  máx ${item.disponibleMetros.toInt()} m"
                    etCant.hint = item.disponibleMetros.toInt().toString()
                    item.disponibleMetros.toInt()
                } else {
                    tvHint.text = "${item.unidad}  ·  máx ${item.disponible.toInt()}"
                    etCant.hint = item.disponible.toInt().toString()
                    item.disponible.toInt()
                }
                tvHint.visibility = android.view.View.VISIBLE
                // Badge ♻ a nivel de fila: solo tiene sentido para productos NO-ONU
                // (cantidad simple, sin chips). Para ONUs, el badge correcto vive en
                // cada chip individual (ver poblarChips) porque puede haber códigos
                // reciclados y nuevos mezclados bajo el mismo producto.
                val tvReciclado = rowView.findViewById<android.widget.TextView>(R.id.tvReciclado)
                // Checkbox "usar reciclado" — se crea dinámicamente debajo del badge.
                // Buscar o crear con tag fijo para no duplicar al repintar la fila.
                var cbReciclado = rowView.findViewWithTag<android.widget.CheckBox>("cb_usar_reciclado")
                val cantReciclados = if (!esOnuProducto) {
                    inventarioVm.recojos.value?.count {
                        it.productoId == item.productoId && it.estado == "en_mano"
                    } ?: 0
                } else 0

                if (tvReciclado != null) {
                    if (cantReciclados > 0) {
                        tvReciclado.visibility = android.view.View.VISIBLE
                        tvReciclado.text = if (cantReciclados >= item.disponible.toInt()) {
                            "♻ Reciclado"
                        } else {
                            "♻ $cantReciclados de ${item.disponible.toInt()} son reciclados"
                        }

                        // Crear el checkbox si no existe, justo después del badge
                        if (cbReciclado == null) {
                            cbReciclado = android.widget.CheckBox(this@InstalacionActivity).apply {
                                tag = "cb_usar_reciclado"
                                text = "Usar equipo reciclado"
                                textSize = 12f
                                setTextColor(android.graphics.Color.parseColor("#15803D"))
                                isChecked = true   // por defecto prioriza gastar lo reciclado
                            }
                            (tvReciclado.parent as? android.widget.LinearLayout)?.addView(
                                cbReciclado, (tvReciclado.parent as android.widget.LinearLayout).indexOfChild(tvReciclado) + 1
                            )
                        }
                        cbReciclado?.visibility = android.view.View.VISIBLE
                    } else {
                        tvReciclado.visibility = android.view.View.GONE
                        cbReciclado?.visibility = android.view.View.GONE
                    }
                }
                // Validar y sincronizar cuando cambia la cantidad
                etCant.addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) {
                        val v = s?.toString()?.toIntOrNull() ?: 0
                        if (v > maxCant) {
                            etCant.removeTextChangedListener(this)
                            etCant.setText(maxCant.toString())
                            etCant.setSelection(etCant.text.length)
                            etCant.error = "Máx: $maxCant"
                            etCant.addTextChangedListener(this)
                        }
                        sincronizarFilas(layoutLista, items)
                    }
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                })
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }

        // Eliminar fila
        btnElim.setOnClickListener {
            val productoIdEliminado = spinner.tag as? Int
            if (productoIdEliminado != null) {
                onusSeleccionadas.remove(productoIdEliminado)
            }
            layoutLista.removeView(rowView)
            sincronizarFilas(layoutLista, items)
            if (layoutLista.childCount == 0) {
                layoutVacio.visibility = android.view.View.VISIBLE
                layoutLista.visibility = android.view.View.GONE
            }
            actualizarContadorMateriales()
            actualizarCardOltInactiva()
        }

        layoutLista.addView(rowView)
        actualizarContadorMateriales()
    }

    /** Lee todas las filas inline y actualiza materialesGastados */
    private fun sincronizarFilas(
        layoutLista: android.widget.LinearLayout,
        items: List<com.enetfiber.tecnico.data.local.InventarioItemEntity>
    ) {
        materialesGastados.clear()
        for (i in 0 until layoutLista.childCount) {
            val row     = layoutLista.getChildAt(i)
            val spinner = row.findViewById<android.widget.Spinner>(R.id.spinnerItem) ?: continue
            val etCant  = row.findViewById<android.widget.EditText>(R.id.etCantidad) ?: continue

            // ── Usar tag en lugar de buscar por posición en items ──
            val productoId = spinner.tag as? Int ?: continue
            val item = itemsInventarioCache.firstOrNull { it.productoId == productoId } ?: continue

            // Las filas de ONU no usan etCant (está oculto) — su "cantidad" es fija (1) y
            // su valor real es el código PON en onusSeleccionadas. Si se las trata igual que
            // a un material normal, etCant.text está vacío y la fila se descarta silenciosamente
            // (bug real: una ONU se perdía de materialesGastados al agregar otro material normal).
            if (esProductoOnu(item)) {
                if (onusSeleccionadas.containsKey(productoId)) {
                    materialesGastados.add(Pair(productoId, 1.0))
                    nombresProductos[productoId] = item.nombre
                }
                continue
            }

            val cant = etCant.text.toString().toDoubleOrNull() ?: 0.0
            if (cant <= 0) continue

            val cbReciclado = row.findViewWithTag<android.widget.CheckBox>("cb_usar_reciclado")
            usarRecicladoPorProducto[productoId] = cbReciclado?.isChecked == true

            val cantFinal = if (item.esMedible && item.metrosPorUnidad != null && item.metrosPorUnidad > 0) {
                val maxMetros = item.disponibleMetros ?: item.disponible * item.metrosPorUnidad
                minOf(cant, maxMetros) / item.metrosPorUnidad
            } else {
                minOf(cant, item.disponible)
            }

            val idx = materialesGastados.indexOfFirst { it.first == productoId }
            if (idx >= 0) materialesGastados[idx] = Pair(productoId, cantFinal)
            else {
                materialesGastados.add(Pair(productoId, cantFinal))
                nombresProductos[productoId] = item.nombre
            }
        }
        actualizarContadorMateriales()
    }

    private fun actualizarContadorMateriales() {
        val tvContador = findViewById<android.widget.TextView>(R.id.tvMaterialesContador) ?: return
        if (materialesGastados.isEmpty()) {
            tvContador.visibility = android.view.View.GONE
        } else {
            tvContador.text = "${materialesGastados.size} item(s)"
            tvContador.visibility = android.view.View.VISIBLE
        }
    }

    private fun actualizarListaMateriales() {
        val layoutLista   = findViewById<android.widget.LinearLayout>(R.id.layoutListaMateriales) ?: return
        val layoutVacio   = findViewById<android.widget.LinearLayout>(R.id.layoutMaterialesVacio) ?: return
        val tvContador    = findViewById<TextView>(R.id.tvMaterialesContador) ?: return

        val ordenTipo     = vm.orden.value?.tipoOrden ?: ""
        val esRetiroOrden = esRetiro(ordenTipo)

        // Para retiros NO limpiar el layout — las cards se gestionan dinámicamente
        if (!esRetiroOrden) {
            layoutLista.removeAllViews()
        }

        // Para instalaciones: lista de materiales gastados
        val listaActual   = materialesGastados
        val nombresActual = nombresProductos

        if (listaActual.isEmpty()) {
            layoutVacio.visibility = View.VISIBLE
            layoutLista.visibility = View.GONE
            tvContador.visibility  = View.GONE
            return
        }

        layoutVacio.visibility = View.GONE
        layoutLista.visibility = View.VISIBLE
        tvContador.visibility  = View.VISIBLE
        tvContador.text        = "${listaActual.size} item${if (listaActual.size != 1) "s" else ""}"

        if (listaActual.isEmpty()) {
            layoutVacio.visibility = View.VISIBLE
            layoutLista.visibility = View.GONE
            tvContador.visibility  = View.GONE
            return
        }

        layoutVacio.visibility = View.GONE
        layoutLista.visibility = View.VISIBLE
        tvContador.visibility  = View.VISIBLE
        tvContador.text        = "${listaActual.size} item${if (listaActual.size != 1) "s" else ""}"

        for ((productoId, cantidad) in listaActual) {
            val nombre = nombresActual[productoId] ?: "Producto #$productoId"
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
                    if (esRetiroOrden) {
                        equiposRetirados.removeAll { it.productoId == productoId }
                        nombresEquipos.remove(productoId)
                    } else {
                        materialesGastados.removeAll { it.first == productoId }
                        nombresProductos.remove(productoId)
                    }
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
        guardarPrecintoSiCambio()
        if (vm.cantidadFotos() == 0) {
            Toast.makeText(this, "Debes tomar al menos una foto", Toast.LENGTH_SHORT).show()
            return
        }
        // Defensa adicional: si la orden requiere OLT, no se puede completar sin autorización confirmada
        if (requiereOltDinamico() && vm.estadoOlt.value !is EstadoOltUi.Autorizada) {
            Toast.makeText(this, "Primero debes autenticar la ONU con la OLT", Toast.LENGTH_LONG).show()
            return
        }
        binding.btnCompletar.isEnabled = false
        binding.progressCompletar.visibility = View.VISIBLE

        val obs = binding.etObservaciones.text.toString().ifBlank { null }

        lifecycleScope.launch {
            // 1. Guardar config ONU
            if (esInternet()) {
                val config = ConfigOnuRequest(
                    ssid             = resultSsid.ifBlank { null },
                    ssidPassword     = resultPass.ifBlank { null },
                    ssid5ghz         = resultSsid5.ifBlank { null },
                    ssidPassword5ghz = resultPass5.ifBlank { null },
                    serialNumber     = (serialActualParaOlt() ?: resultSn).ifBlank { null },
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
            /*     if (materialesGastados.isNotEmpty()) {
                     val consumoItems = materialesGastados.map { (productoId, cantidad) ->
                         ConsumoItemRequest(productoId, cantidad)
                     }
                     val ordenInfo = vm.orden.value
                     inventarioVm.registrarConsumo(
                         items       = consumoItems,
                         motivo      = "SERVICIO",
                         descripcion = "Orden: $ordenId",
                         ordenId     = ordenId,
                         nServicio   = ordenInfo?.nServicio,
                         abonado     = ordenInfo?.abonado,
                         nombresMap  = nombresProductos
                     )
                     val ordenActual = vm.orden.value
                     android.util.Log.d("InstalacionAct", "Al completar — esRetiro=${ordenActual?.let { esRetiro(it.tipoOrden) }}, equiposRetirados=${equiposRetirados.size}")
                     equiposRetirados.forEach { i ->
                         android.util.Log.d("InstalacionAct", "  equipo: productoId=${i.productoId} tipo=${i.tipoEquipo} pon=${i.codigoPon}")
                     }
                     if (ordenActual != null && esRetiro(ordenActual.tipoOrden) &&
                         equiposRetirados.isNotEmpty()) {
                         inventarioVm.registrarRetiro(
                             items   = equiposRetirados.toList(),
                             ordenId = ordenId,
                         )
                     }

                 }
     */
            // 3a. Registrar materiales gastados (solo instalaciones)
            // IMPORTANTE: se espera (suspend) antes de continuar — si esto fuera
            // fire-and-forget, el finish() del paso 4 podría cancelar la corutina
            // a mitad de camino y el inventario nunca se descontaría (bug real detectado).
            val ordenActual = vm.orden.value
            if (materialesGastados.isNotEmpty()) {
                val consumoItems = materialesGastados.map { (productoId, cantidad) ->
                    ConsumoItemRequest(
                        productoId = productoId,
                        cantidad   = cantidad,
                        codigoPon  = onusSeleccionadas[productoId],
                        // Solo aplica a productos normales con checkbox marcado —
                        // 1 unidad reciclada por fila, ya que el checkbox es booleano
                        unidadesRecicladas = if (usarRecicladoPorProducto[productoId] == true) 1 else 0
                    )
                }
                val resultadoConsumo = inventarioVm.registrarConsumoSuspend(
                    items       = consumoItems,
                    motivo      = "SERVICIO",
                    descripcion = "Orden: $ordenId",
                    ordenId     = ordenId,
                    nServicio   = ordenActual?.nServicio,
                    abonado     = ordenActual?.abonado,
                    nombresMap  = nombresProductos
                )
                if (resultadoConsumo is Resultado.Error) {
                    android.util.Log.w("InstalacionAct", "⚠ Error registrando consumo: ${resultadoConsumo.mensaje}")
                }
            }

// 3b. Registrar retiro de equipos (retiro, cambio de equipo, o reconexion con ONU averiada)
            val esReconexionConAveriada = ordenActual != null &&
                    esReconexion(ordenActual.tipoOrden) && onuClienteAveriadaEnReconexion &&
                    equipoOnuAveriadaRetirado != null

            if (ordenActual != null && (esRetiro(ordenActual.tipoOrden) || esCambioEquipo(ordenActual.tipoOrden) || esReconexionConAveriada)) {
                val itemsARetirar = equiposRetirados.toMutableList()
                equipoOnuAveriadaRetirado?.let { itemsARetirar.add(it) }

                android.util.Log.d("InstalacionAct", "Registrando retiro: ${itemsARetirar.size} equipos, ordenId=$ordenId")
                itemsARetirar.forEach { i ->
                    android.util.Log.d("InstalacionAct", "  equipo: productoId=${i.productoId} tipo=${i.tipoEquipo} pon=${i.codigoPon}")
                }
                if (itemsARetirar.isNotEmpty()) {
                    val resultadoRetiro = inventarioVm.registrarRetiroSuspend(
                        items   = itemsARetirar.toList(),
                        ordenId = ordenId,
                    )
                    if (resultadoRetiro is Resultado.Error) {
                        android.util.Log.w("InstalacionAct", "⚠ Error registrando retiro: ${resultadoRetiro.mensaje}")
                    }
                } else {
                    android.util.Log.w("InstalacionAct", "⚠ Retiro sin equipos registrados")
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
            supportActionBar?.title = "${orden.abonado}"
            cargarUbicacionDesdeOrden()
            setupCardPrecinto()
            // Mostrar WAN del NOC
            if (!orden.ipWan.isNullOrBlank()) {
                val cardWan = findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardWan)
                cardWan?.visibility = android.view.View.VISIBLE
                findViewById<android.widget.TextView>(R.id.tvIpWan)?.text = orden.ipWan
                findViewById<android.widget.TextView>(R.id.tvMascara)?.text = orden.mascara ?: "255.255.255.0"
                findViewById<android.widget.TextView>(R.id.tvGateway)?.text = orden.gateway ?: "—"
            }
            // Inicializar materiales, botón config ONU, OLT, etc.
            rellenarPaso4()
            findViewById<CardView>(R.id.cardAutorizarOlt)?.visibility =
                if (requiereOltDinamico()) View.VISIBLE else View.GONE
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
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        @Suppress("DEPRECATION") super.onBackPressed()
    }
}