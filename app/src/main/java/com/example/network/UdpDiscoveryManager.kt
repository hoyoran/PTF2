package com.example.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import org.json.JSONObject

data class DeviceInfo(
    val deviceId: String,
    val ipAddress: String,
    val lastSeenTimestamp: Long
)

class UdpDiscoveryManager(
    private val myDeviceId: String,
    private val localIp: String,
    private val broadcastPort: Int = 19432
) {
    private var socket: DatagramSocket? = null
    
    suspend fun startListening(
        onDeviceDiscovered: (DeviceInfo) -> Unit,
        onRoomDiscovered: ((IntercomRoom, String) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(broadcastPort))
                broadcast = true
            }
            val receiveBuffer = ByteArray(2048)
            while (true) {
                val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)
                socket?.receive(packet)
                
                val message = String(packet.data, 0, packet.length)
                val senderIp = packet.address.hostAddress ?: ""
                
                if (senderIp != localIp) {
                    try {
                        val json = JSONObject(message)
                        if (json.has("deviceId") && json.optString("type") == "DISCOVERY") {
                            val deviceId = json.getString("deviceId")
                            onDeviceDiscovered(DeviceInfo(deviceId, senderIp, System.currentTimeMillis()))

                            if (json.has("room")) {
                                val roomObj = json.getJSONObject("room")
                                val roomId = roomObj.optString("roomId")
                                val roomName = roomObj.optString("roomName")
                                val creatorId = roomObj.optString("creatorId")
                                val memberCount = roomObj.optInt("memberCount", 1)
                                if (roomId.isNotEmpty() && roomName.isNotEmpty()) {
                                    val dummyMembers = List(memberCount) { 
                                        DeviceInfo("member_$it", senderIp, System.currentTimeMillis()) 
                                    }
                                    val room = IntercomRoom(
                                        roomId = roomId,
                                        roomName = roomName,
                                        creatorId = creatorId,
                                        members = dummyMembers
                                    )
                                    onRoomDiscovered?.invoke(room, senderIp)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun broadcastPresence() = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("type", "DISCOVERY")
                put("deviceId", myDeviceId)

                val currentRoom = TransferStateManager.currentRoom.value
                if (currentRoom != null) {
                    put("room", JSONObject().apply {
                        put("roomId", currentRoom.roomId)
                        put("roomName", currentRoom.roomName)
                        put("creatorId", currentRoom.creatorId)
                        put("memberCount", currentRoom.members.size)
                    })
                }
            }
            val data = json.toString().toByteArray()
            val broadcastAddress = InetAddress.getByName("255.255.255.255")
            val packet = DatagramPacket(data, data.size, broadcastAddress, broadcastPort)
            
            val sendSocket = DatagramSocket().apply { broadcast = true }
            sendSocket.send(packet)
            sendSocket.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun stop() {
        socket?.close()
        socket = null
    }
}
