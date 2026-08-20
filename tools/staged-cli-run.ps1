$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$log = Join-Path $root 'tmp\staged-cli-run.log'
New-Item -ItemType Directory -Path (Split-Path $log) -Force | Out-Null
Set-Content -LiteralPath $log -Value ''
$script:cliLog = $log

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $env:ComSpec
$psi.Arguments = "/c `"$(Join-Path $root 'gradlew.bat')`" run -Pcli=true -PcliAutoStart=true -PskipLauncher=true -PcliMap=mountains -Pseed=42 --no-daemon --console=plain"
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

function Send-Cli([string]$line, [int]$delay = 2) {
    Start-Sleep -Seconds $delay
    $process.StandardInput.WriteLine($line)
    $process.StandardInput.Flush()
    Write-Host (">> " + $line)
}

Start-Sleep -Seconds 25
Send-Cli 'resume'
Send-Cli 'speed up'
Send-Cli 'speed up'
Send-Cli 'speed up'
Send-Cli 'status'
Send-Cli 'livings'

# Food and dining first, all close to the starting cluster.
Send-Cli 'order create-zone zpersonal 44 132 13 50 138 13'
Send-Cli 'order create-zone zdining 51 132 13 55 137 13'
Send-Cli 'stockpile rawfood 45 142 13 50 146 13'
Send-Cli 'order custom-action qharvestapple 35 120 13 75 160 13'
Send-Cli 'order custom-action qharvestpear 35 120 13 75 160 13'
Start-Sleep -Seconds 35
Send-Cli 'stockpiles'
Send-Cli 'find item apple'
Send-Cli 'find item pear'

# Gather wood and same-floor stone before attempting any crafting.
Send-Cli 'order custom-action qchopfruittrees 35 120 13 75 160 13'
Send-Cli 'order custom-action qchop 35 120 13 75 160 13'
Send-Cli 'stockpile rawmaterials 51 142 13 56 146 13'
Start-Sleep -Seconds 40
Send-Cli 'find item rmwood'
Send-Cli 'mine-area 40 125 13 70 150 13'
Start-Sleep -Seconds 55
Send-Cli 'find item rmstone'

# Craft the dependency chain one building at a time.
Send-Cli 'order queue-place qcarpentrybench 52 140 13'
Start-Sleep -Seconds 45
Send-Cli 'buildings'
Send-Cli 'find item carpentrybench'

Send-Cli 'order queue-place qwooddetailer 53 140 13'
Start-Sleep -Seconds 45
Send-Cli 'buildings'
Send-Cli 'find item wooddetailer'

Send-Cli 'order queue-place qmasonbench 54 140 13'
Start-Sleep -Seconds 55
Send-Cli 'buildings'
Send-Cli 'find item masonbench'

Send-Cli 'order queue-place qmill 55 140 13'
Start-Sleep -Seconds 55
Send-Cli 'buildings'
Send-Cli 'find item mill'

Send-Cli 'order queue-place qbakertable 56 140 13'
Start-Sleep -Seconds 45
Send-Cli 'find item bakertable'

Send-Cli 'order queue-place qbakeroven 57 140 13'
Start-Sleep -Seconds 45
Send-Cli 'find item bakeroven'

# Production is only queued after the tools are checked above.
Send-Cli 'order custom-action qharvestwildwheat 35 120 13 75 160 13'
Send-Cli 'order queue qflour'
Start-Sleep -Seconds 65
Send-Cli 'find item flour'
Send-Cli 'order queue qbread'
Start-Sleep -Seconds 85
Send-Cli 'pause'
Send-Cli 'status'
Send-Cli 'stockpiles'
Send-Cli 'buildings'
Send-Cli 'find item bread'
Send-Cli 'find item flour'
Send-Cli 'find item apple'
Send-Cli 'find item pear'

Write-Host ("CLI run paused. Full output: " + $log)
Read-Host 'Press Enter to close this controller (the game process will remain available)'
