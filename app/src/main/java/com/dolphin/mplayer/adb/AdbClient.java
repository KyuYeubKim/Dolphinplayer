package com.dolphin.mplayer.adb;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * 차량(DiLink) 자신의 ADB 데몬(127.0.0.1:5555)에 접속해 shell 명령을 실행한다.
 *
 * 처음 접속하면 차량 화면에 "USB 디버깅을 허용하시겠습니까?" 창이 뜨고, "이 컴퓨터에서 항상 허용"을
 * 체크하고 허용하면 이후에는 같은 키로 자동 연결된다. 키는 앱 내부 저장소에만 보관한다.
 *
 * 이렇게 얻은 shell 권한으로 앱이 스스로에게 알림 접근 권한을 부여하면
 * (cmd notification allow_listener ...) PC 없이도 다른 앱의 재생 정보를 읽을 수 있다.
 *
 * ADB 프로토콜(CNXN/AUTH/OPEN/WRTE/OKAY/CLSE)을 외부 라이브러리 없이 직접 구현했다.
 * 참고: AOSP system/core/adb, github.com/andrewloable/BladeWatch (AdbProtocolClient)
 * 반드시 백그라운드 스레드에서 호출할 것.
 */
public final class AdbClient {

    private static final String TAG = "AdbClient";
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5555;

    private static final int A_CNXN = 0x4E584E43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4E45504F;
    private static final int A_OKAY = 0x59414B4F;
    private static final int A_CLSE = 0x45534C43;
    private static final int A_WRTE = 0x45545257;
    private static final int A_STLS = 0x534C5453;
    private static final int VERSION = 0x01000001;
    private static final int MAXDATA = 256 * 1024;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;

    private static final int IO_TIMEOUT_MS = 6000;
    /** 허용 창 응답 대기 기본값. '항상 허용' 체크 + [허용] 까지 시간이 걸려 넉넉히 (재시도는 호출 측에서 짧게) */
    public static final int APPROVE_TIMEOUT_MS = 60000;

    /**
     * 차량의 허용 창이 닫힌 시각 (앱 화면이 다시 앞에 온 시각, SystemClock.elapsedRealtime).
     * 사용자가 답했다는 뜻이라, 2초 안에 이 연결로 CNXN 이 안 오면 기다리지 않고 바로 새 연결로 확인한다
     * (허용 직후 새 연결을 요구하는 adbd 에서 60초를 기다리지 않게).
     */
    public static volatile long dialogClosedAt = 0;
    private static final int AFTER_DIALOG_GRACE_MS = 2000;
    /**
     * SHA1 DigestInfo (15바이트). NONEwithRSA 가 PKCS#1 v1.5 헤더(00 01 FF..FF 00)를 스스로 붙이므로
     * DigestInfo 만 넣어야 한다. 예전엔 맨 앞에 0x00 이 하나 더 있어 adbd 의 서명 검증이 항상 실패했고,
     * 그래서 '항상 허용'을 눌러도 다음 접속마다 미승인 키로 취급돼 허용 창이 다시 떴다.
     */
    private static final byte[] SHA1_PADDING = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2B, 0x0E,
            0x03, 0x02, 0x1A, 0x05, 0x00, 0x04, 0x14};
    private static final String PUBKEY_SUFFIX = " dolphin@mplayer\u0000";

    /**
     * OK 연결됨 · UNREACHABLE 5555 포트 없음 · DENIED 거부 · NOT_APPROVED 키 미등록(허용 창을 띄우지 않은 접속)
     * · RETRY 허용 대기 중 응답 없음/연결 닫힘 (잠시 뒤 새 연결로 다시 시도) · ERROR 기타
     */
    public enum Result { OK, UNREACHABLE, DENIED, NOT_APPROVED, RETRY, ERROR }

    private final File keyDir;
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private int localId = 1;
    private String host;
    /** 연결 과정 단계별 기록 (자가진단용) */
    public final StringBuilder log = new StringBuilder();

    private long t0 = System.currentTimeMillis();

    /** 기록 한 줄 (앞에 첫 접속부터 지난 시간) — 허용 타이밍 문제를 확인하려고 */
    private void step(String s) {
        long ms = System.currentTimeMillis() - t0;
        s = String.format(java.util.Locale.US, "[%5.1fs] ", ms / 1000f) + s;
        log.append(s).append('\n');
        Log.d(TAG, s);
    }

    /** 명령 검사용 (SafeShell): D-Player 패키지 · APK 경로 */
    private final String pkg;
    private final String apk;

    public AdbClient(Context c) {
        keyDir = new File(c.getApplicationContext().getFilesDir(), "adb");
        pkg = c.getPackageName();
        apk = c.getApplicationInfo().sourceDir;
    }

    /** 허용된 명령인지 (아니면 보내지 않고 기록) — 차 시스템을 바꾸는 명령이 나가지 않게 */
    private boolean safe(String cmd) {
        if (SafeShell.allowed(cmd, pkg, apk)) return true;
        step("차단: 허용 목록에 없는 명령이라 보내지 않음 → " + cmd);
        return false;
    }

    /** 포트가 열려 있는지(ADB 디버깅 켜짐) 빠르게 확인 (루프백만) */
    public static boolean isPortOpen() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(HOST, PORT), 800);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * adbd 가 바인딩됐을 수 있는 후보 주소들.
     * service.adb.tcp.port 가 설정돼 있어도 IPv4 루프백이 아니라
     * IPv6(::1) 또는 기기 자신의 LAN IP 에만 열려 있는 경우가 있어 모두 시도한다.
     */
    static List<String> candidateHosts() {
        List<String> hosts = new ArrayList<>();
        hosts.add("127.0.0.1");
        hosts.add("::1");
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a.isLoopbackAddress()) continue;
                    String h = a.getHostAddress();
                    if (h == null) continue;
                    int pct = h.indexOf('%');
                    if (pct >= 0) h = h.substring(0, pct); // IPv6 scope id 제거
                    if (!hosts.contains(h)) hosts.add(h);
                }
            }
        } catch (Exception ignored) {
        }
        return hosts;
    }

    /** 이 앱의 ADB 키가 차량에 승인된 적 있는지(키 파일 존재로 근사) */
    public boolean hasKey() {
        return new File(keyDir, "priv.key").exists();
    }

    // ===================== 연결 / 인증 =====================

    /** 허용 창 대기 중 남은 시간을 알려준다 (백그라운드 스레드에서 호출됨) */
    public interface Waiting {
        void secondsLeft(int s);
    }

    /** 이미 '항상 허용'된 키로만 접속 (허용 창을 띄우지 않음). 분할 · 자동 업데이트 등에서 사용 */
    public Result connect() {
        return connect(null, false);
    }

    /** 미승인 키면 공개키를 보내 차량 화면에 허용 창을 띄우고 기다린다 (권한 설정에서만 사용) */
    public Result connect(Waiting waiting) {
        return connect(waiting, true, APPROVE_TIMEOUT_MS);
    }

    public Result connect(Waiting waiting, boolean prompt) {
        return connect(waiting, prompt, APPROVE_TIMEOUT_MS);
    }

    /**
     * 연결 + 인증 (Dolphin Control 과 같은 방식).
     * 미승인 키이고 prompt=true 면 공개키를 보내고 '같은 연결'에서 허용을 기다린다.
     * 대기 중에는 새 연결을 열지 않는다 (허용 창이 떠 있는 동안 다른 연결이 끼어들면 승인이 꼬이는 adbd 가 있음).
     * adbd 가 허용 후 새 연결을 요구하면 호출 측(AdbProvisioner)이 잠시 뒤 다시 접속한다.
     */
    public Result connect(Waiting waiting, boolean prompt, int approveMs) {
        close();
        step("① 포트 " + PORT + " 접속 시도 (후보 주소 순회)...");
        host = null;
        for (String h : candidateHosts()) {
            try {
                open(h);
                host = h;
                step("   → " + h + ":" + PORT + " 연결 성공");
                break;
            } catch (Exception e) {
                close();
                step("   → " + h + ":" + PORT + " 실패 (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
            }
        }
        if (host == null) {
            step("   ※ 모든 주소에서 5555 접속 실패. adbd 가 TCP 로 듣고 있지 않거나 접근이 차단됨. 허용창도 뜨지 않습니다.");
            return Result.UNREACHABLE;
        }
        try {
            KeyPair keys = loadOrCreateKeys();
            step("② CNXN 전송 → 서명 인증");
            int hs = handshake(keys, true);
            if (hs == HS_OK) {
                step("   ✔ 연결 성공 (승인된 키)");
                return Result.OK;
            }
            if (hs == HS_STLS) {
                step("   ※ adbd 가 TLS(STLS)를 요구합니다. 이 방식은 지원되지 않습니다.");
                return Result.ERROR;
            }
            if (hs != HS_NEED_KEY) {
                step("   ✘ 예상치 못한 응답으로 인증 실패");
                return Result.ERROR;
            }
            if (!prompt) {
                step("   ※ 이 앱의 키가 차량에 '항상 허용'으로 등록돼 있지 않습니다 (설정 → 권한 다시 설정 필요).");
                return Result.NOT_APPROVED;
            }

            step("③ 미승인 키 → 공개키 전송");
            step("   ★ 지금 차량 화면에 'USB 디버깅 허용' 창이 떠야 합니다. '항상 허용' 체크 후 [허용]을 눌러 주세요.");
            write(A_AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBytes((RSAPublicKey) keys.getPublic()));

            long deadline = System.currentTimeMillis() + approveMs;
            long waitStart = android.os.SystemClock.elapsedRealtime();
            int lastLeft = -1;
            socket.setSoTimeout(250);
            while (System.currentTimeMillis() < deadline) {
                int left = (int) ((deadline - System.currentTimeMillis() + 999) / 1000);
                if (waiting != null && left != lastLeft) waiting.secondsLeft(left);
                lastLeft = left;
                long closed = dialogClosedAt;
                if (closed > waitStart && android.os.SystemClock.elapsedRealtime() - closed > AFTER_DIALOG_GRACE_MS) {
                    step("   → 허용 창이 닫혔는데 이 연결로는 응답 없음 → 새 연결로 확인");
                    return Result.RETRY;
                }
                int b;
                try {
                    b = in.read();
                } catch (java.net.SocketTimeoutException e) {
                    continue;
                }
                if (b < 0) {
                    step("   → 대기 연결이 닫힘 (허용 후 새 연결을 요구하는 adbd)");
                    return Result.RETRY;
                }
                ((PushbackInputStream) in).unread(b);
                socket.setSoTimeout(IO_TIMEOUT_MS);
                Packet p = read();
                step("   → 응답 " + cmdName(p.cmd));
                if (p.cmd == A_CNXN) {
                    step("   ✔ 허용됨 — 연결 성공");
                    return Result.OK;
                }
                if (p.cmd == A_CLSE) {
                    step("   → 대기 연결을 adbd 가 닫음 (CLSE)");
                    return Result.RETRY;
                }
                socket.setSoTimeout(250);
            }
            step("   ✘ " + (approveMs / 1000) + "초 동안 응답 없음 (허용 창 미응답 또는 adbd 가 새 연결을 요구)");
            return Result.RETRY;
        } catch (IOException e) {
            step("   → 연결 끊김 (" + e.getMessage() + ")");
            return Result.RETRY;
        } catch (Exception e) {
            step("   ✘ 오류: " + e);
            return Result.ERROR;
        }
    }

    private static final int HS_OK = 0, HS_NEED_KEY = 1, HS_STLS = 2, HS_FAIL = 3;

    /** CNXN 전송 → AUTH 토큰 서명까지. 현재 socket/in/out 사용. */
    private int handshake(KeyPair keys, boolean verbose) throws Exception {
        write(A_CNXN, VERSION, MAXDATA, "host::\u0000".getBytes("UTF-8"));
        Packet p = read();
        if (verbose) step("   → 응답 " + cmdName(p.cmd));
        if (p.cmd == A_CNXN) return HS_OK; // 인증 꺼져 있음
        if (p.cmd == A_STLS) return HS_STLS;
        if (p.cmd != A_AUTH || p.arg0 != AUTH_TOKEN) return HS_FAIL;
        write(A_AUTH, AUTH_SIGNATURE, 0, sign(keys.getPrivate(), p.payload));
        p = read();
        if (verbose) step("   → 서명 응답 " + cmdName(p.cmd));
        if (p.cmd == A_CNXN) return HS_OK;
        if (p.cmd == A_AUTH && p.arg0 == AUTH_TOKEN) return HS_NEED_KEY;
        return HS_FAIL;
    }

    private void open(String h) throws IOException {
        Socket t = new Socket();
        t.connect(new InetSocketAddress(h, PORT), 1500);
        t.setTcpNoDelay(true);
        t.setSoTimeout(IO_TIMEOUT_MS);
        socket = t;
        in = new PushbackInputStream(t.getInputStream(), 1);
        out = t.getOutputStream();
    }

    private static String cmdName(int cmd) {
        if (cmd == A_CNXN) return "CNXN(연결됨)";
        if (cmd == A_AUTH) return "AUTH(인증요청)";
        if (cmd == A_STLS) return "STLS(TLS요구)";
        if (cmd == A_CLSE) return "CLSE(닫힘)";
        if (cmd == A_OKAY) return "OKAY";
        if (cmd == A_WRTE) return "WRTE";
        return "0x" + Integer.toHexString(cmd);
    }

    /**
     * 허용창을 띄우지 않고(공개키 전송 전까지만) adbd 상태만 빠르게 점검한다.
     * 진단 리포트에서 '왜 허용창이 안 뜨는지' 확인용.
     */
    public String probeReport() {
        close();
        StringBuilder r = new StringBuilder();
        String openHost = null;
        for (String h : candidateHosts()) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(h, PORT), 1200);
                r.append("  ").append(h).append(":5555 = 열림\n");
                if (openHost == null) openHost = h;
            } catch (Exception e) {
                r.append("  ").append(h).append(":5555 = 닫힘 (")
                        .append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append(")\n");
            }
        }
        if (openHost == null) {
            r.append("  → 어떤 주소에서도 5555 접속 불가. 이 상태에선 허용창이 뜨지 않습니다.\n");
            return r.toString();
        }
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(openHost, PORT), IO_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(IO_TIMEOUT_MS);
            in = socket.getInputStream();
            out = socket.getOutputStream();
            write(A_CNXN, VERSION, MAXDATA, "host::\u0000".getBytes("UTF-8"));
            Packet p = read();
            r.append("  CNXN 응답(").append(openHost).append(") = ").append(cmdName(p.cmd)).append('\n');
            if (p.cmd == A_AUTH) {
                r.append("  → adbd 정상 응답. [자동 설정]을 누르면 허용창이 떠야 합니다.\n");
            } else if (p.cmd == A_CNXN) {
                r.append("  → 인증 없이 바로 연결됨(이미 허용된 상태).\n");
            } else if (p.cmd == A_STLS) {
                r.append("  → adbd가 TLS를 요구(이 방식 미지원).\n");
            }
        } catch (Exception e) {
            r.append("  핸드셰이크 오류: ").append(e.getClass().getSimpleName()).append(' ').append(e.getMessage()).append('\n');
        } finally {
            close();
        }
        return r.toString();
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    public void close() {
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
        socket = null;
        in = null;
        out = null;
        localId = 1;
    }

    // ===================== shell 명령 =====================

    /** shell 명령 실행 후 표준출력을 돌려준다. 실패 시 null */
    public synchronized String shell(String cmd) {
        if (!isConnected() || !safe(cmd)) return null;
        int id = localId++;
        try {
            write(A_OPEN, id, 0, ("shell:" + cmd + "\u0000").getBytes("UTF-8"));
            int remote = 0;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 2000; i++) {
                Packet p = read();
                if (p.cmd == A_OKAY && p.arg1 == id) {
                    remote = p.arg0;
                } else if (p.cmd == A_WRTE && p.arg1 == id) {
                    sb.append(new String(p.payload, "UTF-8"));
                    write(A_OKAY, id, p.arg0, EMPTY);
                } else if (p.cmd == A_CLSE && p.arg1 == id) {
                    write(A_CLSE, id, remote, EMPTY);
                    break;
                }
            }
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "shell(" + cmd + "): " + e);
            return null;
        }
    }

    /**
     * exec: 서비스(pty 없음 → 바이너리 안전)로 원격 명령을 실행하고 표준입력에 파일 내용을 흘려보낸다.
     * 명령은 입력을 다 읽으면 스스로 끝나야 한다 (예: head -c 크기 > 파일). 명령이 끝나면 true.
     * Android 11+ 에서는 셸이 앱 폴더의 파일을 못 읽으므로 업데이트 APK 를 이 방법으로 /data/local/tmp 에 옮긴다.
     */
    public synchronized boolean execWithInput(String cmd, File input) {
        if (!isConnected() || !safe(cmd)) return false;
        int id = localId++;
        try (java.io.FileInputStream fin = new java.io.FileInputStream(input)) {
            write(A_OPEN, id, 0, ("exec:" + cmd + "\u0000").getBytes("UTF-8"));
            Packet p = read();
            while (p.arg1 != id || (p.cmd != A_OKAY && p.cmd != A_CLSE)) p = read();
            if (p.cmd != A_OKAY) return false;
            int remote = p.arg0;
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = fin.read(buf)) > 0) {
                write(A_WRTE, id, remote, java.util.Arrays.copyOf(buf, n));
                while (true) { // 흐름 제어: 상대의 OKAY 를 받아야 다음 조각을 보낸다
                    p = read();
                    if (p.arg1 != id) continue;
                    if (p.cmd == A_OKAY) break;
                    if (p.cmd == A_WRTE) write(A_OKAY, id, remote, EMPTY);
                    else if (p.cmd == A_CLSE) return false;
                }
            }
            for (int i = 0; i < 2000; i++) { // 명령 종료(CLSE) 대기
                p = read();
                if (p.arg1 != id) continue;
                if (p.cmd == A_WRTE) {
                    write(A_OKAY, id, remote, EMPTY);
                } else if (p.cmd == A_CLSE) {
                    write(A_CLSE, id, remote, EMPTY);
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            Log.w(TAG, "exec(" + cmd + "): " + e);
            return false;
        }
    }

    // ===================== ADB 패킷 =====================

    private static final byte[] EMPTY = new byte[0];

    private static final class Packet {
        int cmd, arg0, arg1;
        byte[] payload = EMPTY;
    }

    private void write(int cmd, int arg0, int arg1, byte[] payload) throws IOException {
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd);
        h.putInt(arg0);
        h.putInt(arg1);
        h.putInt(payload.length);
        int sum = 0;
        for (byte b : payload) sum += (b & 0xFF);
        h.putInt(sum);
        h.putInt(~cmd);
        out.write(h.array());
        if (payload.length > 0) out.write(payload);
        out.flush();
    }

    private Packet read() throws IOException {
        byte[] hb = readN(24);
        ByteBuffer h = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN);
        Packet p = new Packet();
        p.cmd = h.getInt();
        p.arg0 = h.getInt();
        p.arg1 = h.getInt();
        int len = h.getInt();
        h.getInt(); // checksum
        h.getInt(); // magic
        if (len > 0) p.payload = readN(len);
        return p;
    }

    private byte[] readN(int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new IOException("EOF");
            off += r;
        }
        return buf;
    }

    // ===================== RSA 키 / 서명 =====================

    private KeyPair loadOrCreateKeys() throws Exception {
        File priv = new File(keyDir, "priv.key"), pub = new File(keyDir, "pub.key");
        if (priv.exists() && pub.exists()) {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PrivateKey pr = kf.generatePrivate(new PKCS8EncodedKeySpec(readFile(priv)));
            PublicKey pu = kf.generatePublic(new X509EncodedKeySpec(readFile(pub)));
            return new KeyPair(pu, pr);
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        keyDir.mkdirs();
        writeFile(priv, kp.getPrivate().getEncoded());
        writeFile(pub, kp.getPublic().getEncoded());
        return kp;
    }

    /** adbd 토큰 서명: SHA1 DigestInfo 패딩 + 토큰을 RSA 로 서명 (NONEwithRSA) */
    private static byte[] sign(PrivateKey key, byte[] token) throws Exception {
        Signature s = Signature.getInstance("NONEwithRSA");
        s.initSign(key);
        byte[] m = new byte[SHA1_PADDING.length + token.length];
        System.arraycopy(SHA1_PADDING, 0, m, 0, SHA1_PADDING.length);
        System.arraycopy(token, 0, m, SHA1_PADDING.length, token.length);
        s.update(m);
        return s.sign();
    }

    /**
     * 서명 형식 자체 검사: 저장된 키로 임의 토큰을 서명한 뒤, adbd 처럼 공개키로 풀어
     * 'SHA1 DigestInfo + 토큰' 과 정확히 같은지 확인한다 (진단 기록용). "OK" 또는 실패 이유.
     */
    public String signatureSelfTest() {
        try {
            KeyPair keys = loadOrCreateKeys();
            byte[] token = new byte[20];
            new java.security.SecureRandom().nextBytes(token);
            byte[] sig = sign(keys.getPrivate(), token);
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding");
            c.init(javax.crypto.Cipher.DECRYPT_MODE, keys.getPublic());
            byte[] rec = c.doFinal(sig);
            byte[] expected = new byte[SHA1_PADDING.length + token.length];
            System.arraycopy(SHA1_PADDING, 0, expected, 0, SHA1_PADDING.length);
            System.arraycopy(token, 0, expected, SHA1_PADDING.length, token.length);
            return java.util.Arrays.equals(rec, expected) ? "OK" : "불일치 (복원 " + rec.length + "바이트)";
        } catch (Exception e) {
            return "오류: " + e;
        }
    }

    /** ADB 전용 공개키 포맷(mincrypt RSAPublicKey 구조) + base64 + 사용자명 */
    private static byte[] publicKeyBytes(RSAPublicKey key) throws Exception {
        int words = 2048 / 32; // 64
        BigInteger n = key.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger r = BigInteger.ONE.shiftLeft(2048);
        BigInteger rr = r.multiply(r).mod(n);
        BigInteger n0inv = n.modInverse(r32).negate().mod(r32);

        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 + words * 4 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(n0inv.intValue());
        BigInteger mask = r32.subtract(BigInteger.ONE);
        BigInteger t = n;
        for (int i = 0; i < words; i++) {
            b.putInt(t.and(mask).intValue());
            t = t.shiftRight(32);
        }
        t = rr;
        for (int i = 0; i < words; i++) {
            b.putInt(t.and(mask).intValue());
            t = t.shiftRight(32);
        }
        b.putInt(key.getPublicExponent().intValue());

        String b64 = Base64.encodeToString(b.array(), Base64.NO_WRAP);
        return (b64 + PUBKEY_SUFFIX).getBytes("UTF-8");
    }

    private static byte[] readFile(File f) throws IOException {
        byte[] buf = new byte[(int) f.length()];
        try (java.io.FileInputStream s = new java.io.FileInputStream(f)) {
            int off = 0, r;
            while (off < buf.length && (r = s.read(buf, off, buf.length - off)) > 0) off += r;
        }
        return buf;
    }

    private static void writeFile(File f, byte[] data) throws IOException {
        try (java.io.FileOutputStream s = new java.io.FileOutputStream(f)) {
            s.write(data);
        }
    }
}
