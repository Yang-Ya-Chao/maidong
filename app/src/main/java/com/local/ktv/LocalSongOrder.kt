package com.local.ktv

/** Catalog IDs come from the local inventory; quote literals without a SQL bind-variable limit. */
internal object LocalSongOrder {
    fun numberCondition(numbers: Collection<String>, include: Boolean = true): String {
        if (numbers.isEmpty()) return if (include) "0=1" else "1=1"
        val values = numbers.distinct().joinToString(",") { "'" + it.replace("'", "''") + "'" }
        return "s.number ${if (include) "IN" else "NOT IN"} ($values)"
    }
}
