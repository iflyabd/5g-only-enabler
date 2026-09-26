package dev.iflyabd.netmodeenabler

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.telephony.SubscriptionManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Settings-side hooks (com.android.settings + com.oplus.wirelesssettings).
 *
 * NOTE: androidx.preference classes must NEVER be referenced directly here
 * (no imports, no casts, no `Foo::class.java`). The module APK's classloader
 * cannot see them and every direct reference dies with NoClassDefFoundError.
 * Everything below goes through the target process classloader + reflection.
 *
 * Strategy (works without knowing OnePlus's exact controller class names):
 * 1. Extend every Preferred-Network-Type-looking ListPreference with the 4 extra
 *    entries (values 27/11/2/29). Re-applied on every setEntries/setEntryValues
 *    and on every fragment start, so stock refreshes can't wipe it.
 * 2. Wrap the ListPreference's change listener (dynamic Proxy): our 4 values are
 *    applied straight to the modem via TelephonyManager (subId resolved from the
 *    hosting fragment); every other value delegates to the stock controller.
 * 3. Widen TelephonyManager.getAllowedNetworkTypesForReason so stock filtering
 *    keeps its own entries, and un-hide carrier-config-gated network-mode UI.
 */
object NetModeSettingsHook {

    private const val TAG = "NetModeEnabler"
    private const val LIST_PREF = "androidx.preference.ListPreference"
    private const val PREF = "androidx.preference.Preference"
    private const val PREF_GROUP = "androidx.preference.PreferenceGroup"
    private const val LISTENER = "androidx.preference.Preference\$OnPreferenceChangeListener"

    private fun listPrefClass(cl: ClassLoader): Class<*> =
        XposedHelpers.findClass(LIST_PREF, cl)

    private fun isListPref(obj: Any?, cl: ClassLoader): Boolean {
        if (obj == null) return false
        return try {
            listPrefClass(cl).isInstance(obj)
        } catch (_: Throwable) {
            false
        }
    }

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookListPreference(lpparam)
        hookFragments(lpparam)
        hookDiscovery(lpparam)
        hookControllers(lpparam)
        hookAllowedTypes(lpparam)
        NetModeFrameworkHook.hookCarrierUnhide(lpparam)
        XposedBridge.log("$TAG: settings hooks installed in ${lpparam.packageName}")
    }

    // ---------- 0. discovery: find what really builds the OPlus screen ----------

    private val WATCH = listOf(
        "network", "mobile", "sim", "telephon", "carrier", "radio",
        "prefer", "apn", "data", "wireless",
    )

    private fun hookDiscovery(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        val pkg = lpparam.packageName
        try {
            // Log EVERY fragment resume (class name only) — guarantees we see the
            // Preferred Network screen no matter what base class OPlus uses.
            XposedHelpers.findAndHookMethod(
                "androidx.fragment.app.Fragment",
                cl,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val name = param.thisObject.javaClass.name
                            XposedBridge.log("$TAG: frag-resume [$pkg] $name")
                            discover(param.thisObject, cl)
                        } catch (_: Throwable) {
                        }
                    }
                },
            )
            XposedBridge.log("$TAG: discovery hook installed")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: discovery hook failed: $e")
        }
        try {
            // OPlus screens are often plain Activities — catch those too.
            XposedHelpers.findAndHookMethod(
                "android.app.Activity",
                cl,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val act = param.thisObject as? Activity ?: return
                            val name = act.javaClass.name
                            XposedBridge.log("$TAG: activity-resume [$pkg] $name")
                            dumpWatchedActivity(act)
                        } catch (_: Throwable) {
                        }
                    }
                },
            )
            XposedBridge.log("$TAG: activity discovery installed")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: activity discovery failed: $e")
        }
    }

    private fun discover(fragment: Any, cl: ClassLoader) {        val name = fragment.javaClass.name
        val lower = name.lowercase()
        // Details only for watched screens; the class line itself is always logged.
        val interesting = WATCH.any { lower.contains(it) } ||
            lower.contains("choosenetwork") || lower.contains("networkselect") ||
            lower.contains("mobilenetwork") || lower.contains("preferred")
        if (!interesting) return
        XposedBridge.log("$TAG: screen fragment=$name")
        try {
            val args = XposedHelpers.callMethod(fragment, "getArguments") as? Bundle
            if (args != null) {
                XposedBridge.log("$TAG:   args=${args.keySet().joinToString { k -> "$k=${args.get(k)}" }}")
            }
        } catch (_: Throwable) {
        }
        val screen = try {
            XposedHelpers.callMethod(fragment, "getPreferenceScreen")
        } catch (_: Throwable) {
            null
        }
        if (screen == null) {
            XposedBridge.log("$TAG:   no PreferenceScreen (fully custom UI)")
            return
        }
        dumpGroup(screen, cl, "  ")
    }

    private fun dumpGroup(group: Any, cl: ClassLoader, indent: String) {
        val count = try {
            XposedHelpers.callMethod(group, "getPreferenceCount") as? Int ?: return
        } catch (_: Throwable) {
            return
        }
        val groupClass = try {
            XposedHelpers.findClass(PREF_GROUP, cl)
        } catch (_: Throwable) {
            null
        }
        for (i in 0 until count) {
            val p = try {
                XposedHelpers.callMethod(group, "getPreference", i)
            } catch (_: Throwable) {
                continue
            } ?: continue
            val cls = p.javaClass.name
            val key = try {
                XposedHelpers.callMethod(p, "getKey") as? String
            } catch (_: Throwable) {
                null
            }
            val title = try {
                (XposedHelpers.callMethod(p, "getTitle") as? CharSequence)?.toString()
            } catch (_: Throwable) {
                null
            }
            XposedBridge.log("$TAG: $indent$cls key=$key title=$title")
            // List-like prefs: dump entries/values whatever the class is.
            try {
                val e = XposedHelpers.callMethod(p, "getEntries") as? Array<*>
                val v = XposedHelpers.callMethod(p, "getEntryValues") as? Array<*>
                if (e != null || v != null) {
                    XposedBridge.log("$TAG: $indent  entries=${e?.map { it.toString() }} values=${v?.map { it.toString() }}")
                }
            } catch (_: Throwable) {
            }
            if (groupClass != null && groupClass.isInstance(p)) {
                dumpGroup(p, cl, "$indent  ")
            }
        }
    }

    // ---------- 0b. watched Activity dump (OPlus phone-process screens) ----------

    private fun isWatchedActivity(name: String): Boolean {
        val lower = name.lowercase()
        return WATCH.any { lower.contains(it) } ||
            lower.contains("choosenetwork") || lower.contains("networkselect") ||
            lower.contains("mobilenetwork") || lower.contains("preferred") ||
            lower.contains("exportpreferred") || lower.contains("siminfo") ||
            lower.contains("simsettings")
    }

    private fun dumpWatchedActivity(activity: Activity) {
        if (!isWatchedActivity(activity.javaClass.name)) return
        XposedBridge.log("$TAG: watched activity=${activity.javaClass.name}")
        try {
            val ex = activity.intent?.extras
            if (ex != null) {
                XposedBridge.log("$TAG:   extras=${ex.keySet().joinToString { k -> "$k=${ex.get(k)}" }}")
            }
        } catch (_: Throwable) {
        }
        // Class hierarchy + methods + fields: reveals the exact seam that builds
        // the radio list (only for the Preferred Network screen to limit noise).
        if (activity.javaClass.name.contains("PreferredNetwork", ignoreCase = true)) {
            try {
                var c: Class<*>? = activity.javaClass
                var depth = 0
                while (c != null && c.name != "android.app.Activity" && depth < 8) {
                    XposedBridge.log("$TAG:   class: ${c.name}")
                    try {
                        for (m in c.declaredMethods) {
                            XposedBridge.log("$TAG:     m: ${m.name}(${m.parameterTypes.joinToString { it.simpleName }}) -> ${m.returnType.simpleName}")
                        }
                    } catch (_: Throwable) {
                    }
                    try {
                        for (f in c.declaredFields) {
                            XposedBridge.log("$TAG:     f: ${f.name}: ${f.type.simpleName}")
                        }
                    } catch (_: Throwable) {
                    }
                    c = c.superclass
                    depth++
                }
            } catch (e: Throwable) {
                XposedBridge.log("$TAG:   class dump failed: $e")
            }
        }
        try {
            val root = activity.findViewById<android.view.View>(android.R.id.content)
            if (root == null) {
                XposedBridge.log("$TAG:   no content view")
                return
            }
            dumpCounter = 0
            dumpView(root, "  ", 0)
        } catch (e: Throwable) {
            XposedBridge.log("$TAG:   view dump failed: $e")
        }
    }

    private var dumpCounter = 0

    private fun dumpView(v: android.view.View, indent: String, depth: Int) {
        if (depth > 12 || dumpCounter > 400) return
        dumpCounter++
        try {
            val idName = try {
                v.resources?.getResourceEntryName(v.id)
            } catch (_: Throwable) {
                null
            }
            val text = (v as? android.widget.TextView)?.text?.toString()?.take(60)
            val checked = (v as? android.widget.Checkable)?.isChecked
            val extra = if (v is android.view.ViewGroup) " children=${v.childCount}" else ""
            XposedBridge.log("$TAG: $indent${v.javaClass.name} id=$idName text=$text checked=$checked$extra")
        } catch (_: Throwable) {
        }
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) {
                try {
                    dumpView(v.getChildAt(i), "$indent  ", depth + 1)
                } catch (_: Throwable) {
                }
            }
        }
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

    @Suppress("UNCHECKED_CAST")
    private fun entriesOf(lp: Any): Array<out CharSequence>? {
        return try {
            XposedHelpers.callMethod(lp, "getEntries") as? Array<out CharSequence>
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun valuesOf(lp: Any): Array<out CharSequence>? {
        return try {
            XposedHelpers.callMethod(lp, "getEntryValues") as? Array<out CharSequence>
        } catch (_: Throwable) {
            null
        }
    }

    private fun keyOf(lp: Any): String {
        return try {
            XposedHelpers.callMethod(lp, "getKey") as? String ?: "?"
        } catch (_: Throwable) {
            "?"
        }
    }

    private fun contextOf(lp: Any): Context? {
        return try {
            XposedHelpers.callMethod(lp, "getContext") as? Context
        } catch (_: Throwable) {
            null
        }
    }

    private fun extendListPreference(lp: Any, cl: ClassLoader) {
        try {
            if (!isListPref(lp, cl)) return
            val values = valuesOf(lp)
            if (!isNetworkModeList(entriesOf(lp), values)) return
            val cur = values!!.map { it.toString() }.toMutableList()
            var added = false
            for (m in NetModes.EXTRA_MODES) {
                if (!cur.contains(m.networkMode.toString())) {
                    cur.add(m.networkMode.toString())
                    added = true
                }
            }
            if (!added) return
            val oldEntries = entriesOf(lp)?.map { it.toString() } ?: emptyList()
            val allEntries: List<CharSequence> = oldEntries + NetModes.EXTRA_MODES.map { it.label }
            val allValues: List<CharSequence> = cur
            XposedHelpers.callMethod(lp, "setEntries", allEntries.toTypedArray())
            XposedHelpers.callMethod(lp, "setEntryValues", allValues.toTypedArray())
            XposedBridge.log("$TAG: extended '${keyOf(lp)}' values=${cur}")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: extend failed: $e")
        }
    }

    private fun hookListPreference(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        val after = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    extendListPreference(param.thisObject, cl)
                    ensureWrapped(param.thisObject, cl, Int.MIN_VALUE)
                } catch (_: Throwable) {
                }
            }
        }
        for (m in listOf("setEntries", "setEntryValues")) {
            try {
                XposedHelpers.findAndHookMethod(
                    LIST_PREF,
                    cl,
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
        val cl = lpparam.classLoader
        val scan = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    scanFragment(param.thisObject, cl)
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
                XposedHelpers.findAndHookMethod(cls, cl, "onStart", scan)
                XposedBridge.log("$TAG: hooked $cls.onStart")
            } catch (e: Throwable) {
                XposedBridge.log("$TAG: $cls hook failed: $e")
            }
        }
    }

    private fun scanFragment(fragment: Any, cl: ClassLoader) {
        val screen = try {
            XposedHelpers.callMethod(fragment, "getPreferenceScreen") ?: return
        } catch (_: Throwable) {
            return
        }
        val subId = resolveSubId(fragment)
        walkGroup(screen, cl, subId)
    }

    private fun walkGroup(group: Any, cl: ClassLoader, subId: Int) {
        val count = try {
            XposedHelpers.callMethod(group, "getPreferenceCount") as? Int ?: return
        } catch (_: Throwable) {
            return
        }
        val groupClass = try {
            XposedHelpers.findClass(PREF_GROUP, cl)
        } catch (_: Throwable) {
            null
        }
        for (i in 0 until count) {
            val p = try {
                XposedHelpers.callMethod(group, "getPreference", i)
            } catch (_: Throwable) {
                continue
            } ?: continue
            if (groupClass != null && groupClass.isInstance(p)) {
                walkGroup(p, cl, subId)
            } else if (isListPref(p, cl)) {
                extendListPreference(p, cl)
                ensureWrapped(p, cl, subId)
            }
        }
    }

    private fun ensureWrapped(lp: Any, cl: ClassLoader, scanSubId: Int) {
        try {
            if (!isListPref(lp, cl)) return
            if (!isNetworkModeList(entriesOf(lp), valuesOf(lp))) return
            if (XposedHelpers.getAdditionalInstanceField(lp, "netmode_wrapped") != null) return
            val listenerIface = try {
                XposedHelpers.findClass(LISTENER, cl)
            } catch (e: Throwable) {
                XposedBridge.log("$TAG: listener iface missing: $e")
                return
            }
            val orig = try {
                XposedHelpers.callMethod(lp, "getOnPreferenceChangeListener")
            } catch (_: Throwable) {
                null
            }
            if (orig != null && Proxy.isProxyClass(orig.javaClass)) {
                XposedHelpers.setAdditionalInstanceField(lp, "netmode_wrapped", true)
                return
            }
            val handler = NetModeListenerHandler(orig, scanSubId, cl)
            val proxy = Proxy.newProxyInstance(cl, arrayOf(listenerIface), handler)
            XposedHelpers.callMethod(lp, "setOnPreferenceChangeListener", proxy)
            XposedHelpers.setAdditionalInstanceField(lp, "netmode_wrapped", true)
            XposedBridge.log("$TAG: wrapped listener for '${keyOf(lp)}' scanSubId=$scanSubId")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: wrap failed: $e")
        }
    }

    private class NetModeListenerHandler(
        private val orig: Any?,
        private val scanSubId: Int,
        private val cl: ClassLoader,
    ) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
            if (method.name == "onPreferenceChange" && args != null && args.size == 2) {
                val preference = args[0]
                val newValue = args[1]
                try {
                    if (NetModes.isExtra(newValue)) {
                        val mode = newValue.toString().toInt()
                        val ctx = try {
                            XposedHelpers.callMethod(preference, "getContext") as? Context
                        } catch (_: Throwable) {
                            null
                        } ?: return false
                        val subId = if (scanSubId != Int.MIN_VALUE) scanSubId else fallbackSubId(ctx)
                        XposedBridge.log("$TAG: user picked ${NetModes.labelFor(mode)} ($mode) sub=$subId")
                        val ok = NetModes.applyMode(ctx, subId, mode)
                        if (ok) {
                            try {
                                XposedHelpers.callMethod(preference, "setValue", mode.toString())
                                XposedHelpers.callMethod(preference, "setSummary", NetModes.labelFor(mode))
                            } catch (_: Throwable) {
                            }
                        }
                        return ok
                    }
                } catch (e: Throwable) {
                    XposedBridge.log("$TAG: wrapper failed: $e")
                    return false
                }
                return try {
                    if (orig != null) {
                        (method.invoke(orig, preference, newValue) as? Boolean) ?: true
                    } else {
                        true
                    }
                } catch (e: Throwable) {
                    XposedBridge.log("$TAG: stock listener failed: $e")
                    false
                }
            }
            if (method.name == "toString" && (args == null || args.isEmpty())) {
                return "NetModeListenerHandler(orig=$orig)"
            }
            if (method.name == "hashCode" && (args == null || args.isEmpty())) {
                return System.identityHashCode(proxy)
            }
            if (method.name == "equals" && args != null && args.size == 1) {
                return proxy === args[0]
            }
            return null
        }
    }

    // ---------- subId resolution ----------

    private fun resolveSubId(fragment: Any): Int {
        // 1. Fragment arguments (AOSP uses Settings.EXTRA_SUB_ID = "sub_id").
        try {
            val args = XposedHelpers.callMethod(fragment, "getArguments") as? Bundle
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
        val cl = lpparam.classLoader
        val prefClass = try {
            XposedHelpers.findClass(PREF, cl)
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Preference class missing, skipping controller hooks: $e")
            return
        }
        val candidates = listOf(
            "com.android.settings.network.telephony.EnabledNetworkModePreferenceController",
        )
        for (cls in candidates) {
            // Intercept our values before stock logic rejects them.
            try {
                XposedHelpers.findAndHookMethod(
                    cls, cl, "onPreferenceChange",
                    prefClass, Object::class.java,
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
                                    try {
                                        val lp = param.args.getOrNull(0)
                                        if (lp != null && isListPref(lp, cl)) {
                                            XposedHelpers.callMethod(lp, "setValue", mode.toString())
                                            XposedHelpers.callMethod(lp, "setSummary", NetModes.labelFor(mode))
                                        }
                                    } catch (_: Throwable) {
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
                XposedBridge.log("$TAG: $cls hook skipped (generic wrapper covers it): $e")
            }
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
