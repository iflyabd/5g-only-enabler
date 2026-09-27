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
                // Record every modem write: setAllowedNetworkTypes(reason, mask, msg)
                // carries the RIL mode in msg.arg2 and the subId in msg.arg1.
                // This is write-response truth — no re-read, no staleness.
                try {
                    if (param.method.name == "setAllowedNetworkTypes" && param.args.size == 3) {
                        val mask = (param.args[1] as? Number)?.toLong() ?: return
                        val msg = param.args[2] as? android.os.Message ?: return
                        val mode = msg.arg2
                        if (mode in 0..40) {
                            NetModes.observedWrites[msg.arg1] =
                                "$mode:$mask:${System.currentTimeMillis()}"
                            NetModes.lastWrittenMode = mode
                            NetModes.lastWrittenSub = msg.arg1
                            XposedBridge.log("$TAG: write observed sub=${msg.arg1} mode=$mode mask=$mask")
                        }
                    }
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
        hookStrictNrOnly(lpparam)
    }

    /**
     * "5G Only means 5G only": every modem write for RIL mode 27 gets its mask
     * tightened to the NR bit alone (524288), no LTE/UMTS fallback bits. All
     * write paths (Settings UI, dialer codes, ours) flow through
     * Phone.setAllowedNetworkTypes, so this single choke point covers them all.
     * Where no NR/SA exists the modem will show No Service instead of LTE.
     */
    private fun hookStrictNrOnly(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.phone") return
        try {
            val clazz = XposedHelpers.findClass("com.android.internal.telephony.Phone", lpparam.classLoader)
            var n = 0
            for (m in clazz.declaredMethods) {
                if (m.name != "setAllowedNetworkTypes" || m.parameterTypes.size != 3) continue
                // 3rd param must be a Message carrying arg2=mode.
                if (!android.os.Message::class.java.isAssignableFrom(m.parameterTypes[2])) continue
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val msg = param.args[2] as? android.os.Message ?: return
                                if (msg.arg2 != NetModes.NR_ONLY) return
                                val strict = NetModes.maskFor(NetModes.NR_ONLY)
                                val p1 = (param.method as? java.lang.reflect.Method)?.parameterTypes?.getOrNull(1)
                                param.args[1] = when (p1) {
                                    Int::class.javaPrimitiveType, Integer::class.java -> strict.toInt()
                                    else -> strict
                                }
                                XposedBridge.log("$TAG: tightened NR_ONLY sub=${msg.arg1} mask -> $strict")
                            } catch (e: Throwable) {
                                XposedBridge.log("$TAG: tighten failed: $e")
                            }
                        }
                    })
                    n++
                } catch (_: Throwable) {
                }
            }
            XposedBridge.log("$TAG: strict NR hook on $n overloads")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: strict NR hook failed: $e")
        }
    }
}
