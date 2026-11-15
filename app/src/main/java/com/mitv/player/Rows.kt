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
)

data class Row(val title: String, val cards: List<Card>, val wide: Boolean)

data class Hero(
    val label: String,
    val kind: Kind,
    val id: String,
    var title: String,
    var meta: String?,
    var plot: String?,
    var image: String?,
)

/** Tarjetas de una fila (pósters verticales o tarjetas anchas). */
class CardAdapter(
    private val row: Row,
    private val onClick: (Row, Int) -> Unit,
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
        Ui.scaleOnFocus(vh.focusTarget, if (viewType == TYPE_WIDE) 1.06f else 1.1f)
        return vh
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val card = row.cards[position]
        holder.focusTarget.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(row, pos)
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

/** Pantalla de inicio: un destacado opcional arriba y filas horizontales abajo. */
class RowsAdapter(
    private val onCard: (Row, Int) -> Unit,
    private val onHeroPlay: (Hero) -> Unit,
    private val onHeroInfo: (Hero) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val pool = RecyclerView.RecycledViewPool().apply {
        setMaxRecycledViews(CardAdapter.TYPE_POSTER, 40)
        setMaxRecycledViews(CardAdapter.TYPE_WIDE, 30)
    }

    var hero: Hero? = null
        private set
    var rows: List<Row> = emptyList()
        private set

    fun submit(hero: Hero?, rows: List<Row>) {
        this.hero = hero
        this.rows = rows
        notifyDataSetChanged()
    }

    fun heroChanged() {
        if (hero != null) notifyItemChanged(0)
    }

    private val offset get() = if (hero != null) 1 else 0

    override fun getItemCount() = rows.size + offset

    override fun getItemViewType(position: Int) = if (position < offset) TYPE_HERO else TYPE_ROW

    class HeroVH(view: View) : RecyclerView.ViewHolder(view) {
        val backdrop: ImageView = view.findViewById(R.id.backdrop)
        val label: TextView = view.findViewById(R.id.label)
        val title: TextView = view.findViewById(R.id.title)
        val meta: TextView = view.findViewById(R.id.meta)
        val plot: TextView = view.findViewById(R.id.plot)
        val play: TextView = view.findViewById(R.id.btnPlay)
        val info: TextView = view.findViewById(R.id.btnInfo)
    }

    class RowVH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.rowTitle)
        val list: RecyclerView = view.findViewById(R.id.rowList)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HERO) {
            HeroVH(inf.inflate(R.layout.item_hero, parent, false))
        } else {
            val vh = RowVH(inf.inflate(R.layout.item_row, parent, false))
            vh.list.layoutManager = LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)
                .apply { initialPrefetchItemCount = 8 }
            vh.list.setRecycledViewPool(pool)
            vh.list.setHasFixedSize(true)
            vh.list.itemAnimator = null
            vh
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is HeroVH) {
            val h = hero ?: return
            holder.label.text = h.label
            holder.title.text = h.title
            holder.meta.text = h.meta ?: ""
            holder.meta.visibility = if (h.meta.isNullOrBlank()) View.GONE else View.VISIBLE
            holder.plot.text = h.plot ?: ""
            holder.plot.visibility = if (h.plot.isNullOrBlank()) View.GONE else View.VISIBLE
            holder.backdrop.load(h.image) { crossfade(true) }
            holder.play.setOnClickListener { onHeroPlay(h) }
            holder.info.setOnClickListener { onHeroInfo(h) }
        } else if (holder is RowVH) {
            val row = rows[position - offset]
            holder.title.text = row.title
            holder.list.adapter = CardAdapter(row, onCard)
        }
    }

    companion object {
        const val TYPE_HERO = 10
        const val TYPE_ROW = 11
    }
}
