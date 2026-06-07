package com.enetfiber.tecnico.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.enetfiber.tecnico.data.local.OrdenEntity
import com.enetfiber.tecnico.databinding.FragmentPendientesTabsBinding
import com.enetfiber.tecnico.ui.OrdenesViewModel
import com.enetfiber.tecnico.ui.main.adapter.OrdenAdapter
import com.enetfiber.tecnico.ui.detalle.DetalleOrdenActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class PendientesTabsFragment : Fragment() {

    private var _binding: FragmentPendientesTabsBinding? = null
    private val binding get() = _binding!!
    private val vm: OrdenesViewModel by viewModels()
    private lateinit var adapter: OrdenAdapter

    private var todasLasOrdenes: List<OrdenEntity> = emptyList()
    private var filtroActual: String = "todos"   // todos | internet | cable | duo
    private var busquedaActual: String = ""

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPendientesTabsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecycler()
        setupChips()
        setupBusqueda()
        setupSwipeRefresh()
        setupObservers()
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeColors(
            android.graphics.Color.parseColor("#1D4ED8")
        )
        binding.swipeRefresh.setOnRefreshListener {
            vm.refrescar()
        }
    }

    private fun setupRecycler() {
        adapter = OrdenAdapter(
            onClickOrden = { orden ->
                startActivity(
                    Intent(requireContext(), DetalleOrdenActivity::class.java)
                        .putExtra("orden_id", orden.id)
                )
            },
            onClickLlamar = { cel ->
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cel")))
            },
            onClickWhatsapp = { orden ->
                val cel = orden.celular.replace(Regex("[^0-9]"), "")
                val url = "https://wa.me/51$cel"
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        )
        binding.rvOrdenes.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@PendientesTabsFragment.adapter
        }
    }

    private fun setupChips() {
        binding.rgFiltroTipo.setOnCheckedChangeListener { _, id ->
            filtroActual = when (id) {
                com.enetfiber.tecnico.R.id.chipInternet -> "internet"
                com.enetfiber.tecnico.R.id.chipCable    -> "cable"
                com.enetfiber.tecnico.R.id.chipDuo      -> "duo"
                else                                    -> "todos"
            }
            actualizarLista()
        }
    }

    private fun setupBusqueda() {
        binding.etBuscarPendientes.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                busquedaActual = s?.toString()?.trim() ?: ""
                actualizarLista()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun setupObservers() {
        vm.pendientesTodas.observe(viewLifecycleOwner) { ordenes ->
            todasLasOrdenes = ordenes ?: emptyList()
            actualizarContador()
            actualizarLista()
            binding.swipeRefresh.isRefreshing = false
        }
        vm.refresco.observe(viewLifecycleOwner) { refrescando ->
            if (!refrescando) binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun actualizarLista() {
        val filtradas = todasLasOrdenes
            // Filtrar por tipo
            .filter { orden ->
                when (filtroActual) {
                    "internet" -> orden.tipoOrden.endsWith("_I")
                    "cable"    -> orden.tipoOrden.endsWith("_C")
                    "duo"      -> orden.tipoOrden.endsWith("_D")
                    else       -> true
                }
            }
            // Filtrar por búsqueda
            .filter { orden ->
                if (busquedaActual.isEmpty()) true
                else {
                    val q = busquedaActual.lowercase()
                    orden.abonado.lowercase().contains(q) ||
                            orden.nServicio.lowercase().contains(q) ||
                            orden.direccion.lowercase().contains(q) ||
                            (orden.contrato?.lowercase()?.contains(q) == true) ||
                            (orden.dni?.lowercase()?.contains(q) == true)
                }
            }

        adapter.submitList(filtradas)

        // Empty state
        val isEmpty = filtradas.isEmpty()
        binding.layoutEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvOrdenes.visibility   = if (isEmpty) View.GONE else View.VISIBLE

        // Ícono y texto del estado vacío
        if (isEmpty) {
            binding.tvEmptyIcon.text = when (filtroActual) {
                "internet" -> "📡"
                "cable"    -> "📺"
                "duo"      -> "📡📺"
                else       -> "📋"
            }
            binding.tvEmptyText.text = when {
                busquedaActual.isNotEmpty() -> "Sin resultados para \"$busquedaActual\""
                filtroActual == "internet"  -> "Sin órdenes de Internet"
                filtroActual == "cable"     -> "Sin órdenes de Cable"
                filtroActual == "duo"       -> "Sin órdenes Dúo"
                else                        -> "Sin órdenes pendientes"
            }
        }
    }

    private fun actualizarContador() {
        val total = todasLasOrdenes.size
        val internet = todasLasOrdenes.count { it.tipoOrden.endsWith("_I") }
        val cable    = todasLasOrdenes.count { it.tipoOrden.endsWith("_C") }
        val duo      = todasLasOrdenes.count { it.tipoOrden.endsWith("_D") }

        binding.tvContadorPendientes.text = when {
            total == 0  -> "Sin órdenes pendientes"
            total == 1  -> "1 pendiente"
            else        -> "$total pendientes"//  ·  📡$internet  📺$cable  🔀$duo"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = PendientesTabsFragment()
    }
}