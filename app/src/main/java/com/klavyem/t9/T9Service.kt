package com.klavyem.t9

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
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
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.Locale

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
    private var popup: PopupWindow? = null
    private var digits = ""
    private var candidates: List<String> = emptyList()
    private var index = 0
    private var shift = false
    private var autoShift = false
    private var autoCapAllowed = false
    private var t9On = true
    private var panel = 0
    private var uiReady = false

    private var pendingActive = false
    private var pendingKey = '2'
    private var pendingIdx = 0
    private val manualWord = StringBuilder()
    private val commitRunnable = Runnable { commitPending() }
    private val autoCapRunnable = Runnable {
        autoCap()
        refreshNext()
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
        loadWords()
        loadClips()
        clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard?.addPrimaryClipChangedListener(clipListener)
    }

    override fun onDestroy() {
        clipboard?.removePrimaryClipChangedListener(clipListener)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onFinishInputView(finishingInput: Boolean) {
        dismissPopup()
        super.onFinishInputView(finishingInput)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Ayarlar değiştiyse klavyeyi yeniden kur
        if (uiReady && prefs.getInt("ver", 0) != builtVer) setInputView(onCreateInputView())
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
        if (privateField || w.length < 2 || wordSet.contains(w)) return
        val code = toCode(w) ?: return
        words.add(0, w)
        codes.add(0, code)
        wordSet.add(w)
        try {
            val uf = File(filesDir, "kullanici.txt")
            uf.appendText(w + "\n")
            userStamp = uf.lastModified()
        } catch (e: Exception) {
        }
    }

    // ---------- Sonraki kelime tahmini ----------
    // İmlecin önündeki metne bakar: son karakter boşluksa ve ondan önce bir kelime varsa
    // o kelimenin bigram listesini öneri olarak gösterir.
    private fun refreshNext() {
        nextWords = emptyList()
        if (!nextPref || digits.isNotEmpty() || pendingActive || bigram.isEmpty()) return
        val before = currentInputConnection?.getTextBeforeCursor(60, 0)?.toString() ?: return
        if (before.isEmpty() || before.last() != ' ') return
        val last = before.trimEnd().takeLastWhile { it.isLetter() }
        if (last.isEmpty()) return
        nextWords = bigram[last.lowercase(tr)] ?: emptyList()
    }

    // ---------- Büyük harf ----------
    private fun setShift(on: Boolean) {
        shift = on
        if (::shiftKey.isInitialized) shiftKey.setTextColor(if (on) teal else txt)
    }

    private fun resetShift() {
        autoShift = false
        setShift(false)
    }

    private fun autoCapSoon() {
        handler.removeCallbacks(autoCapRunnable)
        handler.postDelayed(autoCapRunnable, 80)
    }

    private fun autoCap() {
        if (!autoCapAllowed || digits.isNotEmpty() || pendingActive) return
        val mode = currentInputConnection?.getCursorCapsMode(TextUtils.CAP_MODE_SENTENCES) ?: 0
        if (mode != 0) {
            autoShift = true
            setShift(true)
        } else if (autoShift) {
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
        return if (shift) cap(w) else w
    }

    private fun updateCandidates() {
        val exact = ArrayList<String>()
        val prefix = ArrayList<String>()
        for (i in words.indices) {
            val c = codes[i]
            if (c == digits) {
                if (exact.size < 12) exact.add(words[i])
            } else if (prefix.size < 8 && c.length > digits.length && c.startsWith(digits)) {
                prefix.add(words[i])
            }
            if (exact.size >= 12 && prefix.size >= 8) break
        }
        val list = ArrayList<String>(exact)
        list.addAll(prefix)
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
            ic.commitText(current() + trailing, 1)
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

    private fun onBackspace() {
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
        commitAll()
        finishManualWord()
        currentInputConnection?.commitText(s, 1)
        resetShift()
        nextWords = emptyList()
        autoCapSoon()
    }

    private fun toggleShift() {
        autoShift = false
        setShift(!shift)
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
        dismissPopup()
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
        soundOn = prefs.getBoolean("ses", true)
        vibOn = prefs.getBoolean("titresim", true)
        vibMs = prefs.getInt("titresimMs", 18)
        nextPref = prefs.getBoolean("sonrakiTahmin", true)
    }

    private fun feedback(v: View) {
        if (soundOn) v.playSoundEffect(SoundEffectConstants.CLICK)
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
        if (candidates.isNotEmpty()) {
            candidates.forEachIndexed { i, w ->
                val tv = TextView(this)
                tv.text = if (shift) cap(w) else w
                tv.textSize = 18f * fontScale
                tv.setPadding(dp(14), 0, dp(14), 0)
                tv.gravity = Gravity.CENTER_VERTICAL
                tv.setTextColor(if (i == index) teal else txt)
                tv.setOnClickListener {
                    index = i
                    commitWord(" ")
                    autoCapSoon()
                }
                candidateBar.addView(tv, LinearLayout.LayoutParams(WRAP, MATCH))
            }
        } else if (digits.isEmpty() && !pendingActive) {
            for (w in nextWords) {
                val shown = if (shift) cap(w) else w
                val tv = TextView(this)
                tv.text = shown
                tv.textSize = 18f * fontScale
                tv.setPadding(dp(14), 0, dp(14), 0)
                tv.gravity = Gravity.CENTER_VERTICAL
                tv.setTextColor(txt)
                tv.setOnClickListener {
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
        k.setOnLongClickListener {
            showPopup(k, d)
            true
        }
        return k
    }

    // ---------- Uzun basınca açılan harf menüsü ----------
    private fun dismissPopup() {
        try {
            popup?.dismiss()
        } catch (e: Exception) {
        }
        popup = null
    }

    private fun showPopup(anchor: View, d: Char) {
        dismissPopup()
        val s = if (d == '1') ".,@?!'-" else (tapMap[d] ?: "")
        val opts = ArrayList<String>()
        for (c in s) opts.add(if (shift) c.toString().uppercase(tr) else c.toString())
        opts.add(d.toString())

        val row = hLayout()
        val bg = GradientDrawable()
        bg.setColor(keyPressedColor)
        bg.cornerRadius = dp(8).toFloat()
        row.background = bg
        row.setPadding(dp(4), dp(4), dp(4), dp(4))
        for (o in opts) {
            val tv = TextView(this)
            tv.text = o
            tv.textSize = 26f
            tv.setTextColor(txt)
            tv.gravity = Gravity.CENTER
            tv.setPadding(dp(14), dp(10), dp(14), dp(10))
            tv.setOnClickListener {
                pickChar(o)
                dismissPopup()
            }
            row.addView(tv, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        row.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val pw = PopupWindow(row, WRAP, WRAP, false)
        pw.isOutsideTouchable = true
        popup = pw
        try {
            pw.showAsDropDown(anchor, 0, -(anchor.height + row.measuredHeight))
        } catch (e: Exception) {
            popup = null
        }
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
        for (s in listOf("!", "?", "'", "~")) {
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
        right.addView(makeKey("Sonraki", "0", 11f) { onNext() }, lp(MATCH, 0, 1f))
        shiftKey = makeKey("⇧", null, 26f) { toggleShift() }
        right.addView(shiftKey, lp(MATCH, 0, 1f))
        a.addView(right, lp(0, MATCH, 0.6f))
        return a
    }

    private fun symbolRow(items: String, size: Float, fill: Int): LinearLayout {
        val r = hLayout()
        val list = items.split(" ").filter { it.isNotEmpty() }
        for (s in list) r.addView(makeKey(s, null, size) { punct(s) }, lp(0, MATCH, 1f))
        for (i in list.size until fill) r.addView(spacer(1f))
        return r
    }

    private fun buildSymbols(): LinearLayout {
        val a = hLayout()
        val grid = vLayout()
        grid.addView(symbolRow("+ - & _ ₺ # @ £ € $", 18f, 10), lp(MATCH, 0, 1f))
        grid.addView(symbolRow("¢ ~ ` | • √ π ÷ × §", 18f, 10), lp(MATCH, 0, 1f))
        grid.addView(symbolRow("∆ \\ } { = ° % © ® ™", 18f, 10), lp(MATCH, 0, 1f))
        grid.addView(symbolRow("✓ [ ] < > ^", 18f, 10), lp(MATCH, 0, 1f))
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

    private fun buildEmoji(): LinearLayout {
        val a = hLayout()
        val sv = ScrollView(this)
        val col = vLayout()
        val list = EMOJIS.split(" ").filter { it.isNotEmpty() }
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
                    tv.setOnClickListener { punct(e) }
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
        bottom.addView(makeKey(".", null, 22f) { punct(".") }, lp(0, MATCH, 0.8f))
        bottom.addView(makeKey("↵", null, 22f) { onEnter() }, lp(0, MATCH, 1.2f))
        root.addView(bottom, LinearLayout.LayoutParams(MATCH, dp((52 * heightScale).toInt())))

        showPanel(0)
        uiReady = true
        renderBar()
        return root
    }
}
