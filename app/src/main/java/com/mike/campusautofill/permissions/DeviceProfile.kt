package com.mike.campusautofill.permissions

import com.mike.campusautofill.R
import java.util.Locale

/** Brand families select guidance, not a guarantee about a particular ROM version. */
enum class DeviceProfile(val label: String, val guidance: Int) {
    OPLUS("OPPO / 一加 / realme", R.string.guide_oplus),
    XIAOMI("小米 / Redmi / POCO", R.string.guide_xiaomi),
    VIVO("vivo / iQOO", R.string.guide_vivo),
    HUAWEI("华为（兼容 Android 的系统）", R.string.guide_huawei),
    HONOR("荣耀", R.string.guide_honor),
    SAMSUNG("三星", R.string.guide_samsung),
    GENERIC("通用 Android", R.string.guide_generic);

    companion object {
        fun detect(manufacturer: String, brand: String): DeviceProfile {
            val names = setOf(manufacturer, brand).map { it.trim().lowercase(Locale.ROOT) }
            return when {
                names.any { it in setOf("oppo", "oplus", "oneplus", "realme") } -> OPLUS
                names.any { it in setOf("xiaomi", "redmi", "poco") } -> XIAOMI
                names.any { it in setOf("vivo", "iqoo") } -> VIVO
                // Some HONOR devices report HUAWEI as their manufacturer.
                "honor" in names -> HONOR
                "huawei" in names -> HUAWEI
                "samsung" in names -> SAMSUNG
                else -> GENERIC
            }
        }
    }
}
