package com.enetfiber.tecnico.ui.plantaexterna

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.enetfiber.tecnico.R
import com.enetfiber.tecnico.data.local.InventarioItemEntity
import com.enetfiber.tecnico.data.remote.*
import com.enetfiber.tecnico.ui.InventarioViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class TrabajoPEDetailSheet : BottomSheetDialogFragment() {

    private val vm: PlantaExternaViewModel by lazy {
        ViewModelProvider(requireParentFragment())[PlantaExternaViewModel::class.java]
    }
    private val inventarioVm: InventarioViewModel by lazy {
        ViewModelProvider(requireParentFragment())[InventarioViewModel::class.java]
    }

    private var trabajoId: String = ""

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_trabajo_pe_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        trabajoId = arguments?.getString(ARG_ID) ?: return

        vm.cargarDetalle(trabajoId)

        vm.trabajoDetalle.observe(viewLifecycleOwner) { trabajo ->
            trabajo ?: return@observe
            bindDetalle(view, trabajo)
        }

        vm.state.observe(viewLifecycleOwner) { state ->
            when (state) {
                is PlantaExternaState.Exito -> {
                    Toast.makeText(requireContext(), "✅ ${state.mensaje}", Toast.LENGTH_SHORT).show()
                    vm.resetState()
                    if (state.mensaje.contains("completado", ignoreCase = true)) dismiss()
                }
                is PlantaExternaState.Error -> {
                    Toast.makeText(requireContext(), state.msg, Toast.LENGTH_LONG).show()
                    vm.resetState()
                }
                else -> {}
            }
        }
    }

    private fun bindDetalle(view: View, trabajo: TrabajoPEDto) {
        val dp = resources.displayMetrics.density
        val enCurso = trabajo.estado == "EN_CURSO"

        // ── Tipo badge ────────────────────────────────────────
        val tvTipoBadge = view.findViewById<TextView>(R.id.tvTipoBadge)
        val (tipoLabel, tipoBg, tipoColor) = when (trabajo.tipo) {
            "PROYECTO"      -> Triple("🏗 Proyecto",       "#EDE9FE", "#6D28D9")
            "AVERIA_MASIVA" -> Triple("⚡ Avería masiva",  "#FEE2E2", "#DC2626")
            "MANTENIMIENTO" -> Triple("🔧 Mantenimiento",  "#FEF3C7", "#D97706")
            else            -> Triple(trabajo.tipo,        "#F1F5F9", "#64748B")
        }
        tvTipoBadge.text = tipoLabel
        tvTipoBadge.setTextColor(Color.parseColor(tipoColor))
        tvTipoBadge.background = GradientDrawable().apply {
            setColor(Color.parseColor(tipoBg))
            cornerRadius = 20f * dp
        }

        // ── Estado badge ──────────────────────────────────────
        val tvEstadoBadge = view.findViewById<TextView>(R.id.tvEstadoBadge)
        tvEstadoBadge.text = if (enCurso) "● En curso" else "● Completado"
        tvEstadoBadge.setTextColor(Color.parseColor(if (enCurso) "#16A34A" else "#2563EB"))
        tvEstadoBadge.background = GradientDrawable().apply {
            setColor(Color.parseColor(if (enCurso) "#DCFCE7" else "#EFF6FF"))
            cornerRadius = 20f * dp
        }

        // ── Nombre, descripción, ubicación ────────────────────
        view.findViewById<TextView>(R.id.tvNombreDetalle).text = trabajo.nombre

        val tvDesc = view.findViewById<TextView>(R.id.tvDescripcionDetalle)
        if (!trabajo.descripcion.isNullOrBlank()) {
            tvDesc.text = trabajo.descripcion
            tvDesc.visibility = View.VISIBLE
        }

        val tvUbic = view.findViewById<TextView>(R.id.tvUbicacionDetalle)
        if (!trabajo.ubicacion.isNullOrBlank()) {
            tvUbic.text = "📍 ${trabajo.ubicacion}"
            tvUbic.visibility = View.VISIBLE
        }

        // ── Fechas ────────────────────────────────────────────
        view.findViewById<TextView>(R.id.tvFechaInicio).text =
            "Inicio: ${trabajo.fechaInicio.take(10)}"
        val tvFechaFin = view.findViewById<TextView>(R.id.tvFechaFin)
        trabajo.fechaFin?.let {
            tvFechaFin.text = "Fin: ${it.take(10)}"
            tvFechaFin.visibility = View.VISIBLE
        }

        // ── Técnicos ──────────────────────────────────────────
        val tvTecnicos = view.findViewById<TextView>(R.id.tvTecnicosDetalle)
        tvTecnicos.text = if (trabajo.tecnicos.isEmpty()) "Sin técnicos asignados"
        else trabajo.tecnicos.joinToString("\n") { t ->
            "👷 ${t.tecnico.usuario.nombre} ${t.tecnico.usuario.apellido}"
        }

        // ── Materiales ────────────────────────────────────────
        val consumos = trabajo.consumos ?: emptyList()
        view.findViewById<TextView>(R.id.tvConteoMateriales).text =
            "${consumos.size} ítem${if (consumos.size != 1) "s" else ""}"

        val containerMats = view.findViewById<LinearLayout>(R.id.containerMateriales)
        containerMats.removeAllViews()

        if (consumos.isEmpty()) {
            containerMats.addView(TextView(requireContext()).apply {
                text = "Sin materiales registrados"
                textSize = 13f
                setTextColor(Color.parseColor("#94A3B8"))
            })
        } else {
            consumos.forEachIndexed { i, c ->
                val esMedible = c.producto?.esMedible == true && c.producto.metrosPorUnidad != null
                val valor = if (esMedible) c.cantidad * (c.producto?.metrosPorUnidad ?: 1.0) else c.cantidad
                val unidad = if (esMedible) "m" else (c.producto?.unidad ?: "und")
                val cantStr = if (valor % 1 == 0.0) valor.toInt().toString() else "%.1f".format(valor)

                val row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = if (i < consumos.size - 1) (10*dp).toInt() else 0 }
                }
                row.addView(TextView(requireContext()).apply {
                    text = c.producto?.nombre ?: "Producto"
                    textSize = 13f
                    setTextColor(Color.parseColor("#0F172A"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(TextView(requireContext()).apply {
                    text = "-$cantStr $unidad"
                    textSize = 13f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#E67E22"))
                })
                containerMats.addView(row)

                if (i < consumos.size - 1) {
                    containerMats.addView(View(requireContext()).apply {
                        setBackgroundColor(Color.parseColor("#F1F5F9"))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 1
                        ).apply { topMargin = (10*dp).toInt() }
                    })
                }
            }
        }

        // ── Botones ───────────────────────────────────────────
        val layoutBotones = view.findViewById<LinearLayout>(R.id.layoutBotonesAccion)
        val btnMaterial   = view.findViewById<MaterialButton>(R.id.btnAgregarMaterial)
        val btnCompletar  = view.findViewById<MaterialButton>(R.id.btnCompletar)

        if (enCurso) {
            layoutBotones.visibility = View.VISIBLE
            btnMaterial.setOnClickListener { mostrarDialogAgregarMaterial(trabajo) }
            btnCompletar.setOnClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("Completar trabajo")
                    .setMessage("¿Marcar '${trabajo.nombre}' como completado?\nNo podrás agregar más materiales.")
                    .setPositiveButton("Completar") { _, _ -> vm.completarTrabajo(trabajo.id) }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        } else {
            layoutBotones.visibility = View.GONE
        }
    }

    // ── Agregar material ──────────────────────────────────────
    private fun mostrarDialogAgregarMaterial(trabajo: TrabajoPEDto) {
        val itemsActuales = inventarioVm.items.value?.filter { item: InventarioItemEntity ->
            val tieneStock = item.disponible > 0 || (item.esMedible && (item.disponibleMetros ?: 0.0) > 0)
            val esOnu = item.nombre.contains("ONU", ignoreCase = true) ||
                    item.nombre.contains("ONT", ignoreCase = true)
            tieneStock && !esOnu
        } ?: emptyList()

        if (itemsActuales.isEmpty()) {
            // Observar hasta que lleguen los datos
            Toast.makeText(requireContext(), "Cargando inventario...", Toast.LENGTH_SHORT).show()
            inventarioVm.items.observe(viewLifecycleOwner) { items ->
                val filtrados = items?.filter { item: InventarioItemEntity ->
                    val tieneStock = item.disponible > 0 || (item.esMedible && (item.disponibleMetros ?: 0.0) > 0)
                    val esOnu = item.nombre.contains("ONU", ignoreCase = true) ||
                            item.nombre.contains("ONT", ignoreCase = true)
                    tieneStock && !esOnu
                } ?: emptyList()
                if (filtrados.isNotEmpty()) {
                    inventarioVm.items.removeObservers(viewLifecycleOwner)
                    abrirDialogMaterial(trabajo, filtrados)
                }
            }
            return
        }

        abrirDialogMaterial(trabajo, itemsActuales)
    }

    private fun abrirDialogMaterial(trabajo: TrabajoPEDto, inventario: List<InventarioItemEntity>) {
        val dp = resources.displayMetrics.density
        val ctx = requireContext()

        val sheet = android.app.Dialog(ctx, com.google.android.material.R.style.ThemeOverlay_Material3_Dialog)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // ── Título ────────────────────────────────────────────
        root.addView(TextView(ctx).apply {
            text = "Agregar materiales"
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0D1B2A"))
            setPadding((20*dp).toInt(), (20*dp).toInt(), (20*dp).toInt(), (12*dp).toInt())
        })

        // Divisor
        root.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#DDE6F0"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })

        // ── ScrollView con rows ───────────────────────────────
        val scrollView = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val rowsContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12*dp).toInt(), (12*dp).toInt(), (12*dp).toInt(), (4*dp).toInt())
        }
        scrollView.addView(rowsContainer)
        root.addView(scrollView)

        // Set de índices ya seleccionados (índice real en inventario)
        val selectedIndices = mutableSetOf<Int>()

        data class MaterialRow(
            val view: View,
            val spinner: Spinner,
            val etCantidad: EditText,
            val tvHint: TextView,
            var selectedIndex: Int = -1
        )
        val rows = mutableListOf<MaterialRow>()

        // Nombres completos del inventario
        fun nombreItem(item: InventarioItemEntity): String {
            val esMedible = item.esMedible && item.disponibleMetros != null
            return if (esMedible)
                "${item.nombre} (${item.disponibleMetros!!.toInt()} m disp.)"
            else
                "${item.nombre} (${item.disponible.toInt()} ${item.unidad} disp.)"
        }

        // Refrescar el adapter de un spinner con los items disponibles
        fun refrescarSpinner(row: MaterialRow) {
            val opciones = mutableListOf("— Seleccionar producto —")
            inventario.forEachIndexed { idx, item ->
                // Mostrar si no está seleccionado por OTRO row
                if (!selectedIndices.contains(idx) || idx == row.selectedIndex) {
                    opciones.add(nombreItem(item))
                }
            }
            val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, opciones)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

            // Guardar posición actual antes de cambiar adapter
            val posAnterior = if (row.selectedIndex >= 0) {
                opciones.indexOfFirst { it == nombreItem(inventario[row.selectedIndex]) }
                    .takeIf { it >= 0 } ?: 0
            } else 0

            row.spinner.adapter = adapter
            row.spinner.setSelection(posAnterior)
        }

        fun addRow() {
            val inflater = android.view.LayoutInflater.from(ctx)
            val rowView = inflater.inflate(R.layout.item_material_row, rowsContainer, false)

            val spinner   = rowView.findViewById<Spinner>(R.id.spinnerItem)
            val etCant    = rowView.findViewById<EditText>(R.id.etCantidad)
            val btnDelete = rowView.findViewById<android.widget.ImageView>(R.id.btnEliminar)
            val tvHint    = rowView.findViewById<TextView>(R.id.tvHint)

            val row = MaterialRow(rowView, spinner, etCant, tvHint)
            rows.add(row)

            // Cargar spinner con items disponibles
            refrescarSpinner(row)

            var maxCantidad = 0

            var primeraVez = true

            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                    if (primeraVez) {
                        primeraVez = false
                        return
                    }
                    if (row.selectedIndex >= 0) {
                        selectedIndices.remove(row.selectedIndex)
                    }

                    // Calcular índice real en inventario
                    // El adapter solo muestra items disponibles + placeholder
                    // Necesitamos mapear pos -> índice real
                    if (pos == 0) {
                        row.selectedIndex = -1
                        tvHint.visibility = View.GONE
                        maxCantidad = 0
                        // Refrescar otros
                        rows.filter { it != row }.forEach { refrescarSpinner(it) }
                        return
                    }

                    // Reconstruir mapa de posición -> índice real
                    val indicesDisponibles = inventario.indices.filter { idx ->
                        !selectedIndices.contains(idx) || idx == row.selectedIndex
                    }
                    val realIndex = indicesDisponibles.getOrNull(pos - 1) ?: return

                    row.selectedIndex = realIndex
                    selectedIndices.add(realIndex)

                    val item = inventario[realIndex]
                    val esMedible = item.esMedible && item.metrosPorUnidad != null && item.metrosPorUnidad > 0
                    maxCantidad = if (esMedible)
                        item.disponibleMetros?.toInt() ?: 0
                    else
                        item.disponible.toInt()

                    val unidad = if (esMedible) "m" else item.unidad
                    tvHint.text = "Unidad: $unidad · Máx: $maxCantidad"
                    tvHint.visibility = View.VISIBLE
                    etCant.inputType = if (esMedible)
                        android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    else
                        android.text.InputType.TYPE_CLASS_NUMBER

                    // Refrescar otros spinners
                    rows.filter { it != row }.forEach { refrescarSpinner(it) }
                }
                override fun onNothingSelected(p: AdapterView<*>) {}
            }

            etCant.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val cant = s.toString().toIntOrNull() ?: return
                    if (maxCantidad > 0 && cant > maxCantidad) {
                        etCant.removeTextChangedListener(this)
                        etCant.setText(maxCantidad.toString())
                        etCant.setSelection(etCant.text.length)
                        etCant.addTextChangedListener(this)
                        Toast.makeText(ctx, "Máximo disponible: $maxCantidad", Toast.LENGTH_SHORT).show()
                    }
                }
            })

            btnDelete.setOnClickListener {
                if (row.selectedIndex >= 0) selectedIndices.remove(row.selectedIndex)
                rows.remove(row)
                rowsContainer.removeView(rowView)
                // Refrescar todos los spinners al eliminar
                rows.forEach { refrescarSpinner(it) }
            }

            rowsContainer.addView(rowView)
        }

        addRow()

        // ── Botón + Agregar otro ──────────────────────────────
        val btnAgregarOtro = TextView(ctx).apply {
            text = "+ Agregar otro material"
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1E4C8A"))
            gravity = Gravity.CENTER
            setPadding((20*dp).toInt(), (10*dp).toInt(), (20*dp).toInt(), (10*dp).toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (selectedIndices.size >= inventario.size) {
                    Toast.makeText(ctx, "Ya seleccionaste todos los materiales disponibles", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                addRow()
            }
        }
        root.addView(btnAgregarOtro)

        // Divisor
        root.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#DDE6F0"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })

        // ── Botón confirmar ───────────────────────────────────
        val btnConfirmar = TextView(ctx).apply {
            text = "Registrar materiales"
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E4C8A"))
                cornerRadius = 12f * dp
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (52*dp).toInt()
            ).apply {
                leftMargin   = (20*dp).toInt()
                rightMargin  = (20*dp).toInt()
                topMargin    = (12*dp).toInt()
                bottomMargin = (24*dp).toInt()
            }
            isClickable = true
            isFocusable = true
        }

        btnConfirmar.setOnClickListener {
            val items = mutableListOf<MaterialPEItem>()
            var valido = true

            for (row in rows) {
                if (row.selectedIndex < 0) {
                    Toast.makeText(ctx, "Selecciona un producto en todos los campos", Toast.LENGTH_SHORT).show()
                    valido = false
                    break
                }
                val cant = row.etCantidad.text.toString().toDoubleOrNull()
                if (cant == null || cant <= 0) {
                    Toast.makeText(ctx, "Ingresa una cantidad válida en todos los materiales", Toast.LENGTH_SHORT).show()
                    valido = false
                    break
                }
                val item = inventario[row.selectedIndex]
                val esMedible = item.esMedible && item.metrosPorUnidad != null && item.metrosPorUnidad > 0
                val cantFinal = if (esMedible) cant / item.metrosPorUnidad!! else cant
                items.add(MaterialPEItem(item.productoId, cantFinal))
            }

            if (valido && items.isNotEmpty()) {
                vm.agregarMaterial(trabajoId = trabajo.id, items = items, comentario = null)
                sheet.dismiss()
            }
        }

        root.addView(btnConfirmar)

        sheet.setContentView(root)
        sheet.window?.apply {
            setLayout(
                (resources.displayMetrics.widthPixels * 0.92).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.CENTER)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
        root.background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = 16f * dp
        }
        sheet.show()
    }

    override fun onStart() {
        super.onStart()
        val bottomSheet = dialog?.findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet
        )
        bottomSheet?.let {
            BottomSheetBehavior.from(it).state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    companion object {
        const val ARG_ID = "trabajo_id"
        const val TAG = "TrabajoPEDetailSheet"

        fun newInstance(trabajoId: String) = TrabajoPEDetailSheet().apply {
            arguments = Bundle().apply { putString(ARG_ID, trabajoId) }
        }
    }
}