package com.enetfiber.tecnico.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.enetfiber.tecnico.databinding.FragmentDashboardBinding
import com.enetfiber.tecnico.ui.DashboardViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@AndroidEntryPoint
class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private val vm: DashboardViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val fecha = SimpleDateFormat("EEEE, dd 'de' MMMM", Locale("es", "PE"))
            .format(Date()).replaceFirstChar { it.uppercase() }
        binding.tvFecha.text    = fecha
        binding.tvConexion.text = if (vm.isOnline) "🟢 Conectado" else "🔴 Sin internet"

        vm.estado.observe(viewLifecycleOwner) { estado ->
            binding.tvPendientesInternet.text = estado.pendientesInternet.toString()
            binding.tvPendientesCable.text    = estado.pendientesCable.toString()
            binding.tvPendientesDuo.text      = estado.pendientesDuo.toString()
            binding.tvCompletadas.text        = estado.completadas.toString()
            binding.progress.visibility       = if (estado.cargando) View.VISIBLE else View.GONE
            // Subtítulos dinámicos
            binding.tvSubInternet.text = "${estado.pendientesInternet} órdenes sin atender"
            binding.tvSubCable.text    = "${estado.pendientesCable} órdenes sin atender"
            binding.tvSubDuo.text      = "${estado.pendientesDuo} órdenes sin atender"
        }

        lifecycleScope.launch {
            vm.nombre.collect { nombre ->
                val apellido = vm.apellido.value ?: ""
                binding.tvBienvenido.text = "Hola, $nombre $apellido"
            }
        }

        binding.swipeRefresh.setOnRefreshListener { vm.refrescar() }
        vm.refresco.observe(viewLifecycleOwner) { binding.swipeRefresh.isRefreshing = it }

        binding.cardInternet.setOnClickListener {
            (activity as? MainActivity)?.cargarFragment(PendientesFragment.newInstance("internet"), "Pendientes Internet")
        }
        binding.cardCable.setOnClickListener {
            (activity as? MainActivity)?.cargarFragment(PendientesFragment.newInstance("cable"), "Pendientes Cable")
        }
        binding.cardDuo.setOnClickListener {
            (activity as? MainActivity)?.cargarFragment(PendientesFragment.newInstance("duo"), "Pendientes Dúo")
        }
        binding.cardCompletadas.setOnClickListener {
            (activity as? MainActivity)?.cargarFragment(CompletadasFragment(), "Completadas")
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refrescar()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}