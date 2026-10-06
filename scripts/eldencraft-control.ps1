# Local, opt-in test controls. Launch with ELDENCRAFT_FILE_CONTROL=1 first.
param(
    [Parameter(Mandatory, Position = 0)]
    [ValidateSet('toggle_build','place','break','material1','material2','material3','material4','material5',
        'material6','material7','material8','material9','toggle_first_person','toggle_inventory','toggle_hitboxes')]
    [string]$Action
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$data = Join-Path $repo '.local\eldencraft-runtime\data'
if (-not (Test-Path -LiteralPath $data)) { throw 'Launch the native host first.' }
$command = Join-Path $data 'command.json'
$ackPath = Join-Path $data 'command-ack.json'
$sequence = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
if (Test-Path -LiteralPath $command) {
    $previous = Get-Content -LiteralPath $command -Raw | ConvertFrom-Json
    $sequence = [Math]::Max($sequence, [long]$previous.seq + 1)
}
$text = @{seq=$sequence; action=$Action} | ConvertTo-Json -Compress
[IO.File]::WriteAllText($command, $text, [Text.UTF8Encoding]::new($false))
$deadline = [DateTime]::UtcNow.AddSeconds(5)
while ([DateTime]::UtcNow -lt $deadline) {
    if (Test-Path -LiteralPath $ackPath) {
        $ack = $null
        try {
            $ack = Get-Content -LiteralPath $ackPath -Raw | ConvertFrom-Json
        } catch { } # The worker may be replacing the tiny acknowledgment file.
        if ($null -ne $ack -and $ack.seq -eq $sequence) {
            Write-Output "$Action : $($ack.status)"
            if ($ack.status -notlike 'accepted*') { throw "Control rejected: $($ack.status)" }
            return
        }
    }
    Start-Sleep -Milliseconds 50
}
throw 'No acknowledgment. Confirm the game is running with ELDENCRAFT_FILE_CONTROL=1. Check the native log before retrying; delivery is unknown.'
