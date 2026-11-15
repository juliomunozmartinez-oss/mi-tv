package com.mitv.player

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Ajustes → Perfiles: renombrar, color, infantil/adulto, agregar y borrar. */
class ProfilesEditActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profiles_edit)
        list = findViewById(R.id.list)
        findViewById<View>(R.id.btnAdd).setOnClickListener { add() }
        build(focusIndex = 0, focusButton = 0)
    }

    private fun profiles() = Profiles.all(this)

    private fun save(newList: List<Profile>, focusIndex: Int, focusButton: Int) {
        Profiles.save(this, newList)
        build(focusIndex, focusButton)
    }

    private fun build(focusIndex: Int, focusButton: Int) {
        list.removeAllViews()
        val inf = LayoutInflater.from(this)
        val all = profiles()
        all.forEachIndexed { i, p ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 10.dp, 0, 10.dp)
            }
            val avatar = TextView(this).apply {
                text = p.initial
                gravity = Gravity.CENTER
                textSize = 22f
                setTextColor(getColor(R.color.text))
                background = Ui.circle(p.color)
                layoutParams = LinearLayout.LayoutParams(52.dp, 52.dp).apply { marginEnd = 20.dp }
            }
            val name = TextView(this).apply {
                text = p.name + if (p.kids) "  ·  Infantil" else "  ·  Adulto"
                textSize = 20f
                setTextColor(getColor(R.color.text))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(avatar)
            row.addView(name)

            fun button(label: String, onClick: () -> Unit): TextView {
                val b = inf.inflate(R.layout.item_button, row, false) as TextView
                b.text = label
                b.setOnClickListener { onClick() }
                row.addView(b)
                return b
            }

            val buttons = listOf(
                button("Renombrar") { rename(i) },
                button("Color") {
                    val next = Profiles.palette[(Profiles.palette.indexOf(p.color) + 1) % Profiles.palette.size]
                    save(all.toMutableList().also { it[i] = p.copy(color = next) }, i, 1)
                },
                button(if (p.kids) "Hacer adulto" else "Hacer infantil") {
                    save(all.toMutableList().also { it[i] = p.copy(kids = !p.kids) }, i, 2)
                },
                button("Borrar") { delete(i) },
            )
            list.addView(row)
            if (i == focusIndex) buttons.getOrNull(focusButton)?.post { buttons[focusButton].requestFocus() }
        }
    }

    private fun rename(i: Int) {
        val all = profiles()
        val p = all[i]
        val input = EditText(this).apply {
            setText(p.name)
            setSelectAllOnFocus(true)
            textSize = 22f
        }
        AlertDialog.Builder(this)
            .setTitle("Nombre del perfil")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) save(all.toMutableList().also { it[i] = p.copy(name = n.take(20)) }, i, 0)
            }
            .setNegativeButton("Cancelar", null)
            .show()
        input.requestFocus()
    }

    private fun delete(i: Int) {
        val all = profiles()
        if (all.size <= 1) {
            Toast.makeText(this, "Debe quedar al menos un perfil", Toast.LENGTH_SHORT).show()
            return
        }
        val p = all[i]
        AlertDialog.Builder(this)
            .setTitle("¿Borrar a ${p.name}?")
            .setMessage("Se pierden su «Seguir viendo», «Mi lista» y recomendaciones.")
            .setPositiveButton("Borrar") { _, _ ->
                getSharedPreferences("profile_${p.id}", MODE_PRIVATE).edit().clear().apply()
                save(all.filterIndexed { idx, _ -> idx != i }, (i - 1).coerceAtLeast(0), 0)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun add() {
        val all = profiles()
        if (all.size >= Profiles.MAX) {
            Toast.makeText(this, "Máximo ${Profiles.MAX} perfiles", Toast.LENGTH_SHORT).show()
            return
        }
        val color = Profiles.palette.firstOrNull { c -> all.none { it.color == c } } ?: Profiles.palette[0]
        val p = Profile(Profiles.newId(), "Perfil ${all.size + 1}", kids = false, color = color)
        save(all + p, all.size, 0)
        rename(all.size)
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
