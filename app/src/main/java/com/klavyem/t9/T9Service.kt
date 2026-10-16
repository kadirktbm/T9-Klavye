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
            val f = File(filesDir, "pano.txt")
            if (f.exists()) {
                f.readText().split("\u001F").filter { it.isNotBlank() }.forEach { clips.add(it) }
            }
        } catch (e: Exception) {
        }
    }

    private fun saveClips() {
        try {
            File(filesDir, "pano.txt").writeText(clips.joinToString("\u001F"))
        } catch (e: Exception) {
        }
    }

    private fun currentClipText(): String? {
        return try {
            val clip = clipboard?.primaryClip
            if (clip == null || clip.itemCount == 0) null
            else clip.getItemAt(0).coerceToText(this)?.toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun captureClip() {
        val t = currentClipText() ?: return
        if (t == lastClip) return
        lastClip = t
        addClip(t)
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
            tv.setTextColor(subColor)
            tv.textSize = 14f * tScale
            tv.setPadding(dp(12), dp(12), dp(12), dp(12))
            clipList.addView(tv)
            return
        }
        for (c in ArrayList(clips)) {
            val tv = TextView(this)
            tv.text = c
            tv.textSize = 15f * tScale
            tv.maxLines = 2
            tv.ellipsize = TextUtils.TruncateAt.END
            tv.setTextColor(textColor)
            tv.setPadding(dp(12), dp(8), dp(12), dp(8))
            tv.background = keyBg()
            tv.setOnClickListener {
                feedback()
                pasteClip(c)
            }
            tv.setOnLongClickListener {
                clips.remove(c)
                saveClips()
                renderClips()
                true
            }
            clipList.addView(tv, lp(MATCH, WRAP, 0f))
        }
    }

    private fun buzz() {
        if (!vibrateOn) return
        try {
            val vb = vibrator ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                vb.vibrate(VibrationEffect.createOneShot(vibMs.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vb.vibrate(vibMs.toLong())
            }
        } catch (e: Exception) {
        }
    }

    private fun feedback() {
        if (soundOn) {
            try {
                audio?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, -1f)
            } catch (e: Exception) {
            }
        }
        buzz()
    }

    // =====================================================================
    // Uzun basış: Parmağı kaydırarak harf seçme algoritması
    // =====================================================================
    private fun attachLongPress(
        tv: TextView, chars: List<String>, onTap: () -> Unit, onPick: (String) -> Unit
    ) {
        tv.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lpX = ev.rawX
                    v.isPressed = true
                    feedback()
                    lpShown = false
                    val r = Runnable { showLongPress(v, chars) }
                    lpRunnable = r
                    handler.postDelayed(r, 280)
                }
                MotionEvent.ACTION_MOVE -> {
                    lpX = ev.rawX
                    if (lpShown) updateLongPress(ev.rawX)
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    lpRunnable?.let { handler.removeCallbacks(it) }
                    lpRunnable = null
                    if (lpShown) {
                        val pick = if (lpSel >= 0 && lpSel < lpChars.size) lpChars[lpSel] else null
                        hideLongPress()
                        if (pick != null) onPick(pick)
                    } else {
                        onTap()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    lpRunnable?.let { handler.removeCallbacks(it) }
                    lpRunnable = null
                    hideLongPress()
                }
            }
            true
        }
    }

    private fun showLongPress(v: View, chars: List<String>) {
        if (!::overlay.isInitialized || chars.isEmpty()) return
        val shown = ArrayList<String>()
        for (c in chars) {
            shown.add(if (shift && c.length == 1 && c[0].isLetter()) c.uppercase(tr) else c)
        }
        lpShown = true
        lpChars = shown
        lpSel = -1
        overlay.removeAllViews()
        lpViews.clear()
        val cell = dp(44)
        val h = dp(52)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        val bg = GradientDrawable()
        bg.setColor(keyColor)
        bg.cornerRadius = dp(8).toFloat()
        bg.setStroke(dp(1), subColor)
        box.background = bg
        for (c in shown) {
            val t = TextView(this)
            t.text = c
            t.textSize = 22f * tScale
            t.gravity = Gravity.CENTER
            t.setTextColor(textColor)
            box.addView(t, LinearLayout.LayoutParams(cell, h))
            lpViews.add(t)
        }
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val oloc = IntArray(2)
        overlay.getLocationOnScreen(oloc)
        val width = cell * shown.size
        var x = loc[0] - oloc[0]
        val maxX = overlay.width - width
        if (x > maxX) x = maxX
        if (x < 0) x = 0
        var y = loc[1] - oloc[1] - h - dp(6)
        if (y < 0) y = 0
        val params = FrameLayout.LayoutParams(width, h)
        params.leftMargin = x
        params.topMargin = y
        overlay.addView(box, params)
        lpLeft = oloc[0] + x
        lpCell = cell
        updateLongPress(lpX)
    }

    private fun updateLongPress(rawX: Float) {
        if (lpChars.isEmpty() || lpCell <= 0) return
        var i = ((rawX - lpLeft) / lpCell).toInt()
        if (rawX < lpLeft) i = 0
        if (i < 0) i = 0
        if (i >= lpChars.size) i = lpChars.size - 1
        lpSel = i
        for ((k, t) in lpViews.withIndex()) {
            if (k == i) {
                t.setBackgroundColor(accent)
                t.setTextColor(bgColor)
            } else {
                t.setBackgroundColor(Color.TRANSPARENT)
                t.setTextColor(textColor)
            }
        }
    }

    private fun hideLongPress() {
        lpShown = false
        lpSel = -1
        if (::overlay.isInitialized) overlay.removeAllViews()
    }

    private fun registerWord(w: String) {
        if (!learnOn) return
        val lw = w.lowercase(tr)
        if (lw.isEmpty()) return
        learn(lastWord, lw)
        lastWord = lw
    }

    private fun learn(prev: String, next: String) {
        if (next.isEmpty()) return
        val m = bigram.getOrPut(prev) { HashMap() }
        m[next] = (m[next] ?: 0) + 1
        bigramDirty++
        if (bigramDirty >= 15) saveBigrams()
    }

    private fun topNext(prev: String): List<String> {
        val m = bg = bigram[prev] ?: return emptyList()
        return m.entries.sortedByDescending { it.value }.take(6).map { it.key }
    }

    private fun refreshNext() {
        if (!nextOn || !learnOn || digits.isNotEmpty() || pendingActive) {
            nextWords = emptyList()
            renderBar()
            return
        }
        if (lastWord.isEmpty()) {
            val before = currentInputConnection?.getTextBeforeCursor(3, 0)?.toString() ?: ""
            val atStart = before.isBlank() || before.endsWith(". ") ||
                before.endsWith("! ") || before.endsWith("? ") || before.endsWith("\n")
            nextWords = if (atStart) topNext("") else emptyList()
        } else {
            nextWords = topNext(lastWord)
        }
        renderBar()
    }

    private fun seedBigrams() {
        val seeds = listOf(
            ": merhaba selam evet tamam ben bu günaydın hayır",
            "merhaba: nasılsın nasılsınız arkadaşım canım",
            "selam: nasılsın canım",
            "günaydın: nasılsın canım herkese",
            "nasılsın: iyi misin canım",
            "iyi: günler akşamlar geceler misin",
            "teşekkür: ederim ederiz",
            "çok: teşekkürler güzel iyi sevindim",
            "tamam: tamamdır olur canım",
            "evet: tabii tabi doğru",
            "hayır: olmaz değil",
            "bir: şey şekilde dakika süre",
            "ne: zaman kadar oldu yapıyorsun",
            "nasıl: bir yapıyorsun gidiyor",
            "geçmiş: olsun",
            "görüşürüz: canım",
            "hoş: geldin geldiniz",
            "ben: de geliyorum bir",
            "sen: de nerede",
            "bu: gün akşam sabah",
            "şimdi: geliyorum yazıyorum",
            "lütfen: bana",
            "nerede: sin",
            "bugün: çok",
            "yarın: görüşürüz"
        )
        for (line in seeds) {
            val i = line.indexOf(':')
            if (i < 0) continue
            val prev = line.substring(0, i).trim()
            val nexts = line.substring(i + 1).trim().split(" ").filter { it.isNotEmpty() }
            val m = bigram.getOrPut(prev) { HashMap() }
            for ((k, n) in nexts.withIndex()) {
                m[n] = (m[n] ?: 0) + (nexts.size - k) * 2
            }
        }
    }

    private fun loadBigrams() {
        val f = File(filesDir, "bigram.txt")
        var ok = false
        try {
            if (f.exists()) {
                f.forEachLine { line ->
                    val p = line.split("\t")
                    if (p.size == 3) {
                        val c = p[2].toIntOrNull() ?: 0
                        if (c > 0) {
                            bigram.getOrPut(p[0]) { HashMap() }[p[1]] = c
                            ok = true
                        }
                    }
                }
            }
        } catch (e: Exception) {
        }
        if (!ok) seedBigrams()
    }

    private fun saveBigrams() {
        bigramDirty = 0
        try {
            val sb = StringBuilder()
            for ((p, m) in bigram) {
                for ((n, c) in m) {
                    sb.append(p).append('\t').append(n).append('\t').append(c).append('\n')
                }
            }
            File(filesDir, "bigram.txt").writeText(sb.toString())
        } catch (e: Exception) {
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun sc(v: Int) = dp((v * hScale).toInt())

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
            sp.setSpan(ForegroundColorSpan(subColor), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sp
    }

    private fun makeKey(
        main: String, sub: String?, size: Float = 20f, action: () -> Unit
    ): TextView {
        val tv = TextView(this)
        tv.text = label(main, sub)
        tv.setTextColor(textColor)
        tv.textSize = size * tScale
        tv.gravity = Gravity.CENTER
        tv.background = keyBg()
        tv.setOnClickListener {
            feedback()
            action()
        }
        return tv
    }

    private fun makeT9Key(d: Char, lab: String): TextView {
        val tv = makeKey(lab, d.toString(), 20f) { }
        val chars = ArrayList<String>()
        if (d == '1') {
            chars.addAll(listOf("@", ".", ",", "?", "!", "1"))
        } else {
            for (c in (keyMap[d] ?: "")) chars.add(c.toString())
            chars.add(d.toString())
        }
        attachLongPress(tv, chars, { onDigit(d) }, { s -> commitLiteral(s) })
        return tv
    }

    private fun makeBackspaceKey(): TextView {
        val tv = makeKey("⌫", null, 22f) { }
        tv.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    feedback()
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

    private fun chip(text: String, color: Int): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 18f * tScale
        tv.setPadding(dp(14), 0, dp(14), 0)
        tv.gravity = Gravity.CENTER_VERTICAL
        tv.setTextColor(color)
        return tv
    }

    // =====================================================================
    // Öneri Çubuğu: Önce tuşa ait rakam ve harf çipleri, sonra tahminler
    // =====================================================================
    private fun renderBar() {
        if (!::candidateBar.isInitialized) return
        candidateBar.removeAllViews()

        // Her tuş basımında öncelikle tuşun kendi harf/rakam çiplerini çıkarır
        if (t9On && digits.isNotEmpty()) {
            val lastDigit = digits.last()
            val chips = ArrayList<String>()
            chips.add(lastDigit.toString())
            for (ch in (keyMap[lastDigit] ?: "")) {
                chips.add(if (shift) ch.toString().uppercase(tr) else ch.toString())
            }
            for (s in chips) {
                val tv = chip(s, accent)
                tv.background = keyBg()
                tv.setOnClickListener {
                    feedback()
                    commitChip(s)
                }
                val p = LinearLayout.LayoutParams(WRAP, MATCH)
                p.setMargins(dp(3), dp(4), dp(3), dp(4))
                candidateBar.addView(tv, p)
            }
        }

        // Ardından T9 kelime tahminleri gösterilir
        if (digits.isNotEmpty()) {
            candidates.forEachIndexed { i, w ->
                val tv = chip(if (shift) cap(w) else w, if (i == index) accent else textColor)
                tv.setOnClickListener {
                    feedback()
                    index = i
                    commitWord(" ")
                    refreshNext()
                    autoCapSoon()
                }
                candidateBar.addView(tv, LinearLayout.LayoutParams(WRAP, MATCH))
            }
        } else {
            // Yazım yapılmıyorken sonraki kelime tahminlerini çıkarır
            for (w in nextWords) {
                val tv = chip(if (shift) cap(w) else w, textColor)
                tv.setOnClickListener {
                    feedback()
                    commitNext(w)
                }
                candidateBar.addView(tv, LinearLayout.LayoutParams(WRAP, MATCH))
            }
        }
    }

    override fun onCreateInputView(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(bgColor)
        root.setPadding(dp(4), dp(4), dp(4), dp(8))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.addView(makeKey("📋", null, 18f) { togglePano() }, lp(dp(48), MATCH, 0f))
        candidateBar = LinearLayout(this)
        candidateBar.orientation = LinearLayout.HORIZONTAL
        val scroll = HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.addView(candidateBar)
        top.addView(scroll, LinearLayout.LayoutParams(0, MATCH, 1f))
        top.addView(makeKey("⚙", null, 18f) { openSettings() }, lp(dp(40), MATCH, 0f))
        root.addView(top, LinearLayout.LayoutParams(MATCH, sc(44)))

        mainArea = LinearLayout(this)
        mainArea.orientation = LinearLayout.HORIZONTAL

        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        for (p in listOf("!", "?", "'", "~")) {
            left.addView(makeKey(p, null) { punct(p) }, lp(MATCH, 0, 1f))
        }
        mainArea.addView(left, LinearLayout.LayoutParams(0, MATCH, 0.6f))

        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        val rows = listOf(listOf('1', '2', '3'), listOf('4', '5', '6'), listOf('7', '8', '9'))
        for (r in rows) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (d in r) {
                val lab = if (d == '1') "@" else keyMap[d]!!
                row.addView(makeT9Key(d, lab), lp(0, MATCH, 1f))
            }
            grid.addView(row, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        mainArea.addView(grid, LinearLayout.LayoutParams(0, MATCH, 3f))

        val right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL
        right.addView(makeBackspaceKey(), lp(MATCH, 0, 1f))
        right.addView(makeKey("Sonraki", "0", 13f) { onNext() }, lp(MATCH, 0, 1f))
        shiftKey = makeKey("⇧", null, 24f) { toggleShift() }
        right.addView(shiftKey, lp(MATCH, 0, 1f))
        mainArea.addView(right, LinearLayout.LayoutParams(0, MATCH, 1f))
        root.addView(mainArea, LinearLayout.LayoutParams(MATCH, sc(176)))

        symbolArea = LinearLayout(this)
        symbolArea.orientation = LinearLayout.HORIZONTAL
        val sScroll = ScrollView(this)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val simgeler = "1234567890*\"':;!?/)(+-&_₺#@£€\$¢~`|•√π÷×§∆\\}{=°%©®™✓[]<>^"
        var srow = LinearLayout(this)
        for ((i, ch) in simgeler.withIndex()) {
            if (i % 10 == 0) {
                srow = LinearLayout(this)
                srow.orientation = LinearLayout.HORIZONTAL
                srow.weightSum = 10f
                box.addView(srow, LinearLayout.LayoutParams(MATCH, sc(44)))
            }
            val s = ch.toString()
            srow.addView(makeKey(s, null, 18f) { punct(s) }, lp(0, MATCH, 1f))
        }
        sScroll.addView(box)
        symbolArea.addView(sScroll, LinearLayout.LayoutParams(0, MATCH, 6f))
        val sRight = LinearLayout(this)
        sRight.orientation = LinearLayout.VERTICAL
        sRight.addView(makeBackspaceKey(), lp(MATCH, 0, 1f))
        sRight.addView(makeKey("😀", null, 22f) { showPanel(3) }, lp(MATCH, 0, 1f))
        sRight.addView(makeKey("123", null, 18f) { showPanel(2) }, lp(MATCH, 0, 1f))
        symbolArea.addView(sRight, LinearLayout.LayoutParams(0, MATCH, 1f))
        root.addView(symbolArea, LinearLayout.LayoutParams(MATCH, sc(176)))

        numArea = LinearLayout(this)
        numArea.orientation = LinearLayout.HORIZONTAL
        val nLeft = LinearLayout(this)
        nLeft.orientation = LinearLayout.VERTICAL
        for (p in listOf("+", "-", "*", "/")) {
            nLeft.addView(makeKey(p, null, 22f) { punct(p) }, lp(MATCH, 0, 1f))
        }
        numArea.addView(nLeft, LinearLayout.LayoutParams(0, MATCH, 0.7f))
        val nGrid = LinearLayout(this)
        nGrid.orientation = LinearLayout.VERTICAL
        val nrows = listOf(
            listOf("1", "2", "3"), listOf("4", "5", "6"),
            listOf("7", "8", "9"), listOf(",", "0", ".")
        )
        for (r in nrows) {
            val nrow = LinearLayout(this)
            nrow.orientation = LinearLayout.HORIZONTAL
            for (s in r) {
                nrow.addView(makeKey(s, null, 24f) { punct(s) }, lp(0, MATCH, 1f))
            }
            nGrid.addView(nrow, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        numArea.addView(nGrid, LinearLayout.LayoutParams(0, MATCH, 3f))
        val nRight = LinearLayout(this)
        nRight.orientation = LinearLayout.VERTICAL
        nRight.addView(makeKey("%", null, 22f) { punct("%") }, lp(MATCH, 0, 1f))
        nRight.addView(makeKey("=", null, 22f) { punct("=") }, lp(MATCH, 0, 1f))
        nRight.addView(makeBackspaceKey(), lp(MATCH, 0, 1f))
        nRight.addView(makeKey("!?#", null, 16f) { showPanel(1) }, lp(MATCH, 0, 1f))
        numArea.addView(nRight, LinearLayout.LayoutParams(0, MATCH, 1f))
        root.addView(numArea, LinearLayout.LayoutParams(MATCH, sc(176)))

        emojiArea = LinearLayout(this)
        emojiArea.orientation = LinearLayout.HORIZONTAL
        val eScroll = ScrollView(this)
        val eBox = LinearLayout(this)
        eBox.orientation = LinearLayout.VERTICAL
        val emojiler = ("😀 😃 😄 😁 😆 😅 😂 🤣 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😋 😛 😜 🤪 🤗 🤭 🤔 " +
            "😐 😑 😶 😏 😒 🙄 😬 😌 😔 😪 😴 😷 🤒 🥵 🥶 😵 🤯 🤠 😎 🤓 😕 😟 🙁 😮 😲 😳 🥺 " +
            "😨 😰 😥 😢 😭 😱 😖 😞 😓 😩 😫 ```kotlin
 😴 😷 🤒 🥵 🥶 😵 🤯 🤠 😎 🤓 😕 😟 🙁 😮 😲 😳 🥺 " +
            "😨 😰 😥 😢 😭 😱 😖 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 💀 💩 🤡 👻 👽 🤖 " +
            "👍 👎 👌 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ ✋ 👋 👏 🙌 🤝 🙏 💪 " +
            "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 💔 💕 💞 💓 💗 💖 💘 💝 " +
            "🔥 ✨ ⭐ 🌟 💥 💫 💦 🎉 🎊 🎁 🎂 🎈 🌹 🌸 🌞 🌙 ☀️ ❄️ 🌈 ☕ 🍕 🍔 🍟 🍰 🍎 🍌 🍉 🍺 " +
            "⚽ 🏀 🎵 🎶 📱 💻 📷 🎮 🚗 ✈️ 🏠 💰 💯 ✅ ❌ ⚠️ ❓ ❗ 🇹🇷").split(" ")
        var erow = LinearLayout(this)
        for ((i, em) in emojiler.withIndex()) {
            if (i % 8 == 0) {
                erow = LinearLayout(this)
                erow.orientation = LinearLayout.HORIZONTAL
                erow.weightSum = 8f
                eBox.addView(erow, LinearLayout.LayoutParams(MATCH, sc(46)))
            }
            erow.addView(makeKey(em, null, 22f) { punct(em) }, lp(0, MATCH, 1f))
        }
        eScroll.addView(eBox)
        emojiArea.addView(eScroll, LinearLayout.LayoutParams(0, MATCH, 6f))
        val eRight = LinearLayout(this)
        eRight.orientation = LinearLayout.VERTICAL
        eRight.addView(makeBackspaceKey(), lp(MATCH, 0, 1f))
        eRight.addView(makeKey("!?#", null, 16f) { showPanel(1) }, lp(MATCH, 0, 1f))
        eRight.addView(makeKey("123", null, 18f) { showPanel(2) }, lp(MATCH, 0, 1f))
        emojiArea.addView(eRight, LinearLayout.LayoutParams(0, MATCH, 1f))
        root.addView(emojiArea, LinearLayout.LayoutParams(MATCH, sc(176)))

        clipArea = LinearLayout(this)
        clipArea.orientation = LinearLayout.VERTICAL
        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.addView(makeKey("Tümünü seç", null, 12f) { doAction(android.R.id.selectAll) }, lp(0, MATCH, 1.4f))
        actions.addView(makeKey("Kes", null, 13f) { doAction(android.R.id.cut) }, lp(0, MATCH, 1f))
        actions.addView(makeKey("Kopyala", null, 13f) { doAction(android.R.id.copy) }, lp(0, MATCH, 1.2f))
        actions.addView(makeKey("Yapıştır", null, 13f) { doAction(android.R.id.paste) }, lp(0, MATCH, 1.3f))
        actions.addView(makeBackspaceKey(), lp(0, MATCH, 0.9f))
        clipArea.addView(actions, LinearLayout.LayoutParams(MATCH, sc(44)))
        val cScroll = ScrollView(this)
        clipList = LinearLayout(this)
        clipList.orientation = LinearLayout.VERTICAL
        cScroll.addView(clipList)
        clipArea.addView(cScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(clipArea, LinearLayout.LayoutParams(MATCH, sc(176)))

        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        modeKey = makeKey("12#", null, 16f) { toggleMode() }
        bottom.addView(modeKey, lp(0, MATCH, 1f))
        t9Key = makeKey("T9", if (t9On) "açık" else "kapalı", 15f) { toggleT9() }
        t9Key.setTextColor(if (t9On) accent else textColor)
        bottom.addView(t9Key, lp(0, MATCH, 1f))
        bottom.addView(makeKey(",", null) { punct(",") }, lp(0, MATCH, 0.7f))
        bottom.addView(makeKey("Türkçe", null, 14f) { punct(" ") }, lp(0, MATCH, 3f))
        bottom.addView(makeKey(".", null) { punct(".") }, lp(0, MATCH, 0.7f))
        bottom.addView(makeKey("↵", null, 22f) { onEnter() }, lp(0, MATCH, 1.2f))
        root.addView(bottom, LinearLayout.LayoutParams(MATCH, sc(52)))

        val frame = FrameLayout(this)
        frame.addView(root, FrameLayout.LayoutParams(MATCH, WRAP))
        overlay = FrameLayout(this)
        frame.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))

        showPanel(panel)
        return frame
    }
}