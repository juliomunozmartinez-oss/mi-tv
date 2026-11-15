package com.mitv.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load

data class Card(
    val kind: Kind,
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val image: String?,
    val logo: Boolean = false,
    val progress: Float = -1f,
    val resume: Resume? = null,
    /** Tarjeta «Ver todo» al final de una fila. */
    val more: Boolean = false,
)

/**
 * Una fila del carrusel. [all] da la sección completa para «Ver todo»
 * (se calcula solo al abrirla, para no hacer trabajo de más).
 */
class Row(
    val title: String,
    val cards: List<Card>,
    val wide: Boolean,
    val all: (() -> List<Card>)? = null,
)


/** Tarjetas de una fila (pósters verticales o tarjetas anchas). */
class CardAdapter(
    private val row: Row,
    private val onClick: (Row, Int) -> Unit,
    private val onFocus: ((Row, Int) -> Unit)? = null,
) : RecyclerView.Adapter<CardAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val focusTarget: View = view.findViewById(R.id.frame) ?: view
        val image: ImageView = view.findViewById(R.id.image)
        val fallback: TextView = view.findViewById(R.id.fallbackTitle)
        val progress: ProgressBar = view.findViewById(R.id.progress)
        val title: TextView? = view.findViewById(R.id.title)
        val subtitle: TextView? = view.findViewById(R.id.subtitle)
    }

    override fun getItemViewType(position: Int) = if (row.wide) TYPE_WIDE else TYPE_POSTER

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (viewType == TYPE_WIDE) R.layout.item_card_wide else R.layout.item_card_poster
        val vh = VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))
        Ui.rounded(vh.focusTarget)
        val scale = if (viewType == TYPE_WIDE) 1.06f else 1.1f
        vh.focusTarget.setOnFocusChangeListener { v, has ->
            val sc = if (has) scale else 1f
            v.animate().scaleX(sc).scaleY(sc).setDuration(140).start()
            v.translationZ = if (has) 8f else 0f
            val pos = vh.bindingAdapterPosition
            if (has && pos != RecyclerView.NO_POSITION) onFocus?.invoke(row, pos)
        }
        return vh
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val card = row.cards[position]
        holder.focusTarget.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(row, pos)
        }
        if (card.more) {
            holder.fallback.text = "Ver todo  →"
            holder.fallback.visibility = View.VISIBLE
            holder.title?.text = ""
            holder.subtitle?.visibility = View.GONE
            holder.image.setImageDrawable(null)
            holder.progress.visibility = View.GONE
            return
        }
        holder.fallback.text = card.title
        holder.title?.text = card.title
        holder.subtitle?.text = card.subtitle ?: ""
        holder.subtitle?.visibility = if (card.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE

        val pad = if (card.logo) (16 * holder.image.resources.displayMetrics.density).toInt() else 0
        holder.image.setPadding(pad, pad, pad, pad)
        holder.image.scaleType = if (card.logo) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        holder.image.setImageDrawable(null)
        holder.fallback.visibility = View.VISIBLE
        if (card.image != null) {
            holder.image.load(card.image) {
                crossfade(true)
                listener(onSuccess = { _, _ -> if (!card.logo) holder.fallback.visibility = View.INVISIBLE })
            }
        }

        if (card.progress >= 0f) {
            holder.progress.visibility = View.VISIBLE
            holder.progress.progress = (card.progress * 1000).toInt()
        } else {
            holder.progress.visibility = View.GONE
        }
    }

    override fun getItemCount() = row.cards.size

    companion object {
        const val TYPE_POSTER = 1
        const val TYPE_WIDE = 2
    }
}

/** Filas horizontales de tarjetas, una debajo de otra. */
class RowsAdapter(
    private val onCard: (Row, Int) -> Unit,
    private val onFocus: (Row, Int) -> Unit,
) : RecyclerView.Adapter<RowsAdapter.RowVH>() {

    private val pool = RecyclerView.RecycledViewPool().apply {
        setMaxRecycledViews(CardAdapter.TYPE_POSTER, 40)
        setMaxRecycledViews(CardAdapter.TYPE_WIDE, 30)
    }

    var rows: List<Row> = emptyList()
        private set

    fun submit(rows: List<Row>) {
        this.rows = rows
        notifyDataSetChanged()
    }

    fun indexOf(row: Row) = rows.indexOfFirst { it === row }

    override fun getItemCount() = rows.size

    class RowVH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.rowTitle)
        val list: RecyclerView = view.findViewById(R.id.rowList)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowVH {
        val vh = RowVH(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false))
        vh.list.layoutManager = LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)
            .apply { initialPrefetchItemCount = 8 }
        vh.list.setRecycledViewPool(pool)
        vh.list.setHasFixedSize(true)
        vh.list.itemAnimator = null
        return vh
    }

    override fun onBindViewHolder(holder: RowVH, position: Int) {
        val row = rows[position]
        holder.title.text = row.title
        holder.list.adapter = CardAdapter(row, onCard, onFocus)
    }
}
