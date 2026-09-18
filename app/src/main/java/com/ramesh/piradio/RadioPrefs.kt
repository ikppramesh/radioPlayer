package com.ramesh.piradio

import android.content.Context

object RadioPrefs {
    private const val PREFS_NAME = "radio_prefs"
    private const val KEY_URL = "stream_url"
    private const val KEY_NAME = "station_name"

    fun getUrl(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_URL, Constants.STREAM_URL) ?: Constants.STREAM_URL

    fun getStationName(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_NAME, Constants.STATION_NAME) ?: Constants.STATION_NAME

    fun save(context: Context, url: String, name: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URL, url)
            .putString(KEY_NAME, name)
            .apply()
    }
}
