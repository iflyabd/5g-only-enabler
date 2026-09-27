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
        XposedBridge.log("$TAG: OPlus preferred-network hooks installed")
    }

    // ---------- parent SIM page summary ----------

    /**
     * OplusSimInfoActivity shows "Preferred network type: <label>". Stock maps the
     * modem mode with a hardcoded table that knows nothing of 27/11/2/29, so after
     * picking one of our modes the summary goes stale. Override it from live state.
     */
    private fun hookParentSummary(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val prefClass = XposedHelpers.findClass("androidx.preference.Preference", lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                prefClass,
                "setSummary",
                CharSequence::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val pref = param.thisObject
                            val title = (XposedHelpers.callMethod(pref, "getTitle") as? CharSequence)?.toString()
                                ?: return
                            if (!title.contains("preferred network", ignoreCase = true)) return
                            val ctx = XposedHelpers.callMethod(pref, "getContext") as? Context ?: return
                            val subId = activitySubId(ctx)
                            val mode = NetModes.currentMode(ctx, subId) ?: return
                            val label = NetModes.labelFor(mode) ?: return
                            val cur = (XposedHelpers.callMethod(pref, "getSummary") as? CharSequence)?.toString()
                            if (cur == label) return
                            XposedBridge.log("$TAG: parent summary sub=$subId -> $label")
                            XposedHelpers.callMethod(pref, "setSummary", label)
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
        setArrayFieldsDeep(act, newLabels, newValues)
        var adapterClass = "?"
        try {
            val adapter = XposedHelpers.getObjectField(act, "m")
            if (adapter != null) {
                adapterClass = adapter.javaClass.name
                dumpDataFields("adapter", adapter)
                setArrayFieldsDeep(adapter, newLabels, newValues)
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

    /** Set every String[] / Integer[] / int[] field on obj (whole hierarchy) to our arrays. */
    private fun setArrayFieldsDeep(obj: Any, labels: Array<String>, values: Array<Int>) {
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Object::class.java && !c.name.startsWith("android.app.")) {
            for (f in c.declaredFields) {
                try {
                    val t = f.type
                    if (t == Array<String>::class.java) {
                        f.isAccessible = true
                        f.set(obj, labels)
                        XposedBridge.log("$TAG: set ${c.simpleName}.${f.name} = labels[${labels.size}]")
                    } else if (t == Array<Int>::class.java) {
                        f.isAccessible = true
                        f.set(obj, values)
                        XposedBridge.log("$TAG: set ${c.simpleName}.${f.name} = values[${values.size}]")
                    } else if (t == IntArray::class.java) {
                        f.isAccessible = true
                        f.set(obj, values.toIntArray())
                        XposedBridge.log("$TAG: set ${c.simpleName}.${f.name} = int[${values.size}]")
                    } else if (java.util.List::class.java.isAssignableFrom(t)) {
                        mutateListField(obj, f, labels, values)
                    }
                } catch (e: Throwable) {
                    XposedBridge.log("$TAG: set field ${f.name} failed: $e")
                }
            }
            c = c.superclass
        }
    }

    private fun mutateListField(obj: Any, f: java.lang.reflect.Field, labels: Array<String>, values: Array<Int>) {
        try {
            f.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val list = f.get(obj) as? List<*> ?: return
            val fname = f.name.lowercase()
            val looksLabels = fname.contains("label") || fname.contains("title") ||
                fname.contains("entr") || fname.contains("text") || fname.contains("name") ||
                fname.contains("string") || fname.contains("item") || fname.contains("object")
            val looksValues = fname.contains("value") || fname.contains("mode") ||
                fname.contains("type") || fname.contains("id") || fname.contains("int")
            val first = list.firstOrNull()
            val useLabels = when {
                looksLabels && !looksValues -> true
                looksValues && !looksLabels -> false
                first is String -> true
                first is Int -> false
                list.isEmpty() && looksLabels -> true
                list.isEmpty() && looksValues -> false
                else -> return // unknown list (probably custom items) — leave, dump covers it
            }
            // REPLACE the reference: ArrayAdapter wraps arrays with fixed-size
            // Arrays.asList, whose clear()/add() throw. A fresh ArrayList works.
            val newData = java.util.ArrayList<Any?>(if (useLabels) labels.toList() else values.toList())
            f.set(obj, newData)
            XposedBridge.log("$TAG: set ${obj.javaClass.simpleName}.${f.name} = ${if (useLabels) "labels" else "values"}[${newData.size}] (replaced)")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: mutate list ${f.name} failed: $e")
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

    // ---------- helpers ----------

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
