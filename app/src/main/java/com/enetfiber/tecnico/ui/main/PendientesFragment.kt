package com.enetfiber.tecnico.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.enetfiber.tecnico.databinding.FragmentListaBinding
import com.enetfiber.tecnico.ui.OrdenesViewModel
import com.enetfiber.tecnico.ui.detalle.DetalleOrdenActivity
import com.enetfiber.tecnico.ui.main.adapter.OrdenAdapter
import dagger.hilt.android.AndroidEntryPoint
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import com.enetfiber.tecnico.ui.DashboardViewModel
@AndroidEntryPoint
class PendientesFragment : Fragment() {

    private var _binding: FragmentListaBinding? = null
    private val binding get() = _binding!!
    private val vm: OrdenesViewModel by viewModels()
    private val dashVm: DashboardViewModel by activityViewModels()
    private lateinit var adapter: OrdenAdapter

    private val categoria by lazy { arguments?.getString(ARG_CATEGORIA) ?: "internet" }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentListaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.layoutFiltros.visibility = View.GONE

        when (categoria) {
            "todos" -> {
                binding.tvEmptyIcon.text      = "📋"
                binding.tvEmptyTitulo.text    = "Sin órdenes pendientes"
                binding.tvEmptySubtitulo.text = "Desliza para actualizar"
            }
            "internet" -> {
                binding.tvEmptyIcon.text      = "📡"
                binding.tvEmptyTitulo.text    = "Sin órdenes de Internet"
                binding.tvEmptySubtitulo.text = "Desliza para actualizar"
            }
            "cable" -> {
                binding.tvEmptyIcon.text      = "📺"
                binding.tvEmptyTitulo.text    = "Sin órdenes de Cable"
                binding.tvEmptySubtitulo.text = "Desliza para actualizar"
            }
            "duo" -> {
                binding.tvEmptyIcon.text      = "📡📺"
                binding.tvEmptyTitulo.text    = "Sin órdenes Dúo"
                binding.tvEmptySubtitulo.text = "Desliza para actualizar"
            }
        }

        adapter = OrdenAdapter(
            onClickOrden = { orden ->
                startActivity(
                    Intent(requireContext(), DetalleOrdenActivity::class.java)
                        .putExtra("orden_id", orden.id)
                        .putExtra("tecnico_nombre", dashVm.nombre.value?.split(" ")?.firstOrNull() ?: "")
                        .putExtra("tecnico_apellido", dashVm.apellido.value?.split(" ")?.firstOrNull() ?: "")

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
                val msg = "Hola, $abonado. Mi nombre es $tecnico, técnico de Enet Fiber Perú. " +
                        "Estoy en camino a su domicilio para realizar su servicio. Estaré llegando en breve."
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

        val liveData = when (categoria) {
            "todos"    -> vm.pendientesTodas
            "internet" -> vm.pendientesInternet
            "cable"    -> vm.pendientesCable
            else       -> vm.pendientesDuo
        }

        liveData.observe(viewLifecycleOwner) { lista ->
            adapter.submitList(lista)
            binding.layoutVacio.visibility = if (lista.isEmpty()) View.VISIBLE else View.GONE
            binding.recycler.visibility    = if (lista.isEmpty()) View.GONE    else View.VISIBLE
        }

        binding.swipeRefresh.setOnRefreshListener { vm.refrescar() }
        vm.refresco.observe(viewLifecycleOwner) { binding.swipeRefresh.isRefreshing = it }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }

    companion object {
        private const val ARG_CATEGORIA = "categoria"
        fun newInstance(categoria: String) = PendientesFragment().apply {
            arguments = Bundle().apply { putString(ARG_CATEGORIA, categoria) }
        }
    }
}