package com.enetfiber.tecnico.ui.main

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.enetfiber.tecnico.R
import com.enetfiber.tecnico.data.local.ConsumoPendienteEntity
import com.enetfiber.tecnico.data.local.InventarioItemEntity
import com.enetfiber.tecnico.data.remote.ConsumoItemRequest
import com.enetfiber.tecnico.databinding.FragmentInventarioBinding
import com.enetfiber.tecnico.ui.ConsumoState
import com.enetfiber.tecnico.ui.InventarioViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class InventarioFragment : Fragment() {

    private var _binding: FragmentInventarioBinding? = null
    private val binding get() = _binding!!
    private val vm: InventarioViewModel by activityViewModels()

    private lateinit var itemsAdapter: InventarioItemAdapter
    private lateinit var consumosAdapter: ConsumoAdapter
    private lateinit var recojoAdapter:   RecojoAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentInventarioBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclers()
        setupObservers()
        setupListeners()
    }

    private fun setupRecyclers() {
        itemsAdapter = InventarioItemAdapter()
        binding.rvItems.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = itemsAdapter
            isNestedScrollingEnabled = false
        }

        consumosAdapter = ConsumoAdapter()
        recojoAdapter   = RecojoAdapter()
        binding.rvConsumos.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = recojoAdapter   // Mostrar recojos (materiales reciclados)
            isNestedScrollingEnabled = false
            setHasFixedSize(false)
        }
    }

    private fun setupObservers() {
        vm.uiState.observe(viewLifecycleOwner) { state ->
            val asignados  = state.totalAsignados.toInt()
            val utilizados = state.totalUtilizados.toInt()
            val sinStock   = state.totalSinStock

            binding.tvAsignados.text  = asignados.toString()
            binding.tvUtilizados.text = utilizados.toString()
            // tvDisponibles lo maneja vm.items para contar correctamente medibles

            val pct = if (asignados > 0) (utilizados * 100 / asignados) else 0
            binding.tvPorcentajeUtilizado.text =
                if (asignados > 0) "$pct% del total asignado" else ""

            if (sinStock > 0) {
                binding.tvSinStock.text = "$sinStock ítem(s) sin stock"
                binding.tvSinStock.visibility = View.VISIBLE
            } else {
                binding.tvSinStock.visibility = View.GONE
            }

            binding.bannerOffline.visibility =
                if (!vm.isOnline) View.VISIBLE else View.GONE

            state.mensaje?.let { msg ->
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                vm.limpiarMensaje()
            }
        }

        vm.items.observe(viewLifecycleOwner) { items ->
            itemsAdapter.submitList(items)

            // Contar items con stock — el rollo de fibra cuenta como 1 item aunque sea medible
            val conStock = items.count { !it.sinStock }
            binding.tvDisponibles.text       = conStock.toString()
            binding.tvDisponiblesHeader.text = "$conStock disponibles"

            binding.tvEmptyItems.visibility =
                if (items.isEmpty()) View.VISIBLE else View.GONE

            val sinStockLst = items.filter { it.sinStock }
            if (sinStockLst.isNotEmpty()) {
                binding.bannerSinStock.visibility = View.VISIBLE
                binding.tvListaSinStock.text = sinStockLst.joinToString(", ") { it.nombre } +
                        ". Contactate con el administrador."
            } else {
                binding.bannerSinStock.visibility = View.GONE
            }
        }

        // Materiales reciclados: equipos recogidos de clientes
        vm.recojos.observe(viewLifecycleOwner) { recojos ->
            android.util.Log.d("InventarioFrag", "recojos observer: ${recojos.size} items")
            recojos.forEach { r ->
                android.util.Log.d("InventarioFrag", "  → id=${r.id} nombre=${r.nombreProducto} tipo=${r.tipoEquipo}")
            }
            recojoAdapter.submitList(recojos)
            binding.tvEmptyConsumos.visibility =
                if (recojos.isEmpty()) View.VISIBLE else View.GONE
        }

        // Banner de consumos pendientes de sync (materiales gastados)
        vm.consumosPendientes.observe(viewLifecycleOwner) { consumos ->
            val pendientes = consumos.count { c -> !c.sincronizado }
            binding.bannerPendientes.visibility =
                if (pendientes > 0) View.VISIBLE else View.GONE
            binding.tvPendientesSync.text =
                "⏳ $pendientes gasto(s) pendiente(s) de sincronizar"
        }

        vm.consumoState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is ConsumoState.Exito -> {
                    Toast.makeText(requireContext(), "✅ Gasto registrado", Toast.LENGTH_SHORT).show()
                    vm.resetConsumoState()
                }
                is ConsumoState.Error -> {
                    Toast.makeText(requireContext(), state.msg, Toast.LENGTH_LONG).show()
                    vm.resetConsumoState()
                }
                else -> { /* Idle / Guardando — no hacer nada */ }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        android.util.Log.d("InventarioFrag", "onResume → cargarMetricas")
        vm.cargarMetricas(sincronizar = true)
    }

    private fun setupListeners() {
        binding.swipeRefresh.setOnRefreshListener {
            vm.cargarMetricas(sincronizar = true)
            binding.swipeRefresh.isRefreshing = false
        }
        binding.btnRegistrarConsumo.setOnClickListener {
            mostrarDialogConsumo()
        }
    }

    private fun mostrarDialogConsumo() {
        val itemsDisponibles = vm.items.value?.filter { item -> item.disponible > 0 }
            ?: emptyList()

        if (itemsDisponibles.isEmpty()) {
            Toast.makeText(requireContext(), "No tienes items disponibles", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_registrar_consumo, null)

        val container  = dialogView.findViewById<ViewGroup>(R.id.containerItems)
        val btnAgregar = dialogView.findViewById<View>(R.id.btnAgregarItem)
        val etDesc     = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(
            R.id.etDescripcion
        )

        data class ConsumoRow(var productoId: Int = -1, var nombre: String = "", var cantidad: String = "")
        val rows = mutableListOf<ConsumoRow>()

        fun agregarFila(row: ConsumoRow = ConsumoRow()) {
            rows.add(row)
            val rowView = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_consumo_row, container, false)

            val etProducto = rowView.findViewById<com.google.android.material.textfield.TextInputEditText>(
                R.id.etProducto
            )
            val etCantidad = rowView.findViewById<com.google.android.material.textfield.TextInputEditText>(
                R.id.etCantidad
            )
            val btnElim = rowView.findViewById<View>(R.id.btnEliminar)

            etProducto.setOnClickListener {
                val nombres = itemsDisponibles
                    .map { item -> "${item.nombre} (disp: ${item.disponible.toInt()} ${item.unidad})" }
                    .toTypedArray()

                AlertDialog.Builder(requireContext())
                    .setTitle("Seleccionar producto")
                    .setItems(nombres) { _, idx ->
                        val selected = itemsDisponibles[idx]
                        etProducto.setText(selected.nombre)
                        row.productoId = selected.productoId
                        row.nombre     = selected.nombre
                    }
                    .show()
            }

            etCantidad.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    row.cantidad = s?.toString() ?: ""
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })

            btnElim.setOnClickListener {
                container.removeView(rowView)
                rows.remove(row)
            }

            container.addView(rowView)
        }

        agregarFila()

        btnAgregar.setOnClickListener { agregarFila() }

        AlertDialog.Builder(requireContext())
            .setTitle("Registrar material gastado")
            .setView(dialogView)
            .setPositiveButton("Registrar") { _, _ ->
                val itemsValidos = rows
                    .filter { r -> r.productoId >= 0 && (r.cantidad.toDoubleOrNull() ?: 0.0) > 0 }
                    .map { r -> ConsumoItemRequest(r.productoId, r.cantidad.toDouble()) }

                if (itemsValidos.isEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        "Agrega al menos un item con cantidad",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }

                val nombresMap = rows
                    .filter { r -> r.productoId >= 0 }
                    .associate { r -> r.productoId to r.nombre }

                vm.registrarConsumo(
                    items       = itemsValidos,
                    descripcion = etDesc.text?.toString()?.ifBlank { null },
                    nombresMap  = nombresMap
                )
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = InventarioFragment()
    }
}

// ── Adapter: items de inventario ─────────────────────────────
class InventarioItemAdapter :
    ListAdapter<InventarioItemEntity, InventarioItemAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<InventarioItemEntity>() {
            override fun areItemsTheSame(
                oldItem: InventarioItemEntity,
                newItem: InventarioItemEntity
            ) = oldItem.productoId == newItem.productoId

            override fun areContentsTheSame(
                oldItem: InventarioItemEntity,
                newItem: InventarioItemEntity
            ) = oldItem == newItem
        }
    }

    inner class VH(val view: View) : RecyclerView.ViewHolder(view) {
        val tvCodigo     : TextView = view.findViewById(R.id.tvCodigo)
        val tvNombre     : TextView = view.findViewById(R.id.tvNombre)
        val tvCategoria  : TextView = view.findViewById(R.id.tvCategoria)
        val tvDisponible : TextView = view.findViewById(R.id.tvDisponible)
        val tvUnidad     : TextView = view.findViewById(R.id.tvUnidad)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_inventario, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.tvCodigo.text    = item.codigo.ifBlank { "—" }
        holder.tvNombre.text    = item.nombre
        holder.tvCategoria.text = item.categoria.ifBlank { "" }

        val color = when {
            item.sinStock       -> Color.parseColor("#EF4444")
            item.disponible < 5 -> Color.parseColor("#D97706")
            else                -> Color.parseColor("#16A34A")
        }

        if (item.esMedible && item.metrosPorUnidad != null && item.disponibleMetros != null) {
            // Mostrar metros para productos medibles
            holder.tvDisponible.text = item.disponibleMetros.toInt().toString()
            holder.tvUnidad.text     = " m"
        } else {
            holder.tvDisponible.text = item.disponible.toInt().toString()
            holder.tvUnidad.text     = " ${item.unidad}"
        }
        holder.tvDisponible.setTextColor(color)
        holder.view.setBackgroundColor(Color.WHITE)
    }
}

// ── Adapter: historial de consumos ───────────────────────────
class ConsumoAdapter :
    ListAdapter<ConsumoPendienteEntity, ConsumoAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<ConsumoPendienteEntity>() {
            override fun areItemsTheSame(
                oldItem: ConsumoPendienteEntity,
                newItem: ConsumoPendienteEntity
            ): Boolean {
                // Usar productoId + descripcion como identidad estable
                // (los ids cambian al re-insertar desde servidor)
                return oldItem.productoId == newItem.productoId &&
                        oldItem.descripcion == newItem.descripcion &&
                        oldItem.creadoEn == newItem.creadoEn
            }

            override fun areContentsTheSame(
                oldItem: ConsumoPendienteEntity,
                newItem: ConsumoPendienteEntity
            ) = oldItem.nombre == newItem.nombre &&
                    oldItem.cantidad == newItem.cantidad &&
                    oldItem.sincronizado == newItem.sincronizado &&
                    oldItem.nServicio == newItem.nServicio
        }
    }

    inner class VH(val view: View) : RecyclerView.ViewHolder(view) {
        val tvNombre      : TextView = view.findViewById(R.id.tvConsumoNombre)
        val tvDescripcion : TextView = view.findViewById(R.id.tvConsumoDescripcion)
        val tvCantidad    : TextView = view.findViewById(R.id.tvConsumoCantidad)
        val tvSync        : TextView = view.findViewById(R.id.tvConsumoSync)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_consumo, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val consumo = getItem(position)
        holder.tvNombre.text   = consumo.nombre
        holder.tvCantidad.text = "-${consumo.cantidad.toInt()}"
        holder.tvSync.text     = if (consumo.sincronizado) "✓ sync" else "⏳ pendiente"

        // Mostrar contrato y cliente si están disponibles
        holder.tvDescripcion.text = when {
            consumo.nServicio != null -> buildString {
                append("Contrato: #${consumo.nServicio}")
                if (!consumo.abonado.isNullOrBlank()) append("  ·  ${consumo.abonado}")
            }
            !consumo.descripcion.isNullOrBlank() &&
                    !consumo.descripcion.startsWith("Orden:") -> consumo.descripcion
            else -> consumo.motivo
        }

        val colorSync = if (consumo.sincronizado)
            Color.parseColor("#27AE60")
        else
            Color.parseColor("#E67E22")
        holder.tvSync.setTextColor(colorSync)
    }
}
// ── Adapter: materiales reciclados (recojos) ──────────────────
class RecojoAdapter :
    ListAdapter<com.enetfiber.tecnico.data.remote.RecojoDto,
            RecojoAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<com.enetfiber.tecnico.data.remote.RecojoDto>() {
            override fun areItemsTheSame(
                a: com.enetfiber.tecnico.data.remote.RecojoDto,
                b: com.enetfiber.tecnico.data.remote.RecojoDto
            ) = a.id == b.id
            override fun areContentsTheSame(
                a: com.enetfiber.tecnico.data.remote.RecojoDto,
                b: com.enetfiber.tecnico.data.remote.RecojoDto
            ) = a == b
        }
    }

    inner class VH(val view: View) : RecyclerView.ViewHolder(view) {
        val tvNombre:    TextView = view.findViewById(R.id.tvRecojoNombre)
        val tvPon:       TextView = view.findViewById(R.id.tvRecojoPon)
        val tvCategoria: TextView = view.findViewById(R.id.tvRecojoCategoria)
        val tvEstado:    TextView = view.findViewById(R.id.tvRecojoEstado)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recojo, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val recojo = getItem(position)

        // Nombre del producto (del catálogo) o tipo de equipo como fallback
        holder.tvNombre.text = recojo.nombreProducto?.ifBlank { null }
            ?: recojo.tipoEquipo.ifBlank { "Equipo" }

        // Código PON-SN si existe
        if (!recojo.codigoPon.isNullOrBlank()) {
            holder.tvPon.text = "◈ ${recojo.codigoPon}"
            holder.tvPon.visibility = View.VISIBLE
        } else {
            holder.tvPon.visibility = View.GONE
        }

        // Comentario u orden asociada
        // Mostrar contrato y cliente si están disponibles
        holder.tvCategoria.text = when {
            !recojo.contrato.isNullOrBlank() -> buildString {
                append("Contrato: ${recojo.contrato}")
                if (!recojo.abonado.isNullOrBlank()) append("  ·  ${recojo.abonado}")
            }
            !recojo.abonado.isNullOrBlank() -> recojo.abonado
            !recojo.comentario.isNullOrBlank() -> recojo.comentario
            else -> ""
        }

        // Estado con color
        val (estadoTexto, textColor, bgColor) = when (recojo.estado) {
            "entregado" -> Triple("entregado", "#16A34A", "#DCFCE7")
            "revision"  -> Triple("en revisión", "#2563EB", "#EFF6FF")
            else        -> Triple("pendiente",   "#D97706", "#FEF3C7")
        }
        holder.tvEstado.text = estadoTexto
        holder.tvEstado.setTextColor(android.graphics.Color.parseColor(textColor))
        holder.tvEstado.setBackgroundColor(android.graphics.Color.parseColor(bgColor))
    }
}