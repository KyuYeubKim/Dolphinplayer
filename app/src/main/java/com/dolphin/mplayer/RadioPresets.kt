package com.dolphin.mplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * 라디오 저장 채널 (0.73) — 라디오를 들을 때 앨범 이미지 자리에 채널 버튼. 누르면 그 주파수로 (CarRadio.tune).
 * 차의 미디어센터 즐겨찾기는 BYD 시스템 앱만 읽을 수 있어(READ_PROVIDER = signature|system) D-Player 가 따로 저장한다.
 * 채널 이름은 저장할 때 미디어센터 재생 정보의 방송 이름을 미리 채우고, 고치거나 나중에 바꿀 수 있다.
 */
class RadioPresets(
    private val a: Activity,
    private val images: ImageLoader,
    private val onTune: (Station) -> Unit,
    onSwipe: () -> Unit,
) {
    /** band 1=FM 2=AM (미디어센터 radio_band), khz = 주파수 kHz (FM 89.1 → 89100) */
    data class Station(val band: Int, val khz: Int, val name: String = "") {
        val freqText: String get() = if (band == FM) "%.1f".format(khz / 1000f) else khz.toString()
        val bandText: String get() = if (band == FM) "FM" else "AM"
        val label: String get() = "$bandText $freqText"
        fun same(o: Station?) = o != null && o.band == band && o.khz == khz
    }

    companion object {
        const val FM = 1
        const val AM = 2
        const val MAX = 12
        private const val KEY = "radio_presets"

        fun load(c: Context): List<Station> = runCatching {
            val arr = JSONArray(c.getSharedPreferences("player", Context.MODE_PRIVATE).getString(KEY, "[]"))
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .map { Station(it.getInt("band"), it.getInt("khz"), cleanName(it.optString("name"))) }
        }.getOrDefault(emptyList())

        /** 주파수 · 'MHz' 만 남은 이름은 이름이 아님 (0.73 은 'FM 93.9 MHz' 에서 'MHz' 를 이름으로 저장했음) */
        private fun cleanName(n: String) = n.trim().takeIf { NOT_NAME.replace(it, "").isNotBlank() } ?: ""

        /**
         * 방송 이름 추정 (0.74): 미디어센터 재생 정보에는 'FM 93.9 MHz' 만 있고 방송 이름이 없어서(2026-11-01 차 진단),
         * 수도권 주파수 기준으로 이름을 짐작해 보여 주고 저장 창에 미리 채운다. 지역이 다르면 틀릴 수 있어 고칠 수 있게.
         */
        fun guessName(s: Station): String = RadioInfo.channel(s.band, s.khz)?.label.orEmpty()

        fun save(c: Context, list: List<Station>) {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("band", it.band).put("khz", it.khz).put("name", it.name)) }
            c.getSharedPreferences("player", Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
        }

        private val BAND_NUM = Regex("""(?i)\b(FM|AM)\s*([0-9]{2,6}(?:\.[0-9]{1,2})?)""")
        private val MHZ = Regex("""(?i)([0-9]{2,3}\.[0-9]{1,2})\s*MHz""")
        private val KHZ = Regex("""(?i)([0-9]{3,4})\s*kHz""")
        private val BARE_FM = Regex("""(?<![0-9.])((?:8[7-9]|9[0-9]|10[0-8])\.[0-9])(?![0-9])""")

        /**
         * 미디어센터 재생 정보 글자들에서 지금 채널 (주파수 + 방송 이름). 주파수를 못 찾으면 null.
         * 형식은 차마다 다를 수 있어 넓게 받는다: "FM 89.1" · "89.1MHz" · "AM 711" · "711kHz" · "89.1"
         */
        fun parse(texts: List<String>): Station? {
            for ((i, t) in texts.withIndex()) {
                var range: IntRange? = null
                val s = BAND_NUM.find(t)?.let { m ->
                    range = m.range
                    val v = m.groupValues[2].toFloat()
                    if (m.groupValues[1].equals("FM", true)) fm(v) else am(v)
                } ?: MHZ.find(t)?.let { range = it.range; fm(it.groupValues[1].toFloat()) }
                ?: KHZ.find(t)?.let { range = it.range; am(it.groupValues[1].toFloat()) }
                ?: BARE_FM.find(t)?.let { range = it.range; fm(it.groupValues[1].toFloat()) }
                if (s == null) continue
                // 방송 이름: 주파수 칸이 아닌 첫 글자 (예: 'KBS 쿨FM'), 없으면 주파수 칸에서 주파수를 뺀 나머지
                val name = texts.withIndex().filter { it.index != i }.map { it.value.trim() }
                    .firstOrNull { it.isNotEmpty() && NOT_NAME.replace(it, "").isNotBlank() }
                    ?: cleanName(t.removeRange(range!!).trim().trim('·', '|', ':', '-', ' ')).takeIf { it.length >= 2 }
                    ?: ""
                return s.copy(name = name.take(24))
            }
            return null
        }

        /** 주파수 · 밴드 글자만 있는 칸은 이름이 아님 */
        private val NOT_NAME = Regex("""(?i)\b(FM|AM|MHz|kHz)\b|[0-9]+(\.[0-9]+)?\s*(MHz|kHz)?|[·|:\-\s]""")

        private fun fm(v: Float): Station? {
            val khz = if (v < 200) Math.round(v * 1000) else Math.round(v) // MHz 또는 이미 kHz
            return if (khz in 76000..108000) Station(FM, khz) else null
        }

        private fun am(v: Float): Station? {
            val khz = Math.round(v)
            return if (khz in 500..1800) Station(AM, khz) else null
        }
    }

    private val d = a.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val grid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    val view = SwipeScrollView(a, onSwipe).apply { addView(grid) }

    private var current: Station? = null

    /** 저장 목록 + 지금 채널(강조)로 다시 그림 */
    fun render(now: Station?) {
        current = now
        grid.removeAllViews()
        val list = load(a)
        val cells: List<View> = list.map { tile(it) } + saveTile(now, list)
        cells.chunked(3).forEach { rowCells ->
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until 3) {
                val cell = rowCells.getOrNull(i) ?: View(a)
                row.addView(cell, LinearLayout.LayoutParams(0, dp(72), 1f).apply {
                    marginStart = if (i == 0) 0 else dp(8)
                })
            }
            grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        if (list.isEmpty()) grid.addView(TextView(a).apply {
            text = a.getString(R.string.radio_presets_hint)
            setTextColor(0xFF8E99A8.toInt())
            textSize = 13f
            setPadding(dp(4), dp(4), dp(4), 0)
        })
    }

    private fun box(on: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        if (on) {
            setColor(0x401DB954)
            setStroke(dp(2), a.getColor(R.color.accent))
        } else {
            setColor(0xFF16233A.toInt())
            setStroke(dp(1), 0xFF2C4466.toInt())
        }
    }

    private fun tile(s: Station): View {
        val on = s.same(current)
        val ch = RadioInfo.channel(s.band, s.khz)
        return LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = box(on)
            setPadding(dp(6), dp(4), dp(6), dp(4))
            // 방송사 로고 (radio-browser, 있을 때만)
            if (ch != null) {
                val logo = ImageView(a).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
                addView(logo, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(6) })
                fun show(l: RadioInfo.Logo) {
                    if (l.favicon.isEmpty()) return
                    logo.visibility = View.VISIBLE
                    // http 그림은 앱에서 막혀(평문 통신 금지) https 로
                    images.load(logo, null, android.net.Uri.parse(l.favicon.replaceFirst("http://", "https://")), 0)
                }
                RadioInfo.cachedLogo(a, ch.query)?.let { show(it) }
                    ?: RadioInfo.fetchLogo(a, ch.query) { l -> logo.post { show(l) } }
            }
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(TextView(a).apply {
                    text = s.freqText
                    textSize = 20f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(if (on) a.getColor(R.color.accent) else 0xFFEEF3F8.toInt())
                })
                addView(TextView(a).apply {
                    // 사용자가 붙인 이름 → 없으면 방송사 · 채널명 (수도권 표)
                    text = when {
                        s.name.isNotEmpty() -> s.name
                        ch != null -> ch.label
                        else -> s.bandText
                    }
                    textSize = 11.5f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setTextColor(0xFFDCE4EE.toInt())
                }, LinearLayout.LayoutParams(-2, -2))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            contentDescription = "${s.label} ${s.name}"
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onTune(s)
            }
            setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                editMenu(s)
                true
            }
        }
    }

    private fun saveTile(now: Station?, list: List<Station>): View = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = box(false)
        alpha = if (now == null || list.any { it.same(now) } || list.size >= MAX) 0.5f else 1f
        addView(TextView(a).apply {
            text = "+"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(0xFF6FB2FF.toInt())
        })
        addView(TextView(a).apply {
            text = a.getString(R.string.radio_save)
            textSize = 11.5f
            gravity = Gravity.CENTER
            setTextColor(0xFFDCE4EE.toInt())
        })
        setOnClickListener {
            when {
                now == null -> toast(a.getString(R.string.radio_no_freq))
                list.any { s -> s.same(now) } -> toast(a.getString(R.string.radio_saved_already, now.label))
                list.size >= MAX -> toast(a.getString(R.string.radio_presets_full, MAX))
                else -> askName(now.copy(name = now.name.ifEmpty { guessName(now) }), a.getString(R.string.radio_save_title, now.label)) { name ->
                    save(a, load(a) + now.copy(name = name))
                    render(current)
                }
            }
        }
    }

    /** 채널 이름 입력 (미디어센터의 방송 이름을 미리 채움, 비워 두면 주파수만) */
    private fun askName(s: Station, title: String, done: (String) -> Unit) {
        val input = EditText(a).apply {
            setText(s.name)
            setSelection(text.length)
            hint = a.getString(R.string.radio_name_hint)
            setSingleLine()
            textSize = 18f
        }
        val box = LinearLayout(a).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(a)
            .setTitle(title)
            .setMessage(R.string.radio_name_note)
            .setView(box)
            .setPositiveButton(R.string.radio_save_ok) { _, _ -> done(input.text.toString().trim().take(24)) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun editMenu(s: Station) {
        AlertDialog.Builder(a)
            .setTitle(if (s.name.isEmpty()) s.label else "${s.label} · ${s.name}")
            .setItems(arrayOf(a.getString(R.string.radio_rename), a.getString(R.string.radio_delete))) { _, which ->
                if (which == 0) askName(s.copy(name = s.name.ifEmpty { guessName(s) }), a.getString(R.string.radio_rename_title, s.label)) { name ->
                    save(a, load(a).map { if (it.same(s)) it.copy(name = name) else it })
                    render(current)
                } else {
                    save(a, load(a).filterNot { it.same(s) })
                    render(current)
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(a, msg, android.widget.Toast.LENGTH_SHORT).show()
}
