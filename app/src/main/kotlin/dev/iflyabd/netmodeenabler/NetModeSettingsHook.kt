package dev.iflyabd.netmodeenabler

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.telephony.SubscriptionManager
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Settings-side hooks (com.android.settings + com.oplus.wirelesssettings).
 *
 * Strategy (works without knowing OnePlus's exact controller class names):
 * 1. Extend every Preferred-Network-Type-looking ListPreference with the 4 extra
 *    entries (values 27/11/2/29). Re-applied on every setEntries/setEntryValues
 *    and on every fragment start, so stock refreshes can't wipe it.
 * 2. Wrap the ListPreference's change listener: our 4 values are applied straight
 *    to the modem via TelephonyManager (subId resolved from the hosting fragment);
 *    every other value delegates to the stock controller untouched.
 * 3. Widen TelephonyManager.getAllowedNetworkTypesForReason so stock filtering
 *    keeps its own entries, and un-hide carrier-config-gated network-mode UI.
 */
object NetModeSettingsHook {

    private const val TAG = "NetModeEnabler"

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookListPreference(lpparam)
        hookFragments(lpparam)
        hookControllers(lpparam)
        hookAllowedTypes(lpparam)
        NetModeFrameworkHook.hookCarrierUnhide(lpparam)
        XposedBridge.log("$TAG: settings hooks installed in ${lpparam.packageName}")
    }

    // ---------- 1. ListPreference extension ----------

    /** Heuristic: is this ListPreference the Preferred Network Type list? */
    private fun isNetworkModeList(entries: Array<out CharSequence>?, values: Array<out CharSequence>?): Boolean {
        if (values.isNullOrEmpty()) return false
        val v = values.map { it.toString() }
        // Entry values of network-mode lists are small RIL NETWORK_MODE ints.
        val looksLikeModes = v.any {
            it.toIntOrNull()?.let { n -> n in 0..40 } == true
        }
        if (!looksLikeModes) return false
        if (v.any { it == "9" || it == "10" || it == "11" || it == "12" || it == "20" || it == "22" }) return true
        val text = (entries ?: emptyArray()).joinToString(" ").lowercase()
        return text.contains("5g") || text.contains("4g") || text.contains("lte") ||
            text.contains("wcdma") || text.contains("preferred network") ||
            text.contains("network mode") || text.contains("nr") ||
            text.contains("2g") || text.contains("3g")
    }

    private fun extendListPreference(lp: ListPreference) {
        try {
            val entries = lp.entries
            val values = lp.entryValues
            if (!isNetworkModeList(entries, values)) return
            val cur = values.map { it.toString() }.toMutableList()
            var added = false
            for (m in NetModes.EXTRA_MODES) {
                if (!cur.contains(m.networkMode.toString())) {
                    cur.add(m.networkMode.toString())
                    added = true
                }
            }
            if (!added) return
            val oldEntries = entries?.map { it.toString() } ?: emptyList()
            val allEntries: List<CharSequence> = oldEntries + NetModes.EXTRA_MODES.map { it.label }
            val newEntries: Array<CharSequence> = allEntries.toTypedArray()
            val allValues: List<CharSequence> = cur
            val newValues: Array<CharSequence> = allValues.toTypedArray()
            lp.entries = newEntries
            lp.entryValues = newValues
            XposedBridge.log("$TAG: extended '${lp.key}' entries=${newEntries.toList()} values=${newValues.toList()}")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: extend failed: $e")
        }
    }

    private fun hookListPreference(lpparam: XC_LoadPackage.LoadPackageParam) {
        val after = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    (param.thisObject as? ListPreference)?.let { extendListPreference(it) }
                } catch (_: Throwable) {
                }
            }
        }
        for (m in listOf("setEntries", "setEntryValues")) {
            try {
                XposedHelpers.findAndHookMethod(
                    "androidx.preference.ListPreference",
                    lpparam.classLoader,
                    m,
                    Array<CharSequence>::class.java,
                    after,
                )
                XposedBridge.log("$TAG: hooked ListPreference.$m")
            } catch (e: Throwable) {
                XposedBridge.log("$TAG: ListPreference.$m hook failed: $e")
            }
        }
    }

    // ---------- 2. fragment scan + listener wrapping ----------

    private fun hookFragments(lpparam: XC_LoadPackage.LoadPackageParam) {
        val scan = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    scanFragment(param.thisObject)
                } catch (e: Throwable) {
                    XposedBridge.log("$TAG: fragment scan failed: $e")
                }
            }
        }
        for (cls in listOf(
            "androidx.preference.PreferenceFragmentCompat",
            "com.android.settings.dashboard.DashboardFragment",
        )) {
            try {
                XposedHelpers.findAndHookMethod(cls, lpparam.classLoader, "onStart", scan)
                XposedBridge.log("$TAG: hooked $cls.onStart")
            } catch (e: Throwable) {
                XposedBridge.log("$TAG: $cls hook failed: $e")
            }
        }
    }

    private fun scanFragment(fragment: Any) {
        val screen = try {
            XposedHelpers.callMethod(fragment, "getPreferenceScreen") as? PreferenceGroup ?: return
        } catch (_: Throwable) {
            return
        }
        val subId = resolveSubId(fragment)
        walkGroup(screen, subId)
    }

    private fun walkGroup(group: PreferenceGroup, subId: Int) {
        for (i in 0 until group.preferenceCount) {
            val p = try {
                group.getPreference(i)
            } catch (_: Throwable) {
                continue
            }
            when (p) {
                is PreferenceGroup -> walkGroup(p, subId)
                is ListPreference -> {
                    extendListPreference(p)
                    ensureWrapped(p, subId)
                }
            }
        }
    }

    private fun ensureWrapped(lp: ListPreference, scanSubId: Int) {
        try {
            if (!isNetworkModeList(lp.entries, lp.entryValues)) return
            if (XposedHelpers.getAdditionalInstanceField(lp, "netmode_wrapped") != null) return
            val orig = try {
                lp.onPreferenceChangeListener
            } catch (_: Throwable) {
                null
            }
            // Don't wrap twice around ourselves.
            if (orig != null && orig.javaClass.name.contains("NetModeWrapper")) return
            lp.onPreferenceChangeListener = NetModeWrapper(orig, scanSubId)
            XposedHelpers.setAdditionalInstanceField(lp, "netmode_wrapped", true)
            XposedBridge.log("$TAG: wrapped listener for '${lp.key}' scanSubId=$scanSubId")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: wrap failed: $e")
        }
    }

    private class NetModeWrapper(
        private val orig: Preference.OnPreferenceChangeListener?,
        private val scanSubId: Int,
    ) : Preference.OnPreferenceChangeListener {
        override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
            try {
                if (NetModes.isExtra(newValue)) {
                    val lp = preference as? ListPreference ?: return false
                    val mode = newValue.toString().toInt()
                    val subId = if (scanSubId != Int.MIN_VALUE) scanSubId else fallbackSubId(lp.context)
                    XposedBridge.log("$TAG: user picked ${NetModes.labelFor(mode)} ($mode) sub=$subId")
                    val ok = NetModes.applyMode(lp.context, subId, mode)
                    if (ok) {
                        lp.value = mode.toString()
                        lp.summary = NetModes.labelFor(mode)
                    }
                    return ok
                }
            } catch (e: Throwable) {
                XposedBridge.log("$TAG: wrapper failed: $e")
                return false
            }
            return try {
                orig?.onPreferenceChange(preference, newValue) ?: true
            } catch (e: Throwable) {
                XposedBridge.log("$TAG: stock listener failed: $e")
                false
            }
        }
    }

    // ---------- subId resolution ----------

    private fun resolveSubId(fragment: Any): Int {
        // 1. Fragment arguments (AOSP uses Settings.EXTRA_SUB_ID = "sub_id").
        try {
            val args = XposedHelpers.callMethod(fragment, "getArguments") as? android.os.Bundle
            if (args != null) {
                for (k in listOf("sub_id", "subscription_id", "subId", "subId_extra", "slot_id", "slot")) {
                    if (args.containsKey(k)) {
                        val v = args.getInt(k, Int.MIN_VALUE)
                        if (v != Int.MIN_VALUE && v >= 0) {
                            if (k.startsWith("slot")) return slotToSubId(fragment, v)
                            return v
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        // 2. Host activity intent extras.
        try {
            val activity = XposedHelpers.callMethod(fragment, "getActivity") as? Activity
            val intent = activity?.intent
            if (intent != null) {
                for (k in listOf("sub_id", "subscription_id", "subId")) {
                    val v = intent.getIntExtra(k, Int.MIN_VALUE)
                    if (v != Int.MIN_VALUE && v >= 0) return v
                }
            }
        } catch (_: Throwable) {
        }
        return Int.MIN_VALUE
    }

    private fun slotToSubId(fragment: Any, slot: Int): Int {
        return try {
            val ctx = XposedHelpers.callMethod(fragment, "getContext") as? Context ?: return Int.MIN_VALUE
            val sm = SubscriptionManager.from(ctx) ?: return Int.MIN_VALUE
            val infos = try {
                sm.activeSubscriptionInfoList
            } catch (_: Throwable) {
                null
            } ?: return Int.MIN_VALUE
            infos.firstOrNull { it.simSlotIndex == slot }?.subscriptionId ?: Int.MIN_VALUE
        } catch (_: Throwable) {
            Int.MIN_VALUE
        }
    }

    private fun fallbackSubId(context: Context): Int {
        var ctx: Context? = context
        var activity: Activity? = null
        while (ctx is ContextWrapper) {
            if (ctx is Activity) {
                activity = ctx
                break
            }
            ctx = ctx.baseContext
        }
        activity?.intent?.let { intent ->
            for (k in listOf("sub_id", "subscription_id", "subId")) {
                val v = intent.getIntExtra(k, Int.MIN_VALUE)
                if (v != Int.MIN_VALUE && v >= 0) return v
            }
        }
        return try {
            SubscriptionManager.getDefaultDataSubscriptionId()
        } catch (_: Throwable) {
            Int.MIN_VALUE
        }
    }

    // ---------- 3. direct AOSP controller hooks (exact mSubId) ----------

    private fun hookControllers(lpparam: XC_LoadPackage.LoadPackageParam) {
        val candidates = listOf(
            "com.android.settings.network.telephony.EnabledNetworkModePreferenceController",
        )
        for (cls in candidates) {
            // Intercept our values before stock logic rejects them.
            try {
                XposedHelpers.findAndHookMethod(
                    cls, lpparam.classLoader, "onPreferenceChange",
                    Preference::class.java, Object::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val newValue = param.args.getOrNull(1) ?: return
                                if (!NetModes.isExtra(newValue)) return
                                val mode = newValue.toString().toInt()
                                val subId = try {
                                    XposedHelpers.getIntField(param.thisObject, "mSubId")
                                } catch (_: Throwable) {
                                    Int.MIN_VALUE
                                }
                                val ctx = try {
                                    XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context
                                } catch (_: Throwable) {
                                    null
                                } ?: return
                                val sid = if (subId != Int.MIN_VALUE) {
                                    subId
                                } else {
                                    fallbackSubId(ctx)
                                }
                                XposedBridge.log("$TAG: controller intercept mode=$mode sub=$sid")
                                val ok = NetModes.applyMode(ctx, sid, mode)
                                if (ok) {
                                    (param.args.getOrNull(0) as? ListPreference)?.let { lp ->
                                        lp.value = mode.toString()
                                        lp.summary = NetModes.labelFor(mode)
                                    }
                                }
                                param.result = ok
                            } catch (e: Throwable) {
                                XposedBridge.log("$TAG: controller intercept failed: $e")
                            }
                        }
                    },
                )
                XposedBridge.log("$TAG: hooked $cls.onPreferenceChange")
            } catch (e: Throwable) {
                XposedBridge.log("$TAG: $cls hook failed (OPlus ROMs use the generic wrapper instead): $e")
            }
            // After stock refreshes the list, our setEntries hook re-extends it.
            // Nothing else needed here.
        }
    }

    // ---------- 4. widen allowed-types so stock entries stay visible ----------

    private fun hookAllowedTypes(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.telephony.TelephonyManager",
                lpparam.classLoader,
                "getAllowedNetworkTypesForReason",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val cur = param.result as? Long ?: return
                            val full = NetModes.fullMask(param.thisObject.javaClass)
                            if ((cur and full) != full) {
                                param.result = cur or full
                            }
                        } catch (_: Throwable) {
                        }
                    }
                },
            )
            XposedBridge.log("$TAG: hooked TelephonyManager.getAllowedNetworkTypesForReason")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: allowed-types hook failed: $e")
        }
    }
}
