param([string]$Path, [int]$Start, [int]$End)
$lines = Get-Content -LiteralPath $Path
for ($i = $Start - 1; $i -le $End - 1; $i++) {
    Write-Output ("{0}: {1}" -f ($i + 1), $lines[$i])
}
