package org.las2mile.scrcpy.history

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class ConnectionHistoryManager private constructor(context: Context) {

    data class HistoryItem(
        var address: String,
        var type: String, // "wifi" or "usb"
        var displayName: String,
        var timestamp: Long
    )

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun addHistory(address: String?, type: String, displayName: String?) {
        if (address.isNullOrEmpty()) return

        val list = historyList.toMutableList()
        // Remove existing item with same address if present
        val iterator = list.iterator()
        while (iterator.hasNext()) {
            if (address.equals(iterator.next().address, ignoreCase = true)) {
                iterator.remove()
                break
            }
        }

        // Add to top
        list.add(0, HistoryItem(address, type, displayName ?: address, System.currentTimeMillis()))

        // Trim to MAX_HISTORY_ITEMS
        val trimmedList = if (list.size > MAX_HISTORY_ITEMS) {
            list.subList(0, MAX_HISTORY_ITEMS)
        } else {
            list
        }

        saveList(trimmedList)
    }

    @get:Synchronized
    val historyList: List<HistoryItem>
        get() {
            val list = mutableListOf<HistoryItem>()
            val jsonStr = prefs.getString(KEY_HISTORY, null)
            if (jsonStr.isNullOrEmpty()) {
                return list
            }

            try {
                val arr = JSONArray(jsonStr)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val address = obj.optString("address")
                    val type = obj.optString("type", "wifi")
                    val displayName = obj.optString("displayName", address)
                    val timestamp = obj.optLong("timestamp", 0)
                    if (!address.isNullOrEmpty()) {
                        list.add(HistoryItem(address, type, displayName, timestamp))
                    }
                }
            } catch (ignored: Exception) {
            }

            return list
        }

    @Synchronized
    fun getRecentAddresses(maxCount: Int): List<String> {
        val addresses = mutableListOf<String>()
        val list = historyList
        for (item in list) {
            if ("wifi" == item.type && item.address.isNotEmpty()) {
                addresses.add(item.address)
                if (addresses.size >= maxCount) {
                    break
                }
            }
        }
        return addresses
    }

    @Synchronized
    fun deleteHistory(address: String?) {
        if (address.isNullOrEmpty()) return
        val list = historyList.toMutableList()
        var changed = false
        val iterator = list.iterator()
        while (iterator.hasNext()) {
            if (address.equals(iterator.next().address, ignoreCase = true)) {
                iterator.remove()
                changed = true
                break
            }
        }
        if (changed) {
            saveList(list)
        }
    }

    @Synchronized
    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    private fun saveList(list: List<HistoryItem>) {
        try {
            val arr = JSONArray()
            for (item in list) {
                val obj = JSONObject()
                obj.put("address", item.address)
                obj.put("type", item.type)
                obj.put("displayName", item.displayName)
                obj.put("timestamp", item.timestamp)
                arr.put(obj)
            }
            prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
        } catch (ignored: Exception) {
        }
    }

    companion object {
        private const val PREF_NAME = "scrcpy_prefs"
        private const val KEY_HISTORY = "pref_connection_history_json"
        private const val MAX_HISTORY_ITEMS = 20

        @Volatile
        private var instance: ConnectionHistoryManager? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): ConnectionHistoryManager {
            return instance ?: ConnectionHistoryManager(context).also { instance = it }
        }
    }
}
