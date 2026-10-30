$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
  mvn -q -Pnative -DskipTests package
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  if (!(Test-Path 'target/lmoci.exe')) { throw 'native executable missing' }
} finally {
  Pop-Location
}
