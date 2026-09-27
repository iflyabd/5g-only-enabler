package dev.iflyabd.netmodeenabler

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

class NetModeModule : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log("NetModeEnabler: ${lpparam.packageName} / ${lpparam.processName}")
        when (lpparam.packageName) {
            "com.android.phone" -> {
                // NOTE: on OPlus/OOS the SIM + Preferred-Network screens live in the
                // phone process (OplusSimSettingsActivity, OplusExportPreferredNetworkSettings).
                // Everything functional runs here; other processes are left alone so
                // Settings pages (e.g. Developer Options) can never be affected.
                NetModeSettingsHook.init(lpparam)
                NetModeFrameworkHook.init(lpparam)
                OplusPreferredNetworkHook.init(lpparam)
            }
            "android" -> {
                NetModeFrameworkHook.init(lpparam)
            }
            // com.android.settings / com.oplus.wirelesssettings: intentionally
            // untouched — no screen we manage lives there on OOS.
        }
    }
}
