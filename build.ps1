$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path

function Resolve-Tool($name, $subDirs) {
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    if ($env:JAVA_HOME) {
        $cand = Join-Path $env:JAVA_HOME "bin\$name.exe"
        if (Test-Path $cand) { return $cand }
    }
    foreach ($dir in $subDirs) {
        if (Test-Path $dir) {
            $match = Get-ChildItem -Path $dir -Directory -Filter "jdk*" | Sort-Object Name -Descending | Select-Object -First 1
            if ($match) {
                $cand = Join-Path $match.FullName "bin\$name.exe"
                if (Test-Path $cand) { return $cand }
            }
        }
    }
    throw "$name not found. Ensure a JDK is installed on PATH or set JAVA_HOME."
}

$javac = Resolve-Tool "javac" @("C:\Program Files\Java")
$jar   = Resolve-Tool "jar"   @("C:\Program Files\Java")

$logisim = $env:LOGISIM_EXE
if (-not $logisim) {
    $candidates = @(
        (Join-Path $root "logisim-win-2.7.1.exe"),
        (Join-Path $root "logisim-generic-2.7.1.jar"),
        (Join-Path $HOME "Desktop\All\logisim-win-2.7.1.exe"),
        (Join-Path $HOME "Desktop\logisim-win-2.7.1.exe"),
        (Join-Path $HOME "Downloads\logisim-win-2.7.1.exe")
    )
    $logisim = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $logisim -or -not (Test-Path $logisim)) {
    throw "Logisim 2.7.1 executable/jar not found. Set `$env:LOGISIM_EXE before running build.ps1."
}

$buildDir = Join-Path $root "java\build"
$outJar   = Join-Path $root "logisim-mcp-bridge.jar"
$manifest = Join-Path $root "java\MANIFEST.MF"
$srcDir   = Join-Path $root "java\src\logisimmcp"

if (Test-Path $buildDir) { Remove-Item -Recurse -Force $buildDir }
New-Item -ItemType Directory -Force -Path $buildDir | Out-Null

$sources = Get-ChildItem -Path $srcDir -Filter "*.java" | ForEach-Object { $_.FullName }
if ($sources.Count -eq 0) { throw "No Java sources found in $srcDir" }

Write-Host "Compiling $($sources.Count) Java sources (--release 8)..."
& $javac -Xlint:-options --release 8 -encoding UTF-8 -cp $logisim -d $buildDir $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

Write-Host "Packaging $outJar..."
& $jar cfm $outJar $manifest -C $buildDir .
if ($LASTEXITCODE -ne 0) { throw "jar failed with exit code $LASTEXITCODE" }

Write-Host "Build succeeded: $outJar"
