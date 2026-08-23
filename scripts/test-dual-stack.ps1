<#
.SYNOPSIS
执行灵珑（LingFrame）双栈（Spring Boot 2 和 Spring Boot 3）并发集成测试。

.DESCRIPTION
此脚本将工作区（排除 target、.git 等目录）克隆到一个临时的沙箱目录中，
然后在当前目录（SB2）和沙箱目录（SB3）中并发运行 Maven 的 `verify` 验证。
它会从 Maven 日志中提取实时进度，保持终端输出整洁，并在结束时打印详细的测试执行统计和精确的失败信息。

.PARAMETER Module
指定要测试的具体模块（例如：'lingframe-core'）。这会极大加快本地迭代和验证的速度。
如果不指定，则对全量项目进行构建验证。

.PARAMETER FailFast
如果开启此参数，脚本将移除 Maven 的 '--fail-at-end' 参数。这意味着构建会在遇到第一个错误时立刻终止，而不是等待所有模块运行完毕（快速失败模式）。

.PARAMETER OpenReport
如果开启此参数，并且构建和测试全部成功，脚本会在最后自动打开生成的 Jacoco 覆盖率 HTML 报告。

.EXAMPLE
.\test-dual-stack.ps1
运行所有模块的完整双栈测试矩阵。

.EXAMPLE
.\test-dual-stack.ps1 -Module lingframe-core
仅在双栈环境下运行 lingframe-core 模块（及其依赖）的测试。

.EXAMPLE
.\test-dual-stack.ps1 -Module lingframe-core -FailFast -OpenReport
运行 core 模块测试，遇到错误立即停止；如果测试全部通过，自动在浏览器中打开 Jacoco 覆盖率测试报告。
#>
[CmdletBinding()]
param (
    [string]$Module = "",
    [switch]$FailFast,
    [switch]$OpenReport
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
$env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8"
$env:MAVEN_OPTS = "-Dfile.encoding=UTF-8"

Write-Host "========================================================" -ForegroundColor Cyan
Write-Host " Starting LingFrame Parallel Dual-Stack Verification" -ForegroundColor Green
Write-Host "========================================================" -ForegroundColor Cyan

try {
    $javaVerStr = (& java -version 2>&1) -join " "
} catch {
    Write-Error "Java not found. Please add Java 17+ to PATH."
    exit 1
}

$projectRoot = $PSScriptRoot
if (-not (Test-Path (Join-Path $projectRoot "pom.xml")) -and (Test-Path (Join-Path $projectRoot "..\pom.xml"))) {
    $projectRoot = (Resolve-Path (Join-Path $projectRoot "..")).Path
}
if (-not (Test-Path (Join-Path $projectRoot "pom.xml"))) {
    Write-Host "`n[FATAL] Cannot find root pom.xml. Please place this script in the project root or a 'scripts' subdirectory." -ForegroundColor Red
    exit 1
}
$currentDir = $projectRoot
$cloneDir = Join-Path $env:TEMP "lingframe_sb3_parallel_clone"

Write-Host "`n[1/3] Building isolated sandbox for SB3..." -ForegroundColor Yellow
$robocopyArgs = @("$currentDir", "$cloneDir", "/MIR", "/XD", ".git", ".idea", "target", "node_modules", "/XF", "*.iml", "*.log", "/NJH", "/NJS", "/NDL", "/NC", "/NS", "/NP", "/NFL")
robocopy @robocopyArgs *>$null
$roboExitCode = $LASTEXITCODE
if ($roboExitCode -ge 8) {
    Write-Host "`n[FATAL] Robocopy clone failed with ExitCode: $roboExitCode. Ensure no files are locked in $cloneDir." -ForegroundColor Red
    exit 1
}

Write-Host "[2/3] Sandbox ready at $cloneDir" -ForegroundColor Green

$logDir = Join-Path $currentDir ".test-logs"
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }
$logSb2 = Join-Path $logDir "sb2-build.log"
$logSb3 = Join-Path $logDir "sb3-build.log"
if (Test-Path $logSb2) { Remove-Item $logSb2 }
if (Test-Path $logSb3) { Remove-Item $logSb3 }

$mvnArgs = "clean verify -Pspring-boot2,integration-check -Djacoco.dest.folder=sb2"
# sb3 在独立沙箱构建，dev-mode 灵元发现写死扫 target/classes，
# 故用 -Dbc3.base.build.dir=target 让产物落在默认 target/，避免 target-boot3 导致加载不到。
$mvnArgsSb3 = "clean verify -Pspring-boot3,integration-check -Dbc3.base.build.dir=target -Djacoco.dest.folder=sb3"

if (-not $FailFast) {
    $mvnArgs += " --fail-at-end"
    $mvnArgsSb3 += " --fail-at-end"
}
if ($Module) {
    $mvnArgs += " -pl $Module -am"
    $mvnArgsSb3 += " -pl $Module -am"
    Write-Host "[3/3] Launching parallel builds for module [$Module]...`n" -ForegroundColor Yellow
} else {
    Write-Host "[3/3] Launching parallel builds for full project...`n" -ForegroundColor Yellow
}

$procSb2 = Start-Process -FilePath "cmd.exe" -ArgumentList "/c `"chcp 65001 >nul && mvn $mvnArgs > `"$logSb2`" 2>&1`"" -WorkingDirectory $currentDir -PassThru -WindowStyle Hidden
$procSb3 = Start-Process -FilePath "cmd.exe" -ArgumentList "/c `"chcp 65001 >nul && mvn $mvnArgsSb3 > `"$logSb3`" 2>&1`"" -WorkingDirectory $cloneDir -PassThru -WindowStyle Hidden

$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
$lastSb2Line = ""
$lastSb3Line = ""

while (($procSb2 -and !$procSb2.HasExited) -or ($procSb3 -and !$procSb3.HasExited)) {
    Start-Sleep -Milliseconds 1000
    
    $pattern = "\[INFO\] Building (?!jar:|war:)|\[INFO\] Running |Tests run: |\[ERROR\] "
    if (Test-Path $logSb2) { 
        $match = Get-Content $logSb2 -Tail 50 -Encoding UTF8 2>$null | Select-String -Pattern $pattern | Select-Object -Last 1
        if ($match) { 
            $sb2Line = $match.Line.Trim() -replace "`r|`n|`t", " "
            if ($sb2Line -ne $lastSb2Line) {
                $elapsed = $stopwatch.Elapsed.ToString("hh\:mm\:ss")
                Write-Host "[$elapsed] [SB2] $sb2Line" -ForegroundColor Cyan
                $lastSb2Line = $sb2Line
            }
        } 
    }
    if (Test-Path $logSb3) { 
        $match = Get-Content $logSb3 -Tail 50 -Encoding UTF8 2>$null | Select-String -Pattern $pattern | Select-Object -Last 1
        if ($match) { 
            $sb3Line = $match.Line.Trim() -replace "`r|`n|`t", " "
            if ($sb3Line -ne $lastSb3Line) {
                $elapsed = $stopwatch.Elapsed.ToString("hh\:mm\:ss")
                Write-Host "[$elapsed] [SB3] $sb3Line" -ForegroundColor Magenta
                $lastSb3Line = $sb3Line
            }
        } 
    }
}
$stopwatch.Stop()




function Print-Errors {
    param([string]$LogFile)
    Write-Host "   --- Failed Tests or Errors Extracted from Log ---" -ForegroundColor DarkRed
    if (Test-Path $LogFile) {
        $lines = Get-Content $LogFile -Encoding UTF8
        $inFailureBlock = $false
        $foundSpecificTests = $false
        
        foreach ($line in $lines) {
            if ($line -match "^\[ERROR\] Failures:\s*$|^\[ERROR\] Errors:\s*$") {
                $inFailureBlock = $true
                $foundSpecificTests = $true
                continue
            }
            if ($inFailureBlock) {
                if ($line -match "^\[INFO\]\s*$" -or $line -match "^\[ERROR\] Tests run: ") {
                    $inFailureBlock = $false
                    continue
                }
                if ($line -match "^\[ERROR\](.*)") {
                    Write-Host "   $($matches[1])" -ForegroundColor Red
                }
            }
        }
        
        if (-not $foundSpecificTests) {
            $errors = $lines | Select-String -Pattern "\[ERROR\]|FAILURE:|[Ff]ailed" -CaseSensitive:$false | Select-Object -Last 20
            if ($errors) {
                $errors | ForEach-Object { Write-Host "   $($_.Line)" -ForegroundColor Red }
            } else {
                $lines | Select-Object -Last 15 | ForEach-Object { Write-Host "   $_" -ForegroundColor Red }
            }
        }
    }
    Write-Host "   -------------------------------------------------" -ForegroundColor DarkRed
}

function Print-TestStats {
    param([string]$LogFile, [string]$MatrixName)
    
    if (-not (Test-Path $LogFile)) { return }
    
    $modules = [ordered]@{}
    $currentModule = "Unknown"
    
    Get-Content $LogFile -Encoding UTF8 | ForEach-Object {
        if ($_ -match "\[INFO\] Building (?!jar:|war:)(.*?)(?:\s+\d+\.\d+.*|\s+\[\d+/\d+\].*)?$") {
            $currentModule = $matches[1].Trim()
            if (-not $modules.Contains($currentModule)) {
                $modules[$currentModule] = @{ Run=0; Fail=0; Error=0; Skip=0; Time=0.0 }
            }
        }
        elseif ($_ -match "(?:\[INFO\]|\[ERROR\])\s*Tests run:\s*(\d+),\s*Failures:\s*(\d+),\s*Errors:\s*(\d+),\s*Skipped:\s*(\d+)\s*$") {
            if ($modules.Contains($currentModule)) {
                $modules[$currentModule].Run += [int]$matches[1]
                $modules[$currentModule].Fail += [int]$matches[2]
                $modules[$currentModule].Error += [int]$matches[3]
                $modules[$currentModule].Skip += [int]$matches[4]
            }
        }
        elseif ($_ -match "Time elapsed:\s*([\d.]+)\s*s\s*--\s*in") {
            if ($modules.Contains($currentModule)) {
                $modules[$currentModule].Time += [double]::Parse($matches[1], [cultureinfo]::InvariantCulture)
            }
        }
    }
    
    $totalRun = 0
    $totalFail = 0
    $totalError = 0
    $totalSkip = 0
    $totalTime = 0.0
    $hasTests = $false
    
    Write-Host "`n=== Test Statistics for $MatrixName ===" -ForegroundColor Cyan
    foreach ($mod in $modules.Keys) {
        $stats = $modules[$mod]
        if ($stats.Run -gt 0) {
            $hasTests = $true
            $totalRun += $stats.Run
            $totalFail += $stats.Fail
            $totalError += $stats.Error
            $totalSkip += $stats.Skip
            $totalTime += $stats.Time
            
            $color = "Green"
            if ($stats.Fail -gt 0 -or $stats.Error -gt 0) { $color = "Red" }
            Write-Host ("  {0,-38} : Run: {1,-5} Fail: {2,-3} Error: {3,-3} Skip: {4,-3} TestTime: {5,6:F2}s" -f $mod, $stats.Run, $stats.Fail, $stats.Error, $stats.Skip, $stats.Time) -ForegroundColor $color
        }
    }
    
    if ($hasTests) {
        Write-Host "  -------------------------------------------------------------------------------------------" -ForegroundColor DarkCyan
        $color = "Green"
        if ($totalFail -gt 0 -or $totalError -gt 0) { $color = "Red" }
        Write-Host ("  {0,-38} : Run: {1,-5} Fail: {2,-3} Error: {3,-3} Skip: {4,-3} TestTime: {5,6:F2}s" -f "TOTAL", $totalRun, $totalFail, $totalError, $totalSkip, $totalTime) -ForegroundColor $color
    } else {
        Write-Host "  No tests executed or unable to parse test results." -ForegroundColor DarkGray
    }
}

$failed = $false

if ($procSb2 -and $procSb2.ExitCode -eq 0) {
    Write-Host "`n[SUCCESS] SB2 Matrix Build PASSED!" -ForegroundColor Green
} elseif ($procSb2) {
    Write-Host "`n[FAIL] SB2 Matrix Build FAILED! ExitCode: $($procSb2.ExitCode)" -ForegroundColor Red
    Print-Errors $logSb2
    $failed = $true
} else {
    Write-Host "`n[FAIL] SB2 Matrix Build Process failed to start." -ForegroundColor Red
    $failed = $true
}
Print-TestStats $logSb2 "SB2 (Spring Boot 2)"

if ($procSb3 -and $procSb3.ExitCode -eq 0) {
    Write-Host "`n[SUCCESS] SB3 Matrix Build PASSED!" -ForegroundColor Green
} elseif ($procSb3) {
    Write-Host "`n[FAIL] SB3 Matrix Build FAILED! ExitCode: $($procSb3.ExitCode)" -ForegroundColor Red
    Print-Errors $logSb3
    $failed = $true
} else {
    Write-Host "`n[FAIL] SB3 Matrix Build Process failed to start." -ForegroundColor Red
    $failed = $true
}
Print-TestStats $logSb3 "SB3 (Spring Boot 3)"

if ($failed) {
    Write-Host "`n[FATAL] Parallel Dual-Stack test completed, but with errors. Please fix and rerun." -ForegroundColor Red
    exit 1
} else {
    Write-Host "`n[SUCCESS] Both matrices passed in parallel." -ForegroundColor Green
}

Write-Host "`n========================================================" -ForegroundColor Cyan
Write-Host " Total Elapsed Time: $($stopwatch.Elapsed.ToString("hh\:mm\:ss"))" -ForegroundColor Yellow
Write-Host "========================================================" -ForegroundColor Cyan

if ($OpenReport -and -not $failed) {
    $reportPath = Join-Path $currentDir "target\site\jacoco\index.html"
    if ($Module) {
        $reportPath = Join-Path $currentDir "$Module\target\site\jacoco\index.html"
    }
    if (Test-Path $reportPath) {
        Write-Host "`nOpening Jacoco Coverage Report..." -ForegroundColor Cyan
        Start-Process $reportPath
    } else {
        Write-Host "`n[WARN] Jacoco report not found at $reportPath" -ForegroundColor Yellow
    }
}

