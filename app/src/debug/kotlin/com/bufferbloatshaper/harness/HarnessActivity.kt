package com.bufferbloatshaper.harness

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONObject

/** Deliberately not in launcher or release source set. Owner starts this component with adb. */
class HarnessActivity : Activity() {
    private var service: HarnessService? = null
    private var bound = false
    private lateinit var address: EditText
    private lateinit var port: EditText
    private lateinit var plan: EditText
    private lateinit var networks: Spinner
    private lateinit var output: TextView
    private var choices: List<Network?> = listOf(null)
    private var activeConfig: ExperimentConfig? = null
    private var rendered: ExperimentResult? = null
    private val handler = Handler(Looper.getMainLooper())
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as HarnessService.LocalBinder).service
            output.text = "Ready. No VPN route exists. Each run opens a fresh test socket."
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }
    private val refresh = object : Runnable {
        override fun run() {
            service?.result?.let { result ->
                if (result !== rendered) {
                    activeConfig?.let { output.text = result.toJson(it) }
                    rendered = result
                }
            }
            handler.postDelayed(this, 500)
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        fun label(text: String) { layout.addView(TextView(this).apply { this.text = text }) }
        fun input(hint: String, value: String = "") = EditText(this).apply {
            this.hint = hint; setText(value); layout.addView(this)
        }
        label("Internal F-01/F-02. Sends no app traffic. No routes. Physical efficacy UNVERIFIED. Use only your authorized test endpoint. VPN consent can replace another prepared VPN; do not use with lockdown. Maximum 256 MiB per run; owner controls total data cost.")
        address = input("Numeric test endpoint IP (not exported)")
        port = input("Test endpoint port (not exported)")
        plan = input("Experiment JSON", "{\"expectedBytes\":16777216,\"durationMs\":60000,\"readBytes\":16384,\"cadenceMs\":0}")
        val cm = getSystemService(ConnectivityManager::class.java)
        choices = listOf(null) + cm.allNetworks.filter { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == false
        }
        networks = Spinner(this).apply {
            adapter = ArrayAdapter(this@HarnessActivity, android.R.layout.simple_spinner_dropdown_item,
                choices.mapIndexed { i, n -> if (n == null) "Default network" else {
                    val c = cm.getNetworkCapabilities(n)
                    "Network $i: " + when {
                        c?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
                        c?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Cellular"
                        else -> "Other"
                    }
                } })
            layout.addView(this)
        }
        layout.addView(Button(this).apply { text = "Prepare test service"; setOnClickListener {
            val consent = VpnService.prepare(this@HarnessActivity)
            if (consent == null) bindHarness() else {
                @Suppress("DEPRECATION")
                startActivityForResult(consent, 1)
            }
        } })
        layout.addView(Button(this).apply { text = "Run fresh socket"; setOnClickListener {
            try {
                require(plan.text.length <= 4096)
                val json = JSONObject(plan.text.toString())
                fun optional(o: JSONObject, key: String) = if (o.has(key)) o.getInt(key) else null
                val changes = json.optJSONArray("changes")
                val config = ExperimentConfig(address.text.toString().trim(), port.text.toString().toInt(),
                    json.getLong("expectedBytes"), optional(json, "receiveBuffer"), optional(json, "clamp"),
                    json.optLong("cadenceMs", 0), json.optInt("readBytes", 16384),
                    (0 until (changes?.length() ?: 0)).map { i -> val c = changes!!.getJSONObject(i)
                        ExperimentConfig.Change(c.getLong("atMs"), optional(c, "receiveBuffer"), optional(c, "clamp"),
                            if (c.has("cadenceMs")) c.getLong("cadenceMs") else null)
                    }, json.optLong("durationMs", 60000), json.optLong("stallTimeoutMs", 10000))
                config.validate()
                if (service?.start(config, choices[networks.selectedItemPosition]) == true) {
                    activeConfig = config; rendered = null; output.text = "Running bounded socket test…"
                } else output.text = "Prepare service first, or wait for current test cleanup."
            } catch (_: Exception) { output.text = "Invalid experiment input. See docs/EXPERIMENTS.md." }
        } })
        layout.addView(Button(this).apply { text = "Cancel"; setOnClickListener { service?.cancel() } })
        layout.addView(Button(this).apply { text = "Save redacted result"; setOnClickListener {
            if (rendered != null) startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = "application/json"; addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_TITLE, "socket-experiment.json")
            }, 2)
        } })
        output = TextView(this).apply { setTextIsSelectable(true); layout.addView(this) }
        setContentView(ScrollView(this).apply { addView(layout) })
        handler.post(refresh)
    }
    private fun bindHarness() {
        if (!bound) bound = bindService(Intent(this, HarnessService::class.java), connection, Context.BIND_AUTO_CREATE)
    }
    @Deprecated("Activity callback used only by internal debug UI")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        if (requestCode == 1) bindHarness()
        if (requestCode == 2) data?.data?.let { uri ->
            val text = rendered?.let { r -> activeConfig?.let(r::toJson) } ?: return
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
        }
    }
    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        service?.cancel()
        if (bound) unbindService(connection)
        service = null
        super.onDestroy()
    }
    override fun onStop() {
        service?.cancel() // this small harness runs only while its owner UI is visible
        super.onStop()
    }
}
