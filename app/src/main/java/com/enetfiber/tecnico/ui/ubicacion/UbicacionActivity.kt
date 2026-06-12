package com.enetfiber.tecnico.ui.ubicacion

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.enetfiber.tecnico.R
import com.enetfiber.tecnico.data.Repository
import com.enetfiber.tecnico.databinding.ActivityUbicacionBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import javax.inject.Inject
import java.util.Locale

@AndroidEntryPoint
class UbicacionActivity : AppCompatActivity() {

    @Inject lateinit var repo: Repository

    private lateinit var binding: ActivityUbicacionBinding

    // Datos recibidos por Intent
    private val numeroContrato by lazy { intent.getStringExtra(EXTRA_CONTRATO) ?: "" }
    private val direccion      by lazy { intent.getStringExtra(EXTRA_DIRECCION) ?: "" }
    private val sector         by lazy { intent.getStringExtra(EXTRA_SECTOR) ?: "" }
    private val latInicial     by lazy { intent.getDoubleExtra(EXTRA_LAT, 0.0) }
    private val lngInicial     by lazy { intent.getDoubleExtra(EXTRA_LNG, 0.0) }
    private val tieneGps       by lazy { latInicial != 0.0 && lngInicial != 0.0 }

    // Estado del pin actual
    private var latActual = 0.0
    private var lngActual = 0.0
    private var marker: Marker? = null

    private val permisoGps = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permisos ->
        if (permisos[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            centrarEnMiPosicion()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configurar osmdroid (user agent requerido)
        Configuration.getInstance().userAgentValue = packageName

        binding = ActivityUbicacionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        configurarMapa()
        configurarBotones()

        // Mostrar dirección del contrato como referencia
        binding.tvDireccionDetectada.text = direccion
        binding.tvCiudadDetectada.text    = sector

        // Si tiene GPS: centrar en el pin del cliente
        // Si no: centrar en la posición del celular
        if (tieneGps) {
            latActual = latInicial
            lngActual = lngInicial
            moverPin(latActual, lngActual, centrar = true)
        } else {
            solicitarGpsOCentrarDefault()
        }
    }

    // ── Mapa ─────────────────────────────────────────────────
    private fun configurarMapa() {
        binding.mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(17.0)
            isClickable = true
        }

        // Tap en el mapa → mover pin
        binding.mapView.overlays.add(object : org.osmdroid.views.overlay.Overlay() {
            override fun onSingleTapConfirmed(
                e: android.view.MotionEvent?,
                mapView: org.osmdroid.views.MapView?
            ): Boolean {
                e ?: return false
                mapView ?: return false
                val proj = mapView.projection
                val geo  = proj.fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint
                moverPin(geo.latitude, geo.longitude, centrar = false)
                return true
            }
        })
    }

    private fun moverPin(lat: Double, lng: Double, centrar: Boolean) {
        latActual = lat
        lngActual = lng

        val punto = GeoPoint(lat, lng)

        // Crear o mover el marker
        if (marker == null) {
            marker = Marker(binding.mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                isDraggable = true
                setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                    override fun onMarkerDrag(marker: Marker?) {}
                    override fun onMarkerDragEnd(marker: Marker?) {
                        marker?.position?.let { pos ->
                            latActual = pos.latitude
                            lngActual = pos.longitude
                            actualizarCoordenadas(latActual, lngActual)
                        }
                    }
                    override fun onMarkerDragStart(marker: Marker?) {}
                })
            }
            binding.mapView.overlays.add(marker)
        }

        marker?.position = punto
        binding.mapView.invalidate()

        if (centrar) binding.mapView.controller.animateTo(punto)
        actualizarCoordenadas(lat, lng)
    }

    private fun actualizarCoordenadas(lat: Double, lng: Double) {
        binding.tvLatitud.text  = "%.7f".format(lat)
        binding.tvLongitud.text = "%.7f".format(lng)
    }

    // ── GPS celular ───────────────────────────────────────────
    private fun solicitarGpsOCentrarDefault() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            centrarEnMiPosicion()
        } else {
            permisoGps.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
            // Mientras tanto centrar en Perú
            binding.mapView.controller.setCenter(GeoPoint(-8.0, -75.0))
            binding.mapView.controller.setZoom(6.0)
        }
    }

    @SuppressLint("MissingPermission")
    private fun centrarEnMiPosicion() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager

        // 1. Intentar última ubicación conocida de cualquier proveedor
        val ultimaUbicacion = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).mapNotNull { proveedor ->
            try { lm.getLastKnownLocation(proveedor) } catch (_: Exception) { null }
        }.maxByOrNull { it.time }

        if (ultimaUbicacion != null) {
            usarUbicacion(ultimaUbicacion.latitude, ultimaUbicacion.longitude)
            return
        }

        // 2. Si no hay última ubicación, pedir una actualización en tiempo real
        Toast.makeText(this, "Obteniendo ubicación GPS...", Toast.LENGTH_SHORT).show()

        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: android.location.Location) {
                usarUbicacion(location.latitude, location.longitude)
                try { lm.removeUpdates(this) } catch (_: Exception) {}
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        }

        var pedido = false
        for (proveedor in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
            if (lm.isProviderEnabled(proveedor)) {
                try {
                    lm.requestLocationUpdates(proveedor, 0L, 0f, listener)
                    pedido = true
                } catch (_: Exception) {}
            }
        }

        if (!pedido) {
            Toast.makeText(this, "Activa el GPS del celular e intenta de nuevo", Toast.LENGTH_LONG).show()
        }
    }

    private fun usarUbicacion(lat: Double, lng: Double) {
        if (!tieneGps) {
            // Sin GPS de contrato: poner pin en mi posición
            moverPin(lat, lng, centrar = true)
        } else {
            // Con GPS de contrato: mostrar mi posición como referencia (marcador secundario)
            val miPunto = GeoPoint(lat, lng)
            val miMarker = Marker(binding.mapView).apply {
                position = miPunto
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "Tu posición actual"
                snippet = "Referencia — no es la ubicación del cliente"
                alpha = 0.7f
            }
            binding.mapView.overlays.add(miMarker)
            binding.mapView.invalidate()
            // No mostrar toast — el título del marker ya lo explica
        }
    }

    // ── Botones ───────────────────────────────────────────────
    private fun configurarBotones() {
        binding.btnZoomIn.setOnClickListener  { binding.mapView.controller.zoomIn() }
        binding.btnZoomOut.setOnClickListener { binding.mapView.controller.zoomOut() }

        binding.btnMiPosicion.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
                centrarEnMiPosicion()
            } else {
                permisoGps.launch(arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ))
            }
        }

        binding.btnConfirmar.setOnClickListener {
            if (latActual == 0.0 && lngActual == 0.0) {
                Toast.makeText(this, "Coloca el pin en el mapa primero", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            guardarUbicacion()
        }

        binding.tvEditarUbicacion.setOnClickListener {
            binding.tvInstruccion.visibility = View.VISIBLE
            binding.tvEditarUbicacion.visibility = View.GONE
        }
    }

    private fun guardarUbicacion() {
        binding.btnConfirmar.isEnabled = false
        binding.btnConfirmar.text      = "Guardando..."

        lifecycleScope.launch {
            val resultado = repo.actualizarUbicacionContrato(
                numero   = numeroContrato,
                latitud  = latActual,
                longitud = lngActual
            )
            withContext(Dispatchers.Main) {
                if (resultado.isExito()) {
                    Toast.makeText(this@UbicacionActivity, "✓ Ubicación guardada", Toast.LENGTH_SHORT).show()
                } else {
                    // Sin internet — igual devolver las coordenadas al llamante
                    // Se sincronizará cuando recupere conexión
                    Toast.makeText(this@UbicacionActivity, "📡 Sin señal — ubicación guardada localmente", Toast.LENGTH_SHORT).show()
                }
                // Siempre devolver resultado y cerrar
                setResult(RESULT_OK, Intent().apply {
                    putExtra(RESULT_LAT, latActual)
                    putExtra(RESULT_LNG, lngActual)
                })
                finish()
            }
        }
    }

    override fun onResume()  { super.onResume();  binding.mapView.onResume() }
    override fun onPause()   { super.onPause();   binding.mapView.onPause() }

    companion object {
        const val EXTRA_CONTRATO = "contrato"
        const val EXTRA_DIRECCION = "direccion"
        const val EXTRA_SECTOR    = "sector"
        const val EXTRA_LAT       = "lat"
        const val EXTRA_LNG       = "lng"
        const val RESULT_LAT      = "result_lat"
        const val RESULT_LNG      = "result_lng"
    }
}