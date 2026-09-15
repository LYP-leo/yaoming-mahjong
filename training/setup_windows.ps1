[CmdletBinding()]
param(
    [string]$PythonExecutable = 'python',
    [switch]$CpuOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Resolve-ApplicationPath {
    param([string]$Name)
    # With -CommandType Application, Get-Command can return every PATH match.
    # Select one before reading Source: an array becomes a space-joined command
    # when passed to a string parameter. Respect the active environment's order.
    $command = Get-Command -Name $Name -CommandType Application -ErrorAction Stop | Select-Object -First 1
    return [string]$command.Source
}

function Invoke-Checked {
    param([string]$FilePath, [string[]]$ArgumentList)
    $nativePath = Resolve-ApplicationPath -Name $FilePath
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $nativePath @ArgumentList
        $nativeExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $savedPreference
    }
    if ($nativeExitCode -ne 0) {
        throw "Command failed (exit $nativeExitCode): $FilePath $($ArgumentList -join ' ')"
    }
}

function Get-CheckedOutput {
    param([string]$FilePath, [string[]]$ArgumentList)
    $nativePath = Resolve-ApplicationPath -Name $FilePath
    # Windows PowerShell 5.1 represents native stderr as ErrorRecords. Capture
    # it without confusing a successful Java version query with a failure.
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $captured = @(& $nativePath @ArgumentList 2>&1)
        $nativeExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $savedPreference
    }
    if ($nativeExitCode -ne 0) {
        throw "Command failed (exit $nativeExitCode): $FilePath`n$($captured -join [Environment]::NewLine)"
    }
    return ($captured -join [Environment]::NewLine)
}

$identityCode = @'
import json, os, platform, struct, sys
print(json.dumps({
    "version": list(sys.version_info[:3]),
    "implementation": platform.python_implementation(),
    "bits": struct.calcsize("P") * 8,
    "base_executable": os.path.normcase(os.path.realpath(getattr(sys, "_base_executable", sys.executable))),
    "is_venv": sys.prefix != sys.base_prefix
}))
'@

try {
    if ($env:OS -ne 'Windows_NT') {
        throw 'This installer is for Windows. Follow INSTALL_WINDOWS.md or install dependencies manually on your platform.'
    }
    $trainingDirectory = [IO.Path]::GetFullPath($PSScriptRoot)
    $requirementsPath = Join-Path $trainingDirectory 'requirements.txt'
    $simulatorPath = Join-Path $trainingDirectory 'simulator/simulator.jar'
    if (-not (Test-Path -LiteralPath $requirementsPath -PathType Leaf)) {
        throw "Missing $requirementsPath. Extract the complete training package first."
    }
    if (-not (Test-Path -LiteralPath $simulatorPath -PathType Leaf)) {
        throw "Missing $simulatorPath. Use the complete portable package, or build it with build_simulator.py before installation."
    }

    $selectedPython = Resolve-ApplicationPath -Name $PythonExecutable
    $selectedIdentity = (Get-CheckedOutput -FilePath $selectedPython -ArgumentList @('-c', $identityCode)) | ConvertFrom-Json
    if ($selectedIdentity.implementation -ne 'CPython' -or $selectedIdentity.bits -ne 64) {
        throw 'Use 64-bit CPython. Python 3.11 is recommended.'
    }
    if ($selectedIdentity.version[0] -ne 3 -or $selectedIdentity.version[1] -lt 11 -or $selectedIdentity.version[1] -gt 13) {
        throw 'This pinned dependency set supports Python 3.11-3.13. Activate a Python 3.11 environment, or pass -PythonExecutable with its python.exe path.'
    }
    Write-Host "Selected Python: $selectedPython ($($selectedIdentity.version -join '.'))"

    # Match the simulator bridge's JAVA_HOME-first lookup exactly.
    if ($env:JAVA_HOME) {
        $selectedJava = Join-Path $env:JAVA_HOME 'bin/java.exe'
        if (-not (Test-Path -LiteralPath $selectedJava -PathType Leaf)) {
            throw "JAVA_HOME does not contain a Java runtime: $selectedJava"
        }
    }
    else {
        $selectedJava = Resolve-ApplicationPath -Name 'java'
    }
    $javaVersion = Get-CheckedOutput -FilePath $selectedJava -ArgumentList @('-version')
    if ($javaVersion -notmatch '(?m)(?:openjdk|java) version "(?<major>\d+)') {
        throw "Cannot identify Java version. Install a Java 21 runtime and put its bin directory on PATH.`n$javaVersion"
    }
    if ([int]$Matches.major -lt 21) {
        throw "Java 21 or later is required. Detected:`n$javaVersion"
    }
    Write-Host "Java runtime: $selectedJava"

    $venvDirectory = Join-Path $trainingDirectory '.venv'
    $venvPython = Join-Path $venvDirectory 'Scripts/python.exe'
    if (Test-Path -LiteralPath $venvDirectory) {
        if (-not (Test-Path -LiteralPath $venvPython -PathType Leaf)) {
            throw "Existing $venvDirectory is not a usable Windows virtual environment. Nothing was deleted. Move it aside manually, or extract the package into a fresh directory."
        }
        $venvIdentity = (Get-CheckedOutput -FilePath $venvPython -ArgumentList @('-c', $identityCode)) | ConvertFrom-Json
        if (-not $venvIdentity.is_venv -or $venvIdentity.base_executable -ne $selectedIdentity.base_executable -or ($venvIdentity.version -join '.') -ne ($selectedIdentity.version -join '.')) {
            throw "Existing .venv was created with a different Python interpreter. Nothing was deleted or installed. Select its original interpreter, or use a fresh extraction directory."
        }
        Write-Host 'Reusing .venv with the same base Python interpreter.'
    }
    else {
        Invoke-Checked -FilePath $selectedPython -ArgumentList @('-m', 'venv', $venvDirectory)
    }

    $torchFlavor = if ($CpuOnly) { 'cpu' } else { 'cu126' }
    $device = if ($CpuOnly) { 'cpu' } else { 'cuda' }
    $torchIndex = "https://download.pytorch.org/whl/$torchFlavor"
    Push-Location -LiteralPath $trainingDirectory
    try {
        # Ignore user pip target/prefix settings so dependencies stay in .venv.
        Invoke-Checked -FilePath $venvPython -ArgumentList @('-m', 'pip', '--isolated', 'install', '--upgrade', 'pip')
        # The local build suffix makes CPU/CUDA selection explicit on reruns.
        Invoke-Checked -FilePath $venvPython -ArgumentList @('-m', 'pip', '--isolated', 'install', "torch==2.9.1+$torchFlavor", '--index-url', $torchIndex)
        Invoke-Checked -FilePath $venvPython -ArgumentList @('-m', 'pip', '--isolated', 'install', '-r', $requirementsPath)
        Invoke-Checked -FilePath $venvPython -ArgumentList @('-m', 'pip', '--isolated', 'check')
        Invoke-Checked -FilePath $venvPython -ArgumentList @('-m', 'yaoming_rl.doctor', '--device', $device)
    }
    finally {
        Pop-Location
    }
    Write-Host ''
    Write-Host 'Setup and device checks completed.' -ForegroundColor Green
    Write-Host "From $trainingDirectory, run commands with .\.venv\Scripts\python.exe"
    Write-Host 'See INSTALL_WINDOWS.md for smoke training, evaluation, and resume commands.'
}
catch {
    Write-Host "Setup stopped: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host 'No existing virtual environment was deleted. Correct the error and rerun this script.'
    exit 1
}
