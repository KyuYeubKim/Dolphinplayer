# D-Player

BYD 돌핀(DiLink, Android) 분할화면용 컴팩트 미디어 컨트롤러.
Spotify API 없이 Android **MediaSession** 으로 지금 재생 중인 음악 앱(Spotify, 멜론, 유튜브 뮤직, 라디오 등)을
읽어서 곡 정보를 보여주고 제어합니다. 로그인·API 키 불필요.

기능: 현재 곡(제목/아티스트/커버/진행바) · 재생 · 일시정지 · 정지 · 이전/다음 곡 · 셔플 · 라이브러리/대기열 · 재생 앱 전환
· **YouTube Music**(내 재생목록을 라이브러리에서 재생) · **YouTube**(전체화면) — 차량에 공식 앱이 없어도 Premium 계정으로 사용

## 설치

GeckoView(Firefox 엔진)를 포함해서 CPU(ABI)별로 APK 를 따로 만든다 (각 약 90MB).

```bash
./gradlew assembleArm64Release assembleArmRelease assembleX64Release
```

| flavor | 결과물 → dist 이름 | 용도 |
|---|---|---|
| arm64 | `app/build/outputs/apk/arm64/release/app-arm64-release.apk` → `D-Player-<버전>-arm64.apk` | 차량 (기본) |
| arm | `.../arm/release/app-arm-release.apk` → `D-Player-<버전>-armv7.apk` | 32비트 차량 |
| x64 | `.../x64/release/app-x64-release.apk` → `D-Player-<버전>-x64.apk` | 에뮬레이터 시험용 (배포 안 함) |

차량에는 `dist/D-Player-0.23-arm64.apk` 를 사이드로드. 이후에는 앱이 자동 업데이트한다.

## 권한: 알림 접근 (자동)

다른 앱의 재생 세션을 읽으려면 *알림 접근* 권한이 필요합니다. DiLink는 이 설정 화면을 막는 경우가 많아서,
권한이 없으면 앱이 실행되자마자 **차량 자신의 ADB(127.0.0.1:5555)에 접속해 스스로 권한을 부여**합니다
(Dolphin Control 과 같은 방식, `adb/AdbClient.java` 는 Dolphin Control 에서 가져옴).

1. 앱 실행(또는 설정 → 권한 다시 설정) → 자동으로 ADB 접속 시작
2. 차량 화면에 **"USB 디버깅을 허용하시겠습니까?"** 창이 뜨면 **이 컴퓨터에서 항상 허용** 체크 후 **[허용]**
   (한 번 접속에 20초 대기, 허용 직후 새 연결을 요구하는 adbd 에 맞춰 1.2초 간격으로 최대 4번 재접속 — Dolphin Control 과 같은 방식.
   허용 창이 떠 있는 동안에는 다른 연결을 열지 않는다)
3. 한 번에 모두 부여: 알림 접근 · USB/SD 읽기 · 알림 표시 · 백그라운드 재생 허용
4. 새 연결로 '항상 허용'이 저장됐는지 확인 → 결과 표시

설치 후 한 번만 자동으로 묻고, '지금 분할 실행' 때 승인 전이면 바로 권한 설정으로 이어진다.
허용 창이 닫히면(앱 화면 복귀) 2초 뒤 바로 새 연결로 확인. 진단 기록은 `Download/D-Player/logs/` 에 자동 저장 (`adb/AdbDiag.kt`).

이후 **화면 분할 · 자동 업데이트 · 저장소 권한은 허용 창 없이** 같은 키로 조용히 동작한다
(운전 중 허용 창이 갑자기 뜨지 않도록 이들은 허용 창을 띄우지 않으며, 키가 등록돼 있지 않으면 '권한 다시 설정'을 안내).

실패하면 [다시 시도] 버튼과 원인이 표시되고, 안내 문구를 탭하면 연결 로그를 볼 수 있습니다.

- **5555 접속 불가**: 차량 ADB가 TCP 모드가 아님 → PC로 한 번 `adb tcpip 5555` (Dolphin Control 에서 이미 했다면 그대로 동작)
- 최후 수단(PC):

```bash
adb shell cmd notification allow_listener com.dolphin.mplayer/.MediaListenerService
```

## 동작 방식

| 기능 | 구현 |
|---|---|
| 곡 정보 | `MediaController.metadata` / `playbackState` 콜백 |
| 재생·일시정지·이전·다음 | `TransportControls` (세션이 없으면 미디어 키 전송 → 마지막 재생 앱이 반응) |
| 정지 | 앱이 STOP 을 지원하면 `stop()`, 아니면 일시정지 + 처음으로 |
| 셔플 | 앱의 셔플 커스텀 액션 → 없으면 AndroidX 표준 셔플 명령 |
| 목록 | 재생 앱의 MediaBrowser 라이브러리 → 막혀 있으면 재생 대기열 → 재생 중인 앱이 없으면 음악 앱 목록(탭하면 실행) |
| 재생 앱 전환 | 오른쪽 위 ⇄ 버튼 |

## 화면

- **배치 (0.16~)**: [리스트 창] [플레이 화면]. 운전석(왼쪽)에서 손이 닿기 쉽게 리스트를 왼쪽에 둔다.
  재생 버튼 아래 **재생 목록**(지금 재생 중인 목록, 곡을 누르면 이동) / **라이브러리** 버튼. 같은 버튼을 다시 누르면 닫힘.
  리스트 헤더를 누르면 라이브러리 ↔ 재생 목록, ← / Back 은 폴더 안이면 한 단계 위로.
  재생목록(폴더) 안에는 **전체 재생 (셔플)**. 리스트 창을 건드리지 않으면 설정한 시간(5~30초, 기본 10초) 뒤 재생 화면으로 자동 복귀.

- **전체화면**: 평소엔 플레이 화면만 크게 표시. 오른쪽 위 **노래 리스트 버튼**으로 리스트 창 열기/닫기.
  리스트는 재생 대기열(노래 목록)을 먼저 보여주고, 대기열이 없으면 앱 라이브러리를 보여줌. 리스트 헤더를 탭하면 대기열 ↔ 라이브러리 전환.
- **실행 시 화면 분할**(설정 → 실행 시 화면 분할): 분할하지 않음 / 5:5 / 7:3, 왼쪽 앱·오른쪽 앱(기본 D-Player) 선택.
  앱 아이콘으로 열면 차량 ADB(shell 권한)로 `app_process` 에서 `shell/SplitTool` 을 돌려
  ① 기존 분할 풀기(`dismissSplitScreenMode`) ② 왼쪽 앱 실행 후 그 작업을 분할 왼쪽 창으로(`setTaskWindowingModeSplitScreenPrimary`,
  이미 실행 중인 앱도 분할됨) ③ 오른쪽 앱을 `am start --windowingMode 4` ④ `resizeDockedStack` 으로 정확한 비율(0.7 / 0.5).
  (분할선 드래그는 차량마다 멈출 수 있는 위치가 달라 분할이 풀리기도 함) 실행 기록: 차량 `/data/local/tmp/dplayer_split.log`.
- **분할화면**(`isInMultiWindowMode` 또는 창 폭 720dp 미만): 플레이 화면만 표시. 위쪽 **라이브러리** 버튼을 누르면
  플레이 화면 자리에 노래 리스트가 뜨고, 왼쪽 위 ← 로 돌아감.
- **재생 정보 표시 (화면 아래)**(설정 → 재생, 켜기/끄기): 재생 중인 곡의 제목 · 가수를 '다른 앱 위에 표시' 창으로 하단 바 바로 위 가운데에 띄움
  (`StatusTicker`, 알림 접근 서비스 안에서 동작). 차량 상단 바(SystemUI)는 불투명하고 앱 창보다 위층이라 그 안에는 띄울 수 없음.
- 왼쪽 위 **설정**(톱니바퀴): 전체 화면 설정 — 화면 분할 · 재생(라이브러리 자동 닫기) · 계정(YouTube / Spotify 웹) · 권한 · 업데이트 · 정보.

## YouTube Music / YouTube

차량(DiLink)에는 공식 YouTube 앱이 없고 시스템 WebView 도 오래돼 Google 로그인이 막히므로,
앱 안에 **GeckoView(Firefox 엔진)** 를 넣어 공식 웹(music.youtube.com / m.youtube.com)을 돌린다.
재생은 YouTube 웹이 직접 하므로 Premium(광고 없음 등)이 그대로 적용된다.

1. **설정 → YouTube 로그인**: Google 로그인 화면에서 Premium 계정으로 1회 로그인 (로그인 정보는 앱에 저장)
2. 플레이 화면 위쪽 **YT Music**: 플레이어를 YouTube Music 으로 고르면 라이브러리에 내 재생목록(좋아요 표시한 음악 포함)이 뜬다 → 재생목록 → 곡 또는 **전체 재생**
   재생은 화면 없이 뒤에서 되고, 곡 정보·커버·진행바는 **왼쪽 플레이 화면**에 표시. 버튼·핸들 미디어 버튼으로 제어
3. 플레이 화면 위쪽 **YouTube**: YouTube 를 전체화면으로 연다 (← 또는 뒤로 가기로 닫기)

플레이 화면 왼쪽 위 아이콘 = 플레이어 선택: **Spotify**(차량 기본 앱. 꺼져 있으면 열어서 재생 시작) · **YouTube Music** · **YouTube**(전체화면).
선택된 플레이어는 아이콘에 흰 링. 고른 플레이어는 고정되고, 다른 앱이 새로 재생을 시작하면 그 앱으로 자동 전환.

라이브러리는 항상 **지금 플레이어**의 목록: YT Music 이면 내 재생목록, 다른 앱(⇄ 로 선택하거나 그 앱이 재생을 시작하면 자동 전환)이면 그 앱의 대기열/라이브러리.

구조 (`youtube/`):
- `YtEngine.kt`: GeckoRuntime + 페이지 2개(MUSIC: 화면 없이 목록·재생, VIDEO: 전체화면). 웹의 `navigator.mediaSession` 을
  Android MediaSession 으로 내보냄 → 기존 플레이 화면/MediaController 경로를 그대로 사용
- `YtService.kt`: 백그라운드 재생 유지용 포그라운드 서비스 + 알림
- `assets/dplayer_ext/`: 내장 확장. 페이지를 항상 '보이는 상태'로 유지(백그라운드 재생), 로그인 상태·재생목록·곡 목록을
  로그인된 페이지 자신의 데이터 요청으로 가져와 앱에 전달, 셔플 등 명령 전달

주의: YouTube 웹 구조가 바뀌면 목록 불러오기가 깨질 수 있다 (`content.js` 의 렌더러 이름 탐색 부분을 고치면 됨).
Google 이 앱 내장 브라우저 정책을 바꾸면 로그인이 다시 막힐 수 있다.

## Spotify 라이브러리

1. 먼저 Spotify 앱의 라이브러리(MediaBrowser)를 읽는다 (차량 Spotify 는 보통 공개)
2. 앱이 거절하면(휴대폰 Spotify 등) D-Player 안의 Firefox 엔진으로 **Spotify 웹(open.spotify.com)에 한 번 로그인**해
   내 플레이리스트·곡 목록을 읽는다 (`assets/dplayer_ext/spotify.js`, 웹 플레이어 자신의 인증 토큰으로 Web API 조회)
3. 재생은 Spotify 앱에 `playFromUri("spotify:playlist:…")` 명령 → 소리·재생 정보는 Spotify 앱 그대로

## USB / MicroSD (`local/`)

위쪽 **USB 아이콘**: USB·SD 카드의 음악·영상을 D-Player 가 직접 재생 (ExoPlayer — GeckoView 에 이미 포함돼 크기 증가 거의 없음).

- 라이브러리: 저장소(USB, SD 카드, 내부 저장소) → 폴더 → 파일. 폴더마다 **전체 재생 (셔플)**
- 음악은 MediaSession(태그 `D-Player Local`)으로 내보내 플레이 화면·재생 목록·핸들 버튼이 그대로 동작
- 영상은 `VideoActivity` 전체화면 (원래 비율, -10/+10초, 진행바). 나가면 일시정지
- 찾기: Android 미디어 목록(MediaStore) → 없으면 이동식 저장소 폴더를 직접 훑음. 목록이 비었을 때 탭하면 다시 찾기
- 권한: 저장소(미디어) 읽기. 권한 창이 막히면 목록을 탭해 차량 ADB 로 부여 (알림 접근 자동 설정 때도 함께 부여)
- D-Player 안의 두 재생(YouTube / USB)은 MediaSession 태그로 구분 (`MainActivity.key()`)

## 자동 업데이트 (GitHub Releases)

앱은 https://github.com/KyuYeubKim/Dolphinplayer 의 `releases/latest` 를 확인해 더 높은 버전이면 안내하고, 기기 CPU 에 맞는
APK 를 받아 **차량 ADB 로 조용히 설치**(알림 접근 때 허용된 키 재사용) → 실패하면 표준 설치 화면을 띄운다.
공개 저장소라 앱에는 토큰이 들어가지 않는다.

새 버전 배포 (PC):

1. `app/build.gradle.kts` 의 `versionCode`/`versionName` 올리기
2. 위의 빌드 명령 → `dist/D-Player-<버전>-arm64.apk`, `dist/D-Player-<버전>-armv7.apk` 로 복사
3. (선택) `dist/notes-<버전>.txt` 에 변경 내용 작성
4. 업로드:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\release.ps1
```

토큰은 `scripts/token.txt` (또는 `GH_TOKEN`) 에서 읽는다. 이 파일은 공유하거나 커밋하지 말 것 (`.gitignore` 에 포함).

### 한계

- **셔플**: 표준 MediaSession 에는 셔플 API 가 없어 앱마다 지원 여부가 다릅니다. 셔플 상태도 앱에서 읽어올 수 없어 이 앱에서 누른 상태만 표시합니다.
- **라이브러리**: Spotify 등 일부 앱은 허용된 앱(Android Auto 등)에만 라이브러리를 공개합니다. 이 경우 대기열이 보이거나 목록이 비어 있습니다.

## 이용 안내 및 면책 고지

앱 처음 실행 시 동의 창으로 표시되며, 설정 → "이용 안내 및 면책 고지"에서 다시 볼 수 있다 (원문: `app/src/main/res/raw/disclaimer.txt`).

D-Player(이하 "앱")는 개인이 만들어 무료로 제공하는 비공식 앱입니다. 앱을 설치하거나 사용하면 아래 내용에 동의한 것으로 봅니다.

1. 있는 그대로 제공
앱은 "있는 그대로(AS IS)" 제공되며, 정상 동작이나 특정 목적에 맞는다는 점을 포함해 어떠한 보증도 하지 않습니다. 앱은 예고 없이 바뀌거나 중단될 수 있습니다.

2. 안전 운전은 운전자 책임
운전 중에는 항상 안전 운전과 교통 법규 준수를 가장 먼저 생각해야 합니다. 앱 조작이나 화면 확인으로 생기는 사고와 법규 위반의 책임은 전적으로 사용자에게 있습니다. 영상은 반드시 정차 중에만 보시고, 운전 중 조작은 최소화하세요.

3. 차량 및 기기
앱은 차량 제조사(BYD 등)의 공식 앱이 아니며, 제조사의 승인이나 보증을 받지 않았습니다. 앱 사용(ADB 권한 설정, 앱 설치·업데이트 포함)으로 생길 수 있는 차량·기기의 오작동, 데이터 손실, 보증 제한 등에 대해 개발자는 책임지지 않습니다.

4. 외부 서비스
Spotify, YouTube, YouTube Music 등은 각 회사의 서비스이며, 앱은 이 회사들과 관련이 없습니다. 각 상표는 해당 회사의 소유입니다. 외부 서비스의 이용 약관을 지키는 것과 계정 관리(로그인 정보, 이용 제한 등)는 사용자 책임입니다. 외부 서비스가 바뀌면 일부 기능이 동작하지 않을 수 있습니다.

5. 개인정보
앱은 사용자의 계정 정보와 개인정보를 개발자에게 보내지 않습니다. 로그인 정보는 사용자의 기기 안에만 저장됩니다.

6. 책임의 제한
법이 허용하는 최대 범위에서, 개발자는 앱 사용 또는 사용하지 못한 것으로 생긴 직접적·간접적 손해(사고, 재산 피해, 데이터 손실, 계정 문제 등)에 대해 책임지지 않습니다.

7. 동의
위 내용에 동의하지 않으면 앱을 사용하지 마세요.
