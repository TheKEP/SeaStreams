<#
.SYNOPSIS
    Regenerates SeaStreams launcher icon and TV banner resources from the brand references.

.DESCRIPTION
    Inputs (repo-relative):
      docs/ui-references/tv_icon.png    512x512, opaque #000000 background
      docs/ui-references/tv_banner.png  1280x720, opaque #000000 background
    Outputs (app/src/main/res):
      mipmap-<d>/ic_launcher_foreground.png  108dp adaptive foreground, art inside the 66dp safe zone
      mipmap-<d>/ic_launcher.png             48dp legacy square fallback
      mipmap-<d>/ic_launcher_round.png       48dp legacy circle-clipped fallback
      drawable-<d>/tv_banner.png             160x90dp Leanback banner (320x180 px at xhdpi)
    Uses only the .NET System.Drawing that ships with Windows; no build dependency.

.EXAMPLE
    pwsh tools/launcher-assets/Generate-LauncherAssets.ps1
#>
[CmdletBinding()]
param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$Densities = [ordered]@{ mdpi = 1.0; hdpi = 1.5; xhdpi = 2.0; xxhdpi = 3.0; xxxhdpi = 4.0 }
$ResDir = Join-Path $RepoRoot 'app\src\main\res'
$IconSrc = Join-Path $RepoRoot 'docs\ui-references\tv_icon.png'
$BannerSrc = Join-Path $RepoRoot 'docs\ui-references\tv_banner.png'

# A pixel counts as artwork when any channel exceeds this (pure-black background is 0,0,0).
$InkThreshold = 12
# Adaptive icon geometry in dp: 108dp canvas, 66dp safe-zone circle; keep art within a 31dp radius.
$AdaptiveCanvasDp = 108
$ArtRadiusDp = 31.0
# Legacy 48dp icons: art radius as a fraction of the half-size (fits circle-clipped fallback too).
$LegacySizeDp = 48
$LegacyArtRadiusFraction = 0.88
# Banner: centred crop of the 1280x720 source that frames the lockup at ~80% width; still 16:9.
$BannerCropWidth = 1120
$BannerCropHeight = 630
$BannerSizeDp = @{ Width = 160; Height = 90 }  # 320x180 px at xhdpi (Android TV banner spec)

function Open-Bitmap([string]$Path, [int]$Width, [int]$Height) {
    if (-not (Test-Path $Path)) { throw "Missing reference image: $Path" }
    $bmp = [System.Drawing.Bitmap]::new($Path)
    if ($bmp.Width -ne $Width -or $bmp.Height -ne $Height) {
        $actual = "$($bmp.Width)x$($bmp.Height)"
        $bmp.Dispose()
        throw "$Path must be ${Width}x${Height}, found $actual"
    }
    return $bmp
}

function New-Canvas([int]$Width, [int]$Height) {
    $bmp = [System.Drawing.Bitmap]::new($Width, $Height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    return @{ Bitmap = $bmp; Graphics = $g }
}

# Draws srcRect of $Source into destRect, clamping edge sampling so borders don't bleed.
function Copy-Region($Graphics, $Source, [System.Drawing.RectangleF]$Dest, [System.Drawing.RectangleF]$Src) {
    $attrs = [System.Drawing.Imaging.ImageAttributes]::new()
    $attrs.SetWrapMode([System.Drawing.Drawing2D.WrapMode]::TileFlipXY)
    $destPoints = [System.Drawing.PointF[]]@(
        [System.Drawing.PointF]::new($Dest.Left, $Dest.Top),
        [System.Drawing.PointF]::new($Dest.Right, $Dest.Top),
        [System.Drawing.PointF]::new($Dest.Left, $Dest.Bottom)
    )
    $Graphics.DrawImage($Source, $destPoints, $Src, [System.Drawing.GraphicsUnit]::Pixel, $attrs)
    $attrs.Dispose()
}

function Save-Png($Canvas, [string]$Path) {
    $Canvas.Graphics.Dispose()
    New-Item -ItemType Directory -Force (Split-Path $Path) | Out-Null
    $Canvas.Bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    Write-Host ("  {0}  {1}x{2}" -f $Path.Substring($RepoRoot.Length + 1), $Canvas.Bitmap.Width, $Canvas.Bitmap.Height)
    $Canvas.Bitmap.Dispose()
}

# Returns the artwork's bounding-box centre and the farthest artwork pixel's distance from it.
function Measure-Artwork($Bitmap) {
    $rect = [System.Drawing.Rectangle]::new(0, 0, $Bitmap.Width, $Bitmap.Height)
    $data = $Bitmap.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $bytes = [byte[]]::new($data.Stride * $Bitmap.Height)
    [System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $bytes, 0, $bytes.Length)
    $Bitmap.UnlockBits($data)

    $ink = [System.Collections.Generic.List[int[]]]::new()
    $minX = $Bitmap.Width; $minY = $Bitmap.Height; $maxX = -1; $maxY = -1
    for ($y = 0; $y -lt $Bitmap.Height; $y++) {
        $row = $y * $data.Stride
        for ($x = 0; $x -lt $Bitmap.Width; $x++) {
            $i = $row + $x * 4  # BGRA
            if ($bytes[$i] -gt $InkThreshold -or $bytes[$i + 1] -gt $InkThreshold -or $bytes[$i + 2] -gt $InkThreshold) {
                $ink.Add(@($x, $y))
                if ($x -lt $minX) { $minX = $x }; if ($x -gt $maxX) { $maxX = $x }
                if ($y -lt $minY) { $minY = $y }; if ($y -gt $maxY) { $maxY = $y }
            }
        }
    }
    if ($maxX -lt 0) { throw 'tv_icon.png contains no artwork above the ink threshold' }

    # Pixel centres span [min, max + 1); measure to the far corner of each ink pixel.
    $cx = ($minX + $maxX + 1) / 2.0
    $cy = ($minY + $maxY + 1) / 2.0
    $maxR2 = 0.0
    foreach ($p in $ink) {
        $dx = [Math]::Max([Math]::Abs($p[0] - $cx), [Math]::Abs($p[0] + 1 - $cx))
        $dy = [Math]::Max([Math]::Abs($p[1] - $cy), [Math]::Abs($p[1] + 1 - $cy))
        $r2 = $dx * $dx + $dy * $dy
        if ($r2 -gt $maxR2) { $maxR2 = $r2 }
    }
    return @{ CenterX = $cx; CenterY = $cy; Radius = [Math]::Sqrt($maxR2) }
}

# Renders the icon art centred on a black square so the farthest ink pixel lands at $ArtRadiusPx.
function New-IconCanvas($Source, $Art, [int]$SizePx, [double]$ArtRadiusPx) {
    $canvas = New-Canvas $SizePx $SizePx
    $canvas.Graphics.Clear([System.Drawing.Color]::Black)
    $scale = $ArtRadiusPx / $Art.Radius
    $dest = [System.Drawing.RectangleF]::new(
        [float]($SizePx / 2.0 - $Art.CenterX * $scale),
        [float]($SizePx / 2.0 - $Art.CenterY * $scale),
        [float]($Source.Width * $scale),
        [float]($Source.Height * $scale))
    $src = [System.Drawing.RectangleF]::new(0, 0, $Source.Width, $Source.Height)
    Copy-Region $canvas.Graphics $Source $dest $src
    return $canvas
}

function New-RoundIcon($Source, $Art, [int]$SizePx, [double]$ArtRadiusPx) {
    $square = New-IconCanvas $Source $Art $SizePx $ArtRadiusPx
    $square.Graphics.Dispose()
    $canvas = New-Canvas $SizePx $SizePx
    $canvas.Graphics.Clear([System.Drawing.Color]::Transparent)
    $brush = [System.Drawing.TextureBrush]::new($square.Bitmap)
    $canvas.Graphics.FillEllipse($brush, 0, 0, $SizePx, $SizePx)
    $brush.Dispose()
    $square.Bitmap.Dispose()
    return $canvas
}

$icon = Open-Bitmap $IconSrc 512 512
$banner = Open-Bitmap $BannerSrc 1280 720
try {
    $art = Measure-Artwork $icon
    Write-Host ("tv_icon.png artwork centre ({0:N1}, {1:N1}), max radius {2:N1}px" -f $art.CenterX, $art.CenterY, $art.Radius)

    foreach ($d in $Densities.Keys) {
        $f = $Densities[$d]
        $mipmap = Join-Path $ResDir "mipmap-$d"

        $fgSize = [int][Math]::Round($AdaptiveCanvasDp * $f)
        Save-Png (New-IconCanvas $icon $art $fgSize ($ArtRadiusDp * $f)) (Join-Path $mipmap 'ic_launcher_foreground.png')

        $legacySize = [int][Math]::Round($LegacySizeDp * $f)
        $legacyRadius = $legacySize / 2.0 * $LegacyArtRadiusFraction
        Save-Png (New-IconCanvas $icon $art $legacySize $legacyRadius) (Join-Path $mipmap 'ic_launcher.png')
        Save-Png (New-RoundIcon $icon $art $legacySize $legacyRadius) (Join-Path $mipmap 'ic_launcher_round.png')

        $bw = [int][Math]::Round($BannerSizeDp.Width * $f)
        $bh = [int][Math]::Round($BannerSizeDp.Height * $f)
        $canvas = New-Canvas $bw $bh
        $canvas.Graphics.Clear([System.Drawing.Color]::Black)
        $src = [System.Drawing.RectangleF]::new(
            ($banner.Width - $BannerCropWidth) / 2.0, ($banner.Height - $BannerCropHeight) / 2.0,
            $BannerCropWidth, $BannerCropHeight)
        Copy-Region $canvas.Graphics $banner ([System.Drawing.RectangleF]::new(0, 0, $bw, $bh)) $src
        Save-Png $canvas (Join-Path $ResDir "drawable-$d\tv_banner.png")
    }
}
finally {
    $icon.Dispose()
    $banner.Dispose()
}
