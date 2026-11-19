package com.fire17.phoneinphone.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import com.fire17.phoneinphone.BuildConfig
import com.fire17.phoneinphone.R
import com.fire17.phoneinphone.backend.Settings
import com.fire17.phoneinphone.ondevice.AvfProbe
import com.fire17.phoneinphone.stream.LinkMode
import java.io.File

/** Launcher: pick a backend, enter where the guest lives, start. */
class MainActivity : Activity() {

    private lateinit var rgBackend: RadioGroup
    private lateinit var rgLink: RadioGroup
    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var etSerial: EditText
    private lateinit var etMaxSize: EditText
    private lateinit var etBitrate: EditText
    private lateinit var etMaxFps: EditText
    private lateinit var spCodec: Spinner
    private lateinit var etNewDisplay: EditText
    private lateinit var cbAvf: CheckBox
    private lateinit var tvInfo: TextView

    private val codecs = listOf("h264", "h265", "av1")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0).padForSystemBars()

        rgBackend = findViewById(R.id.rgBackend)
        rgLink = findViewById(R.id.rgLink)
        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        etSerial = findViewById(R.id.etSerial)
        etMaxSize = findViewById(R.id.etMaxSize)
        etBitrate = findViewById(R.id.etBitrate)
        etMaxFps = findViewById(R.id.etMaxFps)
        spCodec = findViewById(R.id.spCodec)
        etNewDisplay = findViewById(R.id.etNewDisplay)
        cbAvf = findViewById(R.id.cbAvf)
        tvInfo = findViewById(R.id.tvInfo)

        spCodec.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, codecs)

        val s = Settings.load(Settings.prefs(this))
        findViewById<RadioButton>(if (s.backend == "ondevice") R.id.rbOnDevice else R.id.rbStream).isChecked = true
        findViewById<RadioButton>(if (s.linkMode == LinkMode.ADB_SERVER) R.id.rbAdbServer else R.id.rbDirect).isChecked = true
        etHost.setText(s.host)
        etPort.setText(s.port.toString())
        etSerial.setText(s.serial)
        etMaxSize.setText(s.maxSize.toString())
        etBitrate.setText(s.bitRateMbps.toString())
        etMaxFps.setText(s.maxFps.toString())
        spCodec.setSelection(codecs.indexOf(s.codec).coerceAtLeast(0))
        etNewDisplay.setText(s.newDisplay)
        cbAvf.isChecked = s.runAvf

        rgLink.setOnCheckedChangeListener { _, id ->
            // sensible default port per mode
            val p = etPort.text.toString()
            if (id == R.id.rbAdbServer && p == "5555") etPort.setText("5037")
            if (id == R.id.rbDirect && p == "5037") etPort.setText("5555")
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            val cur = read()
            cur.save(Settings.prefs(this))
            startActivity(Intent(this, PhoneActivity::class.java))
        }

        findViewById<Button>(R.id.btnImport).setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(i, REQ_IMPORT)
        }

        refreshInfo()
    }

    private fun read() = Settings(
        backend = if (rgBackend.checkedRadioButtonId == R.id.rbOnDevice) "ondevice" else "stream",
        linkMode = if (rgLink.checkedRadioButtonId == R.id.rbAdbServer) LinkMode.ADB_SERVER else LinkMode.DIRECT,
        host = etHost.text.toString().trim(),
        port = etPort.text.toString().toIntOrNull() ?: 5555,
        serial = etSerial.text.toString().trim(),
        maxSize = etMaxSize.text.toString().toIntOrNull() ?: 1280,
        bitRateMbps = etBitrate.text.toString().toIntOrNull() ?: 6,
        maxFps = etMaxFps.text.toString().toIntOrNull() ?: 60,
        codec = codecs[spCodec.selectedItemPosition.coerceAtLeast(0)],
        newDisplay = etNewDisplay.text.toString().trim(),
        runAvf = cbAvf.isChecked,
    )

    private fun refreshInfo() {
        val tarball = File(filesDir, "android-userland.tar.gz")
        val root = File(filesDir, "android-root/system/build.prop")
        val avfFeature = packageManager.hasSystemFeature("android.software.virtualization_framework")
        val manage = checkSelfPermission(AvfProbe.MANAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val custom = checkSelfPermission(AvfProbe.CUSTOM) == android.content.pm.PackageManager.PERMISSION_GRANTED
        tvInfo.text = buildString {
            append("PhoneInPhone ${BuildConfig.VERSION_NAME} · scrcpy-server ${BuildConfig.SCRCPY_VERSION}\n")
            append("Android userland: ")
            append(when {
                root.exists() -> "extracted ✓"
                tarball.exists() -> "imported (${tarball.length() / (1024 * 1024)} MiB), extracts on first OnDevice run"
                else -> "not imported (OnDevice stops at A3)"
            })
            append("\nAVF: feature=${if (avfFeature) "yes" else "no"} · MANAGE=${if (manage) "granted" else "no"} · CUSTOM=${if (custom) "granted" else "no"}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        tvInfo.text = "Importing…"
        Thread {
            val dst = File(filesDir, "android-userland.tar.gz")
            val msg = try {
                contentResolver.openInputStream(uri)!!.use { input ->
                    dst.outputStream().use { out -> input.copyTo(out, 1 shl 20) }
                }
                File(filesDir, "android-root").deleteRecursively()
                "Imported ${dst.length() / (1024 * 1024)} MiB"
            } catch (t: Throwable) {
                "Import failed: ${t.message}"
            }
            runOnUiThread { refreshInfo(); tvInfo.append("\n$msg") }
        }.start()
    }

    companion object {
        private const val REQ_IMPORT = 41
    }
}
