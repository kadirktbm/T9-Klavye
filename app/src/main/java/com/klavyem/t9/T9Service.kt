package com.klavyem.t9

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale

private const val PROVINCES =
    "Türkiye Adana Adıyaman Afyonkarahisar Ağrı Aksaray Amasya Ankara Antalya Ardahan Artvin Balıkesir " +
    "Bartın Batman Bayburt Bilecik Bingöl Bitlis Bolu Burdur Bursa Çanakkale Çankırı Çorum Denizli " +
    "Diyarbakır Düzce Edirne Elazığ Erzincan Erzurum Eskişehir Gaziantep Giresun Gümüşhane Hakkari " +
    "Hatay Iğdır Isparta İstanbul İzmir Kahramanmaraş Karabük Karaman Kastamonu Kayseri Kırıkkale " +
    "Kırklareli Kırşehir Kilis Kocaeli Konya Kütahya Malatya Manisa Mardin Mersin Muğla Muş Nevşehir " +
    "Niğde Osmaniye Rize Sakarya Samsun Siirt Sinop Sivas Şanlıurfa Şırnak Tekirdağ Tokat Trabzon " +
    "Tunceli Uşak Yalova Yozgat Zonguldak"

private val DEFAULT_E = listOf("😀", "😂", "❤", "👍", "🙏", "😊", "😍", "😉")
private val DEFAULT_S = listOf("@", "#", "&", "%", "+", "-", "=", "_")

private const val EMOJIS =
    "😀 😃 😄 😁 😆 😅 😂 🤣 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😋 😛 😜 🤪 🤗 🤭 🤔 " +
    "😐 😑 😶 😏 😒 🙄 😬 😌 😔 😪 😴 😷 🤒 🤕 🤢 🤮 🥵 🥶 😵 🤯 🤠 🥳 😎 🤓 " +
    "😕 😟 🙁 😮 😲 😳 🥺 😦 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 " +
    "🤬 😈 💀 💩 🤡 👻 👽 🤖 👍 👎 👌 🤞 🤟 🤘 👈 👉 👆 👇 👋 🤚 ✋ 🖖 👏 🙌 " +
    "🤝 🙏 💪 👀 🧠 🧡 💛 💚 💙 💜 🖤 🤍 💔 💕 💖 💯 🔥 ✨ ⭐ 🎉 🎁 🌹 🌸 🌙 " +
    "☕ 🍕 🍔 🍰 🎂 ⚽ 🚗 🏠 📱 💻 📷 🎵 ✅ ❌ ❓ ❗"

class T9Service : InputMethodService() {

    private val tr = Locale("tr", "TR")
    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    private val teal = Color.parseColor("#26C6B0")
    private lateinit var prefs: SharedPreferences
    private var txt = Color.WHITE
    private var rootColor = Color.parseColor("#1F2326")
    private var keyColor = Color.parseColor("#2D3235")
    private var keyPressedColor = Color.parseColor("#4A5256")
    private var fontScale = 1f
    private var heightScale = 1f
    private var soundOn = true
    private var soundVol = 0.16f
    private var vibOn = true
    private var vibMs = 18
    private var nextPref = true
    private var privateField = false
    private var builtVer = -1
    private var panoStamp = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val keyMap = linkedMapOf(
        '2' to "abcç", '3' to "def", '4' to "ghıiğ", '5' to "jkl",
        '6' to "mnoö", '7' to "pqrsş", '8' to "tuüv", '9' to "wxyz"
    )
    private val tapMap = HashMap<Char, String>()
    private val letterToDigit = HashMap<Char, Char>()
    private var words = ArrayList<String>()
    private var codes = ArrayList<String>()
    private var wordSet = HashSet<String>()

    // Sonraki kelime tahmini
    private var bigram = HashMap<String, List<String>>()
    private var nextWords: List<String> = emptyList()

    private var userStamp = 0L
    private var digits = ""
    private var candidates: List<String> = emptyList()
    private var index = 0
    private var shift = false
    private var autoShift = false
    private var capsLock = false
    private var autoSpaceAllowed = true
    private var showPunct = false
    private val punctChips = listOf(";", "\"", "(", ")", "-", "…", "/")
    private val properMap = HashMap<String, String>()
    private val properLearned = HashSet<String>()
    private var properCounter = 0
    private var properOn = true
    private var backReopen = true
    private var lastDigits: String? = null
    private var lastCands: List<String> = emptyList()
    private var lastIndex = 0
    private var lastShown = ""
    private val usage = LinkedHashMap<String, Int>()
    private val recentE = ArrayList<TextView>()
    private val recentS = ArrayList<TextView>()
    private var emojiSetCache: Set<String> = emptySet()
    private var symbolSetCache: Set<String> = emptySet()
    private val abbrRegex = Regex("(^|[^\\p{L}])(vs|vb)\\.\\s*$", RegexOption.IGNORE_CASE)
    private var lastShiftTap = 0L
    private var autoCapAllowed = false
    private var t9On = true
    private var panel = 0
    private var uiReady = false

    private var pendingActive = false
    private var pendingKey = '2'
    private var pendingIdx = 0
    private val manualWord = StringBuilder()
    private var unknownWord: String? = null
    private val freq = HashMap<String, Int>()
    private val learned = HashMap<String, HashMap<String, Int>>()
    private var statDirty = 0
    private var clearCounter = 0
    private val statLock = Any()
    private var delWord: String? = null
    private var delTime = 0L
    private val commitRunnable = Runnable { commitPending() }
    private val autoCapRunnable = Runnable {
        autoCap()
        refreshNext()
        refreshUnknown()
        renderBar()
    }

    private var repeatCount = 0
    private val repeatRunnable = object : Runnable {
        override fun run() {
            repeatCount++
            if (repeatCount > 15) deleteWord() else onBackspace()
            handler.postDelayed(this, if (repeatCount > 15) 160L else 60L)
        }
    }

    private val clips = ArrayList<String>()
    private var lastClip = ""
    private var clipboard: ClipboardManager? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClip() }

    private lateinit var candidateBar: LinearLayout
    private lateinit var shiftKey: TextView
    private lateinit var modeKey: TextView
    private lateinit var t9Key: TextView
    private lateinit var spaceKey: TextView
    private lateinit var mainArea: LinearLayout
    private lateinit var symbolArea: LinearLayout
    private lateinit var numArea: LinearLayout
    private lateinit var emojiArea: LinearLayout
    private lateinit var clipArea: LinearLayout
    private lateinit var clipList: LinearLayout

    override fun onCreate() {
        super.onCreate()
        for ((d, letters) in keyMap) {
            tapMap[d] = letters
            for (c in letters) letterToDigit[c] = d
        }
        tapMap['1'] = ".,@?!'-"
        prefs = getSharedPreferences("ayar", Context.MODE_PRIVATE)
        clearCounter = prefs.getInt("temizSayac", 0)
        properCounter = prefs.getInt("ozelSayac", 0)
        initProper()
        loadUsage()
        loadStats()
        loadWords()
        loadClips()
        clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard?.addPrimaryClipChangedListener(clipListener)
    }

    override fun onDestroy() {
        saveStats()
        clipboard?.removePrimaryClipChangedListener(clipListener)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    // Kullanıcı imleci başka yere götürürse bekleyen (altı çizili) kelimeyi bırak,
    // böylece silme tuşu imleç neredeyse oradan siler
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if ((digits.isNotEmpty() || pendingActive) && candidatesStart != -1 &&
            (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)
        ) {
            abandonComposing()
        }
    }

    private fun abandonComposing() {
        handler.removeCallbacks(commitRunnable)
        currentInputConnection?.finishComposingText()
        digits = ""
        candidates = emptyList()
        index = 0
        pendingActive = false
        manualWord.setLength(0)
        renderBar()
        autoCapSoon()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        saveStats()
        super.onFinishInputView(finishingInput)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Ayarlar değiştiyse klavyeyi yeniden kur
        if (uiReady && prefs.getInt("ver", 0) != builtVer) setInputView(onCreateInputView())
        // Kelime tahmini geçmişi silindiyse bellektekini de temizle
        val cc = prefs.getInt("temizSayac", 0)
        if (cc != clearCounter) {
            clearCounter = cc
            freq.clear()
            learned.clear()
            statDirty = 0
        }
        val oc = prefs.getInt("ozelSayac", 0)
        if (oc != properCounter) {
            properCounter = oc
            initProper()
        }
        // Pano silindiyse yeniden oku
        if (File(filesDir, "pano.txt").lastModified() != panoStamp) {
            clips.clear()
            loadClips()
        }
        // Geri yükleme yapıldıysa kullanıcı sözlüğünü yeniden oku
        if (File(filesDir, "kullanici.txt").lastModified() != userStamp) loadWords()
        handler.removeCallbacks(commitRunnable)
        handler.removeCallbacks(repeatRunnable)
        pendingActive = false
        manualWord.setLength(0)
        digits = ""
        candidates = emptyList()
        nextWords = emptyList()
        index = 0
        capsLock = false
        resetShift()

        val type = info?.inputType ?: 0
        val cls = type and InputType.TYPE_MASK_CLASS
        val v = type and InputType.TYPE_MASK_VARIATION
        privateField = cls == InputType.TYPE_CLASS_TEXT && (
            v == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            ) || (cls == InputType.TYPE_CLASS_NUMBER &&
            (type and InputType.TYPE_NUMBER_VARIATION_PASSWORD) != 0)
        autoCapAllowed = prefs.getBoolean("otoBuyuk", true) && cls == InputType.TYPE_CLASS_TEXT &&
            v != InputType.TYPE_TEXT_VARIATION_PASSWORD &&
            v != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD &&
            v != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD &&
            v != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS &&
            v != InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS &&
            v != InputType.TYPE_TEXT_VARIATION_URI
        autoSpaceAllowed = prefs.getBoolean("otoBosluk", true) && cls == InputType.TYPE_CLASS_TEXT &&
            v != InputType.TYPE_TEXT_VARIATION_PASSWORD &&
            v != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD &&
            v != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD &&
            v != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS &&
            v != InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS &&
            v != InputType.TYPE_TEXT_VARIATION_URI &&
            v != InputType.TYPE_TEXT_VARIATION_FILTER

        if (!restarting && uiReady) {
            showPanel(
                if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) 2 else 0
            )
        }
        captureClip()
        renderBar()
        autoCapSoon()
    }

    // ---------- Sözlük ----------
    private fun loadWords() {
        userStamp = File(filesDir, "kullanici.txt").lastModified()
        Thread {
            val w = ArrayList<String>()
            val c = ArrayList<String>()
            val seen = HashSet<String>()
            fun addWord(raw: String) {
                val word = raw.lowercase(tr)
                if (word.isEmpty() || !seen.add(word)) return
                val code = toCode(word) ?: return
                w.add(word)
                c.add(code)
            }
            try {
                val f = File(filesDir, "kullanici.txt")
                if (f.exists()) f.readLines().forEach { addWord(it.trim()) }
            } catch (e: Exception) {
            }
            try {
                val name = if (assets.list("")?.contains("sozluk.txt") == true)
                    "sozluk.txt" else "kelimeler.txt"
                assets.open(name).bufferedReader().use { r ->
                    r.lineSequence().forEach { line ->
                        line.split(Regex("\\s+")).forEach { addWord(it) }
                    }
                }
            } catch (e: Exception) {
            }
            for (p in PROVINCES.split(" ")) if (p.isNotEmpty()) addWord(p)
            val bg = HashMap<String, List<String>>()
            try {
                assets.open("bigram.tsv").bufferedReader().use { r ->
                    r.lineSequence().forEach { line ->
                        val p = line.trim().split(Regex("\\s+"), 2)
                        if (p.size == 2) {
                            val list = p[1].split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            if (list.isNotEmpty()) bg[p[0].lowercase(tr)] = list
                        }
                    }
                }
            } catch (e: Exception) {
            }
            handler.post {
                words = w
                codes = c
                wordSet = seen
                bigram = bg
            }
        }.start()
    }

    private fun toCode(w: String): String? {
        val sb = StringBuilder()
        for (ch in w) sb.append(letterToDigit[ch] ?: return null)
        return sb.toString()
    }

    private fun finishManualWord() {
        val w = manualWord.toString()
        manualWord.setLength(0)
        if (privateField || w.length < 2) return
        addUserWord(w)
        val before = currentInputConnection?.getTextBeforeCursor(60, 0)?.toString() ?: ""
        val low = before.lowercase(tr)
        val b = if (low.endsWith(w)) before.dropLast(w.length) else ""
        val typed = if (low.endsWith(w)) before.takeLast(w.length) else w
        recordUse(w, b, typed)
    }

    // ---------- Kullanım sıklığı ve öğrenilen kelime çiftleri ----------
    private fun prevWordOf(b: String): String? {
        if (b.isEmpty() || b.last() != ' ') return null
        val t = b.trimEnd()
        if (t.isEmpty() || !t.last().isLetter()) return null
        val p = t.takeLastWhile { it.isLetter() }
        return if (p.isEmpty()) null else p.lowercase(tr)
    }

    private fun sentenceStart(b: String): Boolean {
        val t = b.trimEnd()
        if (t.isEmpty() || b.endsWith("\n")) return true
        return ".!?:\"“…".indexOf(t.last()) >= 0
    }

    private fun learnProperIfNeeded(lw: String, shown: String, b: String) {
        if (!properOn || shown.length < 2 || properMap.containsKey(lw)) return
        if (!shown[0].isUpperCase() || shown.lowercase(tr) != lw) return
        for (i in 1 until shown.length) if (shown[i].isUpperCase()) return
        if (sentenceStart(b)) return
        properMap[lw] = shown
        properLearned.add(lw)
        try {
            File(filesDir, "ozel.txt").appendText(shown + "\n")
        } catch (e: Exception) {
        }
    }

    private fun initProper() {
        properMap.clear()
        properLearned.clear()
        for (p in PROVINCES.split(" ")) if (p.isNotEmpty()) properMap[p.lowercase(tr)] = p
        try {
            val f = File(filesDir, "ozel.txt")
            if (f.exists()) {
                f.readLines().forEach { l ->
                    val s = l.trim()
                    if (s.length >= 2) {
                        val lw = s.lowercase(tr)
                        properMap[lw] = s
                        properLearned.add(lw)
                    }
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun recordUse(w: String, b: String, shown: String = w) {
        if (privateField) return
        val lw = w.lowercase(tr)
        if (!wordSet.contains(lw)) return
        learnProperIfNeeded(lw, shown, b)
        freq[lw] = (freq[lw] ?: 0) + 1
        val p = prevWordOf(b)
        if (p != null) {
            var m = learned[p]
            if (m == null) {
                m = HashMap()
                learned[p] = m
            }
            m[lw] = (m[lw] ?: 0) + 1
        }
        statDirty++
        if (statDirty >= 15) saveStats()
    }

    private fun loadStats() {
        try {
            val f = File(filesDir, "sikilik.txt")
            if (f.exists()) {
                f.forEachLine { l ->
                    val p = l.split('\t')
                    if (p.size == 2) {
                        val n = p[1].toIntOrNull()
                        if (n != null) freq[p[0]] = n
                    }
                }
            }
            val g = File(filesDir, "gecmis.tsv")
            if (g.exists()) {
                g.forEachLine { l ->
                    val p = l.split('\t')
                    if (p.size == 3) {
                        val n = p[2].toIntOrNull()
                        if (n != null) {
                            var m = learned[p[0]]
                            if (m == null) {
                                m = HashMap()
                                learned[p[0]] = m
                            }
                            m[p[1]] = n
                        }
                    }
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun saveStats() {
        if (statDirty == 0) return
        statDirty = 0
        val fb = StringBuilder()
        for ((k, v) in freq) fb.append(k).append('\t').append(v).append('\n')
        val gb = StringBuilder()
        for ((p, m) in learned) {
            for (e in m.entries.sortedByDescending { it.value }.take(12)) {
                gb.append(p).append('\t').append(e.key).append('\t').append(e.value).append('\n')
            }
        }
        val fs = fb.toString()
        val gs = gb.toString()
        Thread {
            synchronized(statLock) {
                try {
                    File(filesDir, "sikilik.txt").writeText(fs)
                    File(filesDir, "gecmis.tsv").writeText(gs)
                } catch (e: Exception) {
                }
            }
        }.start()
    }

    private fun addUserWord(w: String): Boolean {
        if (w.length < 2 || wordSet.contains(w)) return false
        val code = toCode(w) ?: return false
        words.add(0, w)
        codes.add(0, code)
        wordSet.add(w)
        try {
            val uf = File(filesDir, "kullanici.txt")
            uf.appendText(w + "\n")
            userStamp = uf.lastModified()
        } catch (e: Exception) {
        }
        return true
    }

    private fun toast(msg: String) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
        }
    }

    // İmlecin önündeki kelime sözlükte yoksa "Ekle" düğmesi göstermek için bulur
    private fun refreshUnknown() {
        unknownWord = null
        if (privateField || !autoCapAllowed || digits.isNotEmpty() || pendingActive) return
        val before = currentInputConnection?.getTextBeforeCursor(60, 0)?.toString() ?: return
        var end = before.length
        if (end > 0 && before[end - 1] == ' ') end--
        if (end <= 0) return
        var start = end
        while (start > 0 && before[start - 1].isLetter()) start--
        if (end - start < 2 || end - start > 30) return
        if (start > 0 && "'’@./:-_#0123456789".indexOf(before[start - 1]) >= 0) return
        val w = before.substring(start, end).lowercase(tr)
        if (wordSet.contains(w) || toCode(w) == null) return
        unknownWord = w
    }

    // Eklediğin yanlış kelimeyi / öğrenilen özel ismi silmek için: öneriye iki kez uzun bas
    private fun onWordLongPress(w: String) {
        val uf = File(filesDir, "kullanici.txt")
        val mine = try {
            uf.exists() && uf.readLines().any { it.trim() == w }
        } catch (e: Exception) {
            false
        }
        val proper = properLearned.contains(w)
        if (!mine && !proper) {
            if (wordSet.contains(w)) toast("\"$w\" hazır sözlükte, silinemez")
            return
        }
        val now = System.currentTimeMillis()
        if (delWord == w && now - delTime < 4000) {
            if (mine) {
                try {
                    val left = uf.readLines().map { it.trim() }.filter { it.isNotEmpty() && it != w }
                    uf.writeText(if (left.isEmpty()) "" else left.joinToString("\n") + "\n")
                    userStamp = uf.lastModified()
                } catch (e: Exception) {
                }
                val i = words.indexOf(w)
                if (i >= 0) {
                    words.removeAt(i)
                    codes.removeAt(i)
                }
                wordSet.remove(w)
            }
            if (proper) {
                properMap.remove(w)
                properLearned.remove(w)
                try {
                    val pf = File(filesDir, "ozel.txt")
                    val left = pf.readLines().map { it.trim() }.filter { it.isNotEmpty() && it.lowercase(tr) != w }
                    pf.writeText(if (left.isEmpty()) "" else left.joinToString("\n") + "\n")
                } catch (e: Exception) {
                }
            }
            delWord = null
            toast("Silindi: $w")
            if (digits.isNotEmpty()) updateCandidates() else renderBar()
        } else {
            delWord = w
            delTime = now
            toast("Silmek için tekrar uzun basın: $w")
        }
    }

    // ---------- Sonraki kelime tahmini ----------
    // İmlecin önündeki metne bakar: son karakter boşluksa ve ondan önce bir kelime varsa
    // o kelimenin bigram listesini öneri olarak gösterir.
    private fun refreshNext() {
        nextWords = emptyList()
        if (!nextPref || digits.isNotEmpty() || pendingActive) return
        val before = currentInputConnection?.getTextBeforeCursor(60, 0)?.toString() ?: return
        if (before.isEmpty() || before.last() != ' ') return
        val last = before.trimEnd().takeLastWhile { it.isLetter() }
        if (last.isEmpty()) return
        val key = last.lowercase(tr)
        val list = ArrayList<String>()
        val lr = learned[key]
        if (lr != null) {
            for (e in lr.entries.sortedByDescending { it.value }.take(8)) list.add(e.key)
        }
        val st = bigram[key]
        if (st != null) {
            for (w in st) {
                if (list.size >= 12) break
                if (!list.contains(w)) list.add(w)
            }
        }
        nextWords = list
    }

    // ---------- Büyük harf ----------
    private fun setShift(on: Boolean) {
        shift = on
        if (::shiftKey.isInitialized) {
            shiftKey.setTextColor(if (on) teal else txt)
            shiftKey.text = if (capsLock) "⇪" else "⇧"
        }
    }

    private fun resetShift() {
        autoShift = false
        if (capsLock) setShift(true) else setShift(false)
    }

    private fun styled(w: String): String {
        if (capsLock) return w.uppercase(tr)
        if (shift) return cap(w)
        return if (properOn) (properMap[w] ?: w) else w
    }

    private fun autoCapSoon() {
        handler.removeCallbacks(autoCapRunnable)
        handler.postDelayed(autoCapRunnable, 80)
    }

    private fun autoCap() {
        if (!autoCapAllowed || digits.isNotEmpty() || pendingActive) return
        val aic = currentInputConnection
        var mode = aic?.getCursorCapsMode(TextUtils.CAP_MODE_SENTENCES) ?: 0
        if (mode != 0) {
            // "vs." ve "vb." gibi kısaltmalardan sonra büyük harfe geçme
            val tail = aic?.getTextBeforeCursor(8, 0)?.toString() ?: ""
            if (abbrRegex.containsMatchIn(tail)) mode = 0
        }
        if (mode != 0) {
            autoShift = true
            setShift(true)
        } else if (autoShift && !capsLock) {
            autoShift = false
            setShift(false)
        }
    }

    // ---------- T9 mantığı ----------
    private fun cap(w: String) =
        if (w.isEmpty()) w else w.substring(0, 1).uppercase(tr) + w.substring(1)

    private fun current(): String {
        if (candidates.isEmpty() || index >= candidates.size) return ""
        val w = candidates[index]
        return styled(w)
    }

    private fun updateCandidates() {
        val exact = ArrayList<String>()
        val prefix = ArrayList<String>()
        for (i in words.indices) {
            val c = codes[i]
            if (c == digits) {
                if (exact.size < 40) exact.add(words[i])
            } else if (prefix.size < 24 && c.length > digits.length && c.startsWith(digits)) {
                prefix.add(words[i])
            }
            if (exact.size >= 40 && prefix.size >= 24) break
        }
        // Sık kullandığın kelimeler öne geçer (eşitlikte sözlük sırası korunur)
        val list = ArrayList<String>(exact.sortedByDescending { freq[it] ?: 0 }.take(12))
        list.addAll(prefix.sortedByDescending { freq[it] ?: 0 }.take(8))
        if (list.isEmpty()) list.add(digits.map { keyMap[it]!![0] }.joinToString(""))
        candidates = list
        index = 0
        nextWords = emptyList()
        showComposing()
        renderBar()
    }

    private fun showComposing() {
        currentInputConnection?.setComposingText(current(), 1)
    }

    private fun commitWord(trailing: String) {
        val ic = currentInputConnection ?: return
        if (digits.isNotEmpty()) {
            val used = candidates.getOrNull(index) ?: ""
            val cur = current()
            var b = ic.getTextBeforeCursor(60, 0)?.toString() ?: ""
            if (cur.isNotEmpty() && b.endsWith(cur)) b = b.dropLast(cur.length)
            ic.commitText(cur + trailing, 1)
            if (used.isNotEmpty()) recordUse(used, b, cur)
            if (trailing == " " && backReopen && cur.isNotEmpty()) {
                lastDigits = digits
                lastCands = candidates
                lastIndex = index
                lastShown = cur
            } else {
                lastDigits = null
            }
            digits = ""
            candidates = emptyList()
            index = 0
            resetShift()
            renderBar()
        } else if (trailing.isNotEmpty()) {
            ic.commitText(trailing, 1)
        }
    }

    // ---------- Manuel (çoklu dokunma) yazım ----------
    private fun letterFor(d: Char, i: Int): String {
        val s = tapMap[d] ?: return ""
        val ch = s[i % s.length].toString()
        return if (shift) ch.uppercase(tr) else ch
    }

    private fun onManualKey(d: Char) {
        val s = tapMap[d] ?: return
        handler.removeCallbacks(commitRunnable)
        if (pendingActive && pendingKey == d) {
            pendingIdx = (pendingIdx + 1) % s.length
        } else {
            commitPending()
            pendingKey = d
            pendingIdx = 0
            pendingActive = true
        }
        nextWords = emptyList()
        currentInputConnection?.setComposingText(letterFor(d, pendingIdx), 1)
        handler.postDelayed(commitRunnable, 900)
    }

    private fun commitPending() {
        handler.removeCallbacks(commitRunnable)
        if (!pendingActive) return
        val ch = letterFor(pendingKey, pendingIdx)
        pendingActive = false
        currentInputConnection?.commitText(ch, 1)
        if (ch.length == 1 && ch[0].isLetter()) {
            manualWord.append(ch.lowercase(tr))
        } else {
            finishManualWord()
        }
        resetShift()
    }

    private fun commitAll() {
        commitPending()
        commitWord("")
    }

    // ---------- Tuş işlemleri ----------
    private fun onDigit(d: Char) {
        lastDigits = null
        showPunct = false
        if (!t9On) {
            onManualKey(d)
            return
        }
        if (d == '1') {
            punct("@")
            return
        }
        digits += d
        updateCandidates()
    }

    private fun onNext() {
        if (!t9On) {
            commitPending()
            return
        }
        if (candidates.size > 1) {
            index = (index + 1) % candidates.size
            showComposing()
            renderBar()
        }
    }

    private fun onSpace() {
        showPunct = false
        if (digits.isNotEmpty()) {
            commitWord(" ")
        } else {
            commitPending()
            finishManualWord()
            currentInputConnection?.commitText(" ", 1)
            resetShift()
        }
        autoCapSoon()
    }

    // Boşluktan hemen sonra silme: kelime tekrar düzenlenebilir hale gelir
    private fun tryReopen(): Boolean {
        val ld = lastDigits ?: return false
        lastDigits = null
        if (!backReopen) return false
        val ic = currentInputConnection ?: return false
        val tail = lastShown + " "
        val b = ic.getTextBeforeCursor(tail.length, 0)?.toString() ?: return false
        if (b != tail) return false
        ic.deleteSurroundingText(tail.length, 0)
        digits = ld
        candidates = lastCands
        index = lastIndex
        if (lastShown.isNotEmpty() && lastShown[0].isUpperCase()) setShift(true)
        showComposing()
        renderBar()
        return true
    }

    private fun onBackspace() {
        showPunct = false
        if (!pendingActive && digits.isEmpty() && lastDigits != null && tryReopen()) return
        if (pendingActive) {
            handler.removeCallbacks(commitRunnable)
            pendingActive = false
            currentInputConnection?.setComposingText("", 1)
            return
        }
        if (digits.isNotEmpty()) {
            digits = digits.dropLast(1)
            if (digits.isEmpty()) {
                currentInputConnection?.setComposingText("", 1)
                candidates = emptyList()
                index = 0
                renderBar()
            } else {
                updateCandidates()
            }
            return
        }
        if (manualWord.isNotEmpty()) manualWord.setLength(manualWord.length - 1)
        nextWords = emptyList()
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        autoCapSoon()
    }

    private fun deleteWord() {
        val ic = currentInputConnection ?: return
        if (pendingActive || digits.isNotEmpty()) {
            onBackspace()
            return
        }
        val before = ic.getTextBeforeCursor(40, 0)?.toString() ?: ""
        if (before.isEmpty()) return
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--
        while (i > 0 && !before[i - 1].isWhitespace()) i--
        val count = before.length - i
        if (count > 0) ic.deleteSurroundingText(count, 0)
        manualWord.setLength(0)
        nextWords = emptyList()
        autoCapSoon()
    }

    private fun onEnter() {
        lastDigits = null
        showPunct = false
        commitAll()
        finishManualWord()
        val opts = currentInputEditorInfo?.imeOptions ?: 0
        val action = opts and EditorInfo.IME_MASK_ACTION
        val noEnter = (opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noEnter && action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            currentInputConnection?.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        nextWords = emptyList()
        autoCapSoon()
    }

    private fun punct(s: String) {
        lastDigits = null
        showPunct = false
        commitAll()
        finishManualWord()
        val pic = currentInputConnection
        var out = s
        if (autoSpaceAllowed && pic != null && (s == "." || s == "!" || s == "?")) {
            val b = pic.getTextBeforeCursor(3, 0)?.toString() ?: ""
            if (s == "." && b.length >= 3 && b.endsWith(". ") && b[0].isLetter()) {
                // Üç nokta (...) yazılırken otomatik eklenen boşluğu geri al
                pic.deleteSurroundingText(1, 0)
            } else if (b.isNotEmpty() && b.last().isLetter()) {
                out = s + " "
            }
        }
        pic?.commitText(out, 1)
        resetShift()
        nextWords = emptyList()
        autoCapSoon()
    }

    private fun toggleShift() {
        val now = System.currentTimeMillis()
        autoShift = false
        if (capsLock) {
            // Caps lock açıkken tek basış kapatır
            capsLock = false
            setShift(false)
        } else if (shift && now - lastShiftTap < 400) {
            // Hızlı iki basış: caps lock
            capsLock = true
            setShift(true)
        } else {
            setShift(!shift)
        }
        lastShiftTap = now
        if (digits.isNotEmpty()) showComposing()
        renderBar()
    }

    private fun toggleT9() {
        commitAll()
        t9On = !t9On
        t9Key.text = label("T9", if (t9On) "açık" else "kapalı")
        t9Key.setTextColor(if (t9On) teal else txt)
    }

    // ---------- Paneller ----------
    // 0 = T9, 1 = simge, 2 = sayı, 3 = emoji, 4 = pano
    private fun showPanel(p: Int) {
        panel = p
        modeKey.text = if (p == 0) "12#" else "abc"
        mainArea.visibility = if (p == 0) View.VISIBLE else View.GONE
        symbolArea.visibility = if (p == 1) View.VISIBLE else View.GONE
        numArea.visibility = if (p == 2) View.VISIBLE else View.GONE
        emojiArea.visibility = if (p == 3) View.VISIBLE else View.GONE
        clipArea.visibility = if (p == 4) View.VISIBLE else View.GONE
        if (::t9Key.isInitialized) t9Key.visibility = if (p == 2) View.GONE else View.VISIBLE
        if (::spaceKey.isInitialized) {
            if (p == 2) {
                spaceKey.text = label("0", "uzun bas: boşluk")
                spaceKey.textSize = 22f
            } else {
                spaceKey.text = "Türkçe"
                spaceKey.textSize = 16f
            }
        }
        if (p == 4) renderClips()
        if (p == 1 || p == 3) refreshRecents()
    }

    private fun toggleMode() {
        commitAll()
        showPanel(if (panel == 0) 1 else 0)
    }

    private fun openSettings() {
        commitAll()
        try {
            val i = Intent(this, SettingsActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
        }
    }

    private fun togglePano() {
        commitAll()
        showPanel(if (panel == 4) 0 else 4)
    }

    // ---------- Pano ----------
    private fun loadClips() {
        try {
            val f = File(filesDir, "pano.txt")
            panoStamp = f.lastModified()
            if (f.exists()) {
                f.readText().split("\u001F").filter { it.isNotBlank() }.forEach { clips.add(it) }
            }
        } catch (e: Exception) {
        }
    }

    private fun saveClips() {
        try {
            val pf = File(filesDir, "pano.txt")
            pf.writeText(clips.joinToString("\u001F"))
            panoStamp = pf.lastModified()
        } catch (e: Exception) {
        }
    }

    private fun captureClip() {
        try {
            if (privateField) return
            val cm = clipboard ?: return
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            val t = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
            if (t == lastClip) return
            lastClip = t
            addClip(t)
        } catch (e: Exception) {
        }
    }

    private fun addClip(t: String) {
        val s = t.trim()
        if (s.isEmpty() || s.length > 2000) return
        clips.remove(s)
        clips.add(0, s)
        while (clips.size > 30) clips.removeAt(clips.size - 1)
        saveClips()
        if (panel == 4) renderClips()
    }

    private fun pasteClip(t: String) {
        commitAll()
        manualWord.setLength(0)
        currentInputConnection?.commitText(t, 1)
        nextWords = emptyList()
        showPanel(0)
        autoCapSoon()
    }

    private fun doAction(id: Int) {
        commitAll()
        currentInputConnection?.performContextMenuAction(id)
    }

    private fun renderClips() {
        if (!::clipList.isInitialized) return
        clipList.removeAllViews()
        if (clips.isEmpty()) {
            val tv = TextView(this)
            tv.text = "Pano boş. Kopyaladığınız metinler burada görünür."
            tv.setTextColor(Color.GRAY)
            tv.textSize = 14f
            tv.setPadding(dp(12), dp(12), dp(12), dp(12))
            clipList.addView(tv)
            return
        }
        for (c in ArrayList(clips)) {
            val tv = TextView(this)
            tv.text = c
            tv.textSize = 15f
            tv.maxLines = 2
            tv.ellipsize = TextUtils.TruncateAt.END
            tv.setTextColor(txt)
            tv.setPadding(dp(12), dp(8), dp(12), dp(8))
            tv.background = keyBg()
            tv.setOnClickListener { pasteClip(c) }
            tv.setOnLongClickListener {
                clips.remove(c)
                saveClips()
                renderClips()
                true
            }
            clipList.addView(tv, lp(MATCH, WRAP, 0f))
        }
    }

    // ---------- Ayarlar ----------
    private fun loadPrefs() {
        builtVer = prefs.getInt("ver", 0)
        when (prefs.getInt("tema", 0)) {
            1 -> {
                txt = Color.parseColor("#202124")
                rootColor = Color.parseColor("#E3E6E8")
                keyColor = Color.parseColor("#FFFFFF")
                keyPressedColor = Color.parseColor("#C5CACD")
            }
            3 -> {
                txt = prefs.getInt("ozelYazi", Color.WHITE)
                rootColor = prefs.getInt("ozelArka", Color.parseColor("#1F2326"))
                keyColor = prefs.getInt("ozelTus", Color.parseColor("#2D3235"))
                keyPressedColor = prefs.getInt("ozelBasili", Color.parseColor("#4A5256"))
            }
            2 -> {
                txt = Color.WHITE
                rootColor = Color.BLACK
                keyColor = Color.parseColor("#141414")
                keyPressedColor = Color.parseColor("#3A3A3A")
            }
            else -> {
                txt = Color.WHITE
                rootColor = Color.parseColor("#1F2326")
                keyColor = Color.parseColor("#2D3235")
                keyPressedColor = Color.parseColor("#4A5256")
            }
        }
        val scales = floatArrayOf(0.85f, 1f, 1.15f, 1.3f)
        heightScale = scales[prefs.getInt("yukseklik", 1).coerceIn(0, 3)]
        fontScale = scales[prefs.getInt("yazi", 1).coerceIn(0, 3)]
        val lvl = prefs.getInt("sesSeviye", 40).coerceIn(0, 100)
        soundVol = (lvl / 100f) * (lvl / 100f)
        soundOn = prefs.getBoolean("ses", true) && lvl > 0
        vibOn = prefs.getBoolean("titresim", true)
        vibMs = prefs.getInt("titresimMs", 18)
        nextPref = prefs.getBoolean("sonrakiTahmin", true)
        properOn = prefs.getBoolean("ozelIsim", true)
        backReopen = prefs.getBoolean("geriDon", true)
    }

    private fun feedback(v: View) {
        if (soundOn) {
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, soundVol)
            } catch (e: Exception) {
                v.playSoundEffect(SoundEffectConstants.CLICK)
            }
        }
        if (vibOn) {
            try {
                @Suppress("DEPRECATION")
                val vb = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= 26) {
                    vb.vibrate(VibrationEffect.createOneShot(vibMs.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vb.vibrate(vibMs.toLong())
                }
            } catch (e: Exception) {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }
    }

    // ---------- Arayüz yardımcıları ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun lp(w: Int, h: Int, weight: Float) =
        LinearLayout.LayoutParams(w, h, weight).apply {
            setMargins(dp(2), dp(2), dp(2), dp(2))
        }

    private fun keyBg(): StateListDrawable {
        fun shape(c: Int) = GradientDrawable().apply {
            setColor(c)
            cornerRadius = dp(6).toFloat()
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(keyPressedColor))
            addState(intArrayOf(), shape(keyColor))
        }
    }

    private fun label(main: String, sub: String?): SpannableString {
        val text = if (sub != null) main + "\n" + sub else main
        val sp = SpannableString(text)
        if (sub != null) {
            val start = main.length + 1
            sp.setSpan(RelativeSizeSpan(0.55f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(ForegroundColorSpan(Color.GRAY), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sp
    }

    private fun makeKey(
        main: String, sub: String?, size: Float = 20f, action: () -> Unit
    ): TextView {
        val tv = TextView(this)
        tv.text = label(main, sub)
        tv.setTextColor(txt)
        tv.textSize = size * fontScale
        tv.gravity = Gravity.CENTER
        tv.background = keyBg()
        tv.setOnClickListener {
            feedback(tv)
            action()
        }
        return tv
    }

    private fun makeBackspaceKey(): TextView {
        val tv = makeKey("⌫", null, 22f) { }
        tv.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    feedback(v)
                    onBackspace()
                    repeatCount = 0
                    handler.removeCallbacks(repeatRunnable)
                    handler.postDelayed(repeatRunnable, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(repeatRunnable)
                }
            }
            true
        }
        return tv
    }

    private fun hLayout(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.HORIZONTAL
        return l
    }

    private fun vLayout(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        return l
    }

    private fun spacer(weight: Float): View {
        val v = View(this)
        v.layoutParams = lp(0, MATCH, weight)
        return v
    }

    // ---------- Öneri çubuğu ----------
    private fun renderBar() {
        if (!::candidateBar.isInitialized) return
        candidateBar.removeAllViews()
        if (showPunct && digits.isEmpty() && !pendingActive) {
            for (s in punctChips) {
                val chip = TextView(this)
                chip.text = s
                chip.textSize = 22f * fontScale
                chip.setTextColor(txt)
                chip.gravity = Gravity.CENTER
                val cb = GradientDrawable()
                cb.setColor(keyPressedColor)
                cb.cornerRadius = dp(8).toFloat()
                chip.background = cb
                chip.setPadding(dp(14), 0, dp(14), 0)
                chip.setOnClickListener {
                    feedback(chip)
                    showPunct = false
                    punct(s)
                    renderBar()
                }
                val clp = LinearLayout.LayoutParams(WRAP, MATCH)
                clp.setMargins(dp(2), dp(5), dp(2), dp(5))
                candidateBar.addView(chip, clp)
            }
            val psep = View(this)
            psep.setBackgroundColor(keyPressedColor)
            val pslp = LinearLayout.LayoutParams(dp(1), dp(26))
            pslp.gravity = Gravity.CENTER_VERTICAL
            pslp.setMargins(dp(8), 0, dp(4), 0)
            candidateBar.addView(psep, pslp)
        }
        if (t9On && digits.length == 1 && tapMap.containsKey(digits[0]) && digits[0] != '1') {
            val d = digits[0]
            val opts = ArrayList<String>()
            for (c in tapMap[d]!!) opts.add(if (shift) c.toString().uppercase(tr) else c.toString())
            opts.add(d.toString())
            for (o in opts) {
                val chip = TextView(this)
                chip.text = o
                chip.textSize = 19f * fontScale
                chip.setTextColor(txt)
                chip.gravity = Gravity.CENTER
                val cb = GradientDrawable()
                cb.setColor(keyPressedColor)
                cb.cornerRadius = dp(8).toFloat()
                chip.background = cb
                chip.setPadding(dp(11), 0, dp(11), 0)
                chip.setOnClickListener {
                    feedback(chip)
                    pickFirstLetter(o)
                }
                val clp = LinearLayout.LayoutParams(WRAP, MATCH)
                clp.setMargins(dp(2), dp(5), dp(2), dp(5))
                candidateBar.addView(chip, clp)
            }
            val sep = View(this)
            sep.setBackgroundColor(keyPressedColor)
            val slp = LinearLayout.LayoutParams(dp(1), dp(26))
            slp.gravity = Gravity.CENTER_VERTICAL
            slp.setMargins(dp(8), 0, dp(4), 0)
            candidateBar.addView(sep, slp)
        }
        val uw = unknownWord
        if (uw != null && digits.isEmpty() && !pendingActive) {
            val chip = TextView(this)
            chip.text = "+ Ekle: $uw"
            chip.textSize = 16f * fontScale
            chip.setTextColor(teal)
            chip.gravity = Gravity.CENTER
            val cb = GradientDrawable()
            cb.setColor(keyPressedColor)
            cb.cornerRadius = dp(8).toFloat()
            chip.background = cb
            chip.setPadding(dp(12), 0, dp(12), 0)
            chip.setOnClickListener {
                feedback(chip)
                if (addUserWord(uw)) toast("Eklendi: $uw")
                unknownWord = null
                renderBar()
            }
            val clp = LinearLayout.LayoutParams(WRAP, MATCH)
            clp.setMargins(dp(2), dp(5), dp(6), dp(5))
            candidateBar.addView(chip, clp)
        }
        if (candidates.isNotEmpty()) {
            candidates.forEachIndexed { i, w ->
                val tv = TextView(this)
                tv.text = styled(w)
                tv.textSize = 18f * fontScale
                tv.setPadding(dp(14), 0, dp(14), 0)
                tv.gravity = Gravity.CENTER_VERTICAL
                tv.setTextColor(if (i == index) teal else txt)
                tv.setOnClickListener {
                    index = i
                    commitWord(" ")
                    autoCapSoon()
                }
                tv.setOnLongClickListener {
                    onWordLongPress(w)
                    true
                }
                candidateBar.addView(tv, LinearLayout.LayoutParams(WRAP, MATCH))
            }
        } else if (digits.isEmpty() && !pendingActive) {
            for (w in nextWords) {
                val shown = styled(w)
                val tv = TextView(this)
                tv.text = shown
                tv.textSize = 18f * fontScale
                tv.setPadding(dp(14), 0, dp(14), 0)
                tv.gravity = Gravity.CENTER_VERTICAL
                tv.setTextColor(txt)
                tv.setOnClickListener {
                    val b = currentInputConnection?.getTextBeforeCursor(60, 0)?.toString() ?: ""
                    recordUse(w, b, shown)
                    currentInputConnection?.commitText("$shown ", 1)
                    resetShift()
                    nextWords = emptyList()
                    renderBar()
                    autoCapSoon()
                }
                candidateBar.addView(tv, LinearLayout.LayoutParams(WRAP, MATCH))
            }
        }
    }

    // ---------- Panellerin kurulumu ----------
    private fun digitKey(d: Char, main: String): TextView {
        val k = makeKey(main, d.toString(), 22f) { onDigit(d) }
        // Uzun basınca rakamın kendisi yazılır
        k.setOnLongClickListener {
            feedback(k)
            pickChar(d.toString())
            true
        }
        return k
    }

    // ---------- İlk tuşta harf seçenekleri ----------
    private fun pickFirstLetter(o: String) {
        // Bekleyen T9 kelimesini iptal edip seçilen harfi yazar
        digits = ""
        candidates = emptyList()
        index = 0
        currentInputConnection?.commitText(o, 1)
        if (o.length == 1 && o[0].isLetter()) {
            manualWord.append(o.lowercase(tr))
        } else {
            finishManualWord()
        }
        resetShift()
        nextWords = emptyList()
        renderBar()
        autoCapSoon()
    }

    private fun pickChar(o: String) {
        commitAll()
        currentInputConnection?.commitText(o, 1)
        if (o.length == 1 && o[0].isLetter()) {
            manualWord.append(o.lowercase(tr))
        } else {
            finishManualWord()
        }
        resetShift()
        nextWords = emptyList()
        renderBar()
        autoCapSoon()
    }

    private fun buildMain(): LinearLayout {
        val a = hLayout()

        val left = vLayout()
        for (s in listOf("!", "?", "'", ":")) {
            left.addView(makeKey(s, null, 20f) { punct(s) }, lp(MATCH, 0, 1f))
        }
        a.addView(left, lp(0, MATCH, 0.6f))

        val grid = vLayout()

        val r1 = hLayout()
        r1.addView(digitKey('1', "@"), lp(0, MATCH, 1f))
        r1.addView(digitKey('2', "abcç"), lp(0, MATCH, 1f))
        r1.addView(digitKey('3', "def"), lp(0, MATCH, 1f))
        grid.addView(r1, lp(MATCH, 0, 1f))

        val r2 = hLayout()
        r2.addView(digitKey('4', "ghıiğ"), lp(0, MATCH, 1f))
        r2.addView(digitKey('5', "jkl"), lp(0, MATCH, 1f))
        r2.addView(digitKey('6', "mnoö"), lp(0, MATCH, 1f))
        grid.addView(r2, lp(MATCH, 0, 1f))

        val r3 = hLayout()
        r3.addView(digitKey('7', "pqrsş"), lp(0, MATCH, 1f))
        r3.addView(digitKey('8', "tuüv"), lp(0, MATCH, 1f))
        r3.addView(digitKey('9', "wxyz"), lp(0, MATCH, 1f))
        grid.addView(r3, lp(MATCH, 0, 1f))

        a.addView(grid, lp(0, MATCH, 3f))

        // Sağ sütun: soldakiyle aynı genişlikte
        val right = vLayout()
        right.addView(makeBackspaceKey(), lp(MATCH, 0, 1f))
        val nextKey = makeKey("Sonraki", "0", 11f) { onNext() }
        nextKey.setOnLongClickListener {
            feedback(nextKey)
            pickChar("0")
            true
        }
        right.addView(nextKey, lp(MATCH, 0, 1f))
        shiftKey = makeKey("⇧", null, 26f) { toggleShift() }
        shiftKey.setOnLongClickListener {
            autoShift = false
            capsLock = true
            setShift(true)
            if (digits.isNotEmpty()) showComposing()
            renderBar()
            true
        }
        right.addView(shiftKey, lp(MATCH, 0, 1f))
        a.addView(right, lp(0, MATCH, 0.6f))
        return a
    }

    private fun symbolRow(items: String, size: Float, fill: Int): LinearLayout {
        val r = hLayout()
        val list = items.split(" ").filter { it.isNotEmpty() }
        for (s in list) r.addView(makeKey(s, null, size) {
            recordSym(s)
            punct(s)
        }, lp(0, MATCH, 1f))
        for (i in list.size until fill) r.addView(spacer(1f))
        return r
    }

    private fun buildSymbols(): LinearLayout {
        val a = hLayout()
        val grid = vLayout()
        val symRows = listOf(
            "+ - & _ ₺ # @ £ € $",
            "¢ ~ ` | • √ π ÷ × §",
            "∆ \\ } { = ° % © ® ™",
            "✓ [ ] < > ^"
        )
        val symAll = HashSet<String>()
        for (r in symRows) for (s in r.split(" ")) if (s.isNotEmpty()) symAll.add(s)
        symbolSetCache = symAll
        recentS.clear()
        grid.addView(symbolRow(": ; \" ( ) - … / « »", 18f, 10), lp(MATCH, 0, 1f))
        for (r in symRows) grid.addView(symbolRow(r, 18f, 10), lp(MATCH, 0, 1f))
        a.addView(grid, lp(0, MATCH, 8.5f))

        val right = vLayout()
        right.addView(makeBackspaceKey(), lp(MATCH, 0, 1f))
        right.addView(makeKey("😀", null, 22f) { commitAll(); showPanel(3) }, lp(MATCH, 0, 1f))
        right.addView(makeKey("123", null, 20f) { commitAll(); showPanel(2) }, lp(MATCH, 0, 1f))
        a.addView(right, lp(0, MATCH, 1.5f))
        return a
    }

    private fun buildNumbers(): LinearLayout {
        val a = hLayout()
        fun numKey(s: String, size: Float) = makeKey(s, null, size) { punct(s) }

        val left = vLayout()
        for (s in listOf("+", "-", "*", "/")) left.addView(numKey(s, 24f), lp(MATCH, 0, 1f))
        a.addView(left, lp(0, MATCH, 1f))

        val grid = vLayout()
        for (row in listOf("123", "456", "789")) {
            val r = hLayout()
            for (ch in row) r.addView(numKey(ch.toString(), 32f), lp(0, MATCH, 1f))
            grid.addView(r, lp(MATCH, 0, 1f))
        }
        a.addView(grid, lp(0, MATCH, 4.5f))

        val right = vLayout()
        right.addView(numKey("%", 24f), lp(MATCH, 0, 1f))
        right.addView(numKey("=", 24f), lp(MATCH, 0, 1f))
        right.addView(makeBackspaceKey(), lp(MATCH, 0, 1f))
        right.addView(makeKey("!?#", null, 18f) { commitAll(); showPanel(1) }, lp(MATCH, 0, 1f))
        a.addView(right, lp(0, MATCH, 1f))
        return a
    }

    private fun loadUsage() {
        try {
            val f = File(filesDir, "sik_emoji.txt")
            if (f.exists()) {
                f.forEachLine { l ->
                    val p = l.split('\t')
                    if (p.size == 2) {
                        val n = p[1].toIntOrNull()
                        if (n != null) usage[p[0]] = n
                    }
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun recordSym(s: String) {
        val n = (usage[s] ?: 0) + 1
        usage.remove(s)
        usage[s] = n
        if (usage.size > 200) usage.remove(usage.keys.first())
        try {
            val sb = StringBuilder()
            for ((k, v) in usage) sb.append(k).append('\t').append(v).append('\n')
            File(filesDir, "sik_emoji.txt").writeText(sb.toString())
        } catch (e: Exception) {
        }
    }

    private fun topSyms(set: Set<String>, defaults: List<String>): List<String> {
        val res = ArrayList<String>()
        val sorted = usage.entries.toList().reversed()
            .filter { set.contains(it.key) }
            .sortedByDescending { it.value }
        for (e in sorted) {
            if (res.size >= 8) break
            res.add(e.key)
        }
        for (d in defaults) {
            if (res.size >= 8) break
            if (!res.contains(d)) res.add(d)
        }
        return res
    }

    private fun fillRecent(views: List<TextView>, items: List<String>) {
        for (i in views.indices) {
            val e = items.getOrNull(i)
            val tv = views[i]
            if (e == null) {
                tv.text = ""
                tv.setOnClickListener(null)
            } else {
                tv.text = e
                tv.setOnClickListener {
                    feedback(tv)
                    recordSym(e)
                    punct(e)
                }
            }
        }
    }

    private fun refreshRecents() {
        fillRecent(recentE, topSyms(emojiSetCache, DEFAULT_E))
        fillRecent(recentS, topSyms(symbolSetCache, DEFAULT_S))
    }

    private fun buildEmoji(): LinearLayout {
        val a = hLayout()
        val sv = ScrollView(this)
        val col = vLayout()
        val list = EMOJIS.split(" ").filter { it.isNotEmpty() }
        emojiSetCache = list.toHashSet()
        recentE.clear()
        val rr = hLayout()
        for (j in 0 until 8) {
            val tv = TextView(this)
            tv.gravity = Gravity.CENTER
            tv.textSize = 24f
            recentE.add(tv)
            rr.addView(tv, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        col.addView(rr, LinearLayout.LayoutParams(MATCH, WRAP))
        val line = View(this)
        line.setBackgroundColor(keyPressedColor)
        col.addView(line, LinearLayout.LayoutParams(MATCH, dp(1)))
        var i = 0
        while (i < list.size) {
            val row = hLayout()
            for (j in 0 until 8) {
                val e = list.getOrNull(i + j)
                val tv = TextView(this)
                tv.gravity = Gravity.CENTER
                tv.textSize = 24f
                if (e != null) {
                    tv.text = e
                    tv.setOnClickListener {
                        recordSym(e)
                        punct(e)
                    }
                }
                row.addView(tv, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            col.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            i += 8
        }
        sv.addView(col)
        a.addView(sv, lp(0, MATCH, 8f))

        val right = vLayout()
        right.addView(makeBackspaceKey(), lp(MATCH, 0, 1f))
        right.addView(makeKey("!?#", null, 18f) { commitAll(); showPanel(1) }, lp(MATCH, 0, 1f))
        right.addView(makeKey("123", null, 20f) { commitAll(); showPanel(2) }, lp(MATCH, 0, 1f))
        a.addView(right, lp(0, MATCH, 1.5f))
        return a
    }

    private fun buildClip(): LinearLayout {
        val a = vLayout()
        val actions = hLayout()
        actions.addView(makeKey("Tümü", null, 14f) { doAction(android.R.id.selectAll) }, lp(0, MATCH, 1f))
        actions.addView(makeKey("Kes", null, 14f) { doAction(android.R.id.cut) }, lp(0, MATCH, 1f))
        actions.addView(makeKey("Kopyala", null, 14f) { doAction(android.R.id.copy) }, lp(0, MATCH, 1f))
        actions.addView(makeKey("Yapıştır", null, 14f) { doAction(android.R.id.paste) }, lp(0, MATCH, 1f))
        a.addView(actions, lp(MATCH, dp(44), 0f))

        val sv = ScrollView(this)
        clipList = vLayout()
        sv.addView(clipList)
        a.addView(sv, lp(MATCH, 0, 1f))
        return a
    }

    override fun onCreateInputView(): View {
        loadPrefs()
        val root = vLayout()
        root.setBackgroundColor(rootColor)
        root.setPadding(dp(2), dp(2), dp(2), dp(2))

        // Üst satır: pano düğmesi + öneri çubuğu
        val top = hLayout()
        top.addView(makeKey("📋", null, 20f) { togglePano() }, lp(dp(56), MATCH, 0f))
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        candidateBar = hLayout()
        hs.addView(candidateBar)
        top.addView(hs, LinearLayout.LayoutParams(0, MATCH, 1f))
        top.addView(makeKey("⚙", null, 20f) { openSettings() }, lp(dp(56), MATCH, 0f))
        root.addView(top, LinearLayout.LayoutParams(MATCH, dp((48 * heightScale).toInt())))

        // Orta alan: paneller
        mainArea = buildMain()
        symbolArea = buildSymbols()
        numArea = buildNumbers()
        emojiArea = buildEmoji()
        clipArea = buildClip()
        val frame = FrameLayout(this)
        for (area in listOf(mainArea, symbolArea, numArea, emojiArea, clipArea)) {
            frame.addView(area, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        root.addView(frame, LinearLayout.LayoutParams(MATCH, dp((200 * heightScale).toInt())))

        // Alt satır
        val bottom = hLayout()
        modeKey = makeKey("12#", null, 18f) { toggleMode() }
        t9Key = makeKey("T9", "açık", 20f) { toggleT9() }
        t9Key.setTextColor(teal)
        bottom.addView(modeKey, lp(0, MATCH, 1.2f))
        bottom.addView(t9Key, lp(0, MATCH, 1.2f))
        bottom.addView(makeKey(",", null, 22f) { punct(",") }, lp(0, MATCH, 0.8f))
        spaceKey = makeKey("Türkçe", null, 16f) { }
        spaceKey.setOnClickListener {
            feedback(spaceKey)
            if (panel == 2) punct("0") else onSpace()
        }
        spaceKey.setOnLongClickListener {
            if (panel == 2) {
                onSpace()
                true
            } else false
        }
        bottom.addView(spaceKey, lp(0, MATCH, 4f))
        val periodKey = makeKey(".", null, 22f) { punct(".") }
        periodKey.setOnLongClickListener {
            feedback(periodKey)
            commitAll()
            showPunct = true
            renderBar()
            true
        }
        bottom.addView(periodKey, lp(0, MATCH, 0.8f))
        bottom.addView(makeKey("↵", null, 22f) { onEnter() }, lp(0, MATCH, 1.2f))
        root.addView(bottom, LinearLayout.LayoutParams(MATCH, dp((52 * heightScale).toInt())))

        showPanel(0)
        uiReady = true
        renderBar()
        return root
    }
}
