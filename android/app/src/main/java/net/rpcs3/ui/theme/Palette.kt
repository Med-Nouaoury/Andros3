package net.rpcs3.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object Rpcs {
    // Andros3 crimson theme: wine-black surfaces under a vivid red accent.
    val Background = Color(0xFF160307)
    val Surface = Color(0xFF210509)
    val SurfaceRaised = Color(0xFF2E0910)
    val SurfaceInset = Color(0xFF120206)
    val Outline = Color(0xFF4D1520)
    val OutlineSoft = Color(0xFF380E17)

    val Accent = Color(0xFFE11D3C)
    val AccentBright = Color(0xFFFF4D63)

    val TextPrimary = Color(0xFFFFF2F3)
    val TextSecondary = Color(0xFFE0AEB4)
    val TextDim = Color(0xFFA0707A)

    // Kept apart from the red accent so errors still read as errors.
    val Danger = Color(0xFFFF8A4C)
    val Warning = Color(0xFFFFC857)
    val Success = Color(0xFF4CC38A)

    // Backdrop gradient, deep wine to crimson.
    val GradientCyan = Color(0xFF7A0010)
    val GradientViolet = Color(0xFFD01A35)
    val BackdropTop = Color(0xFF3A0009)
    val BackdropMid = Color(0xFF6E0012)
    val BackdropBottom = Color(0xFFB0102A)
    val Glow = Color(0xFFFF6B7D)

    val SelectionFill = Accent.copy(alpha = 0.18f)
    val SelectionBorder = Accent.copy(alpha = 0.65f)
    val FocusBorder = AccentBright
    val PressedFill = Accent.copy(alpha = 0.26f)
}

object Dims {
    val ScreenPadding = 16.dp
    val ContentMaxWidth = 520.dp
    val WideContentMaxWidth = 820.dp
    val ChannelColumnMinWidth = 300.dp
    val SidebarWidth = 232.dp
    val CompactWidth = 600.dp
    val CardCorner = 14.dp
    val InputCorner = 10.dp
    val RowCorner = 10.dp
    val RowSpacing = 8.dp
    val SectionSpacing = 18.dp
    val BorderWidth = 1.dp
    val FocusBorderWidth = 2.dp
    val OverlayWidth = 460.dp
    val ProgressBarHeight = 6.dp
}

object SettingsStyle {
    val BgDeep = Rpcs.Background
    val SidebarBg = Rpcs.Surface
    val ContentBg = Rpcs.Background
    val CardSurface = Rpcs.SurfaceRaised
    val CardBorder = Rpcs.Outline
    val InputSurface = Rpcs.SurfaceInset
    val InputBorder = Rpcs.Outline
    val AccentBlue = Rpcs.Accent
    val TextPrimary = Rpcs.TextPrimary
    val TextSecondary = Rpcs.TextSecondary
    val TextDim = Rpcs.TextDim
    val Divider = Rpcs.OutlineSoft
    val CheckBorder = Rpcs.Outline
    val SliderInactive = Rpcs.Outline
    val ChipSurface = Rpcs.SurfaceInset
    val ChipBorder = Rpcs.Outline
    val DangerRed = Rpcs.Danger
    val WarningAmber = Rpcs.Warning
    val NavHighlight = Rpcs.AccentBright

    val CardCorner = Dims.CardCorner
    val InputCorner = Dims.InputCorner
    val SidebarWidth = Dims.SidebarWidth
    val ContentPadding = Dims.ScreenPadding
    val RowSpacing = Dims.RowSpacing
}

object DrawerStyle {
    const val SheetAlpha = 0.94f
    const val SurfaceAlpha = 0.86f
    const val PressedAlpha = 0.94f

    val Accent = Rpcs.Accent
    val ActiveAccent = Rpcs.AccentBright
    val FocusFill = Rpcs.SelectionFill
    val TextPrimary = Rpcs.TextPrimary
    val TextSecondary = Rpcs.TextSecondary
    val Outline = Rpcs.Outline
    val Background = Rpcs.Background.copy(alpha = SheetAlpha)
    val PaneSurface = Rpcs.Surface.copy(alpha = SheetAlpha)
    val PaneSurfacePressed = Rpcs.SurfaceRaised.copy(alpha = PressedAlpha)
    val TopRailSurface = Rpcs.Surface.copy(alpha = SheetAlpha)
    val TileResting = Rpcs.SurfaceRaised.copy(alpha = SurfaceAlpha)
    val TileExitResting = Color(0xFF3A2125).copy(alpha = SurfaceAlpha)
    val TileExitPressed = Color(0xFF4A2A30).copy(alpha = PressedAlpha)
    val PaneInnerResting = Rpcs.SurfaceInset.copy(alpha = SurfaceAlpha)
    val PaneInnerPressed = Rpcs.SurfaceRaised.copy(alpha = PressedAlpha)
    val RestingCardBorder = Rpcs.Outline
    val DisabledCardBorder = Rpcs.OutlineSoft
    val ActiveCardBorder = Rpcs.FocusBorder
    val Divider = Rpcs.OutlineSoft
    val ExitTint = Color(0xFFE07B6B)

    val Width = 320.dp
    val StartPadding = 6.dp
    val VerticalPadding = 6.dp
    val RailTileSize = 64.dp
    val CornerRadius = 14.dp
    const val PaneScaleMin = 0.78f
    const val PaneScaleReferenceHeightDp = 520f
}
