package com.enetfiber.tecnico.ui.main

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.enetfiber.tecnico.R
import com.enetfiber.tecnico.databinding.FragmentPerfilBinding
import com.enetfiber.tecnico.ui.DashboardViewModel
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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
                    val n = nombre.orEmpty()
                    val a = apellido.orEmpty()
                    binding.tvNombre.text = "$n $a".trim().ifEmpty { "Técnico" }
                    binding.tvIniciales.text = buildString {
                        n.firstOrNull()?.let { append(it.uppercaseChar()) }
                        a.firstOrNull()?.let { append(it.uppercaseChar()) }
                    }.ifEmpty { "?" }
                }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.zona.collect { zona ->
                binding.tvZona.text = zona?.ifBlank { "Sin zona asignada" } ?: "Sin zona asignada"
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

        // Completadas desde el estado del dashboard
        viewLifecycleOwner.lifecycleScope.launch {
            vm.estado.observe(viewLifecycleOwner) { estado ->
                binding.tvOrdenesCompletadas.text = estado.completadas.toString()
            }
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

        // Cambiar contraseña
        binding.btnCambiarPassword.setOnClickListener {
            mostrarDialogCambiarPassword()
        }
    }

    private fun mostrarDialogCambiarPassword() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_cambiar_password, null)

        val etActual  = dialogView.findViewById<TextInputEditText>(R.id.etPasswordActual)
        val etNueva   = dialogView.findViewById<TextInputEditText>(R.id.etPasswordNueva)
        val etConfirm = dialogView.findViewById<TextInputEditText>(R.id.etPasswordConfirmar)

        AlertDialog.Builder(requireContext())
            .setTitle("Cambiar contraseña")
            .setView(dialogView)
            .setPositiveButton("Guardar") { _, _ ->
                val actual  = etActual.text.toString()
                val nueva   = etNueva.text.toString()
                val confirm = etConfirm.text.toString()

                when {
                    actual.isBlank() || nueva.isBlank() || confirm.isBlank() ->
                        Toast.makeText(requireContext(), "Completa todos los campos", Toast.LENGTH_SHORT).show()
                    nueva.length < 6 ->
                        Toast.makeText(requireContext(), "La nueva contraseña debe tener al menos 6 caracteres", Toast.LENGTH_SHORT).show()
                    nueva != confirm ->
                        Toast.makeText(requireContext(), "Las contraseñas no coinciden", Toast.LENGTH_SHORT).show()
                    else ->
                        vm.cambiarPassword(actual, nueva) { exito, mensaje ->
                            Toast.makeText(requireContext(), mensaje, Toast.LENGTH_SHORT).show()
                        }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}