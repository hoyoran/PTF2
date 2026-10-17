package com.example.utils

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtil {
    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            for (intf in interfaces) {
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val hostAddr = addr.hostAddress
                        if (!hostAddr.isNullOrEmpty()) {
                            return hostAddr
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            AppLogger.error("NETWORK_UTIL", "Failed to get local IP", ex)
        }
        return "127.0.0.1"
    }
}
