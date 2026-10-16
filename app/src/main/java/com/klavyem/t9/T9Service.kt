package com.klavyem.t9

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.Locale

class T9Service : InputMethodService() {

    private val tr = Locale("tr", "TR")
    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    private val handler = Handler(Looper.getMainLooper())

    // ---------- Ayarlar ----------
    private var bgColor = Color.parseColor("#1E2124")
    private var keyColor = Color.parseColor("#2D3235")
    private var keyPressedColor = Color.parseColor("#4A5256")
    private var textColor = Color.WHITE
    private var subColor = Color.GRAY
    private var accent = Color.parseColor("#26C6B0")
    private var hScale = 1f
    private var tScale = 1f
    private var soundOn = true
    private var vibrateOn = true
    private var vibMs = 18
    private var capOn = true
    private var nextOn = true
    private var prefsSig = ""
    private var clearStamp = 0L
    private var started = false
    private var learnOn = true
    private var vibrator: Vibrator? = null
    private var audio: AudioManager? = null

    // ---------- Tuş haritası ve sözlük ----------
    private val keyMap = linkedMapOf(
        '2' to "abcç", '3' to "def", '4' to "ghıiğ", '5' to "jkl",
        '6' to "mnoö", '7' to "pqrsş", '8' to "tuüv", '9' to "wxyz"
    )
    private val tapMap = HashMap<Char, String>()
    private val letterToDigit = HashMap<Char, Char>()
    private var words = ArrayList<String>()
    private var codes = ArrayList<String>()
    private var wordSet = HashSet<String>()

    // ---------- Yazma durumu ----------
    private var digits = ""
    private var candidates: List<String> = emptyList()
    private var index = 0
    private var shift = false
    private var autoShift = false
    private var autoCapAllowed = false
    private var t9On = true
    private var panel = 0

    private var pendingActive = false
    private var pendingKey = '2'
    private var pendingIdx = 0
    private val manualWord = StringBuilder()
    private val commitRunnable = Runnable { commitPending() }
    private val autoCapRunnable = Runnable { autoCap() }

    private var repeatCount = 0
    private val repeatRunnable = object : Runnable {
        override fun run() {
            repeatCount++
            if (repeatCount > 15) deleteWord() else onBackspace()
            handler.postDelayed(this, if (repeatCount > 15) 160L else 60L)
        }
    }

    // ---------- Sonraki kelime tahmini ----------
    private val bigram = HashMap<String, HashMap<String, Int>>()
    private var lastWord = ""
    private var nextWords: List<String> = emptyList()
    private var bigramDirty = 0

    // ---------- Pano ----------
    private val clips = ArrayList<String>()
    private var lastClip = ""
    private var clipboard: ClipboardManager? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClip() }

    // ---------- Uzun basış kaydırmalı pencere ----------
    private var lpRunnable: Runnable? = null
    private var lpShown = false
    private var lpChars: List<String> = emptyList()
    private var lpSel = -1
    private var lpLeft = 0
    private var lpCell = 0
    private var lpX = 0f
    private val lpViews = ArrayList<TextView>()

    // ---------- Görünümler ----------
    private lateinit var candidateBar: LinearLayout
    private lateinit var shiftKey: TextView
    private lateinit var modeKey: TextView
    private lateinit var t9Key: TextView
    private lateinit var mainArea: LinearLayout
    private lateinit var symbolArea: LinearLayout
    private lateinit var numArea: LinearLayout
    private lateinit var emojiArea: LinearLayout
    private lateinit var clipArea: LinearLayout
    private lateinit var clipList: LinearLayout
    private lateinit var overlay: FrameLayout

    override fun onCreate() {
        super.onCreate()
        for ((d, letters) in keyMap) {
            tapMap[d] = letters
            for (c in letters) letterToDigit[c] = d
        }
        tapMap['1'] = ".,@?!'-"
        loadPrefs()
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        audio = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        loadWords()
        loadClips()
        loadBigrams()
        clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.addPrimaryClipChangedListener(clipListener)
        started = true
    }

    override fun onDestroy() {
        clipboard?.removePrimaryClipChangedListener(clipListener)
        saveBigrams()
        super.onDestroy()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        saveBigrams()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val oldSig = prefsSig
        loadPrefs()
        if (oldSig != prefsSig) setInputView(onCreateInputView())

        handler.removeCallbacks(commitRunnable)
        handler.removeCallbacks(repeatRunnable)
        hideLongPress()
        pendingActive = false
        manualWord.setLength(0)
        digits = ""
        candidates = emptyList()
        index = 0
        lastWord = ""
        nextWords = emptyList()
        resetShift()

        val type = info?.inputType ?: 0
        val cls = type and InputType.TYPE_MASK_CLASS
        val v = type and InputType.TYPE_MASK_VARIATION
        val isPass = (cls == InputType.TYPE_CLASS_TEXT &&
            (v == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (cls == InputType.TYPE_CLASS_NUMBER && v == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        learnOn = !isPass
        autoCapAllowed = cls == InputType.TYPE_CLASS_TEXT && !isPass &&
            v != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS &&
            v != InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS &&
            v != InputType.TYPE_TEXT_VARIATION_URI

        if (!restarting && ::mainArea.isInitialized && ::symbolArea.isInitialized &&
            ::numArea.isInitialized && ::emojiArea.isInitialized &&
            ::clipArea.isInitialized && ::modeKey.isInitialized
        ) {
            showPanel(
                if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) 2 else 0
            )
        }
        captureClip()
        refreshNext()
        autoCapSoon()
    }

    private fun loadPrefs() {
        val p = getSharedPreferences("ayarlar", Context.MODE_PRIVATE)
        val tema = p.getString("tema", "koyu") ?: "koyu"
        hScale = p.getInt("yukseklik", 100) / 100f
        tScale = p.getInt("yazi", 100) / 100f
        soundOn = p.getBoolean("ses", true)
        vibrateOn = p.getBoolean("titresim", true)
        vibMs = p.getInt("titresimMs", 18)
        capOn = p.getBoolean("buyukHarf", true)
        nextOn = p.getBoolean("sonraki", true)
        when (tema) {
            "acik" -> {
                bgColor = Color.parseColor("#E3E5E8")
                keyColor = Color.parseColor("#FFFFFF")
                keyPressedColor = Color.parseColor("#C5C9CE")
                textColor = Color.parseColor("#1F2328")
                subColor = Color.parseColor("#6B7075")
                accent = Color.parseColor("#00897B")
            }
            "siyah" -> {
                bgColor = Color.BLACK
                keyColor = Color.parseColor("#1A1A1A")
                keyPressedColor = Color.parseColor("#3A3A3A")
                textColor = Color.WHITE
                subColor = Color.GRAY
                accent = Color.parseColor("#26C6B0")
            }
            else -> {
                bgColor = Color.parseColor("#1E2124")
                keyColor = Color.parseColor("#2D3235")
                keyPressedColor = Color.parseColor("#4A5256")
                textColor = Color.WHITE
                subColor = Color.GRAY
                accent = Color.parseColor("#26C6B0")
            }
        }
        prefsSig = tema + "|" + hScale + "|" + tScale
        val cs = p.getLong("temizlendi", 0L)
        if (cs != clearStamp) {
            clearStamp = cs
            if (started) reloadAfterClear()
        }
    }

    private fun reloadAfterClear() {
        clips.clear()
        bigram.clear()
        seedBigrams()
        lastClip = currentClipText() ?: ""
        loadWords()
    }

    private fun loadWords() {
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
            handler.post {
                words = w
                codes = c
                wordSet = seen
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
        if (!learnOn || w.length < 2) return
        registerWord(w)
        if (wordSet.contains(w)) return
        val code = toCode(w) ?: return
        words.add(0, w)
        codes.add(0, code)
        wordSet.add(w)
        try {
            File(filesDir, "kullanici.txt").appendText(w + "\n")
        } catch (e: Exception) {
        }
    }

    private fun setShift(on: Boolean) {
        shift = on
        if (::shiftKey.isInitialized) shiftKey.setTextColor(if (on) accent else textColor)
    }

    private fun resetShift() {
        autoShift = false
        setShift(false)
    }

    private fun autoCapSoon() {
        handler.removeCallbacks(autoCapRunnable)
        handler.postDelayed(autoCapRunnable, 60)
    }

    private fun autoCap() {
        if (!capOn || !autoCapAllowed || digits.isNotEmpty() || pendingActive) return
        val mode = currentInputConnection?.getCursorCapsMode(TextUtils.CAP_MODE_SENTENCES) ?: 0
        if (mode != 0) {
            autoShift = true
            setShift(true)
        } else if (autoShift) {
            autoShift = false
            setShift(false)
        }
    }

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
        showComposing()
        renderBar()
    }

    private fun showComposing() {
        currentInputConnection?.setComposingText(current(), 1)
    }

    private fun commitWord(trailing: String) {
        val ic = currentInputConnection ?: return
        if (digits.isNotEmpty()) {
            val w = current()
            ic.commitText(w + trailing, 1)
            manualWord.setLength(0)
            registerWord(w)
            digits = ""
            candidates = emptyList()
            index = 0
            resetShift()
            renderBar()
        } else if (trailing.isNotEmpty()) {
            ic.commitText(trailing, 1)
        }
    }

    private fun commitChip(s: String) {
        val ic = currentInputConnection ?: return
        digits = ""
        candidates = emptyList()
        index = 0
        ic.setComposingText("", 1)
        val isLetter = s.length == 1 && s[0].isLetter()
        if (isLetter) {
            ic.commitText(if (shift) s.uppercase(tr) else s, 1)
            manualWord.append(s.lowercase(tr))
        } else {
            finishManualWord()
            ic.commitText(s, 1)
            lastWord = ""
        }
        resetShift()
        renderBar()
        autoCapSoon()
    }

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
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        lastWord = ""
        nextWords = emptyList()
        renderBar()
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
        lastWord = ""
        nextWords = emptyList()
        renderBar()
        autoCapSoon()
    }

    private fun onEnter() {
        commitAll()
        finishManualWord()
        lastWord = ""
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
        refreshNext()
        autoCapSoon()
    }

    private fun punct(s: String) {
        commitAll()
        finishManualWord()
        currentInputConnection?.commitText(s, 1)
        if (s == "." || s == "!" || s == "?" || s == "…" ||
            (s.length == 1 && s[0].isDigit())
        ) {
            lastWord = ""
        }
        resetShift()
        refreshNext()
        autoCapSoon()
    }

    private fun toggleShift() {
        autoShift = false
        setShift(!shift)
        if (digits.isNotEmpty()) {
            showComposing()
        }
        renderBar()
    }

    private fun toggleT9() {
        commitAll()
        t9On = !t9On
        t9Key.text = label("T9", if (t9On) "açık" else "kapalı")
        t9Key.setTextColor(if (t9On) accent else textColor)
        renderBar()
    }

    private fun commitLiteral(s: String) {
        val isLetter = s.length == 1 && s[0].isLetter()
        if (!isLetter) {
            punct(s)
            return
        }
        commitAll()
        currentInputConnection?.commitText(if (shift) s.uppercase(tr) else s, 1)
        manualWord.append(s.lowercase(tr))
        resetShift()
        renderBar()
        autoCapSoon()
    }

    private fun commitNext(w: String) {
        commitAll()
        finishManualWord()
        val t = if (shift) cap(w) else w
        currentInputConnection?.commitText(t + " ", 1)
        resetShift()
        registerWord(w)
        refreshNext()
        autoCapSoon()
    }

    private fun showPanel(p: Int) {
        panel = p
        hideLongPress()
        modeKey.text = if (p == 0) "12#" else "abc"
        mainArea.visibility = if (p == 0) View.VISIBLE else View.GONE
        symbolArea.visibility = if (p == 1) View.VISIBLE else View.GONE
        numArea.visibility = if (p == 2) View.VISIBLE else View.GONE
        emojiArea.visibility = if (p == 3) View.VISIBLE else View.GONE
        clipArea.visibility = if (p == 4) View.VISIBLE else View.GONE
        if (p == 4) renderClips()
    }

    private fun toggleMode() {
        commitAll()
        showPanel(if (panel == 0) 1 else 0)
    }

    private fun togglePano() {
        commitAll()
        showPanel(if (panel == 4) 0 else 4)
    }

    private fun openSettings() {
        try {
            val i = Intent()
            i.setClassName(this, "com.klavyem.t9.SettingsActivity")
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
        }
    }

    private fun loadClips() {
        try {