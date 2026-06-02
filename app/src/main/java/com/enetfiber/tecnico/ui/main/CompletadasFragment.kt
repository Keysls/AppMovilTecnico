package com.enetfiber.tecnico.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.enetfiber.tecnico.databinding.FragmentListaBinding
import com.enetfiber.tecnico.ui.OrdenesViewModel
import com.enetfiber.tecnico.ui.detalle.DetalleOrdenActivity
import com.enetfiber.tecnico.ui.main.adapter.OrdenAdapter
import dagger.hilt.android.AndroidEntryPoint

import com.enetfiber.tecnico.R
import kotlinx.coroutines.Job
import androidx.appcompat.widget.SearchView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import com.enetfiber.tecnico.ui.DashboardViewModel

@AndroidEntryPoint
class CompletadasFragment : Fragment() {

    private var _binding: FragmentListaBinding? = null
    private val binding get() = _binding!!
    private val vm: OrdenesViewModel by viewModels()
    private val dashVm: DashboardViewModel by activityViewModels()

    private lateinit var adapter: OrdenAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentListaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.layoutFiltros.visibility = View.VISIBLE


        binding.tvEmptyIcon.text      = "✅"
        binding.tvEmptyTitulo.text    = "Sin completadas aún"
        binding.tvEmptySubtitulo.text = "Las instalaciones completadas aparecerán aquí"

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
                val tecnico = "${nombre.split(" ").firstOrNull() ?: ""} ${apellido.split(" ").firstOrNull() ?: ""}".trim().ifEmpty { "su técnico" }
                val msg = "Hola, $abonado. Mi nombre es $tecnico, técnico de Enet Fiber Perú. "
                try {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://wa.me/51$cel?text=${Uri.encode(msg)}")))
                } catch (_: Exception) {
                    Toast.makeText(requireContext(), "WhatsApp no instalado", Toast.LENGTH_SHORT).show()
                }
            }
        )

        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        // Agrega esto en onViewCreated después de configurar el adapter
        binding.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                val total    = lm.itemCount
                val visible  = lm.childCount
                val first    = lm.findFirstVisibleItemPosition()
                // Cuando quedan 5 items para el final, carga más
                if (!vm.cargandoMas.value!! && (visible + first) >= total - 5) {
                    vm.cargarMas()
                }
            }
        })

        vm.cargandoMas.observe(viewLifecycleOwner) { cargando ->
            // Opcional: mostrar un spinner al final de la lista
        }



        // Chips
        binding.radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val filtro = when (checkedId) {
                R.id.chipInternet -> "INTERNET"
                R.id.chipCable    -> "CABLE"
                R.id.chipDuo      -> "DUO"
                else              -> "TODOS"
            }
            vm.setTipoFiltro(filtro)
        }

        // CompletadasFragment — reemplaza el SearchView listener
        var searchJob: Job? = null

        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(400)
                    vm.setBusqueda(newText ?: "")
                }
                return true
            }
        })

        // Lista
        vm.completadas.observe(viewLifecycleOwner) { lista ->
            adapter.submitList(lista)
            binding.layoutVacio.visibility = if (lista.isEmpty()) View.VISIBLE else View.GONE
            binding.recycler.visibility    = if (lista.isEmpty()) View.GONE    else View.VISIBLE
        }

        binding.swipeRefresh.setOnRefreshListener { vm.refrescar() }
        vm.refresco.observe(viewLifecycleOwner) { binding.swipeRefresh.isRefreshing = it }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}