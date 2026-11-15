package com.mitv.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

/** Ajustes → Ordenar y ocultar categorías (para los perfiles de adultos). */
class CategoriesActivity : AppCompatActivity() {

    private sealed class Item {
        data class Header(val text: String) : Item()
        data class Cat(val category: Category) : Item()
    }

    private lateinit var prefs: Prefs
    private lateinit var list: RecyclerView
    private val sections = linkedMapOf<Kind, MutableList<Category>>()
    private var items: MutableList<Item> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_categories)
        prefs = Prefs(this)
        list = findViewById(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.itemAnimator = null

        findViewById<TextView>(R.id.btnReset).setOnClickListener {
            Kind.values().forEach { prefs.setCategoryOrder(it, emptyList()) }
            prefs.hiddenCategories = emptySet()
            build()
            Toast.makeText(this, "Se restableció el orden original y se mostraron todas", Toast.LENGTH_SHORT).show()
        }

        lifecycleScope.launch {
            try {
                Catalog.ensureLoaded(prefs)
                build()
            } catch (e: Exception) {
                Toast.makeText(this@CategoriesActivity, XtreamClient.friendlyError(e), Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun build() {
        sections.clear()
        Kind.values().forEach { sections[it] = Catalog.ordered(it, prefs).toMutableList() }
        flatten()
        list.adapter = Adapter()
        list.post { list.layoutManager?.findViewByPosition(1)?.requestFocus() }
    }

    private fun flatten() {
        val out = mutableListOf<Item>()
        sections.forEach { (kind, cats) ->
            if (cats.isEmpty()) return@forEach
            out += Item.Header(
                when (kind) {
                    Kind.LIVE -> "TV EN VIVO"
                    Kind.MOVIE -> "PELÍCULAS"
                    Kind.SERIES -> "SERIES"
                }
            )
            cats.forEach { out += Item.Cat(it) }
        }
        items = out
    }

    /** Mueve una categoría un lugar arriba (-1) o abajo (+1) dentro de su sección. */
    private fun move(category: Category, delta: Int, fromPos: Int) {
        val cats = sections[category.kind] ?: return
        val i = cats.indexOfFirst { it.key == category.key }
        val j = i + delta
        if (i < 0 || j !in cats.indices) return
        cats[i] = cats[j].also { cats[j] = cats[i] }
        prefs.setCategoryOrder(category.kind, cats.map { it.key })
        flatten()
        val toPos = fromPos + delta
        list.adapter?.notifyItemMoved(fromPos, toPos)
        list.scrollToPosition(toPos)
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(position: Int) = if (items[position] is Item.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            val v = if (viewType == 0) inf.inflate(R.layout.item_header, parent, false)
            else inf.inflate(R.layout.item_category_edit, parent, false)
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is Item.Header -> (holder.itemView as TextView).text = item.text
                is Item.Cat -> {
                    val row = holder.itemView as LinearLayout
                    val c = item.category
                    val hidden = c.key in prefs.hiddenCategories
                    row.findViewById<TextView>(R.id.name).apply {
                        text = c.name
                        alpha = if (hidden) 0.4f else 1f
                    }
                    row.findViewById<TextView>(R.id.btnUp).setOnClickListener {
                        move(c, -1, holder.bindingAdapterPosition)
                    }
                    row.findViewById<TextView>(R.id.btnDown).setOnClickListener {
                        move(c, +1, holder.bindingAdapterPosition)
                    }
                    row.findViewById<TextView>(R.id.btnHide).apply {
                        text = if (hidden) "Mostrar" else "Ocultar"
                        setOnClickListener {
                            prefs.toggleHidden(c.key)
                            notifyItemChanged(holder.bindingAdapterPosition)
                        }
                    }
                }
            }
        }

        override fun getItemCount() = items.size
    }
}
