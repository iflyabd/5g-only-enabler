package dev.iflyabd.netmodeenabler

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

class NetModeModule : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log("NetModeEnabler: ${lpparam.packageName} / ${lpparam.processName}")
        when (lpparam.packageName) {
            "com.android.settings", "com.oplus.wirelesssettings" -> {
                NetModeSettingsHook.init(lpparam)
                NetModeFrameworkHook.init(lpparam)
            }
            "com.android.phone", "android" -> {
                NetModeFrameworkHook.init(lpparam)
            }
        }
    }
}
