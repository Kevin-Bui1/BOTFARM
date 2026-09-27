param([ValidateSet('preserve','compile','run')][string]$Mode)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-26.0.1'
$maven = 'D:\Tools\apache-maven-3.9.16\bin\mvn.cmd'
if ($Mode -eq 'preserve') {
    $expected = 'C417E8B865C6647E4B68B64E503FEFE55C9C578A761F82B923AF1A322DA8A38C'
    $hasher = [Security.Cryptography.SHA256]::Create()
    try {
        $actual = [BitConverter]::ToString($hasher.ComputeHash([IO.File]::ReadAllBytes("$PSScriptRoot\src\main\java\FirstWorker.java"))).Replace('-', '')
        $control = [BitConverter]::ToString($hasher.ComputeHash([Text.Encoding]::UTF8.GetBytes('changed source control'))).Replace('-', '')
    } finally { $hasher.Dispose() }
    if ($control -eq $expected) { throw 'Preservation negative control failed' }
    if ($actual -ne $expected) { throw 'DERMA source changed' }
    [xml]$pom = Get-Content pom.xml
    if ($pom.project.properties.'exec.mainClass' -ne 'FirstWorker') { throw 'DERMA default changed' }
    'DERMA_PRESERVED'
} elseif ($Mode -eq 'compile') {
    & $maven '-Dmaven.repo.local=D:\BOTFARM\.m2\repository' -B compile
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    foreach ($worker in @('FirstWorker','TikTokUserWorker')) {
        if (!(Test-Path "target\classes\$worker.class")) { throw "Missing compiled $worker" }
    }
    'WORKERS_COMPILED'
} else {
    if ($env:TIKTOK_USERNAME -cnotmatch '^[A-Za-z0-9._]{1,24}$') { throw 'Set TIKTOK_USERNAME to a username without the @ prefix' }
    $runStart = Get-Date
    & $maven '-Dmaven.repo.local=D:\BOTFARM\.m2\repository' '-Dexec.mainClass=TikTokUserWorker' -B compile exec:java 2>&1 | Tee-Object tiktok-run.log
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    if (!(Select-String -Path tiktok-run.log -SimpleMatch "PASS: opened TikTok profile with exact handle @$env:TIKTOK_USERNAME")) { throw 'No profile PASS' }
    $shot = Get-Item tiktok-user.png
    if ($shot.LastWriteTime -lt $runStart -or $shot.Length -lt 100) { throw 'No fresh screenshot' }
    'TIKTOK_PROFILE_VERIFIED'
}
