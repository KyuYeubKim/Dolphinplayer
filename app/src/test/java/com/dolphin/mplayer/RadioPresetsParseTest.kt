package com.dolphin.mplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 미디어센터 재생 정보 글자 → 지금 채널 (주파수 · 이름). 실제 형식은 차 진단 로그로 다시 확인 */
class RadioPresetsParseTest {

    private fun p(vararg t: String) = RadioPresets.parse(t.toList())

    @Test fun bandAndNumber() {
        val s = p("KBS 쿨FM", "FM 89.1")!!
        assertEquals(RadioPresets.FM, s.band)
        assertEquals(89100, s.khz)
        assertEquals("KBS 쿨FM", s.name)
        assertEquals("FM 89.1", s.label)
    }

    @Test fun mhzOnly() {
        val s = p("107.7MHz", "SBS 파워FM")!!
        assertEquals(107700, s.khz)
        assertEquals("SBS 파워FM", s.name)
    }

    @Test fun nameInSameField() {
        val s = p("FM 95.9 MBC 표준FM")!!
        assertEquals(95900, s.khz)
        assertEquals("MBC 표준FM", s.name)
    }

    @Test fun am() {
        val s = p("AM 711")!!
        assertEquals(RadioPresets.AM, s.band)
        assertEquals(711, s.khz)
        assertEquals("AM 711", s.label)
        assertEquals("", s.name)
    }

    @Test fun khzForm() {
        assertEquals(89100, p("FM 89100")!!.khz)
        assertEquals(603, p("603kHz")!!.khz)
    }

    @Test fun bareFm() {
        assertEquals(93100, p("93.1")!!.khz)
    }

    @Test fun noFrequency() {
        assertNull(p("아이유", "Love wins all"))
        assertNull(p("FM 12.3"))
    }
}
