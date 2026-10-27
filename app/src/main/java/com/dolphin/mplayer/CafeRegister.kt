package com.dolphin.mplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 카페 닉네임 등록 (0.58, DolphinControl CafeRegister 와 같은 방식 · 같은 설문).
 * 이용 안내 동의 창에서 닉네임(지역||닉네임||차량)을 받아 구글 설문지로 보낸다 → 응답은 구글 시트에 쌓임.
 * 보내는 것: 카페 닉네임 · 앱 이름 · 버전 · 보낸 시각 · 설치 때 만든 임의 번호(기기 정보 아님). 앱에 비밀 토큰이 없다.
 * 앱 버전이 바뀌면 한 번 더 보냄(버전별 설치 현황). 인터넷이 안 되면 다음 실행 때 다시.
 * 다른 앱(DolphinControl 등)과 닉네임을 공유하지 않는다 — 이 앱에 저장한 값만 미리 채운다.
 */
object CafeRegister {

    private const val FORM = "https://docs.google.com/forms/d/e/1FAIpQLSfGEHP6uyy9opfqvypbNj50nhSN23BoYriY6lp-eX4csERRSA/formResponse"
    private const val F_NICK = "entry.1662765423"
    private const val F_APP = "entry.1712288803"
    private const val F_VER = "entry.1898808316"
    private const val F_TIME = "entry.505978307"
    private const val F_ID = "entry.1199951775"
    private const val APP_NAME = "D-Player"
    private const val ASK_AGAIN_MS = 24 * 3600_000L

    /** 카페 닉네임 형식: 지역||닉네임||차량 약자 — 차량은 이 약자만 */
    private val CAR_CODES = arrayOf("S", "D", "A3", "S6", "S7")
    private val CAR_NAMES = arrayOf("씰", "돌핀", "아토3", "씨라이언 6", "씨라이언 7")
    private const val SEP = "||"

    /** 무엇을 보내는지 안내 (닉네임 창 위쪽) */
    const val NOTICE = "카페 닉네임을 등록해 주세요. 사용자 확인과 공지에 씁니다.\n" +
        "보내는 것: 카페 닉네임 · 앱 이름 · 버전 · 보낸 시각 · 설치할 때 만든 임의 번호 (차량·기기 정보는 보내지 않음) · 앱 버전이 바뀌면 한 번 더 보냄"

    private fun sp(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences("cafe", Context.MODE_PRIVATE)

    fun nickname(c: Context): String = sp(c).getString("nick", "").orEmpty()

    /** 앱 시작(이용 안내 동의 뒤)마다: 등록했으면 버전이 바뀐 경우 한 번 보냄, 아직이면(하루에 한 번까지) 물어봄 */
    fun onAppStart(a: Activity) {
        val p = sp(a)
        if (nickname(a).isNotEmpty()) {
            if (version(a) != p.getString("sent_ver", "")) send(a.applicationContext)
            return
        }
        if (System.currentTimeMillis() - p.getLong("asked_at", 0) < ASK_AGAIN_MS) return // [나중에] 뒤 하루 동안은 묻지 않음
        ask(a)
    }

    /** 지역 · 닉네임 · 차량 입력 묶음 (동의 창과 닉네임 창이 함께 씀) */
    class Form internal constructor(
        private val act: Activity, private val region: EditText, private val name: EditText, private val cars: RadioGroup,
    ) {
        /** 빈칸이면 표시하고 false, 다 됐으면 저장 · 전송하고 true */
        fun validateAndSave(): Boolean {
            if (clean(region).isEmpty()) {
                region.error = "지역을 적어 주세요"
                region.requestFocus()
                return false
            }
            if (clean(name).isEmpty()) {
                name.error = "닉네임을 적어 주세요"
                name.requestFocus()
                return false
            }
            val nick = join(region, name, cars)
            val changed = nick != nickname(act)
            sp(act).edit().putString("nick", nick).apply { if (changed) remove("sent_ver") }.apply()
            send(act.applicationContext) // 같은 버전에 이미 보냈거나 보내는 중이면 안 보냄
            return true
        }
    }

    /** box 에 지역 · 닉네임 · 차량 · 미리보기를 넣음 (이전에 등록한 값으로 채움) */
    fun buildForm(a: Activity, box: LinearLayout): Form {
        val d = a.resources.displayMetrics.density
        val old = parts(a)
        val region = field(a, box, d, "지역 (거주·활동 지역, 예: 서울)", old[0])
        val name = field(a, box, d, "닉네임 (기존 카페 닉네임 · 공백·특수문자 없이)", old[1])
        val cars = RadioGroup(a).apply { orientation = LinearLayout.HORIZONTAL }
        var checked = 1 // 기본 돌핀(D)
        for (i in CAR_CODES.indices) {
            cars.addView(RadioButton(a).apply {
                id = 1000 + i
                text = "${CAR_NAMES[i]} ${CAR_CODES[i]}"
                textSize = 15f
            }, RadioGroup.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (CAR_CODES[i] == old[2]) checked = i
        }
        cars.check(1000 + checked)
        box.addView(cars, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * d).toInt() })
        val preview = TextView(a).apply {
            textSize = 16f
            paint.isFakeBoldText = true
        }
        box.addView(preview, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (8 * d).toInt() })
        val update = { preview.text = "카페 닉네임: " + join(region, name, cars) }
        update()
        val w = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, i: Int, c: Int, a2: Int) {}
            override fun onTextChanged(s: CharSequence?, i: Int, b2: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) = update()
        }
        region.addTextChangedListener(w)
        name.addTextChangedListener(w)
        cars.setOnCheckedChangeListener { _, _ -> update() }
        return Form(a, region, name, cars)
    }

    /** 닉네임 창 (설정에서, 또는 동의 뒤 닉네임이 비어 있을 때). done = 창이 닫힌 뒤 (설정 화면 다시 그리기 등) */
    fun ask(a: Activity, done: () -> Unit = {}) {
        if (a.isFinishing) return
        val d = a.resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * d).toInt(), pad, 0)
        }
        box.addView(TextView(a).apply { text = NOTICE; textSize = 14f })
        val form = buildForm(a, box)
        val dlg = AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("카페 닉네임 등록")
            .setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton("확인", null)
            .setNegativeButton("나중에") { _, _ -> sp(a).edit().putLong("asked_at", System.currentTimeMillis()).apply() }
            .setCancelable(false)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!form.validateAndSave()) return@setOnClickListener
                Toast.makeText(a, "등록했습니다 · " + nickname(a), Toast.LENGTH_SHORT).show()
                dlg.dismiss()
            }
        }
        dlg.setOnDismissListener { done() }
        runCatching { dlg.show() }
    }

    private fun field(a: Activity, box: LinearLayout, d: Float, hint: String, value: String): EditText {
        val e = EditText(a).apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            this.hint = hint
            setText(value)
        }
        box.addView(e, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * d).toInt() })
        return e
    }

    /** 공백 · 구분자(|) 를 뺀 입력 */
    private fun clean(e: EditText): String = e.text.toString().replace("|", "").replace(Regex("\\s+"), "")

    private fun join(region: EditText, name: EditText, cars: RadioGroup): String {
        val i = cars.checkedRadioButtonId - 1000
        val car = if (i in CAR_CODES.indices) CAR_CODES[i] else "D"
        return clean(region) + SEP + clean(name) + SEP + car
    }

    /** 저장된 닉네임을 지역 · 닉네임 · 차량으로 나눔 (형식이 아니면 닉네임 칸에) */
    private fun parts(c: Context): Array<String> {
        val n = nickname(c)
        val p = n.split(SEP)
        return if (p.size == 3) p.toTypedArray() else arrayOf("", n, "D")
    }

    /** 보내는 중인 버전 — 동의 창 저장과 앱 시작이 거의 동시에 보내 시트에 두 줄씩 들어가지 않게 */
    private var sendingVer: String? = null

    /** 백그라운드에서 설문지로 보냄. HTTP 200 이면 이 버전은 보낸 것으로 기록 (commit) */
    private fun send(app: Context) {
        val nick = nickname(app)
        if (nick.isEmpty()) return
        val ver = version(app)
        synchronized(this) {
            if (ver == sp(app).getString("sent_ver", "") || ver == sendingVer) return // 이미 보냈거나 보내는 중
            sendingVer = ver
        }
        Thread({
            try {
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date())
                val body = "$F_NICK=${enc(nick)}&$F_APP=${enc(APP_NAME)}&$F_VER=${enc(ver)}" +
                    "&$F_TIME=${enc(time)}&$F_ID=${enc(installId(app))}"
                val h = URL(FORM).openConnection() as HttpURLConnection
                h.connectTimeout = 10_000
                h.readTimeout = 15_000
                h.doOutput = true
                h.requestMethod = "POST"
                h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                h.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = h.responseCode
                h.disconnect()
                if (code == 200) sp(app).edit().putString("sent_ver", ver).putString("last", "보냄 $time").commit()
                else sp(app).edit().putString("last", "보내기 실패 HTTP $code $time").apply()
            } catch (t: Throwable) {
                sp(app).edit().putString("last", "보내기 실패 (${t.javaClass.simpleName}) · 다음 실행 때 다시").apply()
            } finally {
                synchronized(this) { sendingVer = null }
            }
        }, "cafe-register").start()
    }

    /** 설치 때 한 번 만드는 임의 번호 (같은 사람이 여러 번 보냈는지 구분용 · 기기 식별 정보 아님) */
    @Synchronized
    private fun installId(c: Context): String {
        var id = sp(c).getString("install_id", "").orEmpty()
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString().substring(0, 8)
            sp(c).edit().putString("install_id", id).apply()
        }
        return id
    }

    private fun version(c: Context): String =
        runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "?"

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** 설정 화면 설명 줄 */
    fun summary(c: Context): String {
        val n = nickname(c)
        return if (n.isEmpty()) "등록 안 함 · 눌러서 카페 닉네임을 적어 주세요"
        else n + " · " + sp(c).getString("last", "아직 안 보냄")
    }
}
