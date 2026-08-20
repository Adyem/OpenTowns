$ErrorActionPreference = 'Stop'

$commands = @(
    [pscustomobject]@{ Delay = 25; Line = 'resume' }
    [pscustomobject]@{ Delay = 2; Line = 'status' }
    [pscustomobject]@{ Delay = 2; Line = 'livings' }
    [pscustomobject]@{ Delay = 2; Line = 'cell 48 136 13' }
    [pscustomobject]@{ Delay = 2; Line = 'order create-zone zpersonal 44 132 13 50 138 13' }
    [pscustomobject]@{ Delay = 2; Line = 'order create-zone zdining 51 132 13 55 137 13' }
    [pscustomobject]@{ Delay = 2; Line = 'stockpile rawfood 45 142 13 50 146 13' }
    [pscustomobject]@{ Delay = 2; Line = 'stockpile rawmaterials 51 142 13 56 146 13' }
    [pscustomobject]@{ Delay = 2; Line = 'order custom-action qharvestapple 35 120 13 75 160 13' }
    [pscustomobject]@{ Delay = 2; Line = 'order custom-action qharvestpear 35 120 13 75 160 13' }
    [pscustomobject]@{ Delay = 2; Line = 'order custom-action qharvestwildwheat 35 120 13 75 160 13' }
    [pscustomobject]@{ Delay = 2; Line = 'order custom-action qchopfruittrees 35 120 13 75 160 13' }
    [pscustomobject]@{ Delay = 2; Line = 'mine-area 40 125 13 70 150 13' }
    [pscustomobject]@{ Delay = 2; Line = 'speed up' }
    [pscustomobject]@{ Delay = 2; Line = 'speed up' }
    [pscustomobject]@{ Delay = 2; Line = 'speed up' }
    [pscustomobject]@{ Delay = 25; Line = 'stockpiles' }
    [pscustomobject]@{ Delay = 2; Line = 'zones' }
    [pscustomobject]@{ Delay = 2; Line = 'find item apple' }
    [pscustomobject]@{ Delay = 2; Line = 'find item pear' }
    [pscustomobject]@{ Delay = 2; Line = 'find item wheat' }
    [pscustomobject]@{ Delay = 2; Line = 'find item rmwood' }
    [pscustomobject]@{ Delay = 2; Line = 'find item rmstone' }
    [pscustomobject]@{ Delay = 2; Line = 'order queue-place qcarpentrybench 52 140 13' }
    [pscustomobject]@{ Delay = 2; Line = 'order queue-place qwooddetailer 53 140 13' }
    [pscustomobject]@{ Delay = 2; Line = 'order queue-place qmasonbench 54 140 13' }
    [pscustomobject]@{ Delay = 2; Line = 'order queue-place qmill 55 140 13' }
    [pscustomobject]@{ Delay = 2; Line = 'order queue-place qbakertable 56 140 13' }
    [pscustomobject]@{ Delay = 2; Line = 'order queue-place qbakeroven 57 140 13' }
    [pscustomobject]@{ Delay = 2; Line = 'speed up' }
    [pscustomobject]@{ Delay = 2; Line = 'speed up' }
    [pscustomobject]@{ Delay = 2; Line = 'speed up' }
    [pscustomobject]@{ Delay = 30; Line = 'buildings' }
    [pscustomobject]@{ Delay = 2; Line = 'find item carpentrybench' }
    [pscustomobject]@{ Delay = 2; Line = 'find item wooddetailer' }
    [pscustomobject]@{ Delay = 2; Line = 'find item masonbench' }
    [pscustomobject]@{ Delay = 2; Line = 'find item mill' }
    [pscustomobject]@{ Delay = 2; Line = 'find item bakertable' }
    [pscustomobject]@{ Delay = 2; Line = 'find item bakeroven' }
    [pscustomobject]@{ Delay = 2; Line = 'order queue qflour' }
    [pscustomobject]@{ Delay = 2; Line = 'order queue qbread' }
    [pscustomobject]@{ Delay = 2; Line = 'speed up' }
    [pscustomobject]@{ Delay = 2; Line = 'speed up' }
    [pscustomobject]@{ Delay = 2; Line = 'speed up' }
    [pscustomobject]@{ Delay = 45; Line = 'pause' }
    [pscustomobject]@{ Delay = 2; Line = 'status' }
    [pscustomobject]@{ Delay = 2; Line = 'buildings' }
    [pscustomobject]@{ Delay = 2; Line = 'find item flour' }
    [pscustomobject]@{ Delay = 2; Line = 'find item bread' }
    [pscustomobject]@{ Delay = 2; Line = 'find item apple' }
    [pscustomobject]@{ Delay = 2; Line = 'find item pear' }
    [pscustomobject]@{ Delay = 2; Line = 'find item wheat' }
    [pscustomobject]@{ Delay = 2; Line = 'find item rmstone' }
)

$gradle = Join-Path $PSScriptRoot '..\gradlew.bat'
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $env:ComSpec
$psi.Arguments = "/c `"$gradle`" run -Pcli=true -PcliAutoStart=true -PskipLauncher=true -PcliMap=mountains -Pseed=42 --no-daemon --console=plain"
$psi.WorkingDirectory = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $false
$psi.RedirectStandardError = $false
$process = New-Object System.Diagnostics.Process
$process.StartInfo = $psi
[void]$process.Start()

foreach ($command in $commands) {
    Start-Sleep -Seconds $command.Delay
    $process.StandardInput.WriteLine($command.Line)
    $process.StandardInput.Flush()
}

Write-Host 'CLI command stream ended. The game is left open in its final paused state.'
Read-Host 'Press Enter to close this terminal'
