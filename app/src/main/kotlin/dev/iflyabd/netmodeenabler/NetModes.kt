package dev.iflyabd.netmodeenabler

import android.content.Context
import android.telephony.TelephonyManager
import de.robv.android.xposed.XposedBridge

/**
 * The 4 extra Preferred Network Type modes this module adds, plus the
 * modem bitmask used to apply each one on Android 12+ (allowed-network-types
 * API) and the legacy RIL NETWORK_MODE int used as the ListPreference value.
 *
 * RIL ints (ril.h PREF_NET_TYPE_* series, matches OnePlus 11R values where
 * 24 = LTE/TD/GSM, 26 = LTE auto, 33 = NR/LTE/TD/GSM):
 * - 3G Only (WCDMA only) = 2
 * - 4G Only (LTE only)   = 11
 * - 5G Only (NR only)    = 27
 * - 5G/4G Only (NR/LTE)  = 29
 */
object NetModes {
    const val WCDMA_ONLY = 2
    const val LTE_ONLY = 11
    const val NR_ONLY = 27
    const val NR_LTE = 29

    data class Mode(val networkMode: Int, val label: String)

    val EXTRA_MODES = listOf(
        Mode(NR_ONLY, "5G Only"),
        Mode(LTE_ONLY, "4G Only"),
        Mode(WCDMA_ONLY, "3G Only"),
        Mode(NR_LTE, "5G/4G Only"),
    )

    val EXTRA_VALUES: Set<String> = EXTRA_MODES.map { it.networkMode.toString() }.toSet()

    fun labelFor(networkMode: Int): String? = EXTRA_MODES.firstOrNull { it.networkMode == networkMode }?.label

    fun isExtra(value: Any?): Boolean = value != null && EXTRA_VALUES.contains(value.toString())

    // ---------- allowed-network-types bitmasks (resolved via reflection) ----------

    private fun bitmaskField(tmClass: Class<*>, name: String, fallback: Long): Long {
        return try {
            (tmClass.getField(name).get(null) as? Long) ?: fallback
        } catch (_: Throwable) {
            fallback
        }
    }

    /** Bitmask that enables exactly [networkMode]'s radio techs. */
    fun maskFor(networkMode: Int, tmClass: Class<*>? = null): Long {
        // Exact OPlus/RIL masks, captured from this modem's own write path
        // (Phone.setAllowedNetworkTypes arg2=mode). Standard AOSP RAF values.
        when (networkMode) {
            NR_ONLY -> return 850943L // NR + LTE_CA + TD/GSM/HSPAP/LTE/EHRPD + UMTS-family/EDGE/GPRS/CDMA-group
            NR_LTE -> return 856064L // NR + LTE_CA + IWLAN + LTE
            LTE_ONLY -> return 266240L // LTE_CA + LTE
            WCDMA_ONLY -> return 17284L // GSM + HSPA/HSUPA/HSDPA/UMTS
        }
        val cls = tmClass ?: try {
            TelephonyManager::class.java
        } catch (_: Throwable) {
            null
        }
        // AOSP fallbacks (NetworkTypeBitMask, API 30+): LTE=4096, NR=524288,
        // LTE_CA=262144 (older builds 131072), UMTS family below.
        val lte = if (cls != null) bitmaskField(cls, "NETWORK_TYPE_BITMASK_LTE", 4096L) else 4096L
        val nr = if (cls != null) bitmaskField(cls, "NETWORK_TYPE_BITMASK_NR", 524288L) else 524288L
        var lteCa = if (cls != null) bitmaskField(cls, "NETWORK_TYPE_BITMASK_LTE_CA", 262144L) else 262144L
        if (lteCa == 0L) lteCa = 262144L
        val umts = if (cls != null) bitmaskField(cls, "NETWORK_TYPE_BITMASK_UMTS", 4L) else 4L
        val hsdpa = if (cls != null) bitmaskField(cls, "NETWORK_TYPE_BITMASK_HSDPA", 128L) else 128L
        val hsupa = if (cls != null) bitmaskField(cls, "NETWORK_TYPE_BITMASK_HSUPA", 256L) else 256L
        val hspa = if (cls != null) bitmaskField(cls, "NETWORK_TYPE_BITMASK_HSPA", 512L) else 512L
        val hspap = if (cls != null) bitmaskField(cls, "NETWORK_TYPE_BITMASK_HSPAP", 8192L) else 8192L
        val tdscdma = if (cls != null) bitmaskField(cls, "NETWORK_TYPE_BITMASK_TD_SCDMA", 32768L) else 32768L
        val lteFamily = lte or lteCa or 131072L // cover both LTE_CA encodings seen in the wild
        val wcdmaFamily = umts or hsdpa or hsupa or hspa or hspap or tdscdma
        return when (networkMode) {
            NR_ONLY -> nr
            LTE_ONLY -> lteFamily
            WCDMA_ONLY -> wcdmaFamily
            NR_LTE -> nr or lteFamily
            else -> nr or lteFamily or wcdmaFamily
        }
    }

    /** Union of every tech we manage, used to stop stock filtering from hiding entries. */
    fun fullMask(tmClass: Class<*>? = null): Long {
        return maskFor(NR_ONLY, tmClass) or maskFor(LTE_ONLY, tmClass) or maskFor(WCDMA_ONLY, tmClass)
    }

    fun reasonUser(tmClass: Class<*>? = null): Int {
        val cls = tmClass ?: try {
            TelephonyManager::class.java
        } catch (_: Throwable) {
            return 0
        }
        return try {
            cls.getField("ALLOWED_NETWORK_TYPES_REASON_USER").getInt(null)
        } catch (_: Throwable) {
            0
        }
    }

    /** Known stock masks (mode -> mask), captured from the modem's write path. */
    private val STOCK_MASKS = mapOf(33 to 916479L, 9 to 316295L, 1 to 32771L)

    /** Every observed modem write per subId: "mode:mask:timeMs" (stock + ours). */
    val observedWrites = java.util.concurrent.ConcurrentHashMap<Int, String>()

    /** Last successful write per subId: "mode:mask:timeMs" (same-process memory). */
    private val lastWrite = java.util.concurrent.ConcurrentHashMap<Int, String>()

    /**
     * Read back the modem's USER-reason allowed mask for [subId] and map it to
     * one of our 4 modes. Returns null for any stock/unknown state (leave UI alone).
     */
    fun currentMode(context: Context, subId: Int): Int? {
        try {
            val tm = context.getSystemService(TelephonyManager::class.java) ?: return null
            val subTm = try {
                if (subId != Int.MIN_VALUE) tm.createForSubscriptionId(subId) else tm
            } catch (_: Throwable) {
                tm
            }
            val m = subTm.javaClass.methods.firstOrNull {
                it.name == "getAllowedNetworkTypesForReason" && it.parameterTypes.size == 1
            } ?: return null
            val mask = try {
                m.invoke(subTm, reasonUser(subTm.javaClass)) as? Long
            } catch (_: Throwable) {
                null
            } ?: return null
            // 1. Exact match to our modes.
            for (mode in listOf(NR_ONLY, NR_LTE, LTE_ONLY, WCDMA_ONLY)) {
                if (mask == maskFor(mode, subTm.javaClass)) return mode
            }
            // 2. Exact match to a known stock state -> definitely not ours.
            if (STOCK_MASKS.values.any { it == mask }) return null
            // 3. Recent write by us + live mask is a supermask of it (modem OR-ed
            //    extra bits) -> still ours. Only trusted for 120 s after the tap.
            try {
                val parts = lastWrite[subId]?.split(":") ?: return null
                if (parts.size != 3) return null
                val sm = parts[0].toIntOrNull() ?: return null
                val smm = parts[1].toLongOrNull() ?: return null
                val t = parts[2].toLongOrNull() ?: return null
                if (System.currentTimeMillis() - t > 120_000) return null
                if (sm in listOf(NR_ONLY, NR_LTE, LTE_ONLY, WCDMA_ONLY) && (mask and smm) == smm) {
                    return sm
                }
            } catch (_: Throwable) {
            }
        } catch (_: Throwable) {
        }
        return null
    }

    /**
     * Apply [networkMode] to [subId]'s modem. Tries the modern allowed-network-types
     * API first, falls back to the legacy setPreferredNetworkType int path.
     */
    fun applyMode(context: Context, subId: Int, networkMode: Int): Boolean {
        try {
            val tm = context.getSystemService(TelephonyManager::class.java) ?: run {
                XposedBridge.log("NetModeEnabler: no TelephonyManager")
                return false
            }
            val subTm = try {
                if (subId != Int.MIN_VALUE) tm.createForSubscriptionId(subId) else tm
            } catch (_: Throwable) {
                tm
            }
            val mask = maskFor(networkMode, subTm.javaClass)
            // 1. Modern path: setAllowedNetworkTypesForReason(REASON_USER, mask)
            try {
                val m = subTm.javaClass.methods.firstOrNull {
                    it.name == "setAllowedNetworkTypesForReason" && it.parameterTypes.size == 2
                }
                if (m != null) {
                    val ok = m.invoke(subTm, reasonUser(subTm.javaClass), mask) as? Boolean ?: false
                    XposedBridge.log("NetModeEnabler: setAllowedNetworkTypesForReason sub=$subId mode=$networkMode mask=$mask -> $ok")
                    if (ok) {
                        lastWrite[subId] = "$networkMode:$mask:${System.currentTimeMillis()}"
                        return true
                    }
                }
            } catch (e: Throwable) {
                XposedBridge.log("NetModeEnabler: allowed-types path failed: $e")
            }
            // 2. Legacy path: setPreferredNetworkType(int)
            try {
                val m = subTm.javaClass.methods.firstOrNull {
                    it.name == "setPreferredNetworkType" && it.parameterTypes.size == 1
                }
                if (m != null) {
                    val p0 = m.parameterTypes[0]
                    val arg: Any = when (p0) {
                        Int::class.javaPrimitiveType, Integer::class.java -> networkMode
                        Long::class.javaPrimitiveType, java.lang.Long::class.java -> networkMode.toLong()
                        else -> networkMode
                    }
                    val r = m.invoke(subTm, arg)
                    val ok = (r as? Boolean) ?: true
                    XposedBridge.log("NetModeEnabler: setPreferredNetworkType sub=$subId mode=$networkMode -> $ok")
                    return ok
                }
            } catch (e: Throwable) {
                XposedBridge.log("NetModeEnabler: legacy path failed: $e")
            }
        } catch (e: Throwable) {
            XposedBridge.log("NetModeEnabler: applyMode failed: $e")
        }
        return false
    }
}
