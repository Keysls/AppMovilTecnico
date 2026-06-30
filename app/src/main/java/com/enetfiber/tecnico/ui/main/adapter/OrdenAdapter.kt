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
import com.enetfiber.tecnico.ui.main.DetalleCompletadaUi

class OrdenAdapter(
    private val onClickOrden:    (OrdenEntity) -> Unit,
    private val onClickLlamar:   (String) -> Unit,
    private val onClickWhatsapp: (OrdenEntity) -> Unit,
    // Opcionales: si se proveen, el click en el card EXPANDE en vez de navegar.
    // Usado solo en CompletadasFragment — el resto de pantallas no los pasa.
    private val onExpandir: ((OrdenEntity) -> Unit)? = null,
    private val detallesProvider: (() -> Map<String, DetalleCompletadaUi>)? = null
) : ListAdapter<OrdenEntity, OrdenAdapter.ViewHolder>(DiffCallback()) {

    private val expandidos = mutableSetOf<String>()

    /** Llamar quando detallesProvider entrega datos nuevos, para refrescar la fila expandida. */
    fun notificarDetalleActualizado(ordenId: String) {
        val pos = currentList.indexOfFirst { it.id == ordenId }
        if (pos >= 0) notifyItemChanged(pos)
    }

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
            b.tvSector.text    = orden.sector ?: ""
            b.tvSector.visibility = if (orden.sector.isNullOrEmpty()) View.GONE else View.VISIBLE

            b.tvTipo.text = com.enetfiber.tecnico.TipoOrden.labelDinamico(orden.tipoOrden)

            val ctx = b.root.context
            when (orden.estado) {
                "PENDIENTE_TECNICO" -> {
                    b.tvEstado.text = "Para técnico"
                    b.tvEstado.setTextColor(android.graphics.Color.parseColor("#1D4ED8"))
                    b.tvEstado.setBackgroundResource(R.drawable.bg_estado_pendiente)
                }
                "ACEPTADA" -> {
                    b.tvEstado.text = "Aceptada"
                    b.tvEstado.setTextColor(android.graphics.Color.parseColor("#6D28D9"))
                    b.tvEstado.setBackgroundResource(R.drawable.bg_estado_proceso)
                }
                "EN_PROCESO" -> {
                    b.tvEstado.text = "En proceso"
                    b.tvEstado.setTextColor(android.graphics.Color.parseColor("#166534"))
                    b.tvEstado.setBackgroundResource(R.drawable.bg_estado_proceso)
                }
                "COMPLETADA" -> {
                    b.tvEstado.text = "Completada"
                    b.tvEstado.setTextColor(android.graphics.Color.parseColor("#166534"))
                    b.tvEstado.setBackgroundResource(R.drawable.bg_estado_completado)
                }
                else -> {
                    b.tvEstado.text = orden.estado
                    b.tvEstado.setTextColor(ctx.getColor(R.color.txt_secundario))
                    b.tvEstado.setBackgroundResource(R.drawable.bg_estado_pendiente)
                }
            }

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

            b.btnLlamar.setOnClickListener   { onClickLlamar(orden.celular) }
            b.btnWhatsapp.setOnClickListener { onClickWhatsapp(orden) }

            if (onExpandir != null) {
                // Modo expandible (solo Completadas)
                val expandido = expandidos.contains(orden.id)
                b.layoutExpandible.visibility = if (expandido) View.VISIBLE else View.GONE

                b.root.setOnClickListener {
                    if (expandidos.contains(orden.id)) {
                        expandidos.remove(orden.id)
                    } else {
                        expandidos.add(orden.id)
                        onExpandir.invoke(orden)
                    }
                    notifyItemChanged(bindingAdapterPosition)
                }

                if (expandido) {
                    pintarDetalle(orden)
                }
            } else {
                // Modo normal (otras pantallas) — navega al detalle
                b.root.setOnClickListener { onClickOrden(orden) }
            }
        }

        private fun pintarDetalle(orden: OrdenEntity) {
            val detalle = detallesProvider?.invoke()?.get(orden.id)
            val ctx = b.root.context

            b.progressExpandible.visibility = if (detalle?.cargando == true) View.VISIBLE else View.GONE

            // Materiales
            b.layoutMateriales.removeAllViews()
            val materiales = detalle?.materiales ?: emptyList()
            if (detalle != null && !detalle.cargando) {
                b.tvLabelMateriales.visibility = if (materiales.isNotEmpty()) View.VISIBLE else View.GONE
                b.tvSinMateriales.visibility   = if (materiales.isEmpty())    View.VISIBLE else View.GONE
                materiales.forEach { m ->
                    val tv = android.widget.TextView(ctx).apply {
                        text = "• ${m.nombre} — ${if (m.cantidad % 1.0 == 0.0) m.cantidad.toInt().toString() else m.cantidad.toString()}"
                        textSize = 12f
                        setTextColor(android.graphics.Color.parseColor("#334155"))
                        setPadding(0, 2, 0, 2)
                    }
                    b.layoutMateriales.addView(tv)
                }
            } else {
                b.tvLabelMateriales.visibility = View.GONE
                b.tvSinMateriales.visibility   = View.GONE
            }

            // Configuración WAN/ONU
            b.layoutConfig.removeAllViews()
            val config = detalle?.config
            if (detalle != null && !detalle.cargando) {
                b.tvLabelConfig.visibility = if (config != null) View.VISIBLE else View.GONE
                if (config != null) {
                    val filas = listOfNotNull(
                        config.ssid?.let { "WiFi 2.4G: $it" },
                        config.ssid5ghz?.let { "WiFi 5G: $it" },
                        config.serialNumber?.let { "Serial: $it" },
                        config.ipWan?.let { "IP WAN: $it" },
                        if (config.potenciaRx != null) "Potencia RX: ${config.potenciaRx} dBm" else null,
                    )
                    filas.forEach { texto ->
                        val tv = android.widget.TextView(ctx).apply {
                            text = "• $texto"
                            textSize = 12f
                            setTextColor(android.graphics.Color.parseColor("#334155"))
                            setPadding(0, 2, 0, 2)
                        }
                        b.layoutConfig.addView(tv)
                    }
                    if (filas.isEmpty()) {
                        b.layoutConfig.addView(android.widget.TextView(ctx).apply {
                            text = "Sin datos de configuración"
                            textSize = 11f
                            setTextColor(android.graphics.Color.parseColor("#94A3B8"))
                        })
                    }
                }
            } else {
                b.tvLabelConfig.visibility = View.GONE
            }

            if (detalle?.error != null) {
                b.layoutConfig.addView(android.widget.TextView(ctx).apply {
                    text = "⚠ ${detalle.error}"
                    textSize = 11f
                    setTextColor(android.graphics.Color.parseColor("#DC2626"))
                })
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<OrdenEntity>() {
        override fun areItemsTheSame(a: OrdenEntity, b: OrdenEntity) = a.id == b.id
        override fun areContentsTheSame(a: OrdenEntity, b: OrdenEntity) = a == b
    }
}