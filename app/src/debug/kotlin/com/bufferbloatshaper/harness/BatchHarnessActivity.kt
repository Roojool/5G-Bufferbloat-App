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
import android.util.Base64
import org.json.JSONObject
import java.io.File

/** Debug-only ADB command surface. It never prepares consent, establishes a VPN, or adds routes. */
class BatchHarnessActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var service: HarnessService? = null
    private var bound = false
    private var terminal = false
    private lateinit var runId: String
    private lateinit var resultToken: String
    private lateinit var transport: BatchTransport
    private var config: ExperimentConfig? = null
    private var network: Network? = null
    private var deadlineMs = 0L

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val harness = (binder as HarnessService.LocalBinder).service
            service = harness
            BatchRunRegistry.attach(runId, harness)
            val request = requireNotNull(config)
            if (!harness.start(request, network)) {
                finishWith("HARNESS_BUSY")
                return
            }
            deadlineMs = System.currentTimeMillis() + request.durationMs + 10_000
            handler.post(pollResult)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            if (!terminal) finishWith("SERVICE_DISCONNECTED")
        }
    }

    private val pollResult = object : Runnable {
        override fun run() {
            val harness = service
            val result = harness?.result
            if (result != null && harness.running.not()) {
                finishWith("RESULT", result)
            } else if (System.currentTimeMillis() >= deadlineMs) {
                harness?.cancel()
                finishWith("HOST_VISIBLE_TIMEOUT")
            } else {
                handler.postDelayed(this, 100)
            }
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == BatchProtocol.CANCEL_ACTION) {
            intent.getStringExtra("run_id")?.let(BatchRunRegistry::cancel)
            return
        }
        if (!terminal) {
            val token = intent.getStringExtra("result_token")
            val id = intent.getStringExtra("run_id")
            val requested = BatchTransport.parse(intent.getStringExtra("transport"))
            if (BatchProtocol.validToken(token) && BatchProtocol.validToken(id) && requested != null) {
                writeStandalone(requireNotNull(token), requireNotNull(id), requested, "ACTIVITY_BUSY")
            }
            return
        }
        handle(intent)
    }

    private fun writeStandalone(token: String, id: String, requested: BatchTransport, status: String) {
        val json = JSONObject().apply {
            put("schema", 1); put("run_id", id); put("status", status)
            put("build_type", "debug_internal_no_route")
            put("network_transport", requested.wireName); put("cleanup_joined", JSONObject.NULL)
        }
        writeFile(token, json)
    }

    private fun handle(command: Intent) {
        if (command.action == BatchProtocol.CANCEL_ACTION) {
            command.getStringExtra("run_id")?.let(BatchRunRegistry::cancel)
            finish()
            return
        }
        if (command.action != BatchProtocol.RUN_ACTION) {
            finish()
            return
        }
        runId = command.getStringExtra("run_id").orEmpty()
        resultToken = command.getStringExtra("result_token").orEmpty()
        transport = BatchTransport.parse(command.getStringExtra("transport")) ?: run {
            finishInvalid(command.getStringExtra("result_token"), "INVALID_TRANSPORT"); return
        }
        if (!BatchProtocol.validToken(runId) || !BatchProtocol.validToken(resultToken)) {
            finishInvalid(command.getStringExtra("result_token"), "INVALID_TOKEN"); return
        }
        terminal = false
        if (VpnService.prepare(this) != null) {
            finishWith("CONSENT_REQUIRED")
            return
        }
        network = resolveNetwork(transport, command.getIntExtra("network_ordinal", -1)) ?: run {
            finishWith("NETWORK_UNAVAILABLE_OR_AMBIGUOUS"); return
        }
        if (command.getStringExtra("mode") == "probe") {
            finishWith("READY")
            return
        }
        try {
            val address = command.getStringExtra("address").orEmpty()
            val port = command.getIntExtra("port", 0)
            val encoded = command.getStringExtra("config_b64").orEmpty()
            require(encoded.length in 1..BatchProtocol.MAX_ENCODED_CONFIG)
            val json = JSONObject(String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
            fun optional(o: JSONObject, key: String) = if (o.has(key)) o.getInt(key) else null
            val changes = json.optJSONArray("changes")
            config = ExperimentConfig(address, port, json.getLong("expectedBytes"),
                optional(json, "receiveBuffer"), optional(json, "clamp"),
                json.optLong("cadenceMs", 0), json.optInt("readBytes", 16_384),
                (0 until (changes?.length() ?: 0)).map { i ->
                    val change = changes!!.getJSONObject(i)
                    ExperimentConfig.Change(change.getLong("atMs"), optional(change, "receiveBuffer"),
                        optional(change, "clamp"),
                        if (change.has("cadenceMs")) change.getLong("cadenceMs") else null)
                }, json.optLong("durationMs", 60_000), json.optLong("stallTimeoutMs", 10_000)).also { it.validate() }
        } catch (_: Exception) {
            finishWith("INVALID_CONFIG")
            return
        }
        writeEnvelope("STARTING")
        bound = bindService(Intent(this, HarnessService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (!bound) finishWith("BIND_FAILED")
    }

    private fun resolveNetwork(requested: BatchTransport, ordinal: Int): Network? {
        val cm = getSystemService(ConnectivityManager::class.java)
        val transportId = when (requested) {
            BatchTransport.WIFI -> NetworkCapabilities.TRANSPORT_WIFI
            BatchTransport.CELLULAR -> NetworkCapabilities.TRANSPORT_CELLULAR
        }
        val eligible = cm.allNetworks.filter { candidate ->
            val caps = cm.getNetworkCapabilities(candidate)
            caps != null && caps.hasTransport(transportId) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.sortedBy { it.networkHandle }
        if (ordinal >= 0) return eligible.getOrNull(ordinal)
        cm.activeNetwork?.takeIf { it in eligible }?.let { return it }
        return eligible.singleOrNull()
    }

    private fun finishInvalid(token: String?, status: String) {
        if (BatchProtocol.validToken(token)) {
            resultToken = requireNotNull(token)
            runId = "invalid-command"
            transport = BatchTransport.WIFI
            finishWith(status)
        } else finish()
    }

    private fun finishWith(status: String, result: ExperimentResult? = null) {
        if (terminal) return
        terminal = true
        handler.removeCallbacks(pollResult)
        writeEnvelope(status, result)
        BatchRunRegistry.detach(runId, service)
        if (bound) {
            unbindService(connection)
            bound = false
        }
        service = null
        finishAndRemoveTask()
    }

    private fun writeEnvelope(status: String, result: ExperimentResult? = null) {
        val request = config
        val json = JSONObject().apply {
            put("schema", 1)
            put("run_id", runId)
            put("status", status)
            put("build_type", "debug_internal_no_route")
            put("network_transport", transport.wireName)
            put("cleanup_joined", service?.cleanupJoined ?: JSONObject.NULL)
            if (result != null && request != null) put("result", JSONObject(result.toJson(request)))
        }
        writeFile(resultToken, json)
    }

    private fun writeFile(token: String, json: JSONObject) {
        val directory = File(filesDir, "stage1_batch").apply { mkdirs() }
        val destination = File(directory, BatchProtocol.resultFileName(token))
        val temporary = File(directory, ".${BatchProtocol.resultFileName(token)}.tmp")
        temporary.writeText(json.toString(2))
        if (!temporary.renameTo(destination)) {
            destination.writeText(json.toString(2)); temporary.delete()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollResult)
        if (!terminal) service?.cancel()
        if (::runId.isInitialized) BatchRunRegistry.detach(runId, service)
        if (bound) unbindService(connection)
        service = null
        super.onDestroy()
    }
}

internal object BatchRunRegistry {
    private var runId: String? = null
    private var service: HarnessService? = null

    @Synchronized fun attach(id: String, harness: HarnessService) {
        runId = id
        service = harness
    }

    @Synchronized fun detach(id: String, harness: HarnessService?) {
        if (runId == id && service === harness) {
            runId = null
            service = null
        }
    }

    @Synchronized fun cancel(id: String): Boolean {
        val harness = service
        if (runId != id || harness == null) return false
        harness.cancel()
        return true
    }
}
