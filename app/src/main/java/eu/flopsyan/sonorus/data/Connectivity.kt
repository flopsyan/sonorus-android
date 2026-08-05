package eu.flopsyan.sonorus.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether this phone has a network at all, and whether it is one that costs
 * money per byte.
 *
 * The point of asking is the cold start: an app that is expected to work in a
 * plane must not spend twenty seconds in a connect timeout before it shows the
 * downloads. So the answer is read *before* the first request, and a phone
 * without a network never makes one.
 *
 * A validated connection is deliberately **not** required. A captive portal or
 * a VPN that is still coming up would say "not validated" while an ordinary
 * request may well work, and the case that has to be right is the one where
 * there is no network at all - which shows up as no active network, not as an
 * unvalidated one. A connection that claims to work and then does not is caught
 * a second time by the request that fails.
 */
class Connectivity(context: Context) {

    private val manager = context.getSystemService(ConnectivityManager::class.java)

    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    /** True on Wi-Fi and anything else not billed by the byte. */
    private val _unmetered = MutableStateFlow(false)
    val unmetered: StateFlow<Boolean> = _unmetered.asStateFlow()

    init {
        read()
        runCatching {
            manager?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = read()
                override fun onLost(network: Network) = read()
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = read()
            })
        }
    }

    private fun read() {
        val caps = manager?.activeNetwork?.let { manager.getNetworkCapabilities(it) }
        _online.value = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        _unmetered.value = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }
}
