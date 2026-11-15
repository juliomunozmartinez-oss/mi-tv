package com.mitv.player

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

data class CategoryItem(val id: String, val name: String, val count: Int)

class CategoryAdapter(
    private val onSelect: (Int) -> Unit,
    private val onLongPress: (Int) -> Unit,
    private val onRight: () -> Boolean,
) : RecyclerView.Adapter<CategoryAdapter.VH>() {

    var items: List<CategoryItem> = emptyList()
        private set

    fun submit(list: List<CategoryItem>) {
        items = list
        notifyDataSetChanged()
    }

    /** Cambia solo el contador de una fila, sin redibujar toda la lista. */
    fun updateCount(position: Int, count: Int) {
        if (position !in items.indices) return
        val list = items.toMutableList()
        list[position] = list[position].copy(count = count)
        items = list
        notifyItemChanged(position)
    }

    var selected: Int = -1
        set(v) {
            val old = field
            field = v
            if (old in items.indices) notifyItemChanged(old)
            if (v in items.indices) notifyItemChanged(v)
        }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.name)
        val count: TextView = view.findViewById(R.id.count)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val vh = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false))
        vh.itemView.setOnFocusChangeListener { _, hasFocus ->
            val pos = vh.bindingAdapterPosition
            if (hasFocus && pos != RecyclerView.NO_POSITION && pos != selected) onSelect(pos)
        }
        vh.itemView.setOnClickListener {
            val pos = vh.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                if (pos != selected) onSelect(pos)
                onRight()
            }
        }
        vh.itemView.setOnLongClickListener {
            val pos = vh.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onLongPress(pos)
            true
        }
        vh.itemView.setOnKeyListener { _, keyCode, event ->
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN && onRight()
        }
        return vh
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.count.text = item.count.toString()
        holder.itemView.isActivated = position == selected
    }

    override fun getItemCount() = items.size
}

class ChannelAdapter(
    private val onPlay: (Int) -> Unit,
    private val onLongPress: (Int) -> Unit,
    private val onLeft: () -> Boolean,
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    var items: List<Channel> = emptyList()
        set(v) {
            field = v
            notifyDataSetChanged()
        }

    var favorites: Set<String> = emptySet()

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val number: TextView = view.findViewById(R.id.number)
        val logo: ImageView = view.findViewById(R.id.logo)
        val name: TextView = view.findViewById(R.id.name)
        val star: TextView = view.findViewById(R.id.star)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val vh = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))
        vh.itemView.setOnClickListener {
            val pos = vh.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onPlay(pos)
        }
        vh.itemView.setOnLongClickListener {
            val pos = vh.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onLongPress(pos)
            true
        }
        vh.itemView.setOnKeyListener { _, keyCode, event ->
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN && onLeft()
        }
        return vh
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        holder.number.text = (position + 1).toString()
        holder.name.text = ch.name
        holder.star.visibility = if (ch.id in favorites) View.VISIBLE else View.INVISIBLE
        holder.logo.load(ch.icon) {
            crossfade(true)
            error(R.drawable.ic_launcher)
            fallback(R.drawable.ic_launcher)
        }
    }

    override fun getItemCount() = items.size
}
