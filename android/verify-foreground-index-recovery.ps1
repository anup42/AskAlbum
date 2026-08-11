[CmdletBinding()]
param(
    [string] $Serial
)

$ErrorActionPreference = 'Stop'

$androidRoot = $PSScriptRoot
$sdkRoot = if ($env:ANDROID_HOME) {
    $env:ANDROID_HOME
} elseif ($env:ANDROID_SDK_ROOT) {
    $env:ANDROID_SDK_ROOT
} else {
    Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}
$adb = Join-Path $sdkRoot 'platform-tools\adb.exe'
$gradle = Join-Path $androidRoot 'gradlew.bat'
$fixturePackage = 'io.github.anup42.askalbum.fixture'
$receiver = "$fixturePackage/io.github.anup42.askalbum.FixtureForegroundRecoveryReceiver"
$armAction = 'io.github.anup42.askalbum.fixture.ARM_FOREGROUND_RECOVERY'
$verifyAction = 'io.github.anup42.askalbum.fixture.VERIFY_FOREGROUND_RECOVERY'
$resultPath = 'files/foreground-index-recovery-result.txt'
$fixtureApk = Join-Path $androidRoot 'app\build\outputs\apk\fixtureCi\debug\app-fixtureCi-debug.apk'

if (-not (Test-Path -LiteralPath $adb)) {
    throw "adb was not found at $adb"
}
if (-not (Test-Path -LiteralPath $gradle)) {
    throw "Gradle wrapper was not found at $gradle"
}
if ($fixturePackage -eq 'io.github.anup42.askalbum' -or -not $fixturePackage.EndsWith('.fixture')) {
    throw 'Refusing to run process-death validation against a non-fixture package'
}

function Invoke-Adb {
    param([Parameter(Mandatory)][string[]] $Arguments)

    $output = & $adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb $($Arguments -join ' ') failed:`n$($output -join "`n")"
    }
    return $output
}

function Invoke-RecoveryAction {
    param(
        [Parameter(Mandatory)][string] $Action,
        [Parameter(Mandatory)][string] $ExpectedPrefix
    )

    & $adb -s $Serial shell run-as $fixturePackage rm -f $resultPath 2>$null | Out-Null
    Invoke-Adb @('shell', 'am', 'broadcast', '--receiver-foreground', '-a', $Action, '-n', $receiver) | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    $result = ''
    do {
        Start-Sleep -Milliseconds 200
        $result = (& $adb -s $Serial shell run-as $fixturePackage cat $resultPath 2>$null) -join ''
    } while (-not $result.Trim() -and [DateTime]::UtcNow -lt $deadline)
    if ($result -match '^FAILED\|') {
        throw "Fixture recovery action failed: $result"
    }
    if (-not $result.StartsWith($ExpectedPrefix)) {
        throw "Expected $ExpectedPrefix result, received: $result"
    }
    Write-Host $result
    return $result
}

if (-not $Serial) {
    $devices = & $adb devices | Select-String "`tdevice$" | ForEach-Object {
        ($_.Line -split "`t")[0]
    }
    if ($devices.Count -ne 1) {
        throw "Expected one connected device, found $($devices.Count). Pass -Serial explicitly."
    }
    $Serial = $devices[0]
}

$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot

Push-Location $androidRoot
try {
    & $gradle :app:assembleFixtureCiDebug
    if ($LASTEXITCODE -ne 0) {
        throw 'Fixture APK build failed'
    }
    if (-not (Test-Path -LiteralPath $fixtureApk)) {
        throw 'Fixture APK was not produced'
    }

    Invoke-Adb @('install', '-r', '-d', $fixtureApk) | Write-Host
    Invoke-Adb @(
        'shell', 'am', 'start', '-W',
        '-n', "$fixturePackage/io.github.anup42.askalbum.MainActivity"
    ) | Out-Null
    $armed = Invoke-RecoveryAction -Action $armAction -ExpectedPrefix 'ARMED|'

    $fixturePid = ($armed -split '\|')[1]
    if ($fixturePid -notmatch '^\d+$') {
        throw "Fixture process was not reported by the arm phase: $armed"
    }

    Invoke-Adb @('shell', 'run-as', $fixturePackage, 'kill', '-9', $fixturePid) | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    do {
        Start-Sleep -Milliseconds 250
        $remaining = (& $adb -s $Serial shell pidof $fixturePackage 2>$null) -join ''
    } while ($remaining.Trim() -and [DateTime]::UtcNow -lt $deadline)
    if ($remaining.Trim()) {
        throw "Fixture process $fixturePid did not terminate"
    }

    Invoke-RecoveryAction -Action $verifyAction -ExpectedPrefix 'RECOVERED|' | Out-Null
    Write-Host 'PASS: foreground indexing recovery survived fixture process death.'
} finally {
    & $adb -s $Serial shell am force-stop $fixturePackage 2>$null | Out-Null
    & $adb -s $Serial uninstall $fixturePackage 2>$null | Out-Null
    Pop-Location
}
