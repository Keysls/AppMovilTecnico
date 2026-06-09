package com.enetfiber.tecnico.ui.devolucion

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.enetfiber.tecnico.R
import com.enetfiber.tecnico.data.local.InventarioItemEntity
import com.enetfiber.tecnico.data.remote.DevolucionDto
import com.enetfiber.tecnico.data.remote.DevolucionItemRequest
import com.enetfiber.tecnico.data.remote.DevolucionRecojoRequest
import com.enetfiber.tecnico.data.remote.RecojoDto
import com.enetfiber.tecnico.databinding.ActivityDevolucionBinding
import com.enetfiber.tecnico.ui.InventarioViewModel
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Locale

@AndroidEntryPoint
class DevolucionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDevolucionBinding
    private val vm: InventarioViewModel by viewModels()

    private var itemsDisponibles: List<InventarioItemEntity> = emptyList()
    private var recojosEnMano: List<RecojoDto> = emptyList()
    private val recojosSeleccionados = mutableSetOf<Int>()

    private data class FilaDevolucion(
        var productoId: Int    = -1,
        var nombre:     String = "",
        var cantidad:   Double = 0.0,
        var disponible: Double = 0.0,
        var unidad:     String = "und",
        var esMedible:  Boolean = false,
        var metrosPorUnidad: Int? = null
    )
    private val filas = mutableListOf<FilaDevolucion>()

    private lateinit var historialAdapter: DevolucionHistorialAdapter
    private lateinit var recojosAdapter:   RecojoSeleccionAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDevolucionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupHistorial()
        setupRecojosAdapter()
        setupBotones()
        observar()
        agregarFila()
        vm.cargarDevoluciones()
    }

    private fun setupHistorial() {
        historialAdapter = DevolucionHistorialAdapter()
        binding.rvDevoluciones.layoutManager = LinearLayoutManager(this)
        binding.rvDevoluciones.adapter = historialAdapter
        binding.rvDevoluciones.isNestedScrollingEnabled = false
    }

    private fun setupRecojosAdapter() {
        recojosAdapter = RecojoSeleccionAdapter { recojoId, seleccionado ->
            if (seleccionado) recojosSeleccionados.add(recojoId)
            else              recojosSeleccionados.remove(recojoId)
        }
        binding.rvRecojos.layoutManager = LinearLayoutManager(this)
        binding.rvRecojos.adapter = recojosAdapter
        binding.rvRecojos.isNestedScrollingEnabled = false
    }

    private fun setupBotones() {
        binding.btnAgregarItem.setOnClickListener { agregarFila() }
        binding.btnEnviarDevolucion.setOnClickListener { enviarDevolucion() }
    }

    private fun observar() {
        vm.items.observe(this) { items ->
            val productosRecojos = recojosEnMano.mapNotNull { it.productoId }.toSet()
            itemsDisponibles = items?.filter {
                it.disponible > 0 && it.productoId !in productosRecojos
            } ?: emptyList()
        }

        vm.recojos.observe(this) { recojos ->
            recojosEnMano = recojos?.filter { it.estado == "en_mano" } ?: emptyList()
            recojosAdapter.submitList(recojosEnMano)
            binding.sectionRecojos.visibility =
                if (recojosEnMano.isNotEmpty()) View.VISIBLE else View.GONE

            // Recalcular items disponibles excluyendo productos con recojo
            val productosRecojos = recojosEnMano.mapNotNull { it.productoId }.toSet()
            itemsDisponibles = (vm.items.value ?: emptyList()).filter {
                it.disponible > 0 && it.productoId !in productosRecojos
            }
        }

        vm.devoluciones.observe(this) { lista ->
            historialAdapter.submitList(lista)
            binding.tvEmptyDevoluciones.visibility =
                if (lista.isEmpty()) View.VISIBLE else View.GONE
        }

        vm.devolucionState.observe(this) { state ->
            when (state) {
                is InventarioViewModel.DevolucionState.Guardando -> {
                    binding.btnEnviarDevolucion.isEnabled = false
                    binding.btnEnviarDevolucion.text = "Enviando..."
                }
                is InventarioViewModel.DevolucionState.Exito -> {
                    Toast.makeText(this, "✅ Devolución enviada — esperando aprobación del admin", Toast.LENGTH_LONG).show()
                    binding.layoutItems.removeAllViews()
                    filas.clear()
                    recojosSeleccionados.clear()
                    binding.etComentario.setText("")
                    agregarFila()
                    binding.btnEnviarDevolucion.isEnabled = true
                    binding.btnEnviarDevolucion.text = "Enviar devolución"
                    vm.resetDevolucionState()
                }
                is InventarioViewModel.DevolucionState.Error -> {
                    Toast.makeText(this, "⚠ ${state.msg}", Toast.LENGTH_LONG).show()
                    binding.btnEnviarDevolucion.isEnabled = true
                    binding.btnEnviarDevolucion.text = "Enviar devolución"
                    vm.resetDevolucionState()
                }
                else -> {
                    binding.btnEnviarDevolucion.isEnabled = true
                    binding.btnEnviarDevolucion.text = "Enviar devolución"
                }
            }
        }
    }

    private fun agregarFila() {
        val fila = FilaDevolucion()
        filas.add(fila)
        val index = filas.size - 1

        val rowView = layoutInflater.inflate(R.layout.item_devolucion_row, binding.layoutItems, false)
        val tvNumero    = rowView.findViewById<TextView>(R.id.tvNumeroItem)
        val btnSelector = rowView.findViewById<LinearLayout>(R.id.btnSeleccionarProducto)
        val tvProducto  = rowView.findViewById<TextView>(R.id.tvProductoSeleccionado)
        val etCantidad  = rowView.findViewById<EditText>(R.id.etCantidad)
        val tvUnidad    = rowView.findViewById<TextView>(R.id.tvUnidad)
        val tvDisp      = rowView.findViewById<TextView>(R.id.tvDisponible)
        val btnElim     = rowView.findViewById<ImageView>(R.id.btnEliminarItem)

        tvNumero.text = "ITEM #${index + 1}"

        btnSelector.setOnClickListener {
            if (itemsDisponibles.isEmpty()) {
                Toast.makeText(this, "No tienes ítems disponibles", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Excluir productoIds ya seleccionados en otras filas
            val yaUsados = filas.filter { it.productoId >= 0 && it != fila }
                .map { it.productoId }.toSet()
            val itemsFiltrados = itemsDisponibles.filter { it.productoId !in yaUsados }

            if (itemsFiltrados.isEmpty()) {
                Toast.makeText(this, "Sin más ítems disponibles", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val opciones = itemsFiltrados.map { item ->
                if (item.esMedible && item.disponibleMetros != null)
                    "${item.nombre} — disp: ${item.disponibleMetros.toInt()} m"
                else
                    "${item.nombre} — disp: ${item.disponible.toInt()} ${item.unidad}"
            }.toTypedArray()

            AlertDialog.Builder(this)
                .setTitle("Seleccionar producto")
                .setItems(opciones) { _, idx ->
                    val item = itemsFiltrados[idx]
                    fila.productoId      = item.productoId
                    fila.nombre          = item.nombre
                    fila.esMedible       = item.esMedible
                    fila.metrosPorUnidad = item.metrosPorUnidad
                    fila.disponible      = if (item.esMedible && item.disponibleMetros != null)
                        item.disponibleMetros else item.disponible
                    fila.unidad          = if (item.esMedible) "m" else (item.unidad ?: "und")

                    tvProducto.text = item.nombre
                    tvProducto.setTextColor(android.graphics.Color.parseColor("#0F172A"))
                    tvUnidad.text   = fila.unidad
                    tvDisp.text     = fila.disponible.toInt().toString()
                    etCantidad.setText("")
                    etCantidad.hint = fila.disponible.toInt().toString()
                }
                .show()
        }

        etCantidad.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val v = s?.toString()?.toDoubleOrNull() ?: 0.0
                if (fila.disponible > 0 && v > fila.disponible) {
                    etCantidad.removeTextChangedListener(this)
                    etCantidad.setText(fila.disponible.toInt().toString())
                    etCantidad.setSelection(etCantidad.text.length)
                    etCantidad.error = "Máx: ${fila.disponible.toInt()}"
                    etCantidad.addTextChangedListener(this)
                }
                fila.cantidad = etCantidad.text.toString().toDoubleOrNull() ?: 0.0
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnElim.setOnClickListener {
            binding.layoutItems.removeView(rowView)
            filas.remove(fila)
            for (i in 0 until binding.layoutItems.childCount) {
                binding.layoutItems.getChildAt(i)
                    ?.findViewById<TextView>(R.id.tvNumeroItem)
                    ?.text = "ITEM #${i + 1}"
            }
        }

        binding.layoutItems.addView(rowView)
    }

    private fun enviarDevolucion() {
        for (i in 0 until binding.layoutItems.childCount) {
            val row = binding.layoutItems.getChildAt(i)
            val et  = row?.findViewById<EditText>(R.id.etCantidad)
            filas.getOrNull(i)?.cantidad = et?.text?.toString()?.toDoubleOrNull() ?: 0.0
        }

        val itemsValidos   = filas.filter { it.productoId >= 0 && it.cantidad > 0 }
        val recojosValidos = recojosSeleccionados.toList()

        if (itemsValidos.isEmpty() && recojosValidos.isEmpty()) {
            Toast.makeText(this, "⚠ Agrega al menos un producto o equipo reciclado", Toast.LENGTH_SHORT).show()
            return
        }

        val resumenMaterial = if (itemsValidos.isNotEmpty())
            itemsValidos.joinToString("\n") { "• ${it.nombre}: ${it.cantidad.toInt()} ${it.unidad}" }
        else ""

        val resumenRecojos = if (recojosValidos.isNotEmpty()) {
            val nombres = recojosEnMano
                .filter { it.id in recojosValidos }
                .joinToString("\n") { "♻ ${it.nombreProducto ?: it.tipoEquipo}" }
            (if (resumenMaterial.isNotEmpty()) "\n\n" else "") + "Equipos reciclados:\n$nombres"
        } else ""

        AlertDialog.Builder(this)
            .setTitle("Confirmar devolución")
            .setMessage("Vas a devolver:\n\n$resumenMaterial$resumenRecojos\n\nEl admin deberá aprobar.")
            .setPositiveButton("Confirmar") { _, _ ->
                val items = itemsValidos.map { fila ->
                    val cantFinal = if (fila.esMedible && fila.metrosPorUnidad != null && fila.metrosPorUnidad!! > 0)
                        fila.cantidad / fila.metrosPorUnidad!!
                    else fila.cantidad
                    DevolucionItemRequest(fila.productoId, cantFinal)
                }
                val recojos    = recojosValidos.map { DevolucionRecojoRequest(it) }
                val comentario = binding.etComentario.text?.toString()?.ifBlank { null }
                vm.registrarDevolucion(items, recojos, comentario)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}

// ── Adapter: selección de recojos ────────────────────────────
class RecojoSeleccionAdapter(
    private val onToggle: (recojoId: Int, seleccionado: Boolean) -> Unit
) : ListAdapter<RecojoDto, RecojoSeleccionAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<RecojoDto>() {
            override fun areItemsTheSame(a: RecojoDto, b: RecojoDto) = a.id == b.id
            override fun areContentsTheSame(a: RecojoDto, b: RecojoDto) = a == b
        }
    }

    private val seleccionados = mutableSetOf<Int>()

    inner class VH(val view: View) : RecyclerView.ViewHolder(view) {
        val tvNombre : TextView = view.findViewById(R.id.tvRecojoNombreDev)
        val tvPon    : TextView = view.findViewById(R.id.tvRecojoPonDev)
        val checkbox : CheckBox = view.findViewById(R.id.cbRecojoSeleccion)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recojo_devolucion, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val recojo = getItem(position)
        holder.tvNombre.text = recojo.nombreProducto ?: recojo.tipoEquipo

        if (!recojo.codigoPon.isNullOrBlank()) {
            holder.tvPon.text       = "◈ ${recojo.codigoPon}"
            holder.tvPon.visibility = View.VISIBLE
        } else {
            holder.tvPon.visibility = View.GONE
        }

        holder.checkbox.setOnCheckedChangeListener(null)
        holder.checkbox.isChecked = recojo.id in seleccionados
        holder.checkbox.setOnCheckedChangeListener { _, checked ->
            if (checked) seleccionados.add(recojo.id)
            else         seleccionados.remove(recojo.id)
            onToggle(recojo.id, checked)
        }
    }
}

// ── Adapter historial ─────────────────────────────────────────
class DevolucionHistorialAdapter :
    ListAdapter<DevolucionDto, DevolucionHistorialAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<DevolucionDto>() {
            override fun areItemsTheSame(a: DevolucionDto, b: DevolucionDto) = a.id == b.id
            override fun areContentsTheSame(a: DevolucionDto, b: DevolucionDto) = a == b
        }
    }

    inner class VH(val view: View) : RecyclerView.ViewHolder(view) {
        val tvEstado        : TextView = view.findViewById(R.id.tvEstado)
        val tvFecha         : TextView = view.findViewById(R.id.tvFecha)
        val tvItems         : TextView = view.findViewById(R.id.tvItems)
        val tvRecojos       : TextView = view.findViewById(R.id.tvRecojosDevueltos)
        val tvComentario    : TextView = view.findViewById(R.id.tvComentario)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_devolucion_historial, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val dev = getItem(position)

        val (texto, textColor, bgColor) = when (dev.estado) {
            "aprobado"  -> Triple("✅ Aprobada",  "#166534", "#DCFCE7")
            "rechazado" -> Triple("❌ Rechazada", "#991B1B", "#FEE2E2")
            else        -> Triple("⏳ Pendiente", "#92400E", "#FEF3C7")
        }
        holder.tvEstado.text = texto
        holder.tvEstado.setTextColor(android.graphics.Color.parseColor(textColor))
        holder.tvEstado.setBackgroundColor(android.graphics.Color.parseColor(bgColor))

        try {
            val sdfIn  = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            val sdfOut = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            val fecha  = dev.fecha?.let { sdfIn.parse(it) }
            holder.tvFecha.text = if (fecha != null) sdfOut.format(fecha) else ""
        } catch (_: Exception) {
            holder.tvFecha.text = dev.fecha ?: ""
        }

        holder.tvItems.text = dev.detalles.joinToString(" · ") {
            "${it.cantidad.toInt()} ${it.unidad ?: "und"} ${it.nombre}"
        }

        // Recojos devueltos
        val recojos = dev.recojos
        if (recojos.isNotEmpty()) {
            holder.tvRecojos.text = recojos.joinToString(" · ") {
                "♻ ${it.tipoEquipo}${if (!it.codigoPon.isNullOrBlank()) " (${it.codigoPon})" else ""}"
            }
            holder.tvRecojos.visibility = View.VISIBLE
        } else {
            holder.tvRecojos.visibility = View.GONE
        }

        if (!dev.comentario.isNullOrBlank()) {
            holder.tvComentario.text = dev.comentario
            holder.tvComentario.visibility = View.VISIBLE
        } else {
            holder.tvComentario.visibility = View.GONE
        }
    }
}