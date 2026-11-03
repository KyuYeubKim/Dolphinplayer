package com.dolphin.mplayer.iradio

import com.dolphin.mplayer.R
import com.dolphin.mplayer.iradio.IRadioApi.Source
import com.dolphin.mplayer.iradio.IRadioApi.Station

/**
 * 지역 방송 (0.80) — 채널 편집의 '채널 추가'에서 지역별로 골라 넣는다.
 * 주소는 각 방송사 웹 플레이어가 쓰는 공개 스트림 (radio.bsod.kr 정리를 참고, 2026-11-03 PC 에서 모두 열리는 것만).
 * KBS 지역국은 콩 채널 API(지역코드_채널코드) + 지역 편성표, 부산MBC 는 날짜로 바뀌는 주소, 나머지는 고정 주소.
 * http 로만 열리는 곳은 res/xml/network_security_config.xml 에 그 주소만 따로 허용.
 */
object IRadioRegions {

    data class Region(val id: String, val name: String, val stations: List<Station>)

    private val KBS_CH = listOf("21" to "1라디오", "22" to "해피FM", "24" to "클래식FM")
    private val KBS_ABOUT = mapOf("21" to "지역 뉴스 · 시사 · 교양", "22" to "가요 · 생활 정보", "24" to "클래식 · 국악")

    /** KBS 지역국: 지역코드, 이름(부산), 홈페이지 앞부분(busan), 있는 채널 */
    private fun kbs(local: String, city: String, home: String, vararg ch: String) = KBS_CH.filter { it.first in ch }.map { (code, name) ->
        Station("kbs_${local}_$code", "KBS$city", name, "", Source.Kbs(code, local),
            "KBS$city $name · ${KBS_ABOUT[code]}", "https://$home.kbs.co.kr/", region = city)
    }

    private fun fixed(
        id: String, broadcaster: String, channel: String, region: String, url: String, about: String, home: String,
        logoRes: Int = 0, logoQuery: String = "",
    ) = Station(id, broadcaster, channel, "", Source.Fixed(url), "$about (편성 정보 없음)", home, logoQuery, logoRes, region)

    private fun mbc(id: String, name: String, region: String, std: String?, fm4u: String?, home: String) = listOfNotNull(
        std?.let { fixed("mbc_${id}_sfm", name, "표준FM", region, it, "$name 표준FM · 지역 뉴스 · 시사 · 음악", home, R.drawable.iradio_mbc) },
        fm4u?.let { fixed("mbc_${id}_fm4u", name, "FM4U", region, it, "$name FM4U · 음악", home, R.drawable.iradio_mbc) },
    )

    private fun cbs(id: String, city: String, path: String, music: String? = null) = listOfNotNull(
        fixed("cbs_${id}_sfm", "${city}CBS", "표준FM", city, "https://m-aac.cbs.co.kr/$path/_definst_/$path.stream/playlist.m3u8",
            "${city}CBS 표준FM · 시사 · 교양", "https://www.cbs.co.kr/radio/", R.drawable.iradio_cbs),
        music?.let {
            fixed("cbs_${id}_mfm", "${city}CBS", "음악FM", city, "https://m-aac.cbs.co.kr/$it/_definst_/$it.stream/playlist.m3u8",
                "${city}CBS 음악FM · 음악 전문", "https://www.cbs.co.kr/radio/", R.drawable.iradio_cbs)
        },
    )

    private fun febc(id: String, city: String, url: String) =
        fixed("febc_$id", "${city}극동방송", "", city, url, "${city}극동방송 · 기독교 · 음악", "https://www.febc.net/", R.drawable.iradio_febc)

    private fun tbn(id: String, city: String, path: String) =
        fixed("tbn_$id", "TBN", "${city}교통방송", city, "https://www.tbn.it.kr/$path/myStream/playlist.m3u8",
            "TBN ${city}교통방송 · 교통 · 생활 · 음악", "https://www.tbn.or.kr/")

    private fun bbs(id: String, city: String, url: String) =
        fixed("bbs_$id", "BBS", "${city}불교방송", city, url, "BBS ${city}불교방송 · 불교 · 음악", "https://www.bbsi.co.kr/")

    private fun cpbc(id: String, city: String, url: String) =
        fixed("cpbc_$id", "CPBC", "${city}가톨릭평화방송", city.ifEmpty { "수도권" }, url, "CPBC ${city}가톨릭평화방송 · 가톨릭 · 음악", "https://www.cpbc.co.kr/")

    private fun sbs(id: String, broadcaster: String, channel: String, region: String, url: String, home: String) =
        fixed(id, broadcaster, channel, region, url, "$broadcaster $channel · SBS 계열 지역 민영 방송", home)

    val REGIONS: List<Region> = listOf(
        Region("capital", "수도권 · 전국 더 보기", listOf(
            tbn("gyeongin", "경인", "gyeongin"),
            fixed("obs", "OBS", "라디오", "수도권", "https://vod3.obs.co.kr:444/live/obsstream1/radio.stream/playlist.m3u8",
                "OBS 라디오 · 경기 · 인천", "https://www.obs.co.kr/"),
            fixed("ifm", "iFM", "경인방송", "수도권", "https://stream.ifm.kr/live/aod1/playlist.m3u8",
                "iFM 경인방송 · 인천 · 경기 · 음악", "https://www.ifm.kr/"),
            fixed("kookbang", "국방FM", "", "전국", "https://mediaworks.dema.mil.kr/live_edge/audio.sdp/playlist.m3u8",
                "국방FM · 국군방송 라디오 · 음악", "https://www.dema.mil.kr/"),
            fixed("cbs_joy4u", "CBS", "JOY4U", "인터넷", "https://m-aac.cbs.co.kr/mweb_cbscmc/_definst_/cbscmc.stream/playlist.m3u8",
                "CBS JOY4U · CCM 음악", "https://www.cbs.co.kr/radio/", R.drawable.iradio_cbs),
            fixed("tbs_efm", "TBS", "eFM", "수도권", "https://cdnefm.tbs.seoul.kr/tbs/_definst_/tbs_efm_web_360.smil/playlist.m3u8",
                "TBS eFM · 영어 방송", "https://tbs.seoul.kr/", logoQuery = "TBS eFM"),
            fixed("ebs_bandi", "EBS", "반디", "인터넷", "https://ebsonair.ebs.co.kr/cloud1/iradio/playlist.m3u8",
                "EBS 반디 · 외국어 교육", "https://home.ebs.co.kr/bandi/", R.drawable.iradio_ebs),
            fixed("arirang", "아리랑", "라디오", "인터넷", "https://amdlive-ch03-ctnd-com.akamaized.net/arirang_3ch/smil:arirang_3ch.smil/playlist.m3u8",
                "Arirang Radio · 영어 방송", "https://www.arirang.com/"),
            bbs("seoul", "서울", "https://bbslive.clouducs.com/bbsradio-mlive/radio.stream/playlist.m3u8"),
            cpbc("seoul", "", "http://onair1.cpbc.co.kr:1935/live/_definst_/Instreamer.stream_aac/playlist.m3u8"),
        )),
        Region("busan", "부산", kbs("10", "부산", "busan", "21", "22", "24") + listOf(
            Station("mbc_busan_sfm", "부산MBC", "표준FM", "", Source.BusanMbc("AM"), "부산MBC 표준FM · 지역 뉴스 · 시사 · 음악 (편성 정보 없음)",
                "https://www.busanmbc.co.kr/", logoRes = R.drawable.iradio_mbc, region = "부산"),
            Station("mbc_busan_fm4u", "부산MBC", "FM4U", "", Source.BusanMbc("FM"), "부산MBC FM4U · 음악 (편성 정보 없음)",
                "https://www.busanmbc.co.kr/", logoRes = R.drawable.iradio_mbc, region = "부산"),
            sbs("knn_power", "KNN", "파워FM", "부산", "https://stream1.knn.co.kr/hls/lb9ezl87d37uu0vy65bb_pfm/index.m3u8", "https://www.knn.co.kr/"),
            sbs("knn_love", "KNN", "러브FM", "부산", "https://stream1.knn.co.kr/hls/b3y26uu6471k8tes9w7h_lfm/index.m3u8", "https://www.knn.co.kr/"),
        ) + cbs("busan", "부산", "busan939", "busan981") + listOf(
            febc("busan", "부산", "https://mlive2.febc.net/live/bsfebc/playlist.m3u8"),
            tbn("busan", "부산", "busan"),
            bbs("busan", "부산", "https://live.cdn.smilecdn.com/bbsfm_live/live.sdp/playlist.m3u8"),
            cpbc("busan", "부산", "https://pbcradio.dynamicsmart.com/radio/bscpbc-radio/index.m3u8"),
            fixed("befm", "BeFM", "부산영어방송", "부산", "https://live.cdn.smilecdn.com/befm905_live/live/playlist.m3u8",
                "부산영어방송 BeFM · 영어 방송", "https://www.befm.or.kr/"),
        )),
        Region("ulsan", "울산", kbs("11", "울산", "ulsan", "21") +
            mbc("ulsan", "울산MBC", "울산", "https://5ddfd163bd00d.streamlock.net/STDFM/STDFM/playlist.m3u8",
                "https://5ddfd163bd00d.streamlock.net/FM4U/FM4U/playlist.m3u8", "https://www.usmbc.co.kr/") + listOf(
            sbs("ubc_green", "UBC", "그린FM", "울산", "https://stream.ubc.co.kr/hls/ubcfmstream/index.m3u8", "https://www.ubc.co.kr/"),
        ) + cbs("ulsan", "울산", "ulsan") + listOf(
            febc("ulsan", "울산", "https://mlive2.febc.net/live/uslive/playlist.m3u8"),
            tbn("ulsan", "울산", "ulsan"),
        )),
        Region("gyeongnam", "경남 (창원 · 진주)", kbs("20", "창원", "changwon", "21", "22", "24") + kbs("21", "진주", "jinju", "21") +
            mbc("gyeongnam", "MBC경남", "경남", "https://wowza.mbcgn.kr/MBCFM/TV2.stream/playlist.m3u8",
                "https://wowza.mbcgn.kr/MBCFM4U/TV3.stream/playlist.m3u8", "https://www.mbcgn.kr/") +
            cbs("gyeongnam", "경남", "gyeongnam") + listOf(
            febc("changwon", "창원", "https://mlive2.febc.net/live/cwlive/playlist.m3u8"),
            tbn("gyeongnam", "경남", "gyeongnam"),
        )),
        Region("daegu", "대구 · 경북", kbs("30", "대구", "daegu", "21", "22", "24") + kbs("31", "안동", "andong", "21") + kbs("32", "포항", "pohang", "21") +
            mbc("daegu", "대구MBC", "대구", "https://5ee1ec6f32118.streamlock.net/amradio/am/playlist.m3u8",
                "https://5ee1ec6f32118.streamlock.net/fmradio/fm/playlist.m3u8", "https://www.dgmbc.com/") +
            mbc("andong", "안동MBC", "안동", "https://live.andongmbc.co.kr/live/amlive/playlist.m3u8", null, "https://www.andongmbc.co.kr/") +
            mbc("pohang", "포항MBC", "포항", "http://stream.yubinet.com:1935/live/_definst_/Radio_Am/playlist.m3u8",
                "http://stream.yubinet.com:1935/live/_definst_/Radio_Fm/playlist.m3u8", "https://www.phmbc.co.kr/") + listOf(
            sbs("tbc_dream", "TBC", "드림FM", "대구", "http://203.251.91.122:1935/on-air-Backup/fm/playlist.m3u8", "https://www.tbc.co.kr/"),
        ) + cbs("daegu", "대구", "daegu", "daegu939") + cbs("pohang", "포항", "pohang") + listOf(
            febc("daegu", "대구", "https://mlive3.febc.net/live5/daegulive/playlist.m3u8"),
            febc("pohang", "포항", "https://mlive2.febc.net/live/phlive/playlist.m3u8"),
            tbn("daegu", "대구", "daegu"),
            tbn("gyeongbuk", "경북", "kyungbuk"),
            cpbc("daegu", "대구", "http://live.dgcpbc.co.kr/dgcpbclive/livestream/playlist.m3u8"),
        )),
        Region("gwangju", "광주 · 전남", kbs("40", "광주", "gwangju", "21", "22", "24") + kbs("41", "목포", "mokpo", "21", "24") + kbs("43", "순천", "suncheon", "21") +
            mbc("gwangju", "광주MBC", "광주", "https://media.kjmbc.co.kr/hls/amlive/GWANGJU-MBC-AM/playlist.m3u8",
                "https://media.kjmbc.co.kr/hls/fmlive/GWANGJU-MBC-FM/playlist.m3u8", "https://www.kjmbc.co.kr/") +
            mbc("mokpo", "목포MBC", "목포", "https://vod.mpmbc.co.kr/live/encoder-am/playlist.m3u8",
                "https://vod.mpmbc.co.kr/live/encoder-fm/playlist.m3u8", "https://www.mpmbc.co.kr/") +
            mbc("yeosu", "여수MBC", "여수", "https://5c3639aa99149.streamlock.net/%ED%91%9C%EC%A4%80FM/%ED%91%9C%EC%A4%80FM/playlist.m3u8",
                "https://5c3639aa99149.streamlock.net/FM4U/FM4U/playlist.m3u8", "https://www.ysmbc.co.kr/") + listOf(
            sbs("kbc_my", "KBC", "마이FM", "광주", "https://vod.ikbc.co.kr/KBCFM/kbcra_aac/playlist.m3u8", "https://www.ikbc.co.kr/"),
        ) + cbs("gwangju", "광주", "gwangju", "gwangju939") + cbs("jeonnam", "전남", "jeonnam") + listOf(
            febc("gwangju", "광주", "https://mlive2.febc.net/live/gjlive/playlist.m3u8"),
            febc("mokpo", "목포", "https://mlive3.febc.net/live5/mplive/playlist.m3u8"),
            febc("jeonnam", "전남동부", "https://mlive2.febc.net/live/jndblive/playlist.m3u8"),
            fixed("gugak_gwangju", "광주국악방송", "", "광주", "https://mgugaklive.nowcdn.co.kr/gugakgwangju/Glive.stream/playlist.m3u8",
                "광주국악방송 · 국악 · 전통 음악", "https://www.igbf.kr/", R.drawable.iradio_gugak),
            tbn("gwangju", "광주", "gwangju"),
            bbs("gwangju", "광주", "https://live.cdn.smilecdn.com/kjbbs1_live/live/playlist.m3u8"),
            cpbc("gwangju", "광주", "https://pbcradio.dynamicsmart.com/radio/kjpbc2/index.m3u8"),
        )),
        Region("jeonbuk", "전북", kbs("50", "전주", "jeonju", "21", "22", "24") +
            mbc("jeonju", "전주MBC", "전주", "https://live.jmbc.co.kr/live/am_live.m3u8",
                "https://live.jmbc.co.kr/live/fm_live.m3u8", "https://www.jmbc.co.kr/") + listOf(
            sbs("jtv_magic", "JTV", "매직FM", "전주", "http://61.85.197.53:1935/jtv_radio/myStream/playlist.m3u8", "https://www.jtv.co.kr/"),
        ) + cbs("jeonbuk", "전북", "jeonbuk") + listOf(
            febc("jeonbuk", "전북", "https://mlive2.febc.net/live/jblive/playlist.m3u8"),
            tbn("jeonbuk", "전북", "jeonbuk"),
        )),
        Region("daejeon", "대전 · 충남", kbs("60", "대전", "daejeon", "21", "22", "24") +
            mbc("daejeon", "대전MBC", "대전", "https://ns1.tjmbc.co.kr/live_am/live_am.stream/playlist.m3u8",
                "https://ns1.tjmbc.co.kr/live_fm/live_fm.stream/playlist.m3u8", "https://www.tjmbc.co.kr/") + listOf(
            sbs("tjb_power", "TJB", "파워FM", "대전", "https://vod.tjb.co.kr/radiolive/_definst_/radio_64k/playlist.m3u8", "https://www.tjb.co.kr/"),
        ) + cbs("daejeon", "대전", "daejeon") + listOf(
            febc("daejeon", "대전", "https://mlive2.febc.net/live/djlive/playlist.m3u8"),
            fixed("gugak_daejeon", "대전국악방송", "", "대전", "https://mgugaklive.nowcdn.co.kr/gugakdaejeon/Daejeonradio.stream/playlist.m3u8",
                "대전국악방송 · 국악 · 전통 음악", "https://www.igbf.kr/", R.drawable.iradio_gugak),
            tbn("daejeon", "대전", "daejeon"),
            tbn("chungnam", "충남", "chungnam"),
        )),
        Region("chungbuk", "충북", kbs("70", "청주", "cheongju", "21", "22", "24") + kbs("71", "충주", "chungju", "21", "24") +
            mbc("chungbuk", "MBC충북", "충북", "https://mbccbp.coreit.co.kr/radio_stfm/myStream.sdp/playlist.m3u8",
                "https://mbccbp.coreit.co.kr/radio_fm/myStream.sdp/playlist.m3u8", "https://www.mbccb.co.kr/") +
            cbs("cheongju", "청주", "cheongju") + listOf(
            tbn("chungbuk", "충북", "chungbuk"),
        )),
        Region("gangwon", "강원", kbs("80", "춘천", "chuncheon", "21", "22", "24") + kbs("81", "강릉", "gangneung", "21", "24") + kbs("82", "원주", "wonju", "21", "24") +
            mbc("chuncheon", "춘천MBC", "춘천", "https://stream.chmbc.co.kr/radio1/fm1_aac/playlist.m3u8",
                "https://stream.chmbc.co.kr/live_radio2/fm1/playlist.m3u8", "https://www.chmbc.co.kr/") +
            mbc("wonju", "원주MBC", "원주", null, "http://live.wjmbc.co.kr:1935/fm989/fm989/playlist.m3u8", "https://www.wjmbc.co.kr/") +
            mbc("gangwon", "MBC강원영동", "강릉", "http://123.254.72.24:1935/amlive/livestream/playlist.m3u8",
                "http://123.254.72.24:1935/fmlive/livestream/playlist.m3u8", "https://www.mbcgw.co.kr/") + listOf(
            sbs("g1_fresh", "G1", "프레쉬FM", "강원", "http://61.82.49.4:1935/fm/_definst_/myStream/playlist.m3u8", "https://www.g1tv.co.kr/"),
        ) + cbs("chuncheon", "춘천", "chuncheon") + cbs("youngdong", "강원영동", "youngdong") + listOf(
            febc("gangwon", "강원", "https://mlive3.febc.net/live5/ydlive/playlist.m3u8"),
            tbn("gangwon", "강원", "gangwon"),
        )),
        // 제주MBC 는 서버 인증서 체인이 빠져 있어 Android 에서 안 열림 (우회하지 않고 뺌)
        Region("jeju", "제주", kbs("90", "제주", "jeju", "21", "22", "24") + listOf(
            sbs("jibs_newpower", "JIBS", "뉴파워FM", "제주", "http://123.140.197.22/stream/2/play.m3u8", "https://www.jibs.co.kr/"),
        ) + cbs("jeju", "제주", "jeju") + listOf(
            febc("jeju", "제주", "https://mlive2.febc.net/live/jejufm/playlist.m3u8"),
            tbn("jeju", "제주", "jeju"),
            bbs("jeju", "제주", "https://kdbmobile.clouducs.com/jeju/jeju.stream_aac/playlist.m3u8"),
        )),
    )
}
