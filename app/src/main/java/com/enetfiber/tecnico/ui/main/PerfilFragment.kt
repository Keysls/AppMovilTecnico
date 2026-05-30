package com.enetfiber.tecnico.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.enetfiber.tecnico.R                          // ✅ fix: import R
import com.enetfiber.tecnico.databinding.FragmentPerfilBinding
import com.enetfiber.tecnico.ui.DashboardViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@AndroidEntryPoint
class PerfilFragment : Fragment() {

    private var _binding: FragmentPerfilBinding? = null
    private val binding get() = _binding!!
    private val vm: DashboardViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPerfilBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Nombre + iniciales
        viewLifecycleOwner.lifecycleScope.launch {
            combine(vm.nombre, vm.apellido) { n, a -> Pair(n, a) }
                .collect { (nombre, apellido) ->
                    val n = nombre.orEmpty()             // ✅ fix: String? → String
                    val a = apellido.orEmpty()           // ✅ fix: String? → String
                    binding.tvNombre.text = "$n $a".trim().ifEmpty { "Técnico" }
                    binding.tvIniciales.text = buildString {
                        n.firstOrNull()?.let { append(it.uppercaseChar()) }
                        a.firstOrNull()?.let { append(it.uppercaseChar()) }
                    }.ifEmpty { "?" }
                }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.zona.collect { zona ->
                binding.tvZona.text = zona?.ifBlank { "Sin zona asignada" } ?: "Sin zona asignada" // ✅ fix: String?
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.email.collect { binding.tvEmail.text = it.ifBlank { "—" } }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.telefono.collect { binding.tvTelefono.text = it.ifBlank { "—" } }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.dni.collect { binding.tvDni.text = it.ifBlank { "—" } }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.vehiculo.collect { binding.tvVehiculo.text = it.ifBlank { "—" } }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.ordenesActivas.collect { binding.tvOrdenesActivas.text = it.toString() }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.activo.collect { activo ->
                binding.tvEstado.text = if (activo) "● Activo" else "● Inactivo"
                binding.tvEstado.setTextColor(
                    requireContext().getColor(
                        if (activo) R.color.verde else R.color.txt_hint
                    )
                )
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}