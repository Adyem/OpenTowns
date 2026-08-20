param(
    [string]$Map = 'mountains',
    [int]$Seed = 42
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$queue = Join-Path $root 'tmp\opentowns-cli.in'
$log = Join-Path $root 'tmp\opentowns-cli.log'
New-Item -ItemType Directory -Path (Split-Path $queue) -Force | Out-Null
Set-Content -LiteralPath $queue -Value ''
Set-Content -LiteralPath $log -Value ''
$script:cliLog = $log

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $env:ComSpec
$psi.Arguments = "/c `"$(Join-Path $root 'gradlew.bat')`" run -Pcli=true -PcliAutoStart=true -PskipLauncher=true -PcliMap=$Map -Pseed=$Seed --no-daemon --console=plain"
$psi.WorkingDirectory = $root
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$process = New-Object System.Diagnostics.Process
$process.StartInfo = $psi
$process.add_OutputDataReceived({ param($sender, $event) if ($null -ne $event.Data) { Write-Host $event.Data; Add-Content -LiteralPath $script:cliLog -Value $event.Data } })
$process.add_ErrorDataReceived({ param($sender, $event) if ($null -ne $event.Data) { Write-Host $event.Data; Add-Content -LiteralPath $script:cliLog -Value $event.Data } })
[void]$process.Start()
$process.BeginOutputReadLine()
$process.BeginErrorReadLine()

Write-Host 'OpenTowns live CLI controller started.'
Write-Host (">> append commands to: " + $queue)
Write-Host ("<< responses are written to: " + $log)
Write-Host 'The game is intentionally not given any automatic commands.'

$position = 0
while (-not $process.HasExited) {
    $lines = @(Get-Content -LiteralPath $queue -ErrorAction SilentlyContinue)
    while ($position -lt $lines.Count) {
        $line = $lines[$position]
        if (-not [string]::IsNullOrWhiteSpace($line)) {
            $process.StandardInput.WriteLine($line)
            $process.StandardInput.Flush()
            Write-Host (">> " + $line)
            Add-Content -LiteralPath $log -Value (">> " + $line)
        }
        $position++
    }
    Start-Sleep -Milliseconds 250
}

$process.WaitForExit()
Write-Host ("Game process exited with code " + $process.ExitCode)
Read-Host 'Press Enter to close this controller'
