package com.enetfiber.tecnico.ui.plantaexterna

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.enetfiber.tecnico.R
import com.enetfiber.tecnico.data.local.InventarioItemEntity
import com.enetfiber.tecnico.data.remote.*
import com.enetfiber.tecnico.ui.InventarioViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class PlantaExternaFragment : Fragment() {

    private val vm: PlantaExternaViewModel by viewModels()

    private val inventarioVm: InventarioViewModel by lazy {
        ViewModelProvider(requireParentFragment())[InventarioViewModel::class.java]
    }

    private lateinit var rvTrabajos: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvSubtitulo: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var spinnerTipo: Spinner
    private lateinit var spinnerEstado: Spinner
    private lateinit var adapter: TrabajoPEAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_planta_externa, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Bind views
        rvTrabajos  = view.findViewById(R.id.rvTrabajos)
        tvEmpty     = view.findViewById(R.id.tvEmpty)
        tvSubtitulo = view.findViewById(R.id.tvSubtitulo)
        progressBar = view.findViewById(R.id.progressBar)
        spinnerTipo = view.findViewById(R.id.spinnerTipo)
        spinnerEstado = view.findViewById(R.id.spinnerEstado)

        val fab = view.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fabNuevo)
        fab.setOnClickListener { mostrarDialogCrear() }

        // Setup RecyclerView
        adapter = TrabajoPEAdapter { trabajo -> mostrarDetalle(trabajo) }
        rvTrabajos.layoutManager = LinearLayoutManager(requireContext())
        rvTrabajos.adapter = adapter

        // Spinners
        ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item,
            listOf("Todos los tipos", "Proyecto", "Avería masiva", "Mantenimiento")
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerTipo.adapter = it
        }
        ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item,
            listOf("Todos", "En curso", "Completado")
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerEstado.adapter = it
        }

        // Observers
        vm.trabajos.observe(viewLifecycleOwner) { lista ->
            adapter.submitList(lista)
            tvEmpty.visibility = if (lista.isEmpty()) View.VISIBLE else View.GONE
            val enCurso = lista.count { it.estado == "EN_CURSO" }
            tvSubtitulo.text = "${lista.size} trabajo${if (lista.size != 1) "s" else ""} · $enCurso en curso"
        }
        vm.cargando.observe(viewLifecycleOwner) { cargando ->
            progressBar.visibility = if (cargando) View.VISIBLE else View.GONE
        }
        vm.state.observe(viewLifecycleOwner) { state ->
            when (state) {
                is PlantaExternaState.Exito -> {
                    Toast.makeText(requireContext(), "✅ ${state.mensaje}", Toast.LENGTH_SHORT).show()
                    vm.resetState()
                }
                is PlantaExternaState.Error -> {
                    Toast.makeText(requireContext(), state.msg, Toast.LENGTH_LONG).show()
                    vm.resetState()
                }
                else -> {}
            }
        }

        // Filtros
        val filtroListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                vm.filtroTipo = when (spinnerTipo.selectedItemPosition) {
                    1 -> "PROYECTO"; 2 -> "AVERIA_MASIVA"; 3 -> "MANTENIMIENTO"; else -> null
                }
                vm.filtroEstado = when (spinnerEstado.selectedItemPosition) {
                    1 -> "EN_CURSO"; 2 -> "COMPLETADO"; else -> null
                }
                vm.cargarTrabajos()
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }
        spinnerTipo.onItemSelectedListener = filtroListener
        spinnerEstado.onItemSelectedListener = filtroListener

        vm.cargarTrabajos()
    }

    // ── Dialog: Crear ─────────────────────────────────────────
    private fun mostrarDialogCrear() {
        val dp = resources.displayMetrics.density
        val ctx = requireContext()

        val dialog = android.app.Dialog(ctx, com.google.android.material.R.style.ThemeOverlay_Material3_Dialog)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding((20*dp).toInt(), (20*dp).toInt(), (20*dp).toInt(), (20*dp).toInt())
        }
        root.background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = 16f * dp
        }

        // Título
        root.addView(TextView(ctx).apply {
            text = "Nuevo trabajo"
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0D1B2A"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (16*dp).toInt() }
        })

        fun label(text: String) = TextView(ctx).apply {
            this.text = text
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1E4C8A"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (4*dp).toInt() }
        }

        fun input(hint: String) = EditText(ctx).apply {
            this.hint = hint
            textSize = 14f
            setTextColor(Color.parseColor("#0D1B2A"))
            setHintTextColor(Color.parseColor("#C7C7CC"))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 8f * dp
                setStroke((1*dp).toInt(), Color.parseColor("#CBD5E1"))
            }
            setPadding((12*dp).toInt(), (10*dp).toInt(), (12*dp).toInt(), (10*dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12*dp).toInt() }
        }

        // Tipo
        root.addView(label("TIPO"))
        val spinnerTipoCrear = Spinner(ctx).apply {
            background = requireContext().getDrawable(R.drawable.input_bg)
            setPadding((12*dp).toInt(), 0, (12*dp).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (46*dp).toInt()
            ).apply { bottomMargin = (12*dp).toInt() }
        }
        ArrayAdapter(ctx, android.R.layout.simple_spinner_item,
            listOf("Proyecto", "Avería masiva", "Mantenimiento")
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerTipoCrear.adapter = it
        }
        root.addView(spinnerTipoCrear)

        // Nombre
        root.addView(label("NOMBRE *"))
        val etNombre = input("Nueva zona sur...")
        root.addView(etNombre)

        // Descripción
        root.addView(label("DESCRIPCIÓN"))
        val etDesc = input("Opcional...").apply {
            minLines = 2
        }
        root.addView(etDesc)

        // Fecha de inicio
        root.addView(label("FECHA DE INICIO"))
        val etFecha = EditText(ctx).apply {
            hint = "dd/mm/aaaa"
            textSize = 14f
            setTextColor(Color.parseColor("#0D1B2A"))
            setHintTextColor(Color.parseColor("#C7C7CC"))
            inputType = android.text.InputType.TYPE_CLASS_DATETIME or
                    android.text.InputType.TYPE_DATETIME_VARIATION_DATE
            background = requireContext().getDrawable(R.drawable.input_bg)
            setPadding((12*dp).toInt(), (10*dp).toInt(), (12*dp).toInt(), (10*dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12*dp).toInt() }
            // Abrir DatePicker al tocar
            isFocusable = false
            isClickable = true
            setOnClickListener {
                val cal = java.util.Calendar.getInstance()
                android.app.DatePickerDialog(ctx, { _, year, month, day ->
                    val fecha = "%04d-%02d-%02d".format(year, month + 1, day)
                    setText(fecha)
                }, cal.get(java.util.Calendar.YEAR),
                    cal.get(java.util.Calendar.MONTH),
                    cal.get(java.util.Calendar.DAY_OF_MONTH)
                ).show()
            }
        }
        root.addView(etFecha)

        // Ubicación (solo avería)
        val tvUbicLabel = label("UBICACIÓN *").apply { visibility = View.GONE }
        val etUbicacion = input("Ej: Av. Principal km 3").apply { visibility = View.GONE }
        root.addView(tvUbicLabel)
        root.addView(etUbicacion)

        spinnerTipoCrear.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                val esAveria = pos == 1
                tvUbicLabel.visibility = if (esAveria) View.VISIBLE else View.GONE
                etUbicacion.visibility = if (esAveria) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }

        // ── Botones ───────────────────────────────────────────
        val botonesRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4*dp).toInt() }
        }

        val btnCancelar = TextView(ctx).apply {
            text = "Cancelar"
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 10f * dp
                setStroke((1*dp).toInt(), Color.parseColor("#CBD5E1"))
            }
            layoutParams = LinearLayout.LayoutParams(0, (46*dp).toInt(), 1f).apply {
                marginEnd = (8*dp).toInt()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
        }

        val btnCrear = TextView(ctx).apply {
            text = "Crear"
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E4C8A"))
                cornerRadius = 10f * dp
            }
            layoutParams = LinearLayout.LayoutParams(0, (46*dp).toInt(), 1f)
            isClickable = true
            isFocusable = true
        }

        btnCrear.setOnClickListener {

            val tipo = when (spinnerTipoCrear.selectedItemPosition) {
                1 -> "AVERIA_MASIVA"; 2 -> "MANTENIMIENTO"; else -> "PROYECTO"
            }
            val nombre = etNombre.text.toString().trim()
            if (nombre.isBlank()) {
                Toast.makeText(ctx, "El nombre es obligatorio", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (tipo == "AVERIA_MASIVA" && etUbicacion.text.toString().isBlank()) {
                Toast.makeText(ctx, "La ubicación es obligatoria", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            vm.crearTrabajo(
                tipo        = tipo,
                nombre      = nombre,
                descripcion = etDesc.text.toString().ifBlank { null },
                ubicacion   = etUbicacion.text.toString().ifBlank { null },
                fechaInicio = null,
                tecnicoIds  = emptyList()
            )
            dialog.dismiss()
        }

        botonesRow.addView(btnCancelar)
        botonesRow.addView(btnCrear)
        root.addView(botonesRow)

        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(
                (resources.displayMetrics.widthPixels * 0.88).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.CENTER)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
        dialog.show()
    }
    // ── Detalle ───────────────────────────────────────────────
    private fun mostrarDetalle(trabajo: TrabajoPEDto) {
        TrabajoPEDetailSheet.newInstance(trabajo.id)
            .show(childFragmentManager, TrabajoPEDetailSheet.TAG)
    }

    override fun onResume() {
        super.onResume()
        vm.cargarTrabajos()
    }

    companion object {
        fun newInstance() = PlantaExternaFragment()
    }
}

// ── Adapter ───────────────────────────────────────────────────
class TrabajoPEAdapter(
    private val onClick: (TrabajoPEDto) -> Unit
) : ListAdapter<TrabajoPEDto, TrabajoPEAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<TrabajoPEDto>() {
            override fun areItemsTheSame(a: TrabajoPEDto, b: TrabajoPEDto) = a.id == b.id
            override fun areContentsTheSame(a: TrabajoPEDto, b: TrabajoPEDto) = a == b
        }
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvNombre:    TextView = view.findViewById(R.id.tvNombre)
        val tvTipo:      TextView = view.findViewById(R.id.tvTipo)
        val tvEstado:    TextView = view.findViewById(R.id.tvEstado)
        val tvTecnicos:  TextView = view.findViewById(R.id.tvTecnicos)
        val tvMats:      TextView = view.findViewById(R.id.tvMateriales)
        val accentBar:   View     = view.findViewById(R.id.viewAccentBar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_trabajo_pe, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = getItem(position)
        val dp = holder.itemView.context.resources.displayMetrics.density

        holder.tvNombre.text = t.nombre

        val (tipoLabel, tipoColor, accentColor) = when (t.tipo) {
            "PROYECTO"      -> Triple("🏗 Proyecto",       "#8E44AD", "#8E44AD")
            "AVERIA_MASIVA" -> Triple("⚡ Avería masiva",  "#E74C3C", "#E74C3C")
            "MANTENIMIENTO" -> Triple("🔧 Mantenimiento",  "#E67E22", "#E67E22")
            else            -> Triple(t.tipo,              "#64748B", "#64748B")
        }
        holder.tvTipo.text = tipoLabel
        holder.tvTipo.setTextColor(Color.parseColor(tipoColor))
        holder.accentBar.setBackgroundColor(Color.parseColor(accentColor))

        val enCurso = t.estado == "EN_CURSO"
        holder.tvEstado.text = if (enCurso) "En curso" else "Completado"
        holder.tvEstado.setTextColor(Color.parseColor(if (enCurso) "#16A34A" else "#2563EB"))
        holder.tvEstado.background = GradientDrawable().apply {
            setColor(Color.parseColor(if (enCurso) "#DCFCE7" else "#EFF6FF"))
            cornerRadius = 20f * dp
        }

        holder.tvTecnicos.text = if (t.tecnicos.isEmpty()) "Sin técnicos asignados"
        else "👷 " + t.tecnicos.joinToString(", ") {
            "${it.tecnico.usuario.nombre} ${it.tecnico.usuario.apellido}"
        }

        val numMats = t.consumos?.size ?: 0
        holder.tvMats.text = if (numMats > 0) "📦 $numMats material${if (numMats != 1) "es" else ""}"
        else "📦 Sin materiales"

        holder.itemView.setOnClickListener { onClick(t) }
    }
}