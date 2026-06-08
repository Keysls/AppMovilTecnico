package com.enetfiber.tecnico.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.enetfiber.tecnico.R
import com.enetfiber.tecnico.databinding.FragmentCompletadasBinding
import com.enetfiber.tecnico.ui.DashboardViewModel
import com.enetfiber.tecnico.ui.OrdenesViewModel
import com.enetfiber.tecnico.ui.main.adapter.OrdenAdapter
import com.enetfiber.tecnico.ui.detalle.DetalleOrdenActivity
import com.google.android.material.datepicker.MaterialDatePicker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

@AndroidEntryPoint
class CompletadasFragment : Fragment() {

    private var _binding: FragmentCompletadasBinding? = null
    private val binding get() = _binding!!
    private val vm: OrdenesViewModel by viewModels()
    private val dashVm: DashboardViewModel by activityViewModels()
    private lateinit var adapter: OrdenAdapter

    private var fechaDesde: Long? = null
    private var fechaHasta: Long? = null
    private val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCompletadasBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupAdapter()
        setupRecycler()
        setupBuscador()
        setupFechas()
        setupChips()
        setupObservers()

        binding.swipeRefresh.setOnRefreshListener { vm.refrescar() }
        vm.refresco.observe(viewLifecycleOwner) { binding.swipeRefresh.isRefreshing = it }
    }

    private fun setupAdapter() {
        adapter = OrdenAdapter(
            onClickOrden = { orden ->
                startActivity(
                    Intent(requireContext(), DetalleOrdenActivity::class.java)
                        .putExtra("orden_id", orden.id)
                        .putExtra("solo_ver", true)
                )
            },
            onClickLlamar = { cel ->
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cel")))
            },
            onClickWhatsapp = { orden ->
                val cel      = orden.celular.replace(Regex("[^0-9]"), "")
                val abonado  = orden.abonado
                val nombre   = dashVm.nombre.value ?: ""
                val apellido = dashVm.apellido.value ?: ""
                val tecnico  = "${nombre.split(" ").firstOrNull() ?: ""} ${apellido.split(" ").firstOrNull() ?: ""}".trim().ifEmpty { "su técnico" }
                val msg = "Hola, $abonado. Mi nombre es $tecnico, técnico de Enet Fiber Perú. "
                try {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://wa.me/51$cel?text=${Uri.encode(msg)}")))
                } catch (_: Exception) {
                    Toast.makeText(requireContext(), "WhatsApp no instalado", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun setupRecycler() {
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter
        binding.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm      = rv.layoutManager as LinearLayoutManager
                val total   = lm.itemCount
                val visible = lm.childCount
                val first   = lm.findFirstVisibleItemPosition()
                if (!vm.cargandoMas.value!! && (visible + first) >= total - 5) {
                    vm.cargarMas()
                }
            }
        })
    }

    private fun setupBuscador() {
        var searchJob: Job? = null
        binding.etBuscar.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(400)
                    vm.setBusqueda(s?.toString() ?: "")
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun setupFechas() {
        binding.tvFechaDesde.setOnClickListener { mostrarDatePicker(esDesde = true) }
        binding.tvFechaHasta.setOnClickListener { mostrarDatePicker(esDesde = false) }
        binding.btnLimpiarFechas.setOnClickListener {
            fechaDesde = null
            fechaHasta = null
            binding.tvFechaDesde.text = "Desde"
            binding.tvFechaDesde.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
            binding.tvFechaHasta.text = "Hasta"
            binding.tvFechaHasta.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
            binding.btnLimpiarFechas.visibility = View.GONE
            vm.setFechas(null, null)
        }
    }

    private fun mostrarDatePicker(esDesde: Boolean) {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(if (esDesde) "Fecha desde" else "Fecha hasta")
            .setSelection(if (esDesde) fechaDesde ?: MaterialDatePicker.todayInUtcMilliseconds()
            else fechaHasta ?: MaterialDatePicker.todayInUtcMilliseconds())
            .build()

        picker.addOnPositiveButtonClickListener { millis ->
            if (esDesde) {
                fechaDesde = millis
                binding.tvFechaDesde.text = sdf.format(millis)
                binding.tvFechaDesde.setTextColor(android.graphics.Color.parseColor("#0F172A"))
            } else {
                fechaHasta = millis
                binding.tvFechaHasta.text = sdf.format(millis)
                binding.tvFechaHasta.setTextColor(android.graphics.Color.parseColor("#0F172A"))
            }
            binding.btnLimpiarFechas.visibility =
                if (fechaDesde != null || fechaHasta != null) View.VISIBLE else View.GONE
            vm.setFechas(fechaDesde, fechaHasta)
        }
        picker.show(parentFragmentManager, "datePicker")
    }

    private fun setupChips() {
        binding.rgFiltroTipo.setOnCheckedChangeListener { _, checkedId ->
            val filtro = when (checkedId) {
                R.id.chipInternet -> "INTERNET"
                R.id.chipCable    -> "CABLE"
                R.id.chipDuo      -> "DUO"
                else              -> "TODOS"
            }
            vm.setTipoFiltro(filtro)
        }
    }

    private fun setupObservers() {
        vm.completadas.observe(viewLifecycleOwner) { lista ->
            adapter.submitList(lista)
            val total = lista.size
            binding.tvContador.text = when {
                total == 0 -> "Sin completadas"
                total == 1 -> "1 completada"
                else       -> "$total completadas"
            }
            binding.layoutVacio.visibility = if (lista.isEmpty()) View.VISIBLE else View.GONE
            binding.recycler.visibility    = if (lista.isEmpty()) View.GONE    else View.VISIBLE
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}