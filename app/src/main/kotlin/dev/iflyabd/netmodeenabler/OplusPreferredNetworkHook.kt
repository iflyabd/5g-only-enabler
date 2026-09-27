package dev.iflyabd.netmodeenabler

import android.app.Activity
import android.content.Context
import android.telephony.SubscriptionManager
import android.widget.AdapterView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Direct hooks for OPlus's Preferred Network Type screen:
 * com.android.simsettings.network.OplusExportPreferredNetworkSettings
 * (runs in the com.android.phone process, works per SIM via the
 * `subscription` intent extra, so SIM1 and SIM2 are both covered).
 *
 * Anatomy (from runtime dump):
 * - String[] i = row labels, Integer[] j = RIL network-mode values
 * - m = list adapter, g = COUIListView
 * - A(int)/B(int,int)/o(int)/p(int)/t(int,int)/u(String)/x(String) = apply paths
 *
 * Fix:
 * 1. Append our 4 modes to the arrays (activity + adapter copies) and
 *    notifyDataSetChanged — idempotent, re-applied on every build point.
 * 2. Wrap the ListView click listener by POSITION (our rows are last).
 * 3. Intercept value-based apply methods, but only for real taps
 *    (click frames on the stack), so list-build calls pass through.
 */
object OplusPreferredNetworkHook {

    private const val TAG = "NetModeEnabler"
    private const val ACT = "com.android.simsettings.network.OplusExportPreferredNetworkSettings"
    private val EXTRA_INTS = setOf(27, 11, 2, 29)

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        val actClass = try {
            XposedHelpers.findClass(ACT, cl)
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: OPlus activity not found, skipping: $e")
            return
        }

        // 1. Extend after every build point.
        val extendAfter = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    extendActivity(param.thisObject)
                    if (param.method.name == "onResume") {
                        // Data loads async after onResume — retry delayed, idempotent.
                        scheduleRetry(param.thisObject)
                    }
                } catch (e: Throwable) {
                    XposedBridge.log("$TAG: extend hook failed: $e")
                }
            }
        }
        for (m in listOf("onCreate", "onResume", "y", "z")) {
            try {
                if (m == "onCreate") {
                    XposedHelpers.findAndHookMethod(actClass, m, android.os.Bundle::class.java, extendAfter)
                } else {
                    XposedHelpers.findAndHookMethod(actClass, m, extendAfter)
                }
                XposedBridge.log("$TAG: hooked OPlus $m")
            } catch (e: Throwable) {
                XposedBridge.log("$TAG: OPlus $m hook failed: $e")
            }
        }

        // 2. Value-based apply interception (click-stack guarded).
        val intercept = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val name = param.method.name
                    XposedBridge.log("$TAG: OPlus.$name args=${param.args.toList()}")
                    val hit = findExtraMode(param.args) ?: return
                    if (!isClickStack()) return
                    val act = param.thisObject as? Activity ?: return
                    val subId = subIdOf(act)
                    XposedBridge.log("$TAG: OPlus tap -> mode=$hit sub=$subId")
                    if (NetModes.applyMode(act, subId, hit)) {
                        try {
                            XposedHelpers.callMethod(act, "recreate")
                        } catch (_: Throwable) {
                        }
                    }
                    param.result = null // consume; all hooked apply methods return void
                } catch (e: Throwable) {
                    XposedBridge.log("$TAG: intercept failed: $e")
                }
            }
        }
        try {
            val int1 = Int::class.javaPrimitiveType
            XposedHelpers.findAndHookMethod(actClass, "A", int1, intercept)
            XposedHelpers.findAndHookMethod(actClass, "o", int1, intercept)
            XposedHelpers.findAndHookMethod(actClass, "p", int1, intercept)
            XposedHelpers.findAndHookMethod(actClass, "B", int1, int1, intercept)
            XposedHelpers.findAndHookMethod(actClass, "t", int1, int1, intercept)
            XposedHelpers.findAndHookMethod(actClass, "u", String::class.java, intercept)
            XposedHelpers.findAndHookMethod(actClass, "x", String::class.java, intercept)
            XposedBridge.log("$TAG: hooked OPlus apply methods")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: OPlus apply hooks failed: $e")
        }
        hookParentSummary(lpparam)
        hookCheckTelemetry(lpparam)
        hookClickTelemetry(lpparam)
        hookDotEnforcer(lpparam)
        XposedBridge.log("$TAG: OPlus preferred-network hooks installed")
    }

    // ---------- helpers ----------

    // ---------- check/click telemetry (finds who moves the radio) ----------

    private fun hookCheckTelemetry(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        // The rows are COUI cards with their own checked state — watch the setter.
        try {
            val cardClass = XposedHelpers.findClass(
                "com.coui.appcompat.cardlist.COUICardListSelectedItemLayout", cl,
            )
            if (!dumpedClasses.add("cardmethods")) {
            } else {
                try {
                    for (m in cardClass.declaredMethods) {
                        XposedBridge.log("$TAG: card method: ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
                    }
                } catch (_: Throwable) {
                }
            }
            XposedHelpers.findAndHookMethod(
                cardClass,
                "setIsSelected",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val sel = param.args.getOrNull(0)
                            // Which row? Find our card's label text via siblings.
                            var label = "?"
                            try {
                                val card = param.thisObject as? android.view.ViewGroup
                                if (card != null) {
                                    val texts = mutableListOf<String>()
                                    collectTexts(card, texts, 0)
                                    label = texts.firstOrNull { t ->
                                        t.contains("5G") || t.contains("4G") || t.contains("3G") || t.contains("2G")
                                    } ?: texts.firstOrNull() ?: "?"
                                }
                            } catch (_: Throwable) {
                            }
                            val stack = Thread.currentThread().stackTrace
                                .filter { !it.className.contains("Xposed") && !it.className.contains("LSPosed") && !it.className.contains("HookBridge") && !it.className.contains("LSPHooker") }
                                .take(14).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}" }
                            XposedBridge.log("$TAG: card setIsSelected row='$label' sel=$sel || $stack")
                        } catch (_: Throwable) {
                        }
                    }
                },
            )
            XposedBridge.log("$TAG: card check telemetry installed")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: card check telemetry failed: $e")
        }
        // The click receiver seen in logs: com.android.simsettings.activity.r1
        try {
            val r1 = XposedHelpers.findClass("com.android.simsettings.activity.r1", cl)
            try {
                for (m in r1.declaredMethods) {
                    XposedBridge.log("$TAG: r1 method: ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
                }
                for (f in r1.declaredFields) {
                    XposedBridge.log("$TAG: r1 field: ${f.name}: ${f.type.simpleName}")
                }
            } catch (_: Throwable) {
            }
            XposedHelpers.findAndHookMethod(
                r1,
                "onClick",
                android.view.View::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val v = param.args.getOrNull(0) as? android.view.View
                            val idName = try {
                                v?.resources?.getResourceEntryName(v.id)
                            } catch (_: Throwable) {
                                null
                            }
                            val a = try {
                                XposedHelpers.getIntField(param.thisObject, "a")
                            } catch (_: Throwable) {
                                -999
                            }
                            val b = try {
                                XposedHelpers.getIntField(param.thisObject, "b")
                            } catch (_: Throwable) {
                                -999
                            }
                            val stack = Thread.currentThread().stackTrace
                                .filter { !it.className.contains("Xposed") && !it.className.contains("LSPosed") && !it.className.contains("HookBridge") && !it.className.contains("LSPHooker") }
                                .take(12).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}" }
                            XposedBridge.log("$TAG: r1.onClick a=$a b=$b view=${v?.javaClass?.name} id=$idName || $stack")
                        } catch (_: Throwable) {
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val v = param.args.getOrNull(0) as? android.view.View ?: return
                            var c: Context? = v.context
                            while (c is android.content.ContextWrapper) {
                                if (c.javaClass.name == ACT) {
                                    scheduleVerify(c)
                                    return
                                }
                                c = c.baseContext
                            }
                        } catch (_: Throwable) {
                        }
                    }
                },
            )
            XposedBridge.log("$TAG: r1 telemetry installed")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: r1 telemetry failed: $e")
        }
    }

    private fun hookClickTelemetry(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.view.View",
                lpparam.classLoader,
                "performClick",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val v = param.thisObject as? android.view.View ?: return
                            var c: Context? = v.context
                            var found = false
                            while (c is android.content.ContextWrapper) {
                                if (c.javaClass.name == ACT) {
                                    found = true
                                    break
                                }
                                c = c.baseContext
                            }
                            if (!found) return
                            val text = (v as? android.widget.TextView)?.text?.toString()?.take(40)
                            val listener = try {
                                val li = XposedHelpers.getObjectField(v, "mListenerInfo")
                                XposedHelpers.getObjectField(li, "mOnClickListener")?.javaClass?.name
                            } catch (_: Throwable) {
                                "?"
                            }
                            XposedBridge.log("$TAG: click view=${v.javaClass.name} text=$text listener=$listener")
                        } catch (_: Throwable) {
                        }
                    }
                },
            )
            XposedBridge.log("$TAG: click telemetry installed")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: click telemetry failed: $e")
        }
    }

    // ---------- parent SIM page summary ----------

    /**
     * OplusSimInfoActivity does NOT use androidx Preference (the Preference-based
     * hook never fires), so override at the TextView level, tightly gated:
     * only inside OplusSimInfoActivity, only when the text is exactly one of the
     * 4 stock network labels, and only when live modem state matches our mode.
     * We modify args in beforeHook (no recursion: we never call setText).
     */
    private val STOCK_LABELS = setOf("5G preferred", "4G preferred", "3G preferred", "2G Only")

    private fun hookParentSummary(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.widget.TextView",
                lpparam.classLoader,
                "setText",
                CharSequence::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val newText = (param.args.getOrNull(0) as? CharSequence)?.toString()
                            if (newText == null || !STOCK_LABELS.contains(newText)) return
                            val tv = param.thisObject as? android.widget.TextView ?: return
                            var c: Context? = tv.context
                            var act: Activity? = null
                            while (c is android.content.ContextWrapper) {
                                if (c is Activity) {
                                    act = c
                                    break
                                }
                                c = c.baseContext
                            }
                            if (act == null || !act.javaClass.name.contains("OplusSimInfo")) return
                            val subId = activitySubId(act)
                            val mode = NetModes.currentMode(act, subId) ?: return
                            val label = NetModes.labelFor(mode) ?: return
                            if (newText == label) return
                            XposedBridge.log("$TAG: parent summary sub=$subId '$newText' -> '$label'")
                            param.args[0] = label
                        } catch (_: Throwable) {
                        }
                    }
                },
            )
            XposedBridge.log("$TAG: hooked parent summary")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: parent summary hook failed: $e")
        }
    }

    private fun activitySubId(context: Context): Int {
        var ctx: Context? = context
        var activity: Activity? = null
        while (ctx is android.content.ContextWrapper) {
            if (ctx is Activity) {
                activity = ctx
                break
            }
            ctx = ctx.baseContext
        }
        activity?.intent?.let { intent ->
            try {
                val sub = intent.getIntExtra("subscription", Int.MIN_VALUE)
                if (sub != Int.MIN_VALUE && sub >= 0) return sub
            } catch (_: Throwable) {
            }
            // Parent SIM page carries slotId/simid instead — map slot to subId.
            try {
                val slot = intent.getIntExtra("slotId", Int.MIN_VALUE)
                if (slot != Int.MIN_VALUE && slot >= 0) {
                    val sm = SubscriptionManager.from(activity) ?: return@let
                    val infos = try {
                        sm.activeSubscriptionInfoList
                    } catch (_: Throwable) {
                        null
                    }
                    infos?.firstOrNull { it.simSlotIndex == slot }?.subscriptionId?.let { return it }
                }
            } catch (_: Throwable) {
            }
        }
        return try {
            SubscriptionManager.getDefaultDataSubscriptionId()
        } catch (_: Throwable) {
            Int.MIN_VALUE
        }
    }

    // ---------- extension ----------

    /** Extras filed under their stock parent: 33->[27,29], 9->[11], 0->[2], 1->[]. */
    private val EXTRA_AFTER = mapOf(
        33 to listOf(27, 29),
        9 to listOf(11),
        0 to listOf(2),
    )

    /**
     * Rebuild canonical per-generation order from whatever stock currently holds.
     * Idempotent: already-extended arrays normalize to the same order.
     */
    private fun buildOrdered(stockLabels: List<String>, stockValues: List<Int>): Pair<Array<String>, Array<Int>> {
        val labels = mutableListOf<String>()
        val values = mutableListOf<Int>()
        for (i in stockValues.indices) {
            val v = stockValues[i]
            if (EXTRA_INTS.contains(v)) continue // dropped here, re-added via mapping below
            labels.add(stockLabels.getOrElse(i) { v.toString() })
            values.add(v)
            for (e in EXTRA_AFTER[v].orEmpty()) {
                labels.add(NetModes.labelFor(e) ?: e.toString())
                values.add(e)
            }
        }
        for (m in NetModes.EXTRA_MODES) {
            if (!values.contains(m.networkMode)) {
                labels.add(m.label)
                values.add(m.networkMode)
            }
        }
        return labels.toTypedArray() to values.toTypedArray()
    }

    private fun extendActivity(act: Any) {
        val labels = try {
            XposedHelpers.getObjectField(act, "i") as? Array<String>
        } catch (_: Throwable) {
            null
        }
        val values = try {
            @Suppress("UNCHECKED_CAST")
            XposedHelpers.getObjectField(act, "j") as? Array<Int>
        } catch (_: Throwable) {
            null
        }
        if (labels == null || values == null) return // data not loaded yet
        val (newLabels, newValues) = buildOrdered(labels.toList(), values.toList())
        if (labels.toList() == newLabels.toList() && values.toList() == newValues.toList()) {
            wrapClickListener(act) // already canonical; make sure clicks are wrapped
            refreshCheck(act, newValues)
            return
        }
        setArrayFieldsDeep(act, newLabels, newValues, false)
        var adapterClass = "?"
        try {
            val adapter = XposedHelpers.getObjectField(act, "m")
            if (adapter != null) {
                adapterClass = adapter.javaClass.name
                dumpDataFields("adapter", adapter)
                setArrayFieldsDeep(adapter, newLabels, newValues, true)
                try {
                    val before = XposedHelpers.callMethod(adapter, "getCount")
                    XposedHelpers.callMethod(adapter, "notifyDataSetChanged")
                    val after = try {
                        XposedHelpers.callMethod(adapter, "getCount")
                    } catch (_: Throwable) {
                        "?"
                    }
                    XposedBridge.log("$TAG: adapter count before=$before after=$after")
                } catch (e: Throwable) {
                    XposedBridge.log("$TAG: notifyDataSetChanged failed: $e")
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: adapter update failed: $e")
        }
        XposedBridge.log("$TAG: OPlus list extended (adapter=$adapterClass): ${newLabels.toList()} / ${newValues.toList()}")
        wrapClickListener(act)
        refreshCheck(act, newValues)
        scheduleVerify(act)
    }

    /** Point the list's checked row at the live modem mode (best-effort). */
    private fun refreshCheck(act: Any, values: Array<Int>) {
        try {
            val activity = act as? Activity ?: return
            val subId = try {
                activity.intent?.getIntExtra("subscription", Int.MIN_VALUE) ?: Int.MIN_VALUE
            } catch (_: Throwable) {
                Int.MIN_VALUE
            }
            val mode = NetModes.currentMode(activity, subId) ?: return
            val idx = values.indexOf(mode)
            if (idx < 0) return
            val lv = try {
                XposedHelpers.getObjectField(act, "g")
            } catch (_: Throwable) {
                null
            } ?: return
            try {
                XposedHelpers.callMethod(lv, "setItemChecked", idx, true)
                XposedHelpers.callMethod(lv, "setSelection", idx)
            } catch (_: Throwable) {
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Targeted writes only: activity fields i (String[]) / j (Integer[]) and the
     * adapter's mObjects list. The old hierarchy walk touched framework listener
     * lists — never again.
     */
    private fun setArrayFieldsDeep(obj: Any, labels: Array<String>, values: Array<Int>, isAdapter: Boolean) {
        if (!isAdapter) {
            try {
                val cls = obj.javaClass
                try {
                    val fi = cls.getDeclaredField("i")
                    if (fi.type == Array<String>::class.java) {
                        fi.isAccessible = true
                        fi.set(obj, labels)
                    }
                } catch (_: Throwable) {
                }
                try {
                    val fj = cls.getDeclaredField("j")
                    if (fj.type == Array<Int>::class.java) {
                        fj.isAccessible = true
                        fj.set(obj, values)
                    }
                } catch (_: Throwable) {
                }
            } catch (_: Throwable) {
            }
            return
        }
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Object::class.java) {
            try {
                val f = c.getDeclaredField("mObjects")
                f.isAccessible = true
                val cur = try {
                    f.get(obj)
                } catch (_: Throwable) {
                    null
                }
                if (cur is java.util.List<*>) {
                    val first = cur.firstOrNull()
                    if (first is String || cur.isEmpty()) {
                        f.set(obj, java.util.ArrayList<Any?>(labels.toList()))
                        XposedBridge.log("$TAG: set ${c.simpleName}.mObjects = labels[${labels.size}] (replaced)")
                        return
                    }
                }
            } catch (_: Throwable) {
            }
            if (c.name.startsWith("android.widget.ArrayAdapter")) break
            c = c.superclass
        }
    }

    private val dumpedClasses = mutableSetOf<String>()

    /** One-time dump of an object's data fields (arrays/lists with contents). */
    private fun dumpDataFields(tag: String, obj: Any) {
        val cls = obj.javaClass.name
        if (!dumpedClasses.add(cls)) return
        try {
            var c: Class<*>? = obj.javaClass
            while (c != null && c != Object::class.java && !c.name.startsWith("android.app.")) {
                for (f in c.declaredFields) {
                    try {
                        f.isAccessible = true
                        val v = f.get(obj)
                        val summary = when (v) {
                            is Array<*> -> "Array[${v.size}] first=${v.take(8).map { it.toString() }}"
                            is IntArray -> "int[${v.size}] first=${v.take(8).toList()}"
                            is java.util.List<*> -> "List[${v.size}] first=${v.take(8).map { it.toString() }}"
                            else -> v?.toString()?.take(80)
                        }
                        XposedBridge.log("$TAG: $tag ${c.simpleName}.${f.name}: ${f.type.simpleName} = $summary")
                    } catch (e: Throwable) {
                        XposedBridge.log("$TAG: $tag dump ${f.name} failed: $e")
                    }
                }
                c = c.superclass
            }
        } catch (_: Throwable) {
        }
    }

    private fun scheduleRetry(act: Any) {
        try {
            val actRef = java.lang.ref.WeakReference(act)
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            for (delay in listOf(500L, 1500L, 3000L)) {
                handler.postDelayed({
                    try {
                        actRef.get()?.let { extendActivity(it) }
                    } catch (_: Throwable) {
                    }
                }, delay)
            }
        } catch (_: Throwable) {
        }
    }

    // ---------- position-based click wrapper ----------

    private fun wrapClickListener(act: Any) {
        try {
            val listView = try {
                XposedHelpers.getObjectField(act, "g") ?: return
            } catch (_: Throwable) {
                return
            }
            if (XposedHelpers.getAdditionalInstanceField(listView, "netmode_wrapped") != null) return
            val orig = try {
                XposedHelpers.callMethod(listView, "getOnItemClickListener") as? AdapterView.OnItemClickListener
            } catch (_: Throwable) {
                null
            }
            val activity = act as? Activity ?: return
            val wrapper = object : AdapterView.OnItemClickListener {
                override fun onItemClick(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    try {
                        // Race-proof: resolve the tapped VALUE from the live arrays,
                        // never from position arithmetic (stock rebuilds to 4 rows
                        // between our extensions; positions alone lie in that window).
                        val vals = try {
                            @Suppress("UNCHECKED_CAST")
                            XposedHelpers.getObjectField(act, "j") as? Array<Int>
                        } catch (_: Throwable) {
                            null
                        }
                        val mode = vals?.getOrNull(position)
                        if (mode != null && EXTRA_INTS.contains(mode)) {
                            val subId = subIdOf(activity)
                            XposedBridge.log("$TAG: OPlus row tap pos=$position -> mode=$mode sub=$subId")
                            if (NetModes.applyMode(activity, subId, mode)) {
                                try {
                                    activity.recreate()
                                } catch (_: Throwable) {
                                }
                            }
                            return // consume
                        }
                    } catch (e: Throwable) {
                        XposedBridge.log("$TAG: row wrapper failed: $e")
                    }
                    try {
                        orig?.onItemClick(parent, view, position, id)
                    } catch (e: Throwable) {
                        XposedBridge.log("$TAG: stock click failed: $e")
                    }
                }
            }
            XposedHelpers.callMethod(listView, "setOnItemClickListener", wrapper)
            XposedHelpers.setAdditionalInstanceField(listView, "netmode_wrapped", true)
            XposedBridge.log("$TAG: OPlus click listener wrapped")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: click wrap failed: $e")
        }
    }

    // ---------- truth enforcement: dots follow the live modem, always ----------

    /**
     * Stock updates the radio dot from a stale read (modem hasn't applied the tap
     * yet), so the dot can land on the wrong row while the modem + parent page
     * hold the truth. This pass re-asserts the dot from live modem state after
     * every tap / rebuild. No-op when already correct (no flicker).
     */
    private fun scheduleVerify(act: Any) {
        try {
            val activity = act as? Activity ?: return
            if (XposedHelpers.getAdditionalInstanceField(act, "netmode_verify_at") == "pending") return
            XposedHelpers.setAdditionalInstanceField(act, "netmode_verify_at", "pending")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    XposedHelpers.removeAdditionalInstanceField(act, "netmode_verify_at")
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        verifyDots(act)
                    }
                } catch (_: Throwable) {
                }
            }, 700)
        } catch (_: Throwable) {
        }
    }

    private fun liveRilMode(activity: Activity, subId: Int): Int? {
        try {
            val tm = activity.getSystemService(android.telephony.TelephonyManager::class.java) ?: return null
            // Public API: TelephonyManager.getPreferredNetworkType(int subId)
            val m = tm.javaClass.methods.firstOrNull {
                it.name == "getPreferredNetworkType" && it.parameterTypes.size == 1
            } ?: return null
            val v = m.invoke(tm, subId) as? Int ?: return null
            if (v < 0) return null
            return v
        } catch (_: Throwable) {
            return null
        }
    }

    private fun verifyDots(act: Any) {
        try {
            val activity = act as? Activity ?: return
            val subId = subIdOf(activity)
            if (subId == Int.MIN_VALUE) return
            // Write-response truth: the last mode written for this sub (stock or ours).
            val rec = NetModes.observedWrites[subId]?.split(":")
            val mode = rec?.getOrNull(0)?.toIntOrNull()
            if (mode == null) {
                // No write observed in this process life — fall back to live mask read.
                val live = NetModes.currentMode(activity, subId)
                if (live == null) return
                enforceDots(act, live)
                return
            }
            enforceDots(act, mode)
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: verifyDots failed: $e")
        }
    }

    private fun enforceDots(act: Any, mode: Int) {
        try {
            @Suppress("UNCHECKED_CAST")
            val values = try {
                XposedHelpers.getObjectField(act, "j") as? Array<Int>
            } catch (_: Throwable) {
                null
            } ?: return
            @Suppress("UNCHECKED_CAST")
            val labels = try {
                XposedHelpers.getObjectField(act, "i") as? Array<String>
            } catch (_: Throwable) {
                null
            } ?: return
            val idx = values.indexOf(mode)
            if (idx < 0) return
            val expected = labels.getOrNull(idx) ?: return
            val activity = act as? Activity ?: return
            val root = try {
                activity.findViewById<android.view.View>(android.R.id.content)
            } catch (_: Throwable) {
                null
            } ?: return
            val cards = mutableListOf<android.view.View>()
            collectCards(root, cards)
            var corrected = 0
            var checkedNow = "?"
            for ((i, card) in cards.withIndex()) {
                val texts = mutableListOf<String>()
                collectTexts(card, texts, 0)
                val label = texts.firstOrNull { t ->
                    t.contains("5G") || t.contains("4G") || t.contains("3G") || t.contains("2G")
                } ?: continue
                val should = label == expected
                try {
                    val cur = XposedHelpers.callMethod(card, "getIsSelected") as? Boolean
                    if (cur == true && should) checkedNow = label
                    if (cur != should) {
                        XposedHelpers.callMethod(card, "setIsSelected", should)
                        corrected++
                    }
                } catch (_: Throwable) {
                }
                if (i == idx) checkedNow = if (should) label else checkedNow
            }
            XposedBridge.log("$TAG: verifyDots sub mode=$mode expected='$expected' was='$checkedNow' corrected=$corrected/${cards.size}")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: enforceDots failed: $e")
        }
    }

    private fun collectCards(v: android.view.View, out: MutableList<android.view.View>) {
        try {
            if (v.javaClass.name.contains("COUICardListSelectedItem")) {
                out.add(v)
                return
            }
        } catch (_: Throwable) {
        }
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) {
                try {
                    collectCards(v.getChildAt(i), out)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun findDot(card: android.view.View): android.widget.CompoundButton? {
        if (card is android.widget.CompoundButton) return card
        if (card is android.view.ViewGroup) {
            for (i in 0 until card.childCount) {
                try {
                    findDot(card.getChildAt(i))?.let { return it }
                } catch (_: Throwable) {
                }
            }
        }
        return null
    }

    private fun hookDotEnforcer(lpparam: XC_LoadPackage.LoadPackageParam) {
        // After stock touches any dot in our screen, schedule a truth pass.
        try {
            XposedHelpers.findAndHookMethod(
                "android.widget.CompoundButton",
                lpparam.classLoader,
                "setChecked",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val v = param.thisObject as? android.view.View ?: return
                            var c: Context? = v.context
                            while (c is android.content.ContextWrapper) {
                                if (c.javaClass.name == ACT) {
                                    scheduleVerify(c)
                                    return
                                }
                                c = c.baseContext
                            }
                        } catch (_: Throwable) {
                        }
                    }
                },
            )
            XposedBridge.log("$TAG: dot enforcer installed")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: dot enforcer failed: $e")
        }
    }

    private fun findExtraMode(args: Array<out Any?>): Int? {
        for (a in args) {
            if (a is Int && EXTRA_INTS.contains(a)) return a
            if (a is String) {
                a.toIntOrNull()?.let { if (EXTRA_INTS.contains(it)) return it }
                NetModes.EXTRA_MODES.firstOrNull { it.label == a }?.let { return it.networkMode }
            }
        }
        return null
    }

    private fun isClickStack(): Boolean {
        return try {
            Thread.currentThread().stackTrace.any {
                it.methodName == "onClick" || it.methodName == "onItemClick" ||
                    it.methodName == "performItemClick"
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun collectTexts(v: android.view.View, out: MutableList<String>, depth: Int) {
        if (depth > 6 || out.size > 8) return
        try {
            (v as? android.widget.TextView)?.text?.toString()?.take(40)?.let {
                if (it.isNotBlank()) out.add(it)
            }
        } catch (_: Throwable) {
        }
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) {
                try {
                    collectTexts(v.getChildAt(i), out, depth + 1)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun subIdOf(act: Activity): Int {
        try {
            val v = act.intent?.getIntExtra("subscription", Int.MIN_VALUE) ?: Int.MIN_VALUE
            if (v != Int.MIN_VALUE && v >= 0) return v
        } catch (_: Throwable) {
        }
        return try {
            SubscriptionManager.getDefaultDataSubscriptionId()
        } catch (_: Throwable) {
            Int.MIN_VALUE
        }
    }

    @Suppress("unused")
    private fun contextOf(act: Any): Context? = act as? Context
}
