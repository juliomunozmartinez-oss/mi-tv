package com.mitv.player

import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var link: EditText
    private lateinit var server: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var result: TextView
    private lateinit var btnUnhide: TextView
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)

        link = findViewById(R.id.link)
        server = findViewById(R.id.server)
        username = findViewById(R.id.username)
        password = findViewById(R.id.password)
        result = findViewById(R.id.result)
        btnUnhide = findViewById(R.id.btnUnhide)

        server.setText(prefs.server)
        username.setText(prefs.username)
        password.setText(prefs.password)
        updateUnhideLabel()

        findViewById<TextView>(R.id.btnParse).setOnClickListener { parseLink() }
        findViewById<TextView>(R.id.btnSave).setOnClickListener { testAndSave() }
        findViewById<TextView>(R.id.btnSaveNoTest).setOnClickListener {
            if (validate()) {
                save()
                finish()
            }
        }
        btnUnhide.setOnClickListener {
            prefs.unhideAll()
            updateUnhideLabel()
            show("Listo: todas las categorías se vuelven a mostrar.", ok = true)
        }

        (if (prefs.isConfigured) findViewById<TextView>(R.id.btnSave) else link).requestFocus()
    }

    private fun updateUnhideLabel() {
        val n = prefs.hiddenCategories.size
        btnUnhide.text = if (n > 0) "Mostrar categorías ocultas ($n)" else "Mostrar categorías ocultas"
    }

    private fun parseLink() {
        val parsed = XtreamClient.parseProviderLink(link.text.toString())
        if (parsed == null) {
            show("No reconocí ese enlace. Escribe los datos a mano abajo.", ok = false)
            return
        }
        server.setText(parsed.first)
        username.setText(parsed.second)
        password.setText(parsed.third)
        show("Datos extraídos. Revisa el servidor y toca «Probar y guardar».", ok = true)
        server.requestFocus()
    }

    private fun validate(): Boolean {
        if (server.text.isBlank() || username.text.isBlank() || password.text.isBlank()) {
            show("Llena servidor, usuario y contraseña.", ok = false)
            return false
        }
        return true
    }

    private fun save() {
        prefs.server = XtreamClient.normalizeServer(server.text.toString())
        prefs.username = username.text.toString().trim()
        prefs.password = password.text.toString().trim()
    }

    private fun testAndSave() {
        if (busy || !validate()) return
        busy = true
        show("Probando conexión…", ok = true)
        val client = XtreamClient(
            server.text.toString(),
            username.text.toString().trim(),
            password.text.toString().trim(),
        )
        lifecycleScope.launch {
            try {
                val info = client.login()
                save()
                val exp = info.expDate?.let {
                    " · vence el " + SimpleDateFormat("dd/MM/yyyy", Locale("es", "MX")).format(Date(it * 1000))
                } ?: ""
                show("✔ Conectado. Cuenta: ${info.status.ifBlank { "activa" }}$exp", ok = true)
                delay(1800)
                finish()
            } catch (e: Exception) {
                show("✖ ${XtreamClient.friendlyError(e)}", ok = false)
            } finally {
                busy = false
            }
        }
    }

    private fun show(text: String, ok: Boolean) {
        result.text = text
        result.setTextColor(ContextCompat.getColor(this, if (ok) R.color.text else R.color.error))
    }
}
