package com.fire17.phoneinphone.ondevice

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.fire17.phoneinphone.backend.StageStatus
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executors

/**
 * Track B of the OnDevice experiment: Android Virtualization Framework (pKVM + crosvm).
 *
 * Every gate is checked in order and reported; we stop at the FIRST failing gate and say exactly
 * which one and why. Gate names mirror scion-avf SPIKE-1 (github.com/Scion-Project/scion-avf,
 * docs/SPIKE-1-AVF-LAUNCH.md) so results are comparable:
 *
 *   FEATURE_MISSING      android.software.virtualization_framework not declared
 *   PERMISSION_MISSING   MANAGE_VIRTUAL_MACHINE / USE_CUSTOM_VIRTUAL_MACHINE not granted (adb pm grant)
 *   HIDDEN_API_BLOCKED   VirtualMachineCustomImageConfig$Builder not reachable
 *   CAPABILITIES         VirtualMachineManager.getCapabilities() lacks non-protected VM support
 *   GUEST_FILES_MISSING  no kernel/initrd under files/guest/
 *   CONFIG_REJECTED      VirtualMachineConfig.build() threw
 *   VM_START_FAILED      create()/run() threw or onError fired
 *   VM_OUTPUT            console bytes observed (= a kernel booted in a VM inside THIS app)
 *
 * IMPORTANT, honest scope: this probe boots a LINUX kernel+initrd (the one documented to work in
 * scion's transport on a Pixel 10). Booting an ANDROID guest (Cuttlefish arm64 kernel + super.img
 * + a display path) is the NEXT unproven step and is reported as NOT_IMPLEMENTED, because AVF
 * gives third-party VMs no display device (scion's ashmem framebuffer hook is AGPL-3.0 and is
 * deliberately not copied here).
 *
 * The AVF classes are not in android.jar, so everything is reflection.
 */
class AvfProbe(private val context: Context, private val report: (String, String, StageStatus, String) -> Unit, private val log: (String) -> Unit) {

    private val vmPkg = "android.system.virtualmachine"

    fun run(): Boolean {
        // 0. API level
        if (Build.VERSION.SDK_INT < 34) {
            report("avf.api", "AVF: Android 14+", StageStatus.FAIL, "SDK ${Build.VERSION.SDK_INT}: framework-virtualization not public before 14")
            return false
        }
        report("avf.api", "AVF: Android 14+", StageStatus.OK, "SDK ${Build.VERSION.SDK_INT}")

        // 1. FEATURE_MISSING
        val hasFeature = context.packageManager.hasSystemFeature("android.software.virtualization_framework")
        if (!hasFeature) {
            report("avf.feature", "AVF: virtualization_framework feature", StageStatus.FAIL,
                "FEATURE_MISSING — this device/chip exposes no AVF (e.g. Snapdragon 8 Elite, most emulators)")
            return false
        }
        report("avf.feature", "AVF: virtualization_framework feature", StageStatus.OK, "declared")

        // 2. PERMISSION_MISSING
        val manage = context.checkSelfPermission(MANAGE) == PackageManager.PERMISSION_GRANTED
        val custom = context.checkSelfPermission(CUSTOM) == PackageManager.PERMISSION_GRANTED
        if (!manage || !custom) {
            report("avf.perm", "AVF: development permissions", StageStatus.FAIL,
                "PERMISSION_MISSING — MANAGE=${manage} CUSTOM=${custom}. Run scripts/avf-grant.sh (adb pm grant ×2)")
            return false
        }
        report("avf.perm", "AVF: development permissions", StageStatus.OK, "both granted")

        // 3. HIDDEN_API_BLOCKED — try the global exemption first (set by avf-grant.sh), then HiddenApiBypass.
        val exemptions = try {
            Settings.Global.getString(context.contentResolver, "hidden_api_blacklist_exemptions") ?: ""
        } catch (t: Throwable) { "" }
        val reachable = customBuilderReachable()
        if (!reachable) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("Landroid/system/virtualmachine/")
                log("HiddenApiBypass.addHiddenApiExemptions applied in-process")
            } catch (t: Throwable) {
                log("HiddenApiBypass failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        if (!customBuilderReachable()) {
            report("avf.hidden", "AVF: custom-image API reachable", StageStatus.FAIL,
                "HIDDEN_API_BLOCKED — global exemption='${exemptions.take(80)}'. Android 17 may block these regardless (Podroid #125)")
            return false
        }
        report("avf.hidden", "AVF: custom-image API reachable", StageStatus.OK,
            if (exemptions.contains("VirtualMachineCustomImageConfig")) "via global exemption" else "via HiddenApiBypass")

        // 4. Capabilities
        val vmmClass = try { Class.forName("$vmPkg.VirtualMachineManager") } catch (t: Throwable) { null }
        @Suppress("UNCHECKED_CAST")
        val vmm: Any? = try {
            vmmClass?.let { context.getSystemService(it as Class<Any>) }
        } catch (t: Throwable) { null }
        if (vmm == null) {
            report("avf.caps", "AVF: VirtualMachineManager", StageStatus.FAIL, "getSystemService returned null")
            return false
        }
        val caps = try { vmm.javaClass.getMethod("getCapabilities").invoke(vmm) as Int } catch (t: Throwable) {
            log("getCapabilities failed: ${rootCause(t).javaClass.simpleName}: ${rootCause(t).message}"); -1
        }
        // Read the constant reflectively; 2 is its value in AOSP main (fallback only).
        val nonProtectedBit = try { vmm.javaClass.getField("CAPABILITY_NON_PROTECTED_VM").getInt(null) } catch (t: Throwable) { 2 }
        val nonProtected = caps >= 0 && (caps and nonProtectedBit) != 0
        report("avf.caps", "AVF: non-protected VM capability", if (nonProtected) StageStatus.OK else StageStatus.FAIL,
            when {
                caps < 0 -> "getCapabilities() not callable (hidden-API or SecurityException — see log)"
                nonProtected -> "capabilities=0x${Integer.toHexString(caps)}"
                else -> "capabilities=0x${Integer.toHexString(caps)} — non-protected VMs unsupported on this SoC"
            })
        if (!nonProtected) return false

        // 5. GUEST_FILES_MISSING
        val guestDir = File(context.filesDir, "guest")
        val kernel = File(guestDir, "Image").takeIf { it.exists() } ?: File(guestDir, "linux")
        val initrd = File(guestDir, "initrd.img").takeIf { it.exists() } ?: File(guestDir, "initrd.gz")
        if (!kernel.exists()) {
            report("avf.files", "AVF: guest kernel/initrd", StageStatus.FAIL,
                "GUEST_FILES_MISSING — put an arm64 Image (+initrd) in ${guestDir.absolutePath} via scripts/avf-push-guest.sh")
            return false
        }
        report("avf.files", "AVF: guest kernel/initrd", StageStatus.OK,
            "${kernel.name} ${kernel.length() / 1024} KiB" + if (initrd.exists()) ", ${initrd.name} ${initrd.length() / 1024} KiB" else ", no initrd")

        // 6. CONFIG
        val config = try {
            buildConfig(kernel, initrd.takeIf { it.exists() })
        } catch (t: Throwable) {
            val root = rootCause(t)
            report("avf.config", "AVF: build custom VM config", StageStatus.FAIL,
                "CONFIG_REJECTED — ${root.javaClass.simpleName}: ${root.message}")
            return false
        }
        report("avf.config", "AVF: build custom VM config", StageStatus.OK, "1 vCPU, 512 MiB, non-protected, console captured")

        // 7. START
        return try {
            bootAndWatch(vmm, config)
        } catch (t: Throwable) {
            val root = rootCause(t)
            report("avf.run", "AVF: create + run VM", StageStatus.FAIL, "VM_START_FAILED — ${root.javaClass.simpleName}: ${root.message}")
            false
        }
    }

    private fun customBuilderReachable(): Boolean = try {
        val b = Class.forName("$vmPkg.VirtualMachineCustomImageConfig\$Builder")
        b.getDeclaredConstructor().newInstance()
        true
    } catch (t: Throwable) {
        log("custom builder probe: ${rootCause(t).javaClass.simpleName}: ${rootCause(t).message}")
        false
    }

    private fun buildConfig(kernel: File, initrd: File?): Any {
        val cbClass = Class.forName("$vmPkg.VirtualMachineCustomImageConfig\$Builder")
        val cb = cbClass.getDeclaredConstructor().newInstance()
        cbClass.getMethod("setName", String::class.java).invoke(cb, "pip-probe")
        cbClass.getMethod("setKernelPath", String::class.java).invoke(cb, kernel.absolutePath)
        if (initrd != null) cbClass.getMethod("setInitrdPath", String::class.java).invoke(cb, initrd.absolutePath)
        for (p in listOf("console=hvc0", "earlycon=uart8250,mmio,0x3f8", "panic=-1")) {
            cbClass.getMethod("addParam", String::class.java).invoke(cb, p)
        }
        val customCfg = cbClass.getMethod("build").invoke(cb)

        val bClass = Class.forName("$vmPkg.VirtualMachineConfig\$Builder")
        val b = bClass.getConstructor(Context::class.java).newInstance(context)
        bClass.getMethod("setProtectedVm", Boolean::class.javaPrimitiveType).invoke(b, false)
        bClass.getMethod("setMemoryBytes", Long::class.javaPrimitiveType).invoke(b, 512L * 1024 * 1024)
        // DEBUG_LEVEL_FULL = 1 (required for setVmOutputCaptured)
        bClass.getMethod("setDebugLevel", Int::class.javaPrimitiveType).invoke(b, 1)
        bClass.getMethod("setVmOutputCaptured", Boolean::class.javaPrimitiveType).invoke(b, true)
        val setCustom: Method = bClass.methods.first { it.name == "setCustomImageConfig" }
        setCustom.invoke(b, customCfg)
        return bClass.getMethod("build").invoke(b)!!
    }

    private fun bootAndWatch(vmm: Any, config: Any): Boolean {
        val vmmClass = vmm.javaClass
        val cfgClass = Class.forName("$vmPkg.VirtualMachineConfig")
        // fresh VM each run
        try { vmmClass.getMethod("delete", String::class.java).invoke(vmm, VM_NAME) } catch (ignored: Throwable) {}
        val vm = vmmClass.getMethod("create", String::class.java, cfgClass).invoke(vmm, VM_NAME, config)!!
        val vmClass = vm.javaClass

        val events = StringBuffer()
        val cbIface = Class.forName("$vmPkg.VirtualMachineCallback")
        val handler = InvocationHandler { proxy, m, args ->
            // java.lang.Object methods may be called by the framework (maps, logging): answer them.
            when (m.name) {
                "hashCode" -> return@InvocationHandler System.identityHashCode(proxy)
                "equals" -> return@InvocationHandler (proxy === args?.getOrNull(0))
                "toString" -> return@InvocationHandler "PiPVmCallback"
            }
            val a = args?.joinToString { it?.toString() ?: "null" } ?: ""
            events.append("${m.name}($a)\n")
            log("[vm-callback] ${m.name}($a)")
            null
        }
        val cb = Proxy.newProxyInstance(AvfProbe::class.java.classLoader, arrayOf(cbIface), handler)
        val exec = Executors.newSingleThreadExecutor()
        vmClass.getMethod("setCallback", java.util.concurrent.Executor::class.java, cbIface).invoke(vm, exec, cb)
        report("avf.run", "AVF: create + run VM", StageStatus.RUNNING, "run()")
        vmClass.getMethod("run").invoke(vm)
        report("avf.run", "AVF: create + run VM", StageStatus.OK, "run() returned")

        // console
        var bytes = 0
        val sb = StringBuilder()
        val t = Thread {
            try {
                val input = vmClass.getMethod("getConsoleOutput").invoke(vm) as java.io.InputStream
                val buf = ByteArray(4096)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    bytes += n
                    val chunk = String(buf, 0, n)
                    sb.append(chunk)
                    chunk.lines().filter { it.isNotBlank() }.forEach { log("[guest] $it") }
                }
            } catch (t: Throwable) {
                log("console read ended: ${t.message}")
            }
        }
        t.isDaemon = true
        t.start()
        t.join(20_000)
        try { vmClass.getMethod("stop").invoke(vm) } catch (ignored: Throwable) {}
        exec.shutdown()

        return if (bytes > 0) {
            val linux = sb.contains("Linux version")
            report("avf.out", "AVF: guest console output", StageStatus.OK,
                "$bytes bytes" + if (linux) " — 'Linux version' seen: a kernel booted inside this app's VM" else "")
            report("avf.android", "AVF: boot an ANDROID guest + display", StageStatus.NOT_IMPLEMENTED,
                "next step: Cuttlefish arm64 kernel + super.img, virtio-gpu/ashmem FB, input. Not shown anywhere yet for 3rd-party apps.")
            true
        } else {
            report("avf.out", "AVF: guest console output", StageStatus.FAIL,
                "VM_NO_OUTPUT in 20 s. callbacks: ${events.toString().trim().ifEmpty { "none" }}")
            false
        }
    }

    private fun rootCause(t: Throwable): Throwable {
        var c = t
        while (c.cause != null && c.cause !== c) c = c.cause!!
        return c
    }

    companion object {
        const val MANAGE = "android.permission.MANAGE_VIRTUAL_MACHINE"
        const val CUSTOM = "android.permission.USE_CUSTOM_VIRTUAL_MACHINE"
        const val VM_NAME = "pip_probe"
    }
}
