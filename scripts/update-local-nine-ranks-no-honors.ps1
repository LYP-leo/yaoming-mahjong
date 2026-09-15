param([Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-fA-F]{64}$')][string]$JarHash)
$ErrorActionPreference = 'Stop'
$runtimeWorkspace = 'D:\mahjong'
$runtimeBackend = 'D:\mahjong\backend'
$runtimeArtifact = 'D:\mahjong\artifacts\nine-ranks-no-honors-20260909'
$runtimeTarget = 'D:\mahjong\backend\target'
$runtimeBefore = 'D:\mahjong\artifacts\nine-ranks-no-honors-20260909\local-target-before'
$runtimeArchive = 'D:\mahjong\artifacts\nine-ranks-no-honors-20260909\local-runtime-before.tar.gz'
$runtimeDataArchive = 'D:\mahjong\artifacts\nine-ranks-no-honors-20260909\local-data-stopped.tar.gz'
$runtimeSource = 'D:\mahjong\artifacts\nine-ranks-no-honors-20260909\backend\target\mahjong-server-0.1.0.jar'
$runtimeExpectedHash = $JarHash.ToUpperInvariant()
$runtimeOldJava = 'C:\Program Files\Java\jdk-21.0.10\bin\java.exe'
$runtimeNewJava = 'C:\Program Files\Java\jdk-21.0.10\bin\java.exe'
$runtimeOldCommand = '"C:\Program Files\Java\jdk-21.0.10\bin\java.exe" -jar target/mahjong-server-0.1.0.jar'
$runtimeStopped = $false
$runtimeMoved = $false
$runtimeNewProcess = $null

function Assert-RuntimeTarget {
    $resolved = (Resolve-Path -LiteralPath $runtimeTarget).Path
    if ($resolved -ne $runtimeTarget -or -not $resolved.StartsWith($runtimeWorkspace + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Target escaped the exact workspace path.' }
    if ((Get-Item -LiteralPath $resolved).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Target is a reparse point.' }
    if ([IO.Path]::GetFullPath($runtimeBefore) -ne $runtimeBefore -or -not $runtimeBefore.StartsWith($runtimeArtifact + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Backup destination escaped the artifact directory.' }
}
function Assert-OldRuntime {
    $process = Get-CimInstance Win32_Process -Filter 'ProcessId = 37072'
    if (-not $process -or $process.ExecutablePath -ne $runtimeOldJava -or $process.CommandLine -ne $runtimeOldCommand) { throw 'PID 37072 no longer matches the verified old runtime.' }
    $owners = @(Get-NetTCPConnection -LocalPort 8080 -State Listen | Select-Object -ExpandProperty OwningProcess -Unique)
    if ($owners.Count -ne 1 -or $owners[0] -ne 37072) { throw 'Local port 8080 no longer belongs exclusively to PID 37072.' }
    $response = Invoke-WebRequest -Uri 'http://127.0.0.1:8080/api/yaoming/rooms' -UseBasicParsing -TimeoutSec 10
    if ($response.StatusCode -ne 200 -or $response.Content -notmatch '^\s*\[\s*\]\s*$') { throw 'New-version rooms are present or unavailable; runtime replacement cancelled.' }
}
function Archive-Runtime([string]$destination, [string[]]$entries) {
    & 'C:\Windows\system32\tar.exe' -czf $destination -C $runtimeBackend @entries
    if ($LASTEXITCODE -ne 0) { throw "Archive failed: $destination" }
    & 'C:\Windows\system32\tar.exe' -tzf $destination | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Archive validation failed: $destination" }
}
function Get-EndpointStatus([string]$path) {
    try { return [int](Invoke-WebRequest -Uri ('http://127.0.0.1:8080' + $path) -UseBasicParsing -TimeoutSec 3).StatusCode }
    catch { if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }; return 0 }
}
try {
    foreach ($reserved in @($runtimeBefore, $runtimeArchive, $runtimeDataArchive, "$runtimeArtifact\local-new.stdout.log", "$runtimeArtifact\local-new.stderr.log")) {
        if (Test-Path -LiteralPath $reserved) { throw "Refusing to overwrite existing runtime evidence: $reserved" }
    }
    Assert-RuntimeTarget
    Assert-OldRuntime
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $runtimeSource).Hash -ne $runtimeExpectedHash) { throw 'Tested JAR hash mismatch.' }
    if (-not (Test-Path -LiteralPath $runtimeNewJava)) { throw 'JDK 21 executable is missing.' }
    Archive-Runtime $runtimeArchive @('data', 'target')
    Assert-OldRuntime
    $oldProcess = Get-Process -Id 37072
    Stop-Process -Id 37072
    $runtimeStopped = $true
    if (-not $oldProcess.WaitForExit(15000)) { throw 'The old runtime did not stop.' }
    Archive-Runtime $runtimeDataArchive @('data')
    Assert-RuntimeTarget
    Move-Item -LiteralPath $runtimeTarget -Destination $runtimeBefore
    $runtimeMoved = $true
    New-Item -ItemType Directory -Path $runtimeTarget | Out-Null
    Copy-Item -LiteralPath $runtimeSource -Destination "$runtimeTarget\mahjong-server-0.1.0.jar"
    $installedHash = (Get-FileHash -Algorithm SHA256 -LiteralPath "$runtimeTarget\mahjong-server-0.1.0.jar").Hash
    if ($installedHash -ne $runtimeExpectedHash) { throw 'Installed JAR hash mismatch.' }
    $runtimeNewProcess = Start-Process -FilePath $runtimeNewJava -ArgumentList @('-jar', 'target/mahjong-server-0.1.0.jar') -WorkingDirectory $runtimeBackend -WindowStyle Hidden -RedirectStandardOutput "$runtimeArtifact\local-new.stdout.log" -RedirectStandardError "$runtimeArtifact\local-new.stderr.log" -PassThru
    $healthy = $false
    for ($attempt = 0; $attempt -lt 45; $attempt++) {
        if ($runtimeNewProcess.HasExited) { throw 'New JDK 21 runtime exited during startup.' }
        if ((Get-EndpointStatus '/api/yaoming/rules') -eq 200) { $healthy = $true; break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $healthy) { throw 'New runtime did not become healthy in time.' }
    $newOwners = @(Get-NetTCPConnection -LocalPort 8080 -State Listen | Select-Object -ExpandProperty OwningProcess -Unique)
    if ($newOwners.Count -ne 1 -or $newOwners[0] -ne $runtimeNewProcess.Id) { throw 'Unexpected owner of port 8080 after startup.' }
    $verifiedProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $($runtimeNewProcess.Id)"
    if ($verifiedProcess.ExecutablePath -ne $runtimeNewJava) { throw 'New runtime is not using JDK 21.' }
    $statuses = [ordered]@{}
    foreach ($path in @('/api/yaoming/rules', '/api/yaoming/rooms', '/api/rooms', '/api/rules')) { $statuses[$path] = Get-EndpointStatus $path }
    if ($statuses['/api/yaoming/rules'] -ne 200 -or $statuses['/api/yaoming/rooms'] -ne 200 -or $statuses['/api/rooms'] -ne 404 -or $statuses['/api/rules'] -ne 404) { throw ('Endpoint validation failed: ' + ($statuses | ConvertTo-Json -Compress)) }
    $roomsAfter = (Invoke-WebRequest -Uri 'http://127.0.0.1:8080/api/yaoming/rooms' -UseBasicParsing -TimeoutSec 10).Content
    [ordered]@{ success = $true; processId = $runtimeNewProcess.Id; executable = $verifiedProcess.ExecutablePath; sha256 = $installedHash; runtimeArchive = $runtimeArchive; stoppedDataArchive = $runtimeDataArchive; oldTarget = $runtimeBefore; endpoints = $statuses; rooms = $roomsAfter; stdout = "$runtimeArtifact\local-new.stdout.log"; stderr = "$runtimeArtifact\local-new.stderr.log" } | ConvertTo-Json -Depth 4
} catch {
    $failure = $_
    Write-Warning "Runtime replacement failed: $failure"
    if ($runtimeStopped) {
        if ($runtimeNewProcess -and -not $runtimeNewProcess.HasExited) {
            $processToStop = Get-CimInstance Win32_Process -Filter "ProcessId = $($runtimeNewProcess.Id)"
            if ($processToStop.ExecutablePath -ne $runtimeNewJava -or $processToStop.CommandLine -notmatch 'target/mahjong-server-0\.1\.0\.jar') { throw 'Rollback refused to stop an unrecognized process.' }
            Stop-Process -Id $runtimeNewProcess.Id
            if (-not $runtimeNewProcess.WaitForExit(15000)) { throw 'Rollback could not stop the verified new process.' }
        }
        if ($runtimeMoved) {
            if (Test-Path -LiteralPath $runtimeTarget) {
                Assert-RuntimeTarget
                $failedTarget = "$runtimeArtifact\local-target-failed-$([DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))"
                if (-not ([IO.Path]::GetFullPath($failedTarget)).StartsWith($runtimeArtifact + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Rollback target escaped the artifact directory.' }
                Move-Item -LiteralPath $runtimeTarget -Destination $failedTarget
            }
            if ((Resolve-Path -LiteralPath $runtimeBefore).Path -ne $runtimeBefore) { throw 'Rollback source path mismatch.' }
            Move-Item -LiteralPath $runtimeBefore -Destination $runtimeTarget
        }
        if (@(Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue).Count) { throw 'Rollback refused to start over an existing listener.' }
        $restoredProcess = Start-Process -FilePath $runtimeOldJava -ArgumentList @('-jar', 'target/mahjong-server-0.1.0.jar') -WorkingDirectory $runtimeBackend -WindowStyle Hidden -RedirectStandardOutput "$runtimeArtifact\local-restored.stdout.log" -RedirectStandardError "$runtimeArtifact\local-restored.stderr.log" -PassThru
        Write-Warning "Old target restored; old runtime restarted as PID $($restoredProcess.Id)."
    }
    throw $failure
}
