package app.xreader.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.xreader.R

/**
 * Type system (DESIGN.md "Type & shape"): the designs' actual typefaces
 * are bundled — Plus Jakarta Sans for headlines, Inter for body/labels,
 * Literata serif for reader body text (all SIL Open Font Licence; files
 * in `res/font/`). Typeface does not change with colour mode.
 */
val JakartaSans = FontFamily(
    Font(R.font.jakarta_bold, FontWeight.Bold),
    Font(R.font.jakarta_bold, FontWeight.SemiBold),
    Font(R.font.jakarta_extrabold, FontWeight.ExtraBold)
)

val InterSans = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_semibold, FontWeight.Bold)
)

/** The reader's serif. Literata ships as a variable font; the reader's
 * typography engine (Phase 4) drives weight/size from it. */
val LiterataSerif = FontFamily(
    Font(R.font.literata, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.literata_italic, FontWeight.Normal, FontStyle.Italic)
)

val XreaderTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = JakartaSans, fontWeight = FontWeight.ExtraBold,
        fontSize = 57.sp, lineHeight = 64.sp
    ),
    displayMedium = TextStyle(
        fontFamily = JakartaSans, fontWeight = FontWeight.ExtraBold,
        fontSize = 45.sp, lineHeight = 52.sp
    ),
    displaySmall = TextStyle(
        fontFamily = JakartaSans, fontWeight = FontWeight.Bold,
        fontSize = 36.sp, lineHeight = 44.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = JakartaSans, fontWeight = FontWeight.ExtraBold,
        fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-0.64).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = JakartaSans, fontWeight = FontWeight.Bold,
        fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = (-0.7).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = JakartaSans, fontWeight = FontWeight.Bold,
        fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = (-0.48).sp
    ),
    titleLarge = TextStyle(
        fontFamily = JakartaSans, fontWeight = FontWeight.Bold,
        fontSize = 22.sp, lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = JakartaSans, fontWeight = FontWeight.Bold,
        fontSize = 16.sp, lineHeight = 24.sp
    ),
    titleSmall = TextStyle(
        fontFamily = JakartaSans, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = InterSans, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = InterSans, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = InterSans, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontFamily = InterSans, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = InterSans, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = InterSans, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 16.sp
    )
)

/**
 * Nocturne Violet overline (DESIGN.md type scale): 10sp Jakarta
 * bold, uppercase, +0.08em tracking — the small caps anchor above
 * titles and inside stat tiles ("TIME LEFT", "ON DEVICE").
 * Jakarta has no separate medium file; bold is the bundled cut.
 */
val NocturneOverline = TextStyle(
    fontFamily = JakartaSans, fontWeight = FontWeight.Bold,
    fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.8.sp
)
