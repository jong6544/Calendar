package com.jong6544.calendar

import android.content.Context

/** 위젯 색 테마. 앱의 "위젯 테마"에서 고르며 모든 위젯에 함께 적용된다. */
enum class WidgetTheme(
    val label: String,
    val backgroundRes: Int,
    val text: Int,
    val subText: Int,
    val accent: Int,
) {
    CREAM("크림", R.drawable.widget_bg_cream, 0xFF3A3024.toInt(), 0xFFA69B86.toInt(), 0xFFF28C28.toInt()),
    WHITE("화이트", R.drawable.widget_bg_white, 0xFF222222.toInt(), 0xFF999999.toInt(), 0xFF3B82F6.toInt()),
    SKY("스카이", R.drawable.widget_bg_sky, 0xFF1F3A52.toInt(), 0xFF7B97AE.toInt(), 0xFF2F80ED.toInt()),
    DARK("다크", R.drawable.widget_bg_dark, 0xFFF2F2F2.toInt(), 0xFF8C9099.toInt(), 0xFFF28C28.toInt());

    val sunday: Int get() = if (this == DARK) 0xFFFF7B7B.toInt() else 0xFFE5484D.toInt()
    val saturday: Int get() = if (this == DARK) 0xFF7FB2FF.toInt() else 0xFF3B82F6.toInt()

    companion object {
        private const val PREFS = "settings"
        private const val KEY = "widget_theme"

        fun current(context: Context): WidgetTheme {
            val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            return entries.firstOrNull { it.name == name } ?: CREAM
        }

        fun set(context: Context, theme: WidgetTheme) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, theme.name).apply()
        }
    }
}
