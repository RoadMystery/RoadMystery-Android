<#
.SYNOPSIS
  STT 테스트용 음성 파일을 만든다.

.DESCRIPTION
  voice-eval의 질문 목록(probes)과 추가 음성 명령을 Windows 한국어 음성(Heami)으로 읽어
  Pleos SpeechToText.sendAudio()가 받는 형식(16kHz, 16bit, mono PCM)으로 저장한다.
  결과는 app/src/main/assets/stt/ 아래 *.pcm 파일과 정답 목록 cases.json이다.

  합성 음성은 실제 차 안의 사람 목소리보다 깨끗하므로, 여기서 나온 인식률은 상한으로 본다.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\make-stt-fixtures.ps1
#>
param(
    [string]$ProbesPath = (Join-Path $PSScriptRoot "..\..\..\tools\voice-eval\probes\butler.json"),
    [string]$OutDir = (Join-Path $PSScriptRoot "..\app\src\main\assets\stt"),
    [string]$Voice = "Microsoft Heami Desktop"
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Speech

# 질문 목록 외에 F-06(수사 수첩), F-10(멈춰), 다른 용의자 지목 명령도 함께 확인한다.
$extraUtterances = [ordered]@{
    "cmd-summary"      = "지금까지 정리해줘"
    "cmd-stop"         = "멈춰"
    "ask-cook"         = "요리사한테 정전 때 어디 있었는지 물어봐"
    "ask-niece-pawn"   = "서진 씨한테 전당포 영수증 얘기 물어봐"
}
# 일부러 틀리게 쓴 문장(stt_noise)은 합성 음성의 정답으로 쓸 수 없으므로 뺀다.
$skipCategories = @("stt_noise")

function Get-DataChunk([byte[]]$wav) {
    $position = 12
    while ($position -lt $wav.Length - 8) {
        $chunkId = [Text.Encoding]::ASCII.GetString($wav, $position, 4)
        $chunkSize = [BitConverter]::ToInt32($wav, $position + 4)
        if ($chunkId -eq "data") {
            $pcm = New-Object byte[] $chunkSize
            [Array]::Copy($wav, $position + 8, $pcm, 0, $chunkSize)
            return , $pcm
        }
        $position += 8 + $chunkSize
    }
    throw "WAV 파일에 data 청크가 없습니다."
}

$utterances = [ordered]@{}
$probes = (Get-Content $ProbesPath -Raw -Encoding UTF8 | ConvertFrom-Json).probes
foreach ($probe in $probes) {
    if ($skipCategories -notcontains $probe.category) {
        $utterances[$probe.id] = $probe.question
    }
}
foreach ($key in $extraUtterances.Keys) {
    $utterances[$key] = $extraUtterances[$key]
}

New-Item -ItemType Directory -Force $OutDir | Out-Null
$format = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(
    16000,
    [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,
    [System.Speech.AudioFormat.AudioChannel]::Mono
)
$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
$synth.SelectVoice($Voice)

$cases = @()
try {
    foreach ($id in $utterances.Keys) {
        $wavPath = Join-Path ([IO.Path]::GetTempPath()) "stt-fixture-$id.wav"
        $synth.SetOutputToWaveFile($wavPath, $format)
        $synth.Speak($utterances[$id])
        $synth.SetOutputToNull()

        $pcm = Get-DataChunk ([IO.File]::ReadAllBytes($wavPath))
        Remove-Item $wavPath
        [IO.File]::WriteAllBytes((Join-Path $OutDir "$id.pcm"), $pcm)
        $cases += [ordered]@{ id = $id; file = "stt/$id.pcm"; expected = $utterances[$id] }
        Write-Output ("{0,-28} {1,6:N1}s  {2}" -f $id, ($pcm.Length / 32000), $utterances[$id])
    }
}
finally {
    $synth.Dispose()
}

# Android의 org.json이 BOM을 읽지 못하므로 BOM 없는 UTF-8로 쓴다.
$json = ConvertTo-Json -InputObject $cases -Depth 3
[IO.File]::WriteAllText((Join-Path $OutDir "cases.json"), $json, (New-Object Text.UTF8Encoding $false))
Write-Output "저장: $OutDir ($($cases.Count)개)"
