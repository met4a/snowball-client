<#
.SYNOPSIS
  Checks the Authenticode signature on everything in release/ before it is published.

.DESCRIPTION
  Run after `npm run dist`. Every installer and portable executable is inspected and the result
  printed. The exit code is what matters:

    0  every artefact is Valid, or SNOWBALL_ALLOW_UNSIGNED=1 and every artefact is NotSigned
    1  something is signed badly, tampered with, or signed by an unexpected publisher

  A build that is merely unsigned is not silently treated as fine: it has to be allowed
  explicitly, so an unsigned artefact can never reach a release by accident.

  Snowball has no code-signing certificate yet (SignPath Foundation approval is pending), so
  local builds are expected to report NotSigned. Set SNOWBALL_ALLOW_UNSIGNED=1 for those.
#>

$ErrorActionPreference = 'Stop'
$release = Join-Path $PSScriptRoot '..\release'
$expectedPublisher = 'Snowball Client'
$allowUnsigned = $env:SNOWBALL_ALLOW_UNSIGNED -eq '1'

if (-not (Test-Path $release)) {
  Write-Host "No release folder at $release - run 'npm run dist' first." -ForegroundColor Yellow
  exit 1
}

$artefacts = Get-ChildItem -Path $release -Include *.exe, *.appx -Recurse -File
if ($artefacts.Count -eq 0) {
  Write-Host "No .exe or .appx artefacts found in $release." -ForegroundColor Yellow
  exit 1
}

$bad = 0
$unsigned = 0

foreach ($file in $artefacts) {
  $sig = Get-AuthenticodeSignature -FilePath $file.FullName
  $subject = if ($sig.SignerCertificate) { $sig.SignerCertificate.Subject } else { '(none)' }

  switch ($sig.Status) {
    'Valid' {
      # A valid signature from the wrong publisher is worse than no signature at all.
      if ($sig.SignerCertificate.Subject -notmatch [regex]::Escape($expectedPublisher)) {
        Write-Host "MISMATCH  $($file.Name)" -ForegroundColor Red
        Write-Host "          signed by $subject, expected CN to contain '$expectedPublisher'"
        $bad++
      } else {
        $thumb = $sig.SignerCertificate.Thumbprint
        Write-Host "SIGNED    $($file.Name)" -ForegroundColor Green
        Write-Host "          $subject"
        Write-Host "          thumbprint $thumb"
      }
    }
    'NotSigned' {
      Write-Host "UNSIGNED  $($file.Name)" -ForegroundColor Yellow
      $unsigned++
    }
    default {
      Write-Host "INVALID   $($file.Name) - $($sig.Status): $($sig.StatusMessage)" -ForegroundColor Red
      $bad++
    }
  }
}

Write-Host ''
Write-Host "Checked $($artefacts.Count) artefact(s): $bad bad, $unsigned unsigned."

if ($bad -gt 0) {
  Write-Host 'Refusing to publish: fix the signatures above.' -ForegroundColor Red
  exit 1
}
if ($unsigned -gt 0 -and -not $allowUnsigned) {
  Write-Host 'Refusing to publish unsigned artefacts. Set SNOWBALL_ALLOW_UNSIGNED=1 if that is intended.' -ForegroundColor Red
  exit 1
}
Write-Host 'All good.' -ForegroundColor Green
exit 0
