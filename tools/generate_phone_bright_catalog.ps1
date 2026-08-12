param(
    [Parameter(Mandatory = $true)]
    [string]$HygCsvPath,
    [string]$OutputPath = "app/src/main/assets/catalog/phone_hyg_v41_m6.csv",
    [double]$MaximumMagnitude = 6.0
)

$ErrorActionPreference = 'Stop'
$source = Resolve-Path -LiteralPath $HygCsvPath
$output = Join-Path (Get-Location) $OutputPath
$outputDirectory = Split-Path -Parent $output
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null

$rows = Import-Csv -LiteralPath $source |
    Where-Object {
        $mag = 0.0
        $ra = 0.0
        $dec = 0.0
        [double]::TryParse($_.mag, [ref]$mag) -and
            [double]::TryParse($_.ra, [ref]$ra) -and
            [double]::TryParse($_.dec, [ref]$dec) -and
            $mag -ge -1.0 -and $mag -le $MaximumMagnitude
    } |
    ForEach-Object {
        [pscustomobject]@{
            raDeg = ([double]$_.ra * 15.0)
            decDeg = [double]$_.dec
            mag = [double]$_.mag
            hip = $_.hip
            name = $_.proper
        }
    } |
    Sort-Object mag, raDeg

$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add('# HYG Database v4.1 subset: -1 <= visual magnitude <= {0:F1}' -f $MaximumMagnitude)
$lines.Add('# Source: https://github.com/astronexus/HYG-Database')
$lines.Add('# License: CC BY-SA 4.0; see THIRD_PARTY_HYG.txt')
$lines.Add('ra_deg,dec_deg,mag,hip,name')
foreach ($row in $rows) {
    $name = ($row.name -replace ',', ' ' -replace '[\r\n]', ' ').Trim()
    $lines.Add(('{0:F7},{1:F7},{2:F2},{3},{4}' -f $row.raDeg, $row.decDeg, $row.mag, $row.hip, $name))
}
[System.IO.File]::WriteAllLines($output, $lines, [System.Text.UTF8Encoding]::new($false))
Write-Output "Wrote $($rows.Count) stars to $output"
