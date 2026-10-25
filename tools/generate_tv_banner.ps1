param([string]$ProjectRoot = (Split-Path $PSScriptRoot -Parent))
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$target = Join-Path $ProjectRoot 'app/src/main/res/drawable-xhdpi/tv_launcher_banner.png'
[void][System.IO.Directory]::CreateDirectory((Split-Path $target -Parent))
$bitmap = [System.Drawing.Bitmap]::new(320, 180)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.SmoothingMode = 'AntiAlias'
$graphics.TextRenderingHint = 'AntiAliasGridFit'
$background = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml('#17223D'))
$accent = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml('#263761'))
$purple = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml('#A384FF'))
$white = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::White)
$pen = [System.Drawing.Pen]::new([System.Drawing.Color]::White, 6)
$font = [System.Drawing.Font]::new('Microsoft YaHei', 32, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
$capsule = [System.Drawing.Drawing2D.GraphicsPath]::new()
try {
    $graphics.FillRectangle($background, 0, 0, 320, 180)
    $graphics.FillPolygon($accent, [System.Drawing.Point[]]@([System.Drawing.Point]::new(0,0), [System.Drawing.Point]::new(110,0), [System.Drawing.Point]::new(215,180), [System.Drawing.Point]::new(0,180)))
    $capsule.AddArc(54, 28, 48, 48, 180, 180)
    $capsule.AddArc(54, 62, 48, 48, 0, 180)
    $capsule.CloseFigure()
    $graphics.FillPath($purple, $capsule)
    $graphics.DrawArc($pen, 43, 53, 70, 70, 0, 180)
    $graphics.DrawLine($pen, 43, 78, 43, 88)
    $graphics.DrawLine($pen, 113, 78, 113, 88)
    $graphics.DrawLine($pen, 78, 123, 78, 143)
    $graphics.DrawLine($pen, 58, 143, 98, 143)
    $title = [string]::Concat([char]0x9EA6, [char]0x52A8, ' KTV')
    $graphics.DrawString($title, $font, $white, [System.Drawing.PointF]::new(128,65), [System.Drawing.StringFormat]::GenericTypographic)
    $graphics.FillRectangle($purple, 134, 119, 152, 3)
    $bitmap.Save($target, [System.Drawing.Imaging.ImageFormat]::Png)
} finally {
    $capsule.Dispose(); $font.Dispose(); $pen.Dispose()
    $white.Dispose(); $purple.Dispose(); $accent.Dispose(); $background.Dispose()
    $graphics.Dispose(); $bitmap.Dispose()
}
Write-Output $target
