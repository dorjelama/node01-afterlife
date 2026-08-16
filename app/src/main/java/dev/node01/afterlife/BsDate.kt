package dev.node01.afterlife

import java.time.LocalDate

/** A date in the Bikram Sambat calendar. [month] is 1..12. */
data class BsDate(val year: Int, val month: Int, val day: Int)

/**
 * Bikram Sambat conversion.
 *
 * BS month lengths are astronomically determined and vary irregularly between
 * 29 and 32 days, so they cannot be computed -- they have to come from a table.
 * The data below covers BS 2000..2099 and is anchored on the standard epoch
 * BS 2000/01/01 = AD 1943-04-14.
 *
 * Verified against a live Nepali calendar: AD 2026-08-16 is 31 Shrawan 2083,
 * which this table reproduces, as it does Nepali New Year AD 2026-04-14 =
 * BS 2083/01/01 and the epoch round-trip.
 *
 * Source note: two public tables were compared. One had clearly synthetic data
 * past BS 2080 (every year ending 30, 30, 30); this uses the other, which
 * carries realistic varying month lengths across the whole range.
 */
object Bs {

    const val START_YEAR = 2000

    /** Anchor: the first day of BS 2000 in the Gregorian calendar. */
    private val EPOCH: LocalDate = LocalDate.of(1943, 4, 14)

    /** Romanised names, kept for logging and the Gregorian fallback path. */
    val MONTH_NAMES = arrayOf(
        "BAISHAKH", "JESTHA", "ASHADH", "SHRAWAN", "BHADRA", "ASHOJ",
        "KARTIK", "MANGSIR", "POUSH", "MAGH", "FALGUN", "CHAITRA",
    )

    /** Devanagari month names, as printed on Nepali calendars. */
    val MONTH_NAMES_NE = arrayOf(
        "बैशाख", "जेठ", "असार", "साउन", "भदौ", "असोज",
        "कार्तिक", "मंसिर", "पुस", "माघ", "फागुन", "चैत",
    )

    /** Weekday initials, Sunday first: Aaitabar .. Sanibar. */
    val WEEKDAYS_NE = arrayOf("आ", "सो", "मं", "बु", "बि", "शु", "श")

    private const val DEVANAGARI_ZERO = '०'

    /** As [neDigits], zero-padded to two digits for clock-style figures. */
    fun neDigits2(value: Int): String =
        if (value in 0..9) "०" + neDigits(value) else neDigits(value)

    /** Renders a number with Devanagari digits (०१२३४५६७८९); '-' passes through. */
    fun neDigits(value: Int): String {
        val s = value.toString()
        val out = StringBuilder(s.length)
        for (c in s) {
            out.append(if (c in '0'..'9') DEVANAGARI_ZERO + (c - '0') else c)
        }
        return out.toString()
    }

    /** Days per month, one row per year starting at [START_YEAR]. */
    private val MONTH_DAYS = arrayOf(
        intArrayOf(30, 32, 31, 32, 31, 30, 30, 30, 29, 30, 29, 31),  // 2000
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30),  // 2001
        intArrayOf(31, 31, 32, 32, 31, 30, 30, 29, 30, 29, 30, 30),  // 2002
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 31),  // 2003
        intArrayOf(30, 32, 31, 32, 31, 30, 30, 30, 29, 30, 29, 31),  // 2004
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30),  // 2005
        intArrayOf(31, 31, 32, 32, 31, 30, 30, 29, 30, 29, 30, 30),  // 2006
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 31),  // 2007
        intArrayOf(31, 31, 31, 32, 31, 31, 29, 30, 30, 29, 29, 31),  // 2008
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30),  // 2009
        intArrayOf(31, 31, 32, 32, 31, 30, 30, 29, 30, 29, 30, 30),  // 2010
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 31),  // 2011
        intArrayOf(31, 31, 31, 32, 31, 31, 29, 30, 30, 29, 30, 30),  // 2012
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30),  // 2013
        intArrayOf(31, 31, 32, 32, 31, 30, 30, 29, 30, 29, 30, 30),  // 2014
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 31),  // 2015
        intArrayOf(31, 31, 31, 32, 31, 31, 29, 30, 30, 29, 30, 30),  // 2016
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30),  // 2017
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 29, 30, 29, 30, 30),  // 2018
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 30, 29, 31),  // 2019
        intArrayOf(31, 31, 31, 32, 31, 31, 30, 29, 30, 29, 30, 30),  // 2020
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30),  // 2021
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 30),  // 2022
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 30, 29, 31),  // 2023
        intArrayOf(31, 31, 31, 32, 31, 31, 30, 29, 30, 29, 30, 30),  // 2024
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30),  // 2025
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 31),  // 2026
        intArrayOf(30, 32, 31, 32, 31, 30, 30, 30, 29, 30, 29, 31),  // 2027
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30),  // 2028
        intArrayOf(31, 31, 32, 31, 32, 30, 30, 29, 30, 29, 30, 30),  // 2029
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 31),  // 2030
        intArrayOf(30, 32, 31, 32, 31, 30, 30, 30, 29, 30, 29, 31),  // 2031
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30),  // 2032
        intArrayOf(31, 31, 32, 32, 31, 30, 30, 29, 30, 29, 30, 30),  // 2033
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 31),  // 2034
        intArrayOf(30, 32, 31, 32, 31, 31, 29, 30, 30, 29, 29, 31),  // 2035
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30),  // 2036
        intArrayOf(31, 31, 32, 32, 31, 30, 30, 29, 30, 29, 30, 30),  // 2037
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 31),  // 2038
        intArrayOf(31, 31, 31, 32, 31, 31, 29, 30, 30, 29, 30, 30),  // 2039
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30),  // 2040
        intArrayOf(31, 31, 32, 32, 31, 30, 30, 29, 30, 29, 30, 30),  // 2041
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 31),  // 2042
        intArrayOf(31, 31, 31, 32, 31, 31, 29, 30, 30, 29, 30, 30),  // 2043
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30),  // 2044
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 29, 30, 29, 30, 30),  // 2045
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 31),  // 2046
        intArrayOf(31, 31, 31, 32, 31, 31, 30, 29, 30, 29, 30, 30),  // 2047
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30),  // 2048
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 30),  // 2049
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 30, 29, 31),  // 2050
        intArrayOf(31, 31, 31, 32, 31, 31, 30, 29, 30, 29, 30, 30),  // 2051
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30),  // 2052
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 30),  // 2053
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 30, 29, 31),  // 2054
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30),  // 2055
        intArrayOf(31, 31, 32, 31, 32, 30, 30, 29, 30, 29, 30, 30),  // 2056
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 31),  // 2057
        intArrayOf(30, 32, 31, 32, 31, 30, 30, 30, 29, 30, 29, 31),  // 2058
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30),  // 2059
        intArrayOf(31, 31, 32, 32, 31, 30, 30, 29, 30, 29, 30, 30),  // 2060
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 31),  // 2061
        intArrayOf(30, 32, 31, 32, 31, 31, 29, 30, 29, 30, 29, 31),  // 2062
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30),  // 2063
        intArrayOf(31, 31, 32, 32, 31, 30, 30, 29, 30, 29, 30, 30),  // 2064
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 31),  // 2065
        intArrayOf(31, 31, 31, 32, 31, 31, 29, 30, 30, 29, 29, 31),  // 2066
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30),  // 2067
        intArrayOf(31, 31, 32, 32, 31, 30, 30, 29, 30, 29, 30, 30),  // 2068
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 31),  // 2069
        intArrayOf(31, 31, 31, 32, 31, 31, 29, 30, 30, 29, 30, 30),  // 2070
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30),  // 2071
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 29, 30, 29, 30, 30),  // 2072
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 31),  // 2073
        intArrayOf(31, 31, 31, 32, 31, 31, 30, 29, 30, 29, 30, 30),  // 2074
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30),  // 2075
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 30),  // 2076
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 30, 29, 31),  // 2077
        intArrayOf(31, 31, 31, 32, 31, 31, 30, 29, 30, 29, 30, 30),  // 2078
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30),  // 2079
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 30),  // 2080
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 30, 29, 31),  // 2081
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30),  // 2082
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30),  // 2083
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 31),  // 2084
        intArrayOf(30, 32, 31, 32, 31, 30, 30, 30, 29, 30, 29, 31),  // 2085
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30),  // 2086
        intArrayOf(31, 31, 32, 32, 31, 30, 30, 29, 30, 29, 30, 30),  // 2087
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 31),  // 2088
        intArrayOf(30, 32, 31, 32, 31, 30, 30, 30, 29, 30, 29, 31),  // 2089
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30),  // 2090
        intArrayOf(31, 31, 32, 32, 31, 30, 30, 29, 30, 29, 30, 30),  // 2091
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 31),  // 2092
        intArrayOf(31, 31, 31, 32, 31, 31, 29, 30, 30, 29, 29, 31),  // 2093
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30),  // 2094
        intArrayOf(31, 31, 32, 32, 31, 30, 30, 29, 30, 29, 30, 30),  // 2095
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 31),  // 2096
        intArrayOf(31, 31, 31, 32, 31, 31, 29, 30, 30, 29, 30, 30),  // 2097
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30),  // 2098
        intArrayOf(31, 31, 32, 32, 31, 30, 30, 29, 30, 29, 30, 30),  // 2099
    )

    val END_YEAR = START_YEAR + MONTH_DAYS.size - 1

    fun daysInMonth(year: Int, month: Int): Int {
        if (year !in START_YEAR..END_YEAR || month !in 1..12) return 0
        return MONTH_DAYS[year - START_YEAR][month - 1]
    }

    /** Null when [date] falls outside the table's range. */
    fun fromAd(date: LocalDate): BsDate? {
        var remaining = java.time.temporal.ChronoUnit.DAYS.between(EPOCH, date)
        if (remaining < 0) return null

        var year = START_YEAR
        var month = 1
        while (year <= END_YEAR) {
            val dim = MONTH_DAYS[year - START_YEAR][month - 1]
            if (remaining < dim) return BsDate(year, month, (remaining + 1).toInt())
            remaining -= dim
            month++
            if (month > 12) { month = 1; year++ }
        }
        return null
    }

    /** Null when the BS date is outside the table's range or not a real day. */
    fun toAd(year: Int, month: Int, day: Int): LocalDate? {
        if (year !in START_YEAR..END_YEAR || month !in 1..12) return null
        if (day < 1 || day > daysInMonth(year, month)) return null

        var days = 0L
        for (y in START_YEAR until year) days += MONTH_DAYS[y - START_YEAR].sum()
        for (m in 1 until month) days += MONTH_DAYS[year - START_YEAR][m - 1]
        return EPOCH.plusDays(days + day - 1)
    }
}
