package com.klavyem.t9

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.Locale

class SettingsActivity : Activity() {

    private val REQ_EXPORT = 1
    private val REQ_IMPORT = 2
    private val tr = Locale("tr", "TR")
    private lateinit var info: TextView

    private fun userFile() = File(filesDir, "kullanici.txt")

    private fun readWords(): List<String> =
        try {
            if (userFile().exists()) userFile().readLines().map { it.trim() }.filter { it.isNotEmpty() }
            else emptyList()
        } catch (e: Exception) {
            emptyList()
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#1F2326"))
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.setPadding(dp(20), dp(40), dp(20), dp(20))

        val title = TextView(this)
        title.text = "T9 Klavye Ayarları"
        title.textSize = 22f
        title.setTextColor(Color.WHITE)
        title.gravity = Gravity.CENTER
        root.addView(title)

        info = TextView(this)
        info.textSize = 16f
        info.setTextColor(Color.parseColor("#26C6B0"))
        info.gravity = Gravity.CENTER
        info.setPadding(0, dp(20), 0, dp(20))
        root.addView(info)

        fun button(text: String, action: () -> Unit) {
            val b = Button(this)
            b.text = text
            b.textSize = 18f
            b.setOnClickListener { action() }
            root.addView(
                b,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(64)
                ).apply { setMargins(0, dp(8), 0, dp(8)) }
            )
        }

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
        button("Kapat") { finish() }

        setContentView(root)
        showCount()
    }

    private fun showCount() {
        info.text = "Kendi eklediğiniz kelime sayısı: " + readWords().size
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
                info.text = "Geri yüklendi: " + added + " yeni kelime eklendi. Toplam: " + merged.size
            }
        } catch (e: Exception) {
            info.text = "İşlem başarısız oldu. Dosyayı kontrol edip tekrar deneyin."
        }
    }
}
