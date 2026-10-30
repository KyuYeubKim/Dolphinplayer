package com.dolphin.mplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.dolphin.mplayer.update.AppUpdater
import com.dolphin.mplayer.youtube.YtEngine

/**
 * 설정 화면 (전체 화면). 항목을 묶음(화면 분할 · 재생 · 계정 · 권한 · 업데이트 · 정보)으로 나눠 카드로 보여준다.
 * 로그인·권한 같은 실제 동작은 MainActivity 가 [Actions] 로 처리한다.
 */
class SettingsPanel(private val a: Activity, private val actions: Actions) {

    interface Actions {
        fun checkUpdate()
        fun regrant()
        fun openListenerSettings()
        fun saveDiagnostics()
        fun cleanBeforeUninstall()
        fun ytLogin()
        fun ytLogout()
        /** 차에 Spotify 앱이 있는지 */
        fun spotifyInstalled(): Boolean
        /** Spotify 앱 라이브러리 연동: "ok" / "refused" / null(확인 전) */
        fun spotifyBrowse(): String?
        /** 지금 연동되는지 잠깐 연결해 확인 (결과가 바뀌면 refresh) */
        fun probeSpotify()
        fun showTerms()
        /** 위쪽 버튼 줄의 앱 바로가기 고르기 */
        fun editShortcuts()
        /** 위쪽 음악 소스 버튼 고르기 (0.68) · 지금 보이는 것 */
        fun editSources()
        fun sourcesSummary(): String
        fun autoCloseSec(): Int
        fun setAutoCloseSec(sec: Int)
        /** 재생 중 차량 제어 패널을 켜고 끔 (0.61) */
        fun carPanelChanged()
        /** 즐겨찾기를 지웠을 때 재생 화면 버튼 다시 그리기 (0.64) */
        fun favoritesChanged()
    }

    private val overlay: View = a.findViewById(R.id.settingsOverlay)
    private val scroll: ScrollView = a.findViewById(R.id.settingsScroll)
    private val list: LinearLayout = a.findViewById(R.id.settingsList)
    private val version: TextView = a.findViewById(R.id.settingsVersion)

    val isShowing: Boolean get() = overlay.visibility == View.VISIBLE

    /** 분할 화면처럼 좁은 창: 왼쪽 아이콘을 빼고 선택 버튼을 폭에 맞춤 */
    private var narrow = false

    init {
        a.findViewById<View>(R.id.btnSettingsClose).setOnClickListener { hide() }
    }

    fun show() {
        overlay.visibility = View.VISIBLE
        actions.probeSpotify()
        build()
        scroll.scrollTo(0, 0)
    }

    fun hide() {
        overlay.visibility = View.GONE
    }

    /** 시험판 받기 스위치를 바꿨을 때 (블루투스 버튼 보이기 등) */
    var onBetaChanged: (() -> Unit)? = null

    /** 로그인 상태 등이 바뀌었을 때 (열려 있을 때만 다시 그림) */
    fun refresh() {
        if (isShowing) build()
    }

    // ---------- 화면 구성 ----------

    private fun build() {
        val y = scroll.scrollY
        list.removeAllViews()
        version.text = "D-Player " + AppUpdater.currentVersion(a)
        val widthDp = a.resources.configuration.screenWidthDp
        narrow = widthDp < 520
        list.layoutParams = (list.layoutParams as FrameLayout.LayoutParams).apply {
            width = if (widthDp > 800) dp(760) else ViewGroup.LayoutParams.MATCH_PARENT
        }

        playbackSection()
        accountSection()
        permissionSection()
        updateSection()
        aboutSection()
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun playbackSection() {
        val choices = intArrayOf(5, 10, 15, 20, 30)
        val tickerSwitch = Switch(a).apply {
            isChecked = StatusTicker.mode(a) != StatusTicker.Mode.OFF
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(a.getColor(R.color.accent), 0xFFBDBDBD.toInt()))
            setOnCheckedChangeListener { _, on ->
                StatusTicker.setMode(a, if (on) StatusTicker.Mode.BOTTOM else StatusTicker.Mode.OFF)
                if (on) ensureOverlay()
                build()
            }
        }
        val resumeSwitch = Switch(a).apply {
            isChecked = YtEngine.isResumeOnStart(a)
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(a.getColor(R.color.accent), 0xFFBDBDBD.toInt()))
            setOnCheckedChangeListener { _, on -> YtEngine.setResumeOnStart(a, on) }
        }
        val volSwitch = Switch(a).apply {
            isChecked = VolumeMemory.isEnabled(a)
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(a.getColor(R.color.accent), 0xFFBDBDBD.toInt()))
            setOnCheckedChangeListener { _, on -> VolumeMemory.setEnabled(a, on) }
        }
        val favs = Favorites.list(a)
        val carSwitch = Switch(a).apply {
            isChecked = CarPanel.isEnabled(a)
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(a.getColor(R.color.accent), 0xFFBDBDBD.toInt()))
            setOnCheckedChangeListener { _, on ->
                CarPanel.setEnabled(a, on)
                actions.carPanelChanged()
            }
        }
        section(R.string.set_sec_playback,
            row(icon(R.drawable.ic_music), s(R.string.set_ticker),
                s(R.string.set_ticker_desc) + if (StatusTicker.mode(a) != StatusTicker.Mode.OFF &&
                    !android.provider.Settings.canDrawOverlays(a)) "\n" + s(R.string.set_ticker_no_perm) else "",
                trailing = tickerSwitch) { tickerSwitch.toggle() },
            row(spacer(), s(R.string.set_ticker_pos), s(R.string.set_ticker_pos_desc),
                trailing = action(s(R.string.set_ticker_reset)) {
                    StatusTicker.resetPosition(a)
                    a.toastShort(s(R.string.set_ticker_reset_done))
                }),
            row(icon(R.drawable.ic_library_music), s(R.string.set_favorites),
                (favs.joinToString(" · ") { it.title }.ifEmpty { s(R.string.set_favorites_none) }) + "\n" +
                    a.getString(R.string.set_favorites_desc, Favorites.MAX),
                trailing = if (favs.isEmpty()) null else action(s(R.string.set_favorites_clear)) {
                    Favorites.clear(a)
                    actions.favoritesChanged()
                    build()
                }),
            row(icon(R.drawable.ic_music), s(R.string.set_vol_memory), s(R.string.set_vol_memory_desc),
                trailing = volSwitch) { volSwitch.toggle() },
            row(icon(R.drawable.ic_car), s(R.string.set_car_panel),
                s(R.string.set_car_panel_desc) + if (!CarPanel.supported(a)) "\n" + s(R.string.set_car_panel_need) else "",
                trailing = carSwitch) { carSwitch.toggle() },
            row(icon(R.drawable.ic_timer), s(R.string.set_auto_close), s(R.string.set_auto_close_desc),
                below = segment(choices.map { s(R.string.seconds, it) }, choices.indexOf(actions.autoCloseSec())) {
                    actions.setAutoCloseSec(choices[it])
                    build()
                }),
            row(logo(R.drawable.ic_logo_ytmusic), s(R.string.set_yt_resume), s(R.string.set_yt_resume_desc),
                trailing = resumeSwitch) { resumeSwitch.toggle() },
            row(icon(R.drawable.ic_library_music), s(R.string.set_sources),
                actions.sourcesSummary() + "\n" + s(R.string.set_sources_desc),
                trailing = chevron()) { actions.editSources() },
            row(icon(R.drawable.ic_split), s(R.string.set_shortcuts),
                AppShortcuts.list(a).joinToString(" · ") { AppShortcuts.appLabel(a, it) }.ifEmpty { s(R.string.set_shortcuts_none) } +
                    "\n" + s(R.string.set_shortcuts_desc),
                trailing = chevron()) { actions.editShortcuts() },
        )
    }

    private fun accountSection() {
        val yt = YtEngine.loggedIn
        val spBrowse = actions.spotifyBrowse()
        val spRow: View? = when {
            // 차의 Spotify 앱이 라이브러리를 열어 줌 → D-Player 에서 로그인할 필요 없음
            spBrowse == "ok" -> row(logo(R.drawable.ic_logo_spotify), "Spotify", s(R.string.set_sp_linked_desc),
                trailing = TextView(a).apply {
                    text = s(R.string.set_sp_linked)
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(a.getColor(R.color.accent))
                })
            // 차의 Spotify 앱이 목록을 열어 주지 않음 (0.66 부터 Spotify 웹 예비 목록 없음 — 재생 화면 · 버튼은 그대로)
            spBrowse == "refused" -> row(logo(R.drawable.ic_logo_spotify), "Spotify", s(R.string.set_sp_refused_desc))
            actions.spotifyInstalled() -> row(logo(R.drawable.ic_logo_spotify), "Spotify", s(R.string.set_sp_checking))
            else -> null
        }
        section(R.string.set_sec_account,
            row(logo(R.drawable.ic_logo_youtube), s(R.string.set_yt_title),
                (if (yt == true) a.getString(R.string.set_yt_premium, YtEngine.accountName)
                else if (YtEngine.notPremium) s(R.string.yt_not_premium) else status(yt)) + "\n" + s(R.string.set_yt_login_desc),
                trailing = if (yt == true) action(s(R.string.set_logout)) {
                    confirm(s(R.string.set_yt_title), s(R.string.set_logout_confirm)) { actions.ytLogout(); build() }
                } else action(s(R.string.set_login), primary = true) { actions.ytLogin() }),
            *listOfNotNull(spRow).toTypedArray(),
        )
    }

    private fun permissionSection() {
        section(R.string.set_sec_permission,
            row(icon(R.drawable.ic_shield), s(R.string.settings_regrant), s(R.string.set_regrant_desc),
                trailing = chevron()) { hide(); actions.regrant() },
            row(icon(R.drawable.ic_bell), s(R.string.listener_settings), s(R.string.set_listener_desc),
                trailing = chevron()) { actions.openListenerSettings() },
            row(icon(R.drawable.ic_doc), s(R.string.set_diag), s(R.string.set_diag_desc),
                trailing = action(s(R.string.set_check)) { actions.saveDiagnostics() }),
        )
    }

    private fun updateSection() {
        val sw = Switch(a).apply {
            isChecked = AppUpdater.isAutoCheck(a)
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(a.getColor(R.color.accent), 0xFFBDBDBD.toInt()))
            setOnCheckedChangeListener { _, on -> AppUpdater.setAutoCheck(a, on) }
        }
        val beta = Switch(a).apply {
            isChecked = AppUpdater.isBetaChannel(a)
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(a.getColor(R.color.accent), 0xFFBDBDBD.toInt()))
            setOnCheckedChangeListener { _, on -> AppUpdater.setBetaChannel(a, on); onBetaChanged?.invoke() }
        }
        section(R.string.set_sec_update,
            row(icon(R.drawable.ic_refresh), s(R.string.set_update_check),
                s(R.string.set_update_current, AppUpdater.currentVersion(a)),
                trailing = action(s(R.string.set_check)) { actions.checkUpdate() }),
            row(spacer(), s(R.string.set_auto_update), s(R.string.set_auto_update_desc), trailing = sw) { sw.toggle() },
            row(spacer(), s(R.string.set_beta), s(R.string.set_beta_desc), trailing = beta) { beta.toggle() },
        )
    }

    private fun aboutSection() {
        section(R.string.set_sec_about,
            row(icon(R.drawable.ic_doc), s(R.string.set_terms_again), null, trailing = chevron()) { actions.showTerms() },
            row(icon(R.drawable.ic_info), s(R.string.set_cafe), CafeRegister.summary(a), trailing = chevron()) {
                CafeRegister.ask(a) { refresh() }
            },
            row(icon(R.drawable.ic_info), "D-Player " + AppUpdater.currentVersion(a), s(R.string.set_about_desc)),
            row(icon(R.drawable.ic_doc), s(R.string.set_license), s(R.string.set_license_desc)),
            row(spacer(), s(R.string.set_clean), s(R.string.set_clean_desc),
                trailing = action(s(R.string.set_clean_btn)) { actions.cleanBeforeUninstall() }),
        )
    }

    /** 다른 앱 위에 표시 권한: 없으면 차량 ADB('항상 허용'된 키)로 받고, 안 되면 시스템 설정 화면을 연다 */
    private fun ensureOverlay() {
        if (android.provider.Settings.canDrawOverlays(a)) return
        Thread {
            val ok = com.dolphin.mplayer.adb.AdbProvisioner.grantOverlay(a)
            a.runOnUiThread {
                if (ok) return@runOnUiThread
                val opened = runCatching {
                    a.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + a.packageName)))
                }.isSuccess
                if (!opened) a.toastShort(s(R.string.set_ticker_need_perm))
            }
        }.start()
    }

    // ---------- 부품 ----------

    private fun section(titleRes: Int, vararg rows: View) {
        list.addView(TextView(a).apply {
            text = s(titleRes)
            setTextColor(a.getColor(R.color.accent))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.04f
            setPadding(dp(8), dp(if (list.childCount == 0) 4 else 22), dp(8), dp(8))
        })
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            clipToOutline = true
        }
        rows.forEachIndexed { i, r ->
            if (i > 0) card.addView(View(a).apply { setBackgroundColor(a.getColor(R.color.divider)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { marginStart = dp(20) })
            card.addView(r)
        }
        list.addView(card)
    }

    /** 한 줄: [아이콘] 제목/설명(+아래 부품) [오른쪽 부품] */
    private fun row(
        lead: View?, title: String, subtitle: String?,
        trailing: View? = null, below: View? = null, onClick: (() -> Unit)? = null,
    ): View {
        val r = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(76)
            setPadding(dp(if (narrow) 16 else 20), dp(14), dp(if (narrow) 12 else 16), dp(14))
            if (onClick != null) {
                setBackgroundResource(R.drawable.bg_row)
                setOnClickListener { onClick() }
            }
        }
        if (!narrow) lead?.let { r.addView(it, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(16) }) }
        val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(a).apply {
            text = title
            setTextColor(a.getColor(R.color.text))
            textSize = 18f
        })
        subtitle?.let {
            texts.addView(TextView(a).apply {
                text = it
                setTextColor(a.getColor(R.color.text_dim))
                textSize = 14f
                setPadding(0, dp(2), 0, 0)
            })
        }
        below?.let {
            val w = if (narrow) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            texts.addView(it, LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12) })
        }
        r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        trailing?.let { r.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) }) }
        return r
    }

    /** 둥근 사각형 안의 단색 아이콘 */
    private fun icon(res: Int): View = ImageView(a).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(a.getColor(R.color.text))
        setBackgroundResource(R.drawable.bg_segment)
        setPadding(dp(10), dp(10), dp(10), dp(10))
    }

    /** 아이콘 없는 줄도 글자 위치를 맞추는 빈 칸 */
    private fun spacer(): View = View(a)

    /** 원래 색 그대로의 로고 */
    private fun logo(res: Int): View = ImageView(a).apply {
        setImageResource(res)
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }

    private fun chevron(): View = ImageView(a).apply {
        setImageResource(R.drawable.ic_chevron)
        imageTintList = ColorStateList.valueOf(a.getColor(R.color.text_dim))
        layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
        minimumWidth = dp(28)
        minimumHeight = dp(28)
    }

    /** 알약 모양 버튼. primary = 초록으로 강조 */
    private fun action(label: String, primary: Boolean = false, onClick: () -> Unit): View = TextView(a).apply {
        text = label
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minWidth = dp(96)
        setPadding(dp(20), dp(10), dp(20), dp(10))
        setBackgroundResource(R.drawable.bg_action)
        isActivated = primary
        setTextColor(if (primary) 0xFF000000.toInt() else a.getColor(R.color.text))
        setOnClickListener { onClick() }
    }

    /** 여러 개 중 하나 고르기 (분할 방식 · 자동 닫기 시간) */
    private fun segment(labels: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_segment)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        labels.forEachIndexed { i, label ->
            val item = TextView(a).apply {
                text = label
                textSize = if (narrow) 15f else 16f
                gravity = Gravity.CENTER
                maxLines = 1
                minWidth = if (narrow) 0 else dp(64)
                setPadding(dp(if (narrow) 4 else 16), dp(10), dp(if (narrow) 4 else 16), dp(10))
                setBackgroundResource(R.drawable.bg_segment_item)
                isActivated = i == selected
                typeface = if (i == selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (i == selected) 0xFF000000.toInt() else a.getColor(R.color.text))
                setOnClickListener { if (i != selected) onPick(i) }
            }
            if (narrow) box.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            else box.addView(item)
        }
        return box
    }

    private fun confirm(title: String, msg: String, ok: () -> Unit) {
        AlertDialog.Builder(a).setTitle(title).setMessage(msg)
            .setPositiveButton(R.string.set_logout) { _, _ -> ok() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun status(loggedIn: Boolean?) = s(
        when (loggedIn) {
            true -> R.string.set_logged_in
            false -> R.string.set_logged_out
            null -> R.string.set_login_unknown
        }
    )

    private fun s(res: Int, vararg args: Any) = a.getString(res, *args)
    private fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()
}

private fun Activity.toastShort(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
