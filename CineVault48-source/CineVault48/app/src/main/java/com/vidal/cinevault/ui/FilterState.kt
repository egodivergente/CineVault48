package com.vidal.cinevault.ui

import com.vidal.cinevault.model.ImageType

/** UI filter values retained while MainActivity is open. */
data class FilterState(
    val project: String? = null,
    val type: ImageType? = null,
    val datePreset: DatePreset = DatePreset.ALL,
    val expiringOnly: Boolean = false
)

/** Friendly date ranges shown in the mobile filter dialog. */
enum class DatePreset(val displayName: String, val days: Int?) {
    ALL("Cualquier fecha", null),
    LAST_24_HOURS("Últimas 24 horas", 1),
    LAST_7_DAYS("Últimos 7 días", 7),
    LAST_30_DAYS("Últimos 30 días", 30)
}
