Add-Type -AssemblyName System.Drawing

$outputDir = "D:\CodeX\music\output\icon-options"
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null

function New-Color([int]$r, [int]$g, [int]$b, [int]$a = 255) {
    return [System.Drawing.Color]::FromArgb($a, $r, $g, $b)
}

function New-RoundedRectPath([float]$x, [float]$y, [float]$w, [float]$h, [float]$r) {
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $diameter = $r * 2
    $path.AddArc($x, $y, $diameter, $diameter, 180, 90)
    $path.AddArc($x + $w - $diameter, $y, $diameter, $diameter, 270, 90)
    $path.AddArc($x + $w - $diameter, $y + $h - $diameter, $diameter, $diameter, 0, 90)
    $path.AddArc($x, $y + $h - $diameter, $diameter, $diameter, 90, 90)
    $path.CloseFigure()
    return $path
}

function New-Canvas([int]$size = 1024) {
    $bmp = New-Object System.Drawing.Bitmap $size, $size
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.Clear((New-Color 6 10 16))
    return @{ Bitmap = $bmp; Graphics = $g }
}

function Save-AndDispose($canvas, [string]$path) {
    $canvas.Bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $canvas.Graphics.Dispose()
    $canvas.Bitmap.Dispose()
}

function Draw-ShadowedTile($g, $x, $y, $w, $h, $radius, $shadowColor, $fillBrush, $borderColor) {
    $shadowPath = New-RoundedRectPath ($x + 12) ($y + 22) $w $h $radius
    $shadowBrush = New-Object System.Drawing.SolidBrush $shadowColor
    $g.FillPath($shadowBrush, $shadowPath)
    $shadowBrush.Dispose()
    $shadowPath.Dispose()

    $tilePath = New-RoundedRectPath $x $y $w $h $radius
    $g.FillPath($fillBrush, $tilePath)
    $borderPen = New-Object System.Drawing.Pen $borderColor, 6
    $g.DrawPath($borderPen, $tilePath)
    $borderPen.Dispose()
    return $tilePath
}

function Draw-IconOption1([string]$path) {
    $canvas = New-Canvas
    $g = $canvas.Graphics

    $rect = New-Object System.Drawing.Rectangle 80, 80, 864, 864
    $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush $rect, (New-Color 31 162 255), (New-Color 13 42 70), 45
    $tilePath = Draw-ShadowedTile $g 80 80 864 864 180 (New-Color 0 0 0 90) $brush (New-Color 255 255 255 36)

    $glowPen = New-Object System.Drawing.Pen (New-Color 159 239 255 70), 16
    $g.DrawPath($glowPen, $tilePath)
    $glowPen.Dispose()

    $noteBrush = New-Object System.Drawing.SolidBrush (New-Color 255 255 255)
    $g.FillEllipse($noteBrush, 250, 520, 132, 132)
    $g.FillEllipse($noteBrush, 395, 478, 124, 124)
    $g.FillRectangle($noteBrush, 420, 250, 36, 292)
    $g.FillRectangle($noteBrush, 420, 250, 166, 36)
    $g.FillRectangle($noteBrush, 550, 250, 36, 138)

    $lineBrush = New-Object System.Drawing.SolidBrush (New-Color 228 248 255 240)
    foreach ($line in @(
        @{X=560;Y=360;W=180;H=34},
        @{X=560;Y=430;W=230;H=34},
        @{X=560;Y=500;W=160;H=34}
    )) {
        $linePath = New-RoundedRectPath $line.X $line.Y $line.W $line.H 17
        $g.FillPath($lineBrush, $linePath)
        $linePath.Dispose()
    }

    for ($i = 0; $i -lt 5; $i++) {
        $barHeight = 44 + ($i % 3) * 24
        $barPath = New-RoundedRectPath (564 + ($i * 48)) 620 24 $barHeight 12
        $barBrush = New-Object System.Drawing.SolidBrush (New-Color 170 236 255 220)
        $g.FillPath($barBrush, $barPath)
        $barBrush.Dispose()
        $barPath.Dispose()
    }

    $lineBrush.Dispose()
    $noteBrush.Dispose()
    $tilePath.Dispose()
    $brush.Dispose()
    Save-AndDispose $canvas $path
}

function Draw-IconOption2([string]$path) {
    $canvas = New-Canvas
    $g = $canvas.Graphics

    $rect = New-Object System.Drawing.Rectangle 80, 80, 864, 864
    $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush $rect, (New-Color 14 30 45), (New-Color 22 120 180), 315
    $tilePath = Draw-ShadowedTile $g 80 80 864 864 180 (New-Color 0 0 0 95) $brush (New-Color 255 255 255 30)

    $screenPath = New-RoundedRectPath 190 220 644 460 90
    $screenBrush = New-Object System.Drawing.SolidBrush (New-Color 11 20 30 210)
    $screenPen = New-Object System.Drawing.Pen (New-Color 140 228 255 85), 8
    $g.FillPath($screenBrush, $screenPath)
    $g.DrawPath($screenPen, $screenPath)

    $artRect = New-Object System.Drawing.Rectangle 242, 272, 224, 224
    $artBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush $artRect, (New-Color 68 217 255), (New-Color 36 92 215), 45
    $artPath = New-RoundedRectPath 242 272 224 224 48
    $g.FillPath($artBrush, $artPath)

    $noteBrush = New-Object System.Drawing.SolidBrush (New-Color 255 255 255)
    $g.FillEllipse($noteBrush, 298, 402, 64, 64)
    $g.FillEllipse($noteBrush, 372, 380, 60, 60)
    $g.FillRectangle($noteBrush, 385, 324, 18, 92)
    $g.FillRectangle($noteBrush, 385, 324, 84, 18)

    $lineBrush = New-Object System.Drawing.SolidBrush (New-Color 235 247 255 235)
    foreach ($line in @(
        @{X=520;Y=300;W=190;H=32},
        @{X=520;Y=362;W=150;H=32},
        @{X=520;Y=424;W=210;H=32}
    )) {
        $linePath = New-RoundedRectPath $line.X $line.Y $line.W $line.H 16
        $g.FillPath($lineBrush, $linePath)
        $linePath.Dispose()
    }

    $controlsPath = New-RoundedRectPath 280 732 464 88 44
    $controlsBrush = New-Object System.Drawing.SolidBrush (New-Color 7 16 24 210)
    $g.FillPath($controlsBrush, $controlsPath)

    $iconBrush = New-Object System.Drawing.SolidBrush (New-Color 255 255 255)
    $g.FillRectangle($iconBrush, 356, 758, 12, 36)
    $g.FillPolygon($iconBrush, ([System.Drawing.Point[]]@(
        (New-Object System.Drawing.Point 440, 746),
        (New-Object System.Drawing.Point 440, 806),
        (New-Object System.Drawing.Point 494, 776)
    )))
    $g.FillRectangle($iconBrush, 600, 758, 12, 36)
    $g.FillPolygon($iconBrush, ([System.Drawing.Point[]]@(
        (New-Object System.Drawing.Point 568, 746),
        (New-Object System.Drawing.Point 568, 806),
        (New-Object System.Drawing.Point 622, 776)
    )))
    $g.FillRectangle($iconBrush, 638, 758, 12, 36)

    $iconBrush.Dispose()
    $controlsBrush.Dispose()
    $controlsPath.Dispose()
    $lineBrush.Dispose()
    $noteBrush.Dispose()
    $artBrush.Dispose()
    $artPath.Dispose()
    $screenPen.Dispose()
    $screenBrush.Dispose()
    $screenPath.Dispose()
    $tilePath.Dispose()
    $brush.Dispose()
    Save-AndDispose $canvas $path
}

function Draw-IconOption3([string]$path) {
    $canvas = New-Canvas
    $g = $canvas.Graphics

    $rect = New-Object System.Drawing.Rectangle 80, 80, 864, 864
    $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush $rect, (New-Color 6 23 40), (New-Color 21 85 140), 45
    $tilePath = Draw-ShadowedTile $g 80 80 864 864 180 (New-Color 0 0 0 100) $brush (New-Color 255 255 255 28)

    $ringPenOuter = New-Object System.Drawing.Pen (New-Color 110 225 255 120), 30
    $g.DrawEllipse($ringPenOuter, 206, 206, 612, 612)
    $ringPenOuter.Dispose()

    $ringPenInner = New-Object System.Drawing.Pen (New-Color 255 255 255 42), 10
    $g.DrawEllipse($ringPenInner, 264, 264, 496, 496)
    $ringPenInner.Dispose()

    $bubblePath = New-RoundedRectPath 280 322 464 316 80
    $bubbleBrush = New-Object System.Drawing.SolidBrush (New-Color 10 19 28 214)
    $bubblePen = New-Object System.Drawing.Pen (New-Color 154 236 255 85), 7
    $g.FillPath($bubbleBrush, $bubblePath)
    $g.DrawPath($bubblePen, $bubblePath)

    $tailPoints = [System.Drawing.Point[]]@(
        (New-Object System.Drawing.Point -ArgumentList 392, 638),
        (New-Object System.Drawing.Point -ArgumentList 454, 716),
        (New-Object System.Drawing.Point -ArgumentList 504, 638)
    )
    $tailBrush = New-Object System.Drawing.SolidBrush (New-Color 10 19 28 214)
    $tailPen = New-Object System.Drawing.Pen (New-Color 154 236 255 85), 7
    $g.FillPolygon($tailBrush, $tailPoints)
    $g.DrawPolygon($tailPen, $tailPoints)

    $noteBrush = New-Object System.Drawing.SolidBrush (New-Color 255 255 255)
    $g.FillEllipse($noteBrush, 344, 432, 86, 86)
    $g.FillEllipse($noteBrush, 446, 402, 78, 78)
    $g.FillRectangle($noteBrush, 460, 330, 24, 120)
    $g.FillRectangle($noteBrush, 460, 330, 112, 24)

    $lineBrush = New-Object System.Drawing.SolidBrush (New-Color 226 246 255 240)
    foreach ($line in @(
        @{X=554;Y=390;W=100;H=28},
        @{X=554;Y=446;W=126;H=28},
        @{X=554;Y=502;W=88;H=28}
    )) {
        $linePath = New-RoundedRectPath $line.X $line.Y $line.W $line.H 14
        $g.FillPath($lineBrush, $linePath)
        $linePath.Dispose()
    }

    $pulsePen = New-Object System.Drawing.Pen (New-Color 90 226 255 135), 18
    $pulsePen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pulsePen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $points = [System.Drawing.Point[]]@(
        (New-Object System.Drawing.Point -ArgumentList 244, 796),
        (New-Object System.Drawing.Point -ArgumentList 328, 744),
        (New-Object System.Drawing.Point -ArgumentList 406, 812),
        (New-Object System.Drawing.Point -ArgumentList 492, 732),
        (New-Object System.Drawing.Point -ArgumentList 590, 818),
        (New-Object System.Drawing.Point -ArgumentList 688, 742),
        (New-Object System.Drawing.Point -ArgumentList 780, 796)
    )
    $g.DrawLines($pulsePen, $points)

    $pulsePen.Dispose()
    $lineBrush.Dispose()
    $noteBrush.Dispose()
    $tailPen.Dispose()
    $tailBrush.Dispose()
    $bubblePen.Dispose()
    $bubbleBrush.Dispose()
    $bubblePath.Dispose()
    $tilePath.Dispose()
    $brush.Dispose()
    Save-AndDispose $canvas $path
}

Draw-IconOption1 (Join-Path $outputDir "icon-option-1-lyrics-note.png")
Draw-IconOption2 (Join-Path $outputDir "icon-option-2-player-screen.png")
Draw-IconOption3 (Join-Path $outputDir "icon-option-3-wave-bubble.png")

Write-Output $outputDir
