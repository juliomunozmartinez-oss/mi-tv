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
    /** Posición en el Top 10 (1–10); 0 = tarjeta normal. */
    val rank: Int = 0,
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
) {
    /** Último póster enfocado en esta fila, para volver justo a él al subir o bajar. */
    var focusPos: Int = 0
}

/**
 * Fila horizontal que recuerda su póster enfocado. Al llegar desde arriba o desde abajo
 * solo ofrece ese póster, así el foco no se «recorre» hacia un lado.
 */
class RowRecyclerView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: android.util.AttributeSet? = null,
) : RecyclerView(context, attrs) {

    var row: Row? = null

    override fun addFocusables(views: java.util.ArrayList<View>, direction: Int, focusableMode: Int) {
        if (!hasFocus() && (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN)) {
            val lm = layoutManager as? LinearLayoutManager
            val pos = row?.focusPos ?: 0
            val target = lm?.findViewByPosition(pos)
                ?: lm?.findViewByPosition(lm.findFirstCompletelyVisibleItemPosition().coerceAtLeast(0))
            if (target != null) {
                target.addFocusables(views, direction, focusableMode)
                return
            }
        }
        super.addFocusables(views, direction, focusableMode)
    }

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        val pos = child?.let { getChildAdapterPosition(it) } ?: NO_POSITION
        if (pos != NO_POSITION) row?.focusPos = pos
    }
}


/** Tarjetas de una fila (pósters verticales o tarjetas anchas). */
class CardAdapter(
    private val row: Row,
    private val onClick: (Row, Int) -> Unit,
    private val onFocus: ((Row, Int) -> Unit)? = null,
    /** Si se da, mantener OK agrega o quita de Mi lista y se muestra ★ en lo que ya está. */
    private val store: ProfileStore? = null,
) : RecyclerView.Adapter<CardAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val focusTarget: View = view.findViewById(R.id.frame) ?: view
        val image: ImageView = view.findViewById(R.id.image)
        val fallback: TextView = view.findViewById(R.id.fallbackTitle)
        val progress: ProgressBar = view.findViewById(R.id.progress)
        val title: TextView? = view.findViewById(R.id.title)
        val subtitle: TextView? = view.findViewById(R.id.subtitle)
        val badge: View = view.findViewById(R.id.listBadge)
        val rank: TextView? = view.findViewById(R.id.rank)
    }

    override fun getItemViewType(position: Int) = when {
        row.cards[position].rank > 0 -> TYPE_RANK
        row.wide -> TYPE_WIDE
        else -> TYPE_POSTER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = when (viewType) {
            TYPE_WIDE -> R.layout.item_card_wide
            TYPE_RANK -> R.layout.item_card_rank
            else -> R.layout.item_card_poster
        }
        val vh = VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))
        Ui.rounded(vh.focusTarget)
        val scale = if (viewType == TYPE_WIDE) 1.06f else 1.1f
        val wide = viewType == TYPE_WIDE
        // Los pósters no enfocados se ven un poco atenuados; el enfocado crece, brilla y muestra su título.
        if (!wide) vh.focusTarget.alpha = DIM
        vh.focusTarget.setOnFocusChangeListener { v, has ->
            val sc = if (has) scale else 1f
            v.animate().scaleX(sc).scaleY(sc).alpha(if (has || wide) 1f else DIM).setDuration(160).start()
            v.translationZ = if (has) 16f else 0f
            v.elevation = if (has) 12f else 0f
            if (!wide) vh.title?.visibility = if (has) View.VISIBLE else View.INVISIBLE
            vh.rank?.setTextColor(if (has) 0xFFCBD5E1.toInt() else 0xFF334155.toInt())
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
        val key = MyList.keyOf(card)
        holder.badge.visibility = if (key != null && store?.inList(key) == true) View.VISIBLE else View.GONE
        if (store != null && key != null) {
            holder.focusTarget.setOnLongClickListener { v ->
                val added = store.toggleList(key)
                MyList.toast(v.context, card.title, added)
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos)
                true
            }
        } else {
            holder.focusTarget.setOnLongClickListener(null)
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
        holder.rank?.text = card.rank.toString()
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
        const val TYPE_RANK = 3
        private const val DIM = 0.82f
    }
}

/** Filas horizontales de tarjetas, una debajo de otra. */
class RowsAdapter(
    private val onCard: (Row, Int) -> Unit,
    private val onFocus: (Row, Int) -> Unit,
    private val store: ProfileStore? = null,
) : RecyclerView.Adapter<RowsAdapter.RowVH>() {

    private val pool = RecyclerView.RecycledViewPool().apply {
        setMaxRecycledViews(CardAdapter.TYPE_POSTER, 40)
        setMaxRecycledViews(CardAdapter.TYPE_WIDE, 30)
        setMaxRecycledViews(CardAdapter.TYPE_RANK, 12)
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
        val list: RowRecyclerView = view.findViewById(R.id.rowList)
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
        holder.list.row = row
        holder.list.adapter = CardAdapter(row, onCard, onFocus, store)
        // Al volver a mostrar la fila, conservar dónde se quedó.
        (holder.list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(row.focusPos, 0)
    }
}

/** Ayudas para «Mi lista» desde cualquier tarjeta. */
object MyList {
    fun keyOf(card: Card): String? = when {
        card.more -> null
        card.kind == Kind.MOVIE -> "movie:${card.id}"
        card.kind == Kind.SERIES -> "series:${card.id}"
        card.kind == Kind.LIVE -> "live:${card.id}"
        else -> null
    }

    fun toast(ctx: android.content.Context, title: String, added: Boolean) {
        android.widget.Toast.makeText(
            ctx,
            if (added) "★ $title agregado a Mi lista — está en la pestaña «Mi lista», arriba"
            else "$title quitado de Mi lista",
            android.widget.Toast.LENGTH_LONG,
        ).show()
    }
}
