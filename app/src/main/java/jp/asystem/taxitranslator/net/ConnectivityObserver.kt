package jp.asystem.taxitranslator.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * オンライン(高精度モード)/オフライン(簡易モード)の自動切替のための接続監視。
 * VALIDATED(実際にインターネット到達性が確認されたネットワーク)のみをオンラインとみなす。
 */
class ConnectivityObserver(context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    fun observe(): Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(currentlyOnline())
            }

            override fun onLost(network: Network) {
                trySend(currentlyOnline())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                trySend(
                    networkCapabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED
                    )
                )
            }
        }
        trySend(currentlyOnline())
        cm.registerDefaultNetworkCallback(callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    fun currentlyOnline(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
