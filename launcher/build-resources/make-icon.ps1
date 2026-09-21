<#
.SYNOPSIS
  Draws the Snowball application icon.

.DESCRIPTION
  The old icon was the bare snowball on transparency. That is the right mark for inside the app,
  but a poor Windows icon: pale art with no silhouette disappears against a light taskbar, has no
  edge to recognise at 16px, and looks like a loose image rather than an installed program.

  This draws the snowball on a rounded tile with a deep blue face, which gives it a shape that
  reads at any size and separates it from every other icon on the taskbar. The snowball keeps the
  centre, so it is still obviously Snowball.

  Writes build-resources/icon.png (512, for electron-builder) and the multi-size
  build-resources/installer/icon.ico used by the installer and the .exe itself.

    powershell -ExecutionPolicy Bypass -File build-resources/make-icon.ps1
#>

Add-Type -AssemblyName System.Drawing
$ErrorActionPreference = 'Stop'

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Resolve-Path (Join-Path $here '..\..')
$ball = Join-Path $repo 'Snowball.png'
if (-not (Test-Path $ball)) { throw "Source artwork not found: $ball" }

$source = [System.Drawing.Bitmap]::FromFile($ball)
# The snowball's own content box inside the 865px master, so the tile centres the art and not the
# empty margin around it.
$cropX = 86; $cropY = 86; $cropSide = 690

function New-Icon([int]$size) {
  $bmp = New-Object System.Drawing.Bitmap $size, $size, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.SmoothingMode      = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
  $g.InterpolationMode  = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
  $g.PixelOffsetMode    = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
  $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality

  # Windows 11 app icons sit in a rounded square with a little breathing room around them.
  $pad    = [math]::Round($size * 0.055)
  $side   = $size - ($pad * 2)
  $radius = [math]::Round($side * 0.235)

  $tile = New-Object System.Drawing.Drawing2D.GraphicsPath
  $d = $radius * 2
  $tile.AddArc($pad, $pad, $d, $d, 180, 90)
  $tile.AddArc($pad + $side - $d, $pad, $d, $d, 270, 90)
  $tile.AddArc($pad + $side - $d, $pad + $side - $d, $d, $d, 0, 90)
  $tile.AddArc($pad, $pad + $side - $d, $d, $d, 90, 90)
  $tile.CloseFigure()

  # A cold blue face, lighter at the top so the tile has a direction of light like the ball does.
  $rect = New-Object System.Drawing.Rectangle $pad, $pad, $side, $side
  $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    $rect,
    [System.Drawing.Color]::FromArgb(255, 32, 106, 196),
    [System.Drawing.Color]::FromArgb(255, 9, 34, 71),
    90.0)
  $g.FillPath($brush, $tile)

  # A hairline of lighter blue along the top edge reads as a lit rim and keeps the tile from
  # looking flat against a dark taskbar.
  $penWidth = [math]::Max(1.0, $size * 0.012)
  $pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(90, 190, 226, 255)), $penWidth
  $g.DrawPath($pen, $tile)

  # The snowball, centred and sized so the tile frames it rather than crops it.
  $art = [math]::Round($side * 0.66)
  $ox  = [math]::Round(($size - $art) / 2)
  $oy  = [math]::Round(($size - $art) / 2)
  $wrap = New-Object System.Drawing.Imaging.ImageAttributes
  $wrap.SetWrapMode([System.Drawing.Drawing2D.WrapMode]::TileFlipXY)
  $dest = New-Object System.Drawing.Rectangle $ox, $oy, $art, $art
  $g.DrawImage($source, $dest, $cropX, $cropY, $cropSide, $cropSide, [System.Drawing.GraphicsUnit]::Pixel, $wrap)

  $brush.Dispose(); $pen.Dispose(); $tile.Dispose(); $g.Dispose()
  return $bmp
}

# electron-builder wants a 512 PNG; it derives what it needs from that.
$png = Join-Path $here 'icon.png'
$big = New-Icon 512
$big.Save($png, [System.Drawing.Imaging.ImageFormat]::Png)
$big.Dispose()
Write-Output "wrote $png"

# The .ico carries every size Explorer asks for, each drawn at its own size rather than scaled
# down from one bitmap - small sizes need the tile's radius and rim redrawn to stay crisp.
$sizes = 16, 24, 32, 48, 64, 128, 256
$tmp = Join-Path $env:TEMP 'snowball-icon'
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
foreach ($s in $sizes) {
  $b = New-Icon $s
  $b.Save((Join-Path $tmp "$s.png"), [System.Drawing.Imaging.ImageFormat]::Png)
  $b.Dispose()
}
$source.Dispose()
Write-Output "rendered $($sizes -join ', ') into $tmp"
