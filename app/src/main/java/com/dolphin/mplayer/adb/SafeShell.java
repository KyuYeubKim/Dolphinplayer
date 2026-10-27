package com.dolphin.mplayer.adb;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 차량 ADB 로 보내는 명령 검사기 (0.49). D-Player 가 차 시스템을 바꾸거나 손상시킬 수 없도록,
 * 아래 목록에 있는 명령만 보낸다. 목록에 없으면 보내지 않고 기록만 남긴다 (코드가 바뀌어도 실수로 위험한 명령이 나가지 않게).
 *
 * 허용하는 일 (모두 D-Player 자신에게만 영향, 또는 읽기):
 *  - D-Player 자신에게 권한 주기: pm grant / appops set / 알림 접근 / 배터리 최적화 예외 (패키지 = D-Player 만)
 *  - D-Player 자신 업데이트: /data/local/tmp/dplayer-update.apk 만 설치 (AppUpdater 가 패키지 · 서명도 확인),
 *    설치 뒤 D-Player 다시 켜기 (am start 는 D-Player 자신만)
 *  - 읽기: dumpsys media_session · bluetooth_manager, grep · head · cat · stat
 *  (화면 분할 명령은 0.52 에서 분할 기능과 함께 뺌 — 분할은 DolphinControl 이 맡음)
 *  - D-Player 가 만든 임시 파일(/data/local/tmp/dplayer*)만 지우기
 * 하지 않는 일: 시스템 설정 쓰기, 시스템 파일 수정, 다른 앱 삭제 · 중지 · 권한 변경, 재부팅 등.
 */
final class SafeShell {

    private SafeShell() {}

    private static final String TMP = "/data/local/tmp/dplayer[-_][\\w.-]+";

    /** 명령을 보내도 되는지. pkg = D-Player 패키지, apk = D-Player APK 경로 */
    static boolean allowed(String cmd, String pkg, String apk) {
        if (cmd == null || cmd.isEmpty() || cmd.indexOf('`') >= 0 || cmd.contains("$(")) return false;
        return script(cmd.trim(), pkg, apk, 0);
    }

    /** ; && || & | 로 이어진 명령들 — 따옴표 안은 나누지 않음 */
    private static boolean script(String s, String pkg, String apk, int depth) {
        if (depth > 3) return false;
        List<String> parts = split(s);
        if (parts == null || parts.isEmpty()) return false;
        for (String p : parts) if (!simple(p.trim(), pkg, apk, depth)) return false;
        return true;
    }

    private static boolean simple(String s, String pkg, String apk, int depth) {
        if (s.isEmpty()) return true;
        // sh -c '...' / setsid sh -c '...' → 안쪽 명령도 같은 규칙으로
        java.util.regex.Matcher w = Pattern.compile("^(?:setsid )?sh -c '([^']*)'(.*)$").matcher(s);
        if (w.matches()) return redirects(w.group(2)) && script(w.group(1), pkg, apk, depth + 1);
        // 끝의 입출력 연결(< /dev/null, > 로그, 2>&1)을 떼고 본 명령만 검사
        String body = s;
        java.util.regex.Matcher r = Pattern.compile("^(.*?)((?:\\s+(?:<\\s*/dev/null|\\d?>\\s*\\S+|2>&1))+)$").matcher(s);
        if (r.matches()) {
            if (!redirects(r.group(2))) return false;
            body = r.group(1).trim();
        }
        String P = Pattern.quote(pkg);
        String[] ok = {
            "dumpsys (media_session|bluetooth_manager|audio)", // 읽기만 (audio = 오디오 포커스 진단, 0.56)
            "dumpsys deviceidle whitelist \\+" + P,
            "grep( -[a-zA-Z]+\\d*)* ('[^']*'|[\\w.:-]+)( " + TMP + ")?",
            "head -(n )?\\d+",
            "tail -(n )?\\d+",
            "head -c \\d+",
            "cat " + TMP,
            "stat -c %s " + TMP,
            "rm -f " + TMP,
            "sleep \\d+",
            "pm grant " + P + " [\\w.]+",
            "pm install -r /data/local/tmp/dplayer-update\\.apk",
            "cmd notification allow_listener " + P + "/[\\w.$]+",
            "cmd notification set_enabled " + P + "/[\\w.$]+ true",
            "(cmd )?appops set " + P + " [A-Z_]+ allow",
            "am start -n " + P + "/" + P + "\\.MainActivity",
        };
        for (String re : ok) if (body.matches(re)) return true;
        return false;
    }

    /** 입출력 연결은 /dev/null 이나 D-Player 임시 파일로만 */
    private static boolean redirects(String s) {
        String t = s.trim();
        if (t.isEmpty()) return true;
        return t.matches("((<\\s*/dev/null|\\d?>\\s*(/dev/null|" + TMP + ")|2>&1)\\s*)+");
    }

    /** 따옴표 밖의 ; && || & | 에서 나눔. 따옴표가 안 닫히면 null */
    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') { q = !q; cur.append(c); continue; }
            if (!q && (c == ';' || c == '|' || c == '&')) {
                // 2>&1 의 & 는 나누지 않음
                if (c == '&' && i > 0 && s.charAt(i - 1) == '>') { cur.append(c); continue; }
                out.add(cur.toString());
                cur.setLength(0);
                if (i + 1 < s.length() && (s.charAt(i + 1) == c)) i++; // && ||
                continue;
            }
            cur.append(c);
        }
        if (q) return null;
        out.add(cur.toString());
        return out;
    }
}
