package dev.pranav.reef.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object WebsiteBlocklist {
    private lateinit var sharedPreferences: SharedPreferences

    fun init(context: Context) {
        sharedPreferences = context.getSharedPreferences("website_blocklist", Context.MODE_PRIVATE)
    }

    fun isBlocked(domain: String): Boolean {
        return sharedPreferences.getBoolean(domain, false)
    }

    fun addDomain(domain: String) {
        sharedPreferences.edit { putBoolean(domain, true) }
    }

    fun removeDomain(domain: String) {
        sharedPreferences.edit { remove(domain) }
    }

    fun getBlockedDomains(): Set<String> {
        return sharedPreferences.all.keys
    }
}
