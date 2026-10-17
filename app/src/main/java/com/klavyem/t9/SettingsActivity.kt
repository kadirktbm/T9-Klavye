package com.klavyem.t9

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
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
import java.io.File
import java.util.Locale

class SettingsActivity : Activity() {

    private val REQ_EXPORT = 1
    private val REQ_IMPORT = 2
    private val tr = Locale("tr", "TR")
    private lateinit var prefs: SharedPreferences
    private lateinit var root: LinearLayout
    private lateinit var info: TextView
    private lateinit var countText: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun userFile() = File(filesDir, "kullanici.txt")

    private fun readWords(): List<String> =
        try {
            if (userFile().exists()) userFile().readLines().map { it.trim() }.filter { it.isNotEmpty() }
            else emptyList()
        } catch (e: Exception) {
            emptyList()
        }

    // Ayar değişince sürüm sayısını artırır; klavye bunu görünce kendini yeniden kurar
    private fun save(f: SharedPreferences.Editor.() -> Unit) {
        val e = prefs.edit()
        e.f()
        e.putInt("ver", prefs.getInt("ver", 0) + 1)
        e.apply()
    }

    private fun header(title: String) {
        val tv = TextView(this)
        tv.text = title
        tv.textSize = 22f
        tv.setTypeface(null, Typeface.BOLD)
        tv.setTextColor(Color.parseColor("#7A6A65"))
        tv.setPadding(0, dp(28), 0, dp(8))
        root.addView(tv)
    }

    private fun button(text: String, action: () -> Unit) {
        val b = Button(this)
        b.text = text
        b.textSize = 17f
        b.isAllCaps = false
        b.setOnClickListener { action() }
        root.addView(
            b,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(4), 0, dp(4)) }
        )
    }

    private fun radios(title: String, key: String, options: List<String>, def: Int) {
        header(title)
        val g = RadioGroup(this)
        val ids = ArrayList<Int>()
        for (o in options) {
            val rb = RadioButton(this)
            rb.text = o
            rb.textSize = 18f
            rb.id = View.generateViewId()
            ids.add(rb.id)
            g.addView(rb)
        }
        g.check(ids[prefs.getInt(key, def).coerceIn(0, options.size - 1)])
        g.setOnCheckedChangeListener { _, id ->
            val idx = ids.indexOf(id)
            if (idx >= 0) save { putInt(key, idx) }
        }
        root.addView(g)
    }

    private fun toggle(label: String, key: String, def: Boolean) {
        val sw = Switch(this)
        sw.text = label
        sw.textSize = 17f
        sw.isChecked = prefs.getBoolean(key, def)
        sw.setPadding(0, dp(10), 0, dp(10))
        sw.setOnCheckedChangeListener { _, on -> save { putBoolean(key, on) } }
        root.addView(sw)
    }

    private fun confirm(message: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("Evet") { _, _ -> action() }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun touchFile(f: File, content: String) {
        try {
            f.writeText(content)
        } catch (e: Exception) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("ayar", Context.MODE_PRIVATE)

        val sv = ScrollView(this)
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(18), dp(8), dp(18), dp(28))
        sv.addView(root)

        header("Kurulum")
        button("Klavyeyi etkinleştir") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        button("Klavyeyi seç (şimdi kullan)") {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showInputMethodPicker()
        }

        radios("Tema", "tema", listOf("Koyu", "Açık", "Tam siyah"), 0)
        radios("Klavye yüksekliği", "yukseklik", listOf("Küçük", "Normal", "Büyük", "Çok büyük"), 1)
        radios("Yazı boyutu", "yazi", listOf("Küçük", "Normal", "Büyük", "Çok büyük"), 1)

        header("Ses ve titreşim")
        toggle("Tuş sesi (telefonun \"dokunma sesleri\" ayarı da açık olmalı)", "ses", true)
        toggle("Tuş titreşimi", "titresim", true)
        val vibLabel = TextView(this)
        vibLabel.textSize = 16f
        vibLabel.setTextColor(Color.parseColor("#7A6A65"))
        root.addView(vibLabel)
        val seek = SeekBar(this)
        seek.max = 55
        val cur = prefs.getInt("titresimMs", 18).coerceIn(5, 60)
        seek.progress = cur - 5
        vibLabel.text = "Titreşim süresi: $cur ms"
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                vibLabel.text = "Titreşim süresi: ${p + 5} ms"
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                val ms = (s?.progress ?: 13) + 5
                save { putInt("titresimMs", ms) }
            }
        })
        root.addView(seek)

        header("Yazım")
        toggle("Cümle başında otomatik büyük harf", "otoBuyuk", true)
        toggle("Sonraki kelime tahmini", "sonrakiTahmin", true)
        toggle("Noktalamadan sonra otomatik boşluk", "otoBosluk", true)
        toggle("Özel isimleri otomatik büyük harfle yaz", "ozelIsim", true)
        toggle("Boşluktan sonra silme tuşu kelimeyi yeniden düzenlesin", "geriDon", true)

        header("Yedekleme")
        countText = TextView(this)
        countText.textSize = 16f
        root.addView(countText)
        button("Kelimelerimi yedekle") {
            if (readWords().isEmpty()) {
                info.text = "Yedeklenecek kendi kelimeniz yok."
            } else {
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                i.addCategory(Intent.CATEGORY_OPENABLE)
                i.type = "text/plain"
                i.putExtra(Intent.EXTRA_TITLE, "t9-kelime-yedegi.txt")
                startActivityForResult(i, REQ_EXPORT)
            }
        }
        button("Yedeği geri yükle") {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            startActivityForResult(i, REQ_IMPORT)
        }
        info = TextView(this)
        info.textSize = 16f
        info.setTextColor(Color.parseColor("#8D4B35"))
        info.setPadding(0, dp(6), 0, dp(6))
        root.addView(info)

        header("Verileri temizle")
        button("Eklediğim kelimeleri sil") {
            confirm("Eklediğiniz tüm kelimeler silinsin mi? Yedeğiniz yoksa geri alınamaz.") {
                touchFile(userFile(), "")
                showCount()
                info.text = "Eklediğiniz kelimeler silindi."
            }
        }
        button("Kelime tahmini geçmişini sil") {
            confirm("Öğrenilen kelime sıklığı ve kelime sırası geçmişi silinsin mi? Eklediğiniz kelimeler silinmez.") {
                try {
                    File(filesDir, "sikilik.txt").delete()
                    File(filesDir, "gecmis.tsv").delete()
                } catch (e: Exception) {
                }
                save { putInt("temizSayac", prefs.getInt("temizSayac", 0) + 1) }
                info.text = "Kelime tahmini geçmişi silindi."
            }
        }
        button("Öğrenilen özel isimleri sil") {
            confirm("Büyük harfle yazarak öğrettiğiniz özel isimler silinsin mi? İller silinmez.") {
                try {
                    File(filesDir, "ozel.txt").delete()
                } catch (e: Exception) {
                }
                save { putInt("ozelSayac", prefs.getInt("ozelSayac", 0) + 1) }
                info.text = "Öğrenilen özel isimler silindi."
            }
        }
        button("Pano geçmişini sil") {
            confirm("Pano geçmişi silinsin mi?") {
                touchFile(File(filesDir, "pano.txt"), "")
                info.text = "Pano geçmişi silindi."
            }
        }

        val note = TextView(this)
        note.text = "Not: Tema ve boyut değişiklikleri, klavyeyi bir sonraki açışınızda uygulanır. " +
            "Şifre alanlarında kelime öğrenme ve pano kaydı yapılmaz."
        note.textSize = 15f
        note.setTextColor(Color.parseColor("#7A6A65"))
        note.setPadding(0, dp(24), 0, 0)
        root.addView(note)

        setContentView(sv)
        showCount()
    }

    private fun showCount() {
        countText.text = "Kendi eklediğiniz kelime sayısı: " + readWords().size
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        try {
            if (requestCode == REQ_EXPORT) {
                val text = readWords().joinToString("\n") + "\n"
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                info.text = "Yedek kaydedildi: " + readWords().size + " kelime."
            } else if (requestCode == REQ_IMPORT) {
                val text = contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                } ?: ""
                val merged = LinkedHashSet<String>()
                readWords().forEach { merged.add(it.lowercase(tr)) }
                var added = 0
                text.lines().forEach { line ->
                    val w = line.trim().lowercase(tr)
                    if (w.isNotEmpty() && w.length < 40 && !w.contains(" ") && merged.add(w)) added++
                }
                userFile().writeText(merged.joinToString("\n") + "\n")
                info.text = "Geri yüklendi: $added yeni kelime eklendi. Toplam: " + merged.size
                showCount()
            }
        } catch (e: Exception) {
            info.text = "İşlem başarısız oldu. Dosyayı kontrol edip tekrar deneyin."
        }
    }
}
