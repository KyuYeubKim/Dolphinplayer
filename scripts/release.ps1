# D-Player GitHub Release 자동 업로드
# 사용법:  powershell -ExecutionPolicy Bypass -File scripts\release.ps1
#   릴리스 노트는 dist\notes-<버전>.txt 에 적어 두면 사용 (없으면 -Notes 인자 또는 기본 문구).
#   토큰은 환경변수 GH_TOKEN 또는 scripts\token.txt 에서 읽는다 (token.txt 는 공유/커밋 금지).
#   버전은 app/build.gradle.kts 의 versionName.
#   파일: 0.04 부터 ABI 별 dist\D-Player-<버전>-arm64.apk / -armv7.apk (없으면 예전 형식 dist\D-Player-<버전>.apk).
#   앱은 releases/latest 의 tag(버전)와 자기 CPU 에 맞는 .apk 에셋으로 업데이트한다.
#   0.03 이하 앱은 이름순 첫 번째 .apk 를 받으므로 arm64 가 armv7 보다 앞에 오도록 이름을 정했다.
#   -Beta    : 시험판(Pre-release)으로 올림 → 일반 앱은 안 받고, 설정에서 '시험판(베타) 업데이트 받기'를 켠 기기만 받음 (0.45~)
#   -Promote : 이미 올린 시험판(현재 versionName 또는 -Version)을 정식판(Latest)으로 전환 → 모두에게 자동 업데이트
param([string]$Notes = "", [switch]$Beta, [switch]$Promote, [string]$Version)

$ErrorActionPreference = 'Stop'
$repo = 'KyuYeubKim/Dolphinplayer'
$root = Split-Path -Parent $PSScriptRoot

# 1) 토큰
$token = $env:GH_TOKEN
if (-not $token) {
    $tokenFile = Join-Path $PSScriptRoot 'token.txt'
    if (Test-Path $tokenFile) { $token = (Get-Content $tokenFile -Raw).Trim() }
}
if (-not $token) { Write-Error "토큰이 없습니다. scripts\token.txt 에 GitHub 토큰(repo 권한)을 저장하거나 GH_TOKEN 을 설정하세요." }

# 2) 버전 / APK
$gradle = Get-Content (Join-Path $root 'app\build.gradle.kts') -Raw
$ver = [regex]::Match($gradle, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
if ($Version) { $ver = $Version }
if (-not $ver) { Write-Error "versionName 을 build.gradle.kts 에서 찾지 못했습니다." }

if ($Promote) {
    $h = @{ Authorization = "token $token"; 'User-Agent' = 'D-Player-Release'; Accept = 'application/vnd.github+json' }
    $rel = Invoke-RestMethod -Headers $h -Method Get -Uri "https://api.github.com/repos/$repo/releases/tags/$ver"
    $body = @{ prerelease = $false; make_latest = 'true' } | ConvertTo-Json
    Invoke-RestMethod -Headers $h -Method Patch -Uri "https://api.github.com/repos/$repo/releases/$($rel.id)" -Body $body -ContentType 'application/json' | Out-Null
    Write-Host "PROMOTED $ver -> Latest"
    exit 0
}
$apks = @("arm64", "armv7") | ForEach-Object { Join-Path $root "dist\D-Player-$ver-$_.apk" } | Where-Object { Test-Path $_ }
if (-not $apks) {
    $single = Join-Path $root "dist\D-Player-$ver.apk"
    if (Test-Path $single) { $apks = @($single) }
}
if (-not $apks) { Write-Error "APK 가 없습니다: dist\D-Player-$ver-arm64.apk (먼저 빌드 후 dist 에 복사하세요)" }
# 릴리스 노트: dist\notes-<버전>.txt (UTF-8) 우선. 명령줄 한글은 인코딩이 깨질 수 있다.
$notesFile = Join-Path $root "dist\notes-$ver.txt"
if (Test-Path $notesFile) { $Notes = [IO.File]::ReadAllText($notesFile, [Text.Encoding]::UTF8).Trim() }
if (-not $Notes) { $Notes = "D-Player $ver" }
Write-Host "릴리스: $repo  tag=$ver  apk=$($apks -join ', ')"

$headers = @{ Authorization = "token $token"; 'User-Agent' = 'D-Player-Release'; Accept = 'application/vnd.github+json' }
$api = "https://api.github.com/repos/$repo"

# 3) 빈 저장소면 태그를 달 커밋이 없으므로 README 로 첫 커밋 생성
try {
    Invoke-RestMethod -Headers $headers -Method Get -Uri "$api/commits?per_page=1" | Out-Null
} catch {
    $readme = "# D-Player`n`nBYD Dolphin 분할화면용 미디어 컨트롤러. APK 는 Releases 에서 받으세요.`n"
    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($readme))
    $body = @{ message = 'Initial commit'; content = $b64 } | ConvertTo-Json
    Invoke-RestMethod -Headers $headers -Method Put -Uri "$api/contents/README.md" -Body $body -ContentType 'application/json' | Out-Null
    Write-Host "빈 저장소 → README 첫 커밋 생성"
}

# 4) 같은 태그 릴리스가 있으면 재사용, 없으면 생성
$relId = $null
try {
    $existing = Invoke-RestMethod -Headers $headers -Method Get -Uri "$api/releases/tags/$ver"
    $relId = $existing.id
    Write-Host "기존 릴리스 재사용 (id=$relId)"
    if ([bool]$Beta -ne [bool]$existing.prerelease) {
        $pb = @{ prerelease = [bool]$Beta } | ConvertTo-Json
        Invoke-RestMethod -Headers $headers -Method Patch -Uri "$api/releases/$relId" -Body $pb -ContentType 'application/json' | Out-Null
    }
} catch {
    $body = @{ tag_name = $ver; name = "D-Player $ver"; body = $Notes; draft = $false; prerelease = [bool]$Beta } | ConvertTo-Json
    $created = Invoke-RestMethod -Headers $headers -Method Post -Uri "$api/releases" -Body ([Text.Encoding]::UTF8.GetBytes($body)) -ContentType 'application/json; charset=utf-8'
    $relId = $created.id
    Write-Host "새 릴리스 생성 (id=$relId)"
}

# 5) APK 업로드 (같은 이름 에셋은 삭제 후 다시). 바이너리는 curl.exe 가 안정적
$existingAssets = @()
try { $existingAssets = Invoke-RestMethod -Headers $headers -Method Get -Uri "$api/releases/$relId/assets" } catch {}
foreach ($apk in $apks) {
    $assetName = Split-Path -Leaf $apk
    foreach ($a in $existingAssets) {
        if ($a.name -eq $assetName) {
            Invoke-RestMethod -Headers $headers -Method Delete -Uri "$api/releases/assets/$($a.id)" | Out-Null
            Write-Host "기존 에셋 삭제: $assetName"
        }
    }
    $uri = "https://uploads.github.com/repos/$repo/releases/$relId/assets?name=$assetName"
    Write-Host "업로드 중: $assetName ($([math]::Round((Get-Item $apk).Length / 1MB)) MB)"
    $out = & curl.exe -sS -X POST `
        -H "Authorization: token $token" `
        -H "User-Agent: D-Player-Release" `
        -H "Content-Type: application/vnd.android.package-archive" `
        --data-binary "@$apk" `
        $uri
    if ($out -match '"browser_download_url"\s*:\s*"([^"]+)"') {
        Write-Host "업로드 완료: $($Matches[1])"
    } else {
        Write-Error "업로드 실패 ($assetName): $out"
    }
}
Write-Host ("DONE" + $(if ($Beta) { " (BETA/pre-release)" } else { "" }))
