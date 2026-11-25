param([Parameter(Mandatory)][string]$KeyFile)
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
$tool = (Get-Command openssl -ErrorAction SilentlyContinue).Source
if (!$tool) { $tool = 'C:\Program Files\Git\usr\bin\openssl.exe' }
if (!(Test-Path -LiteralPath $KeyFile)) { throw 'Catalog key unavailable.' }
$tmp = Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString() + '.der')
try {
    & $tool pkey -in $KeyFile -pubout -outform DER -out $tmp
    if ($LASTEXITCODE -or ((Get-FileHash $tmp).Hash -ne (Get-FileHash app/src/main/assets/catalog.der).Hash)) { throw 'Wrong signing key.' }
    & $tool dgst -sha256 -sign $KeyFile -out catalog/servers.sig catalog/servers.json
    if ($LASTEXITCODE) { throw 'Failed to sign catalog.' }
    Copy-Item catalog/servers.json,catalog/servers.sig app/src/main/assets/ -Force
} finally { if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp } }
