package jp.asystem.taxitranslator.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import org.json.JSONObject

/**
 * 2台のタブレット間の直接通信(Nearby Connections)。
 * Bluetooth/Wi-Fi Directを自動で使い分けるため、車内にネット回線がなくても動作する。
 *
 * 運転手用が広告(advertise)、お客様用が探索(discover)して1対1で接続する。
 * 切断されたら自動で広告/探索に戻り、再接続を試み続ける。
 * PoCのため接続は自動承認(本番では認証トークンの照合を追加すること)。
 */
class NearbyLink(
    context: Context,
    private val onPeerChanged: (Boolean) -> Unit,
    private val onMessage: (JSONObject) -> Unit,
) {
    enum class Role { DRIVER, GUEST }

    private val client = Nearby.getConnectionsClient(context)
    private val handler = Handler(Looper.getMainLooper())
    private var role: Role? = null
    private var endpointId: String? = null

    fun start(role: Role) {
        this.role = role
        restart()
    }

    fun stop() {
        role = null
        handler.removeCallbacksAndMessages(null)
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        endpointId = null
    }

    fun send(message: JSONObject) {
        val id = endpointId ?: return
        client.sendPayload(id, Payload.fromBytes(message.toString().toByteArray(Charsets.UTF_8)))
    }

    private fun restart() {
        client.stopAdvertising()
        client.stopDiscovery()
        when (role) {
            Role.DRIVER -> advertise()
            Role.GUEST -> discover()
            null -> {}
        }
    }

    private fun retryLater() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ if (role != null && endpointId == null) restart() }, RETRY_MS)
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            client.acceptConnection(id, payloadCallback)
        }

        override fun onConnectionResult(id: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                endpointId = id
                client.stopAdvertising()
                client.stopDiscovery()
                onPeerChanged(true)
            } else {
                retryLater()
            }
        }

        override fun onDisconnected(id: String) {
            if (endpointId == id) {
                endpointId = null
                onPeerChanged(false)
            }
            restart()
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            runCatching { onMessage(JSONObject(String(bytes, Charsets.UTF_8))) }
        }

        override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) {}
    }

    private fun advertise() {
        client.startAdvertising(
            "driver",
            SERVICE_ID,
            lifecycleCallback,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build(),
        ).addOnFailureListener { retryLater() }
    }

    private fun discover() {
        client.startDiscovery(
            SERVICE_ID,
            object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
                    client.requestConnection("guest", id, lifecycleCallback)
                        .addOnFailureListener { retryLater() }
                }

                override fun onEndpointLost(id: String) {}
            },
            DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build(),
        ).addOnFailureListener { retryLater() }
    }

    private companion object {
        const val SERVICE_ID = "jp.asystem.taxitranslator"
        const val RETRY_MS = 3000L
    }
}
