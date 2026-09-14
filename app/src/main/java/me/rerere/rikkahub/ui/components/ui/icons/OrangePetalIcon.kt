/*
 * Elian
 * 衍生自 RikkaHub / OrangeChat，基于 GNU AGPL v3 开源
 * 猫爪印图标 - 基于 Lucide paw-print (ISC License)
 */

package me.rerere.rikkahub.ui.components.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 猫爪印"思考中"图标 (替代橘瓣花瓣)
 * 基于 Lucide paw-print，纯描边线条风格，通过 tint 跟随主题色
 */
public val OrangePetalIcon: ImageVector
    get() {
        if (_orangePetalIcon != null) return _orangePetalIcon!!
        _orangePetalIcon = ImageVector.Builder(
            name = "CatPaw",
            defaultWidth = 16.dp,
            defaultHeight = 16.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // 左上趾垫 (circle at 9,4 r=2)
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(11f, 4f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 7f, 4f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 11f, 4f)
                close()
            }
            // 右上趾垫 (circle at 15,4 r=2)
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(17f, 4f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 13f, 4f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 17f, 4f)
                close()
            }
            // 左下趾垫 (circle at 6,10 r=2)
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(8f, 10f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 4f, 10f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 8f, 10f)
                close()
            }
            // 右下趾垫 (circle at 18,10 r=2)
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(20f, 10f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 16f, 10f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 20f, 10f)
                close()
            }
            // 大掌垫 (rounded bottom pad)
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(7f, 14f)
                curveTo(7f, 14f, 5f, 18f, 5f, 19f)
                curveTo(5f, 20.657f, 6.343f, 22f, 8f, 22f)
                lineTo(16f, 22f)
                curveTo(17.657f, 22f, 19f, 20.657f, 19f, 19f)
                curveTo(19f, 18f, 17f, 14f, 17f, 14f)
                curveTo(17f, 14f, 15f, 12f, 12f, 12f)
                curveTo(9f, 12f, 7f, 14f, 7f, 14f)
                close()
            }
        }.build()
        return _orangePetalIcon!!
    }

private var _orangePetalIcon: ImageVector? = null
