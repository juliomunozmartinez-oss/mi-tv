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
    private lateinit var pinStatus: TextView
    private lateinit var pinInput: EditText
    private lateinit var btnPin: TextView
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
        pinStatus = findViewById(R.id.pinStatus)
        pinInput = findViewById(R.id.pinInput)
        btnPin = findViewById(R.id.btnPin)

        server.setText(prefs.server)
        username.setText(prefs.username)
        password.setText(prefs.password)
        updatePin()

        findViewById<TextView>(R.id.btnParse).setOnClickListener { parseLink() }
        findViewById<TextView>(R.id.btnSave).setOnClickListener { testAndSave() }
        findViewById<TextView>(R.id.btnSaveNoTest).setOnClickListener {
            if (validate()) {
                save()
                finish()
            }
        }
        findViewById<TextView>(R.id.btnProfiles).setOnClickListener {
            startActivity(android.content.Intent(this, ProfilesEditActivity::class.java))
        }
        findViewById<TextView>(R.id.btnCategories).setOnClickListener {
            if (!prefs.isConfigured) {
                show("Primero guarda tu servidor, usuario y contraseña.", ok = false)
            } else {
                startActivity(android.content.Intent(this, CategoriesActivity::class.java))
            }
        }
        val btnPreview = findViewById<TextView>(R.id.btnPreview)
        fun previewLabel() {
            btnPreview.text = "Avance automático: " + if (prefs.autoPreview) "Sí" else "No"
        }
        previewLabel()
        btnPreview.setOnClickListener {
            prefs.autoPreview = !prefs.autoPreview
            previewLabel()
        }
        findViewById<TextView>(R.id.btnKids).setOnClickListener {
            if (!prefs.isConfigured) {
                show("Primero guarda tu servidor, usuario y contraseña.", ok = false)
            } else {
                startActivity(android.content.Intent(this, KidsActivity::class.java))
            }
        }
        btnPin.setOnClickListener {
            if (prefs.pinEnabled) {
                prefs.pinEnabled = false
                updatePin()
            } else {
                val pin = pinInput.text.toString()
                if (pin.length != 4 || !pin.all { it.isDigit() }) {
                    pinStatus.text = "Escribe un PIN de 4 números y vuelve a tocar «Activar PIN»."
                    pinInput.requestFocus()
                } else {
                    prefs.pin = pin
                    prefs.pinEnabled = true
                    pinInput.setText("")
                    updatePin()
                }
            }
        }

        (if (prefs.isConfigured) findViewById<TextView>(R.id.btnSave) else link).requestFocus()
    }

    private fun updatePin() {
        if (prefs.pinEnabled) {
            pinStatus.text = "Activado: se pide al entrar a perfiles de adultos y a Ajustes."
            btnPin.text = "Desactivar PIN"
            pinInput.visibility = android.view.View.GONE
        } else {
            pinStatus.text = "Desactivado. Para activarlo escribe 4 números y toca «Activar PIN»."
            btnPin.text = "Activar PIN"
            pinInput.visibility = android.view.View.VISIBLE
        }
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
