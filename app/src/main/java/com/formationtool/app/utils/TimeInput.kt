package com.formationtool.app.utils

import java.math.BigDecimal
import java.math.RoundingMode

/** 支持秒数和分:秒.毫秒；拒绝负数、无穷值及超出范围的输入。 */
fun parseTimeInput(value: String): Long? = try {
    val parts = value.trim().split(':')
    require(parts.size in 1..2)
    require(parts.all { it.matches(Regex("[0-9]+(?:\\.[0-9]{1,3})?")) })
    val seconds = if (parts.size == 1) BigDecimal(parts[0]) else {
        require(parts[0].matches(Regex("[0-9]+")))
        val remainder = BigDecimal(parts[1])
        require(remainder < BigDecimal(60))
        BigDecimal(parts[0]).multiply(BigDecimal(60)).add(remainder)
    }
    seconds.multiply(BigDecimal(1000)).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
} catch (_: Exception) { null }
