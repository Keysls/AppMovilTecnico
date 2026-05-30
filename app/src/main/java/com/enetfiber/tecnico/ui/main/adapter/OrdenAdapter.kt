package com.enetfiber.tecnico.ui.main.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.enetfiber.tecnico.R
import com.enetfiber.tecnico.data.local.OrdenEntity
import com.enetfiber.tecnico.databinding.ItemOrdenBinding

class OrdenAdapter(
    private val onClickOrden:    (OrdenEntity) -> Unit,
    private val onClickLlamar:   (String) -> Unit,
    private val onClickWhatsapp: (OrdenEntity) -> Unit
) : ListAdapter<OrdenEntity, OrdenAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemOrdenBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val b: ItemOrdenBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(orden: OrdenEntity) {
            b.tvNServicio.text = "#${orden.nServicio}"
            b.tvAbonado.text   = orden.abonado
            b.tvDireccion.text = orden.direccion
            b.tvCelular.text   = orden.celular
            b.tvSector.text    = orden.sector ?: ""
            b.tvSector.visibility = if (orden.sector.isNullOrEmpty()) View.GONE else View.VISIBLE

            b.tvTipo.text = com.enetfiber.tecnico.TipoOrden.label(orden.tipoOrden)

            val (estadoTexto, estadoColor, estadoBg) = when (orden.estado) {
                "PENDIENTE_TECNICO" -> Triple("Pendiente",  R.color.naranja, R.drawable.bg_estado_pendiente)
                "ACEPTADA"          -> Triple("Aceptada",   R.color.morado,  R.drawable.bg_estado_proceso)
                "EN_PROCESO"        -> Triple("En proceso", R.color.azul_secundario, R.drawable.bg_estado_proceso)
                "COMPLETADA"        -> Triple("Completada", R.color.verde,   R.drawable.bg_estado_completado)
                else                -> Triple(orden.estado, R.color.txt_secundario, R.drawable.bg_estado_pendiente)
            }
            b.tvEstado.text = estadoTexto
            b.tvEstado.setTextColor(b.root.context.getColor(estadoColor))
            b.tvEstado.setBackgroundResource(estadoBg)

            b.tvWanLista.visibility = if (!orden.ipWan.isNullOrEmpty()) View.VISIBLE else View.GONE

            if (!orden.fechaAceptacion.isNullOrEmpty() && orden.estado != "COMPLETADA") {
                try {
                    val sdf = java.text.SimpleDateFormat(
                        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                        java.util.Locale.US
                    ).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    val fecha = sdf.parse(orden.fechaAceptacion)
                    if (fecha != null) {
                        val mins = ((System.currentTimeMillis() - fecha.time) / 60000).toInt()
                        b.tvTimer.text = if (mins < 60) "⏱ ${mins}m"
                        else "⏱ ${mins / 60}h ${mins % 60}m"
                        b.tvTimer.visibility = View.VISIBLE
                        b.tvTimer.setTextColor(b.root.context.getColor(when {
                            mins < 60  -> R.color.verde
                            mins < 120 -> R.color.naranja
                            else       -> R.color.rojo
                        }))
                    }
                } catch (_: Exception) {}
            } else {
                b.tvTimer.visibility = View.GONE
            }

            b.root.setOnClickListener        { onClickOrden(orden) }
            b.btnLlamar.setOnClickListener   { onClickLlamar(orden.celular) }
            b.btnWhatsapp.setOnClickListener { onClickWhatsapp(orden) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<OrdenEntity>() {
        override fun areItemsTheSame(a: OrdenEntity, b: OrdenEntity) = a.id == b.id
        override fun areContentsTheSame(a: OrdenEntity, b: OrdenEntity) = a == b
    }
}