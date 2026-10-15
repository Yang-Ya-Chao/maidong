$ErrorActionPreference = 'Stop'
$sampleDirectory = Join-Path $PSScriptRoot '../../app/src/debug/assets/decoder-samples'
New-Item -ItemType Directory -Force -Path $sampleDirectory | Out-Null
$samples = @(
    @('h264-eac3.mkv', 'https://samples.ffmpeg.org/Matroska/H264+EAC3.mkv'),
    @('vorbis-multiaudio.mkv', 'https://samples.ffmpeg.org/Matroska/vorbis-audio-switch.mkv'),
    @('multitrack.mkv', 'https://samples.ffmpeg.org/Matroska/multiple_tracks.mkv'),
    @('msmpeg4v3-subrip.mkv', 'https://samples.ffmpeg.org/Matroska/bug669-simpleblocks.mkv'),
    @('hdr10tags-both.mkv', 'https://fate-suite.ffmpeg.org/mkv/hdr10tags-both.mkv'),
    @('spherical.mkv', 'https://fate-suite.ffmpeg.org/mkv/spherical.mkv'),
    @('test7_cut.mkv', 'https://fate-suite.ffmpeg.org/mkv/test7_cut.mkv'),
    @('aac-sbr.mkv', 'https://samples.ffmpeg.org/Matroska/aac-sbr-ffaac-slow.mkv')
)
$expectedSamples = Get-Content -Raw (Join-Path $PSScriptRoot 'sample-metadata.json') | ConvertFrom-Json
foreach ($sample in $samples) {
    $targetFile = Join-Path $sampleDirectory $sample[0]
    if (-not (Test-Path -LiteralPath $targetFile)) {
        Invoke-WebRequest -Uri $sample[1] -OutFile $targetFile
    }
    $expectedSample = $expectedSamples | Where-Object name -eq $sample[0]
    $actualHash = (Get-FileHash -LiteralPath $targetFile -Algorithm SHA256).Hash
    if ($actualHash -ne $expectedSample.sha256) { throw "样本哈希不一致：$($sample[0])" }
    Write-Output "样本已核对：$($sample[0])"
}
