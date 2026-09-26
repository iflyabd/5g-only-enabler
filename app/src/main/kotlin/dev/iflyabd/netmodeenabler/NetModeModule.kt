package dev.iflyabd.netmodeenabler

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

class NetModeModule : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log("NetModeEnabler: ${lpparam.packageName} / ${lpparam.processName}")
        when (lpparam.packageName) {
            "com.android.settings", "com.oplus.wirelesssettings", "com.android.phone" -> {
                // NOTE: on OPlus/OOS the SIM + Preferred-Network screens live in the
                // phone process (OplusSimSettingsActivity, OplusExportPreferredNetworkSettings),
                // so it needs the full Settings hooks, not just the framework ones.
                NetModeSettingsHook.init(lpparam)
                NetModeFrameworkHook.init(lpparam)
            }
            "android" -> {
                NetModeFrameworkHook.init(lpparam)
            }
        }
    }
}
