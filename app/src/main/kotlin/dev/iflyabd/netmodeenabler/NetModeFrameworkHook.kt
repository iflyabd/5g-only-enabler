package dev.iflyabd.netmodeenabler

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Framework-side hooks (android/system_server + com.android.phone):
 * - Unhide carrier-gated Preferred Network Type UI everywhere by forcing the
 *   hide_* carrier-config flags to false.
 * - Log setAllowedNetworkTypes* calls so LSPosed logs show exactly what the
 *   modem was asked to do (no behavior change here; Settings is privileged
 *   and applies USER-reason changes itself).
 */
object NetModeFrameworkHook {

    private const val TAG = "NetModeEnabler"

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookCarrierUnhide(lpparam)
        hookPhoneLogging(lpparam)
    }

    fun hookCarrierUnhide(lpparam: XC_LoadPackage.LoadPackageParam) {
        // CarrierConfigManager.getConfigForSubId -> clear hide flags in the bundle.
        try {
            XposedHelpers.findAndHookMethod(
                "android.telephony.CarrierConfigManager",
                lpparam.classLoader,
                "getConfigForSubId",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val bundle = param.result as? android.os.PersistableBundle ?: return
                            var changed = false
                            for (k in HIDE_KEYS) {
                                try {
                                    if (bundle.getBoolean(k, false)) {
                                        XposedHelpers.callMethod(bundle, "putBoolean", k, false)
                                        changed = true
                                    }
                                } catch (_: Throwable) {
                                }
                            }
                            if (changed) {
                                XposedBridge.log("$TAG: cleared carrier hide flags for sub=${param.args.getOrNull(0)}")
                            }
                        } catch (_: Throwable) {
                        }
                    }
                },
            )
            XposedBridge.log("$TAG: hooked CarrierConfigManager.getConfigForSubId")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: CarrierConfigManager hook failed: $e")
        }

        // Belt and braces: any hide_* network/carrier read returns false.
        val hideHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val key = param.args.getOrNull(0) as? String ?: return
                    if (isHideKey(key)) param.result = false
                } catch (_: Throwable) {
                }
            }
        }
        try {
            XposedHelpers.findAndHookMethod(
                "android.os.PersistableBundle", lpparam.classLoader,
                "getBoolean", String::class.java, hideHook,
            )
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.findAndHookMethod(
                "android.os.PersistableBundle", lpparam.classLoader,
                "getBoolean", String::class.java, Boolean::class.javaPrimitiveType, hideHook,
            )
        } catch (_: Throwable) {
        }
    }

    private val HIDE_KEYS = listOf(
        "hide_enabled_network_mode_bool",
        "hide_preferred_network_type_bool",
        "hide_carrier_network_settings_bool",
        "hide_lte_plus_data_icon_bool",
        "world_phone_bool",
    )

    private fun isHideKey(key: String): Boolean {
        val k = key.lowercase()
        return k.startsWith("hide_") &&
            (k.contains("network") || k.contains("carrier") || k.contains("preferred"))
    }

    private fun hookPhoneLogging(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android" && lpparam.packageName != "com.android.phone") return
        val logHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    XposedBridge.log("$TAG: ${param.method.declaringClass.simpleName}.${param.method.name} args=${param.args.toList()}")
                } catch (_: Throwable) {
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    XposedBridge.log("$TAG: -> result=${param.result}")
                } catch (_: Throwable) {
                }
            }
        }
        for (cls in listOf(
            "com.android.internal.telephony.Phone",
            "com.android.internal.telephony.PhoneInterfaceManager",
            "com.android.phone.PhoneInterfaceManager",
        )) {
            try {
                val clazz = XposedHelpers.findClass(cls, lpparam.classLoader)
                var n = 0
                for (m in clazz.declaredMethods) {
                    if (!m.name.contains("AllowedNetworkType", ignoreCase = true) &&
                        !m.name.contains("PreferredNetworkType", ignoreCase = true)
                    ) {
                        continue
                    }
                    try {
                        XposedBridge.hookMethod(m, logHook)
                        n++
                    } catch (_: Throwable) {
                    }
                }
                if (n > 0) XposedBridge.log("$TAG: logging $n network-type methods on $cls")
            } catch (_: Throwable) {
            }
        }
    }
}
