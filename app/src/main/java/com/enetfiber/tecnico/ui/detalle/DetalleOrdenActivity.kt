package com.enetfiber.tecnico.ui.detalle

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.enetfiber.tecnico.TipoOrden
import com.enetfiber.tecnico.databinding.ActivityDetalleOrdenBinding
import com.enetfiber.tecnico.ui.InstalacionState
import com.enetfiber.tecnico.ui.InstalacionViewModel
import com.enetfiber.tecnico.ui.instalacion.InstalacionActivity
import dagger.hilt.android.AndroidEntryPoint
import com.enetfiber.tecnico.ui.DashboardViewModel


@AndroidEntryPoint
class DetalleOrdenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetalleOrdenBinding
    private val vm: InstalacionViewModel by viewModels()

    private val ordenId by lazy { intent.getStringExtra("orden_id") ?: "" }
    private val soloVer by lazy { intent.getBooleanExtra("solo_ver", false) }

    private val permisosGps = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permisos ->
        if (permisos[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            aceptarConGps()
        } else {
            Toast.makeText(this, "Se necesita permiso de ubicación", Toast.LENGTH_SHORT).show()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetalleOrdenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        vm.cargarOrden(ordenId)
        observar()
        botones()
    }

    private fun observar() {
        vm.orden.observe(this) { orden ->
            if (orden == null) return@observe

            supportActionBar?.title = "Orden #${orden.nServicio}"
            binding.tvAbonado.text  = orden.abonado
            binding.tvDni.text      = "${orden.dni ?: "—"}"
            binding.tvContrato.text = "${orden.contrato ?: "—"}"
            binding.tvDireccion.text = orden.direccion
            binding.tvSector.text   = orden.sector ?: ""

            binding.tvTipo.text = TipoOrden.label(orden.tipoOrden)

            if (!orden.referencia.isNullOrEmpty() && orden.referencia != "0") {
                binding.tvReferencia.text = "${orden.referencia}"
                binding.tvReferencia.visibility = View.VISIBLE
            }

            if (!orden.observacion.isNullOrEmpty()) {
                binding.tvObservacion.text = orden.observacion
                binding.cardObservacion.visibility = View.VISIBLE
            }

            if (!orden.ipWan.isNullOrEmpty()) {
                binding.cardWan.visibility = View.VISIBLE
                binding.tvIpWan.text    = "${orden.ipWan}"
                binding.tvMascara.text  = "${orden.mascara}"
                binding.tvGateway.text  = "${orden.gateway}"
            }

            binding.tvCelular.text = orden.celular

            if (soloVer) {
                binding.btnAceptar.visibility   = View.GONE
                binding.btnContinuar.visibility = View.GONE
                return@observe
            }

            when (orden.estado) {
                "PENDIENTE_TECNICO" -> {
                    binding.btnAceptar.visibility   = View.VISIBLE
                    binding.btnContinuar.visibility = View.GONE
                }
                "ACEPTADA", "EN_PROCESO" -> {
                    binding.btnAceptar.visibility   = View.GONE
                    binding.btnContinuar.visibility = View.VISIBLE
                }
                else -> {
                    binding.btnAceptar.visibility   = View.GONE
                    binding.btnContinuar.visibility = View.GONE
                }
            }
        }

        vm.state.observe(this) { state ->
            val cargando = state is InstalacionState.Cargando
            binding.progressBar.visibility = if (cargando) View.VISIBLE else View.GONE
            binding.btnAceptar.isEnabled   = !cargando
            binding.btnContinuar.isEnabled = !cargando
        }
    }

    private fun botones() {
        binding.btnLlamar.setOnClickListener {
            val cel = vm.orden.value?.celular ?: return@setOnClickListener
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cel")))
        }

        binding.btnWhatsapp.setOnClickListener {
            val cel     = vm.orden.value?.celular?.replace(Regex("[^0-9]"), "") ?: return@setOnClickListener
            val abonado = vm.orden.value?.abonado ?: ""
            val nombre   = intent.getStringExtra("tecnico_nombre") ?: ""
            val apellido = intent.getStringExtra("tecnico_apellido") ?: ""
            val tecnico = "${nombre.split(" ").firstOrNull() ?: ""} ${apellido.split(" ").firstOrNull() ?: ""}".trim().ifEmpty { "su técnico" }
            val msg = "Hola, $abonado. Mi nombre es $tecnico, técnico de Enet Fiber Perú. " +
                    "Estoy en camino a su domicilio para realizar su servicio. Estaré llegando en breve."
            try {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://wa.me/51$cel?text=${Uri.encode(msg)}")))
            } catch (_: Exception) {
                Toast.makeText(this, "WhatsApp no instalado", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnMapa.setOnClickListener {
            val dir = vm.orden.value?.direccion ?: return@setOnClickListener
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("geo:0,0?q=${Uri.encode(dir)}")))
        }

        // Aceptar → obtiene GPS primero, luego acepta
        binding.btnAceptar.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
                aceptarConGps()
            } else {
                permisosGps.launch(arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ))
            }
        }

        binding.btnContinuar.setOnClickListener {
            // Tomar GPS al iniciar instalación si aún no lo tenemos
            if (vm.latitud == null) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                    vm.obtenerGps(
                        onExito = { iniciarInstalacion() },
                        onError = { iniciarInstalacion() } // continuar aunque GPS falle
                    )
                } else {
                    iniciarInstalacion() // sin GPS
                }
            } else {
                iniciarInstalacion()
            }
        }
    }

    private fun iniciarInstalacion() {
        vm.iniciarInstalacion(
            ordenId = ordenId,
            onExito = { instId ->
                // Opción B: si no hay señal, la instalación se inició offline.
                // Avisamos al técnico — igual puede trabajar normalmente.
                if (!vm.isOnline) {
                    Toast.makeText(
                        this,
                        "📡 Sin señal — la instalación se registrará automáticamente al recuperar conexión",
                        Toast.LENGTH_LONG
                    ).show()
                }
                startActivity(
                    Intent(this, InstalacionActivity::class.java)
                        .putExtra("instalacion_id", instId)
                        .putExtra("orden_id", ordenId)
                )
            },
            onError = { msg ->
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun aceptarConGps() {
        // Obtener GPS en background y aceptar la orden
        vm.obtenerGps(
            onExito = { _ ->
                vm.aceptarOrden(
                    ordenId = ordenId,
                    onExito = {
                        Toast.makeText(this, "✓ Orden aceptada", Toast.LENGTH_SHORT).show()
                        binding.btnAceptar.visibility   = View.GONE
                        binding.btnContinuar.visibility = View.VISIBLE
                    },
                    onError = { msg ->
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                )
            },
            onError = { _ ->
                // Si GPS falla, aceptar igual sin coordenadas
                vm.aceptarOrden(
                    ordenId = ordenId,
                    onExito = {
                        Toast.makeText(this, "✓ Orden aceptada (sin GPS)", Toast.LENGTH_SHORT).show()
                        binding.btnAceptar.visibility   = View.GONE
                        binding.btnContinuar.visibility = View.VISIBLE
                    },
                    onError = { msg ->
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                )
            }
        )
    }
}