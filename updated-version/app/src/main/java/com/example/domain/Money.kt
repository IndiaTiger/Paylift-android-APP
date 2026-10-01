package com.example.domain

/**
 * Money in integer paise. All authoritative amounts in the app are [Long] paise received from the
 * backend; conversion to rupees happens only for display.
 */
@JvmInline
value class Paise(val value: Long) {
    init {
        require(value >= 0) { "Paise cannot be negative: $value" }
    }

    /** Display-only conversion. Never feed the result back into a money calculation. */
    fun toRupeesForDisplay(): Double = value / 100.0

    operator fun plus(other: Paise) = Paise(Math.addExact(value, other.value))
    operator fun minus(other: Paise) = Paise(Math.subtractExact(value, other.value))
    operator fun compareTo(other: Paise) = value.compareTo(other.value)

    companion object {
        val ZERO = Paise(0)

        /** Parses a user-entered rupee amount ("250", "99.5") into paise without floating point. */
        fun parseRupees(input: String): Paise? {
            val s = input.trim()
            if (!Regex("^\\d{1,7}(\\.\\d{1,2})?$").matches(s)) return null
            val parts = s.split('.')
            val rupees = parts[0].toLong()
            val fraction = parts.getOrNull(1)?.padEnd(2, '0')?.toLong() ?: 0L
            return Paise(rupees * 100 + fraction)
        }
    }
}
