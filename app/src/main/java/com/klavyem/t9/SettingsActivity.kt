package com.klavyem.t9

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File

class SettingsActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var root: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("ayarlar", Context.MODE_PRIVATE)
        title = "T9 Klavyem Ayarları"

        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(16), dp(16), dp(32))
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        addTitle("Kurulum")
        addButton("Klavyeyi etkinleştir") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        addButton("Klavyeyi seç (şimdi kullan)") {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        addTitle("Tema")
        addStringChoice(
            "tema", "koyu",
            listOf("Koyu", "Açık", "Tam siyah"),
            listOf("koyu", "acik", "siyah")
        )

        addTitle("Klavye yüksekliği")
        addIntChoice(
            "yukseklik", 100,
            listOf("Küçük", "Normal", "Büyük", "Çok büyük"),
            listOf(85, 100, 115, 130)
        )

        addTitle("Yazı boyutu")
        addIntChoice(
            "yazi", 100,
            listOf("Küçük", "Normal", "Büyük", "Çok büyük"),
            listOf(85, 100, 115, 135)
        )

        addTitle("Ses ve titreşim")
        addSwitch("ses", true, "Tuş sesi (telefonun \"dokunma sesleri\" ayarı da açık olmalı)")
        addSwitch("titresim", true, "Tuş titreşimi")
        addVibrationBar()

        addTitle("Yazım")
        addSwitch("buyukHarf", true, "Cümle başında otomatik büyük harf")
        addSwitch("sonraki", true, "Sonraki kelime tahmini")

        addTitle("Verileri temizle")
        addButton("Eklediğim kelimeleri sil") {
            File(filesDir, "kullanici.txt").delete()
            markCleared("Eklenen kelimeler silindi")
        }
        addButton("Kelime tahmini geçmişini sil") {
            File(filesDir, "bigram.txt").delete()
            markCleared("Tahmin geçmişi silindi")
        }
        addButton("Pano geçmişini sil") {
            File(filesDir, "pano.txt").delete()
            markCleared("Pano geçmişi silindi")
        }

        val note = TextView(this)
        note.text = "Not: Tema ve boyut değişiklikleri, klavyeyi bir sonraki açışınızda uygulanır. " +
            "Şifre alanlarında kelime öğrenme ve pano kaydı yapılmaz."
        note.textSize = 13f
        note.setPadding(0, dp(20), 0, 0)
        root.addView(note)
    }

    private fun markCleared(msg: String) {
        prefs.edit().putLong("temizlendi", System.currentTimeMillis()).apply()
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun addTitle(text: String) {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 18f
        tv.setTypeface(null, Typeface.BOLD)
        tv.setPadding(0, dp(22), 0, dp(6))
        root.addView(tv)
    }

    private fun addButton(text: String, action: () -> Unit) {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setOnClickListener { action() }
        root.addView(
            b,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun addSwitch(key: String, def: Boolean, text: String) {
        val sw = Switch(this)
        sw.text = text
        sw.isChecked = prefs.getBoolean(key, def)
        sw.setPadding(0, dp(8), 0, dp(8))
        sw.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(key, checked).apply()
        }
        root.addView(sw)
    }

    private fun addStringChoice(key: String, def: String, labels: List<String>, values: List<String>) {
        val group = RadioGroup(this)
        group.orientation = RadioGroup.VERTICAL
        val cur = prefs.getString(key, def) ?: def
        val ids = ArrayList<Int>()
        for ((i, l) in labels.withIndex()) {
            val rb = RadioButton(this)
            rb.text = l
            val id = View.generateViewId()
            rb.id = id
            ids.add(id)
            group.addView(rb)
            if (values[i] == cur) group.check(id)
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val i = ids.indexOf(checkedId)
            if (i >= 0) prefs.edit().putString(key, values[i]).apply()
        }
        root.addView(group)
    }

    private fun addIntChoice(key: String, def: Int, labels: List<String>, values: List<Int>) {
        val group = RadioGroup(this)
        group.orientation = RadioGroup.VERTICAL
        val cur = prefs.getInt(key, def)
        val ids = ArrayList<Int>()
        for ((i, l) in labels.withIndex()) {
            val rb = RadioButton(this)
            rb.text = l
            val id = View.generateViewId()
            rb.id = id
            ids.add(id)
            group.addView(rb)
            if (values[i] == cur) group.check(id)
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val i = ids.indexOf(checkedId)
            if (i >= 0) prefs.edit().putInt(key, values[i]).apply()
        }
        root.addView(group)
    }

    private fun addVibrationBar() {
        val label = TextView(this)
        val cur = prefs.getInt("titresimMs", 18)
        label.text = "Titreşim süresi: $cur ms"
        label.setPadding(0, dp(8), 0, 0)
        root.addView(label)
        val bar = SeekBar(this)
        bar.max = 55
        bar.progress = cur - 5
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                prefs.edit().putInt("titresimMs", p + 5).apply()
                label.text = "Titreşim süresi: ${p + 5} ms"
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}

            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        root.addView(bar)
    }
}
