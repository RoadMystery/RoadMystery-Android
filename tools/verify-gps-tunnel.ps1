param(
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string]$Serial = 'emulator-5554',
    [double]$RestoreLatitude = 37.421998,
    [double]$RestoreLongitude = -122.084
)
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-\d+$') { throw 'Emulator only' }
$project = Split-Path -Parent $PSScriptRoot
$route = Get-Content -LiteralPath (Join-Path $project 'docs/f09/verification/gps-route.json') -Raw | ConvertFrom-Json
$logFile = Join-Path $project 'docs/f09/verification/gps-auto-route.log'
$appPid = $null
$passed = $false
function Move-Gps($point) {
    $lon = $point.longitude.ToString([Globalization.CultureInfo]::InvariantCulture)
    $lat = $point.latitude.ToString([Globalization.CultureInfo]::InvariantCulture)
    & $Adb -s $Serial emu geo fix $lon $lat 5 8
    if ($LASTEXITCODE -ne 0) { throw 'GPS injection failed' }
}
function Read-Log {
    (& $Adb -s $Serial logcat -d "--pid=$appPid" -s F09GpsTest:I F03Speech:I TunnelLocation:D '*:S') -join "`n"
}
function Wait-Log([string]$pattern, [int]$seconds = 90) {
    $until = (Get-Date).AddSeconds($seconds)
    do {
        $text = Read-Log
        if ($text -match $pattern) { return }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $until)
    throw "Timed out: $pattern"
}
try {
    Move-Gps $route.outsideBefore
    & $Adb -s $Serial shell am start -W -S -n com.roadmystery.app/.director.GpsTunnelTestActivity
    $appPid = (& $Adb -s $Serial shell pidof com.roadmystery.app).Trim()
    if (!$appPid) { throw 'App did not start' }
    Wait-Log 'GPS_FIX PositionFix' 30
    Start-Sleep -Seconds 3
    Write-Output 'Outside GPS received; moving inside'
    Move-Gps $route.inside
    Wait-Log 'LOCATION_EVENT Place\(tag=tunnel\)'
    Write-Output 'GPS entry detected'
    Wait-Log 'SCENE tunnel_flashback'
    Wait-Log 'SCENE s0[23]_' # 본편 첫 장면 뒤 터널이 오거나, 두 번째 본편 뒤에 올 수 있음.
    # 다음 본편이 터널보다 뒤에 있는지 확인한다.
    $until = (Get-Date).AddSeconds(60)
    do {
        $text = Read-Log
        if ($text -match '(?s)SCENE tunnel_flashback.*SCENE s0[23]_') { break }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $until)
    if ($text -notmatch '(?s)SCENE tunnel_flashback.*SCENE s0[23]_') { throw 'No return to main story' }
    Write-Output 'Tunnel narration finished; moving outside'
    Move-Gps $route.outsideAfter
    Wait-Log 'LOCATION_EVENT PlaceExited\(tag=tunnel\)' 30
    $text = Read-Log
    if ([regex]::Matches($text, 'LOCATION_EVENT Place\(tag=tunnel\)').Count -ne 1) { throw 'Unexpected entry count' }
    if ([regex]::Matches($text, 'LOCATION_EVENT PlaceExited\(tag=tunnel\)').Count -ne 1) { throw 'Unexpected exit count' }
    $passed = $true
    Write-Output 'PASS: GPS entry -> tunnel -> main story -> GPS exit; entry/exit exactly once'
} finally {
    if ($appPid) {
        Read-Log | Set-Content -LiteralPath $logFile -Encoding utf8
        # 본편 복귀·이탈까지 확인된 뒤 테스트 앱의 낭독/위치 구독을 종료한다.
        # 실패 시 강제 종료로 연출 복원을 방해하지 않고 화면을 남겨 점검한다.
        if ($passed) { & $Adb -s $Serial shell am force-stop com.roadmystery.app }
    }
    Move-Gps ([pscustomobject]@{latitude=$RestoreLatitude;longitude=$RestoreLongitude})
}
