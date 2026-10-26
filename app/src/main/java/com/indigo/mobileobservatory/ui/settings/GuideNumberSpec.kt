package com.indigo.mobileobservatory.ui.settings

import java.math.BigDecimal
import java.math.RoundingMode

/** Display precision and input bounds for guide settings; device values remain in native units. */
internal class GuideNumberSpec(
    minimum: String,
    maximum: String,
    step: String,
    val decimalPlaces: Int = 0,
    private val blankIsZero: Boolean = false,
) {
    private val minimum = minimum.toBigDecimal()
    private val maximum = maximum.toBigDecimal()
    private val step = step.toBigDecimal()
    val minimumText: String get() = format(minimum)
    val maximumText: String get() = format(maximum)
    val stepText: String get() = format(step)

    fun parse(text: String): BigDecimal? {
        val input = text.trim().replace(',', '.')
        if (blankIsZero && input.isEmpty()) return BigDecimal.ZERO
        val pattern = if (decimalPlaces == 0) "[0-9]+" else "(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)"
        if (!input.matches(Regex(pattern))) return null
        return input.toBigDecimalOrNull()?.takeIf {
            it.scale() <= decimalPlaces && it >= minimum && it <= maximum
        }
    }

    fun format(value: Float): String = format(value.toString().toBigDecimal())

    fun format(value: BigDecimal): String = value.setScale(decimalPlaces, RoundingMode.HALF_UP)
        .stripTrailingZeros().toPlainString()

    fun adjusted(text: String, current: Float, direction: Int): BigDecimal =
        ((parse(text) ?: current.toString().toBigDecimal()) + step * direction.toBigDecimal())
            .coerceIn(minimum, maximum)
}
