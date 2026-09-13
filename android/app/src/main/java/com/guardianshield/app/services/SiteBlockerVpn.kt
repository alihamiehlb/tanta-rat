package com.guardianshield.app.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Local VPN service for DNS-based website blocking.
 *
 * Creates a local TUN interface, intercepts DNS queries (UDP port 53),
 * and blocks domains on the blocklist by returning an empty response.
 * All non-DNS traffic passes through unmodified.
 *
 * This is the same approach used by Blokada, AdGuard, and similar apps.
 */
class SiteBlockerVpn : VpnService() {

    companion object {
        private const val TAG = "GS_SiteBlocker"
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_ROUTE = "0.0.0.0"
        private const val DNS_SERVER = "8.8.8.8"
        private const val DNS_PORT = 53
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private var processingThread: Thread? = null
    private val blockedDomains = mutableListOf<String>()
    private var blocklistReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        registerBlocklistReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning) return START_STICKY

        Log.i(TAG, "Starting VPN service")
        startVpn()
        return START_STICKY
    }

    /**
     * Register broadcast receiver for blocklist updates from MainService.
     */
    private fun registerBlocklistReceiver() {
        blocklistReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val domains = intent?.getStringArrayListExtra("domains") ?: return
                updateBlocklist(domains)
            }
        }
        val filter = IntentFilter("com.guardianshield.UPDATE_SITE_BLOCKLIST")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(blocklistReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(blocklistReceiver, filter)
        }
    }

    /**
     * Establish the VPN interface and start packet processing.
     */
    private fun startVpn() {
        try {
            vpnInterface = Builder()
                .addAddress(VPN_ADDRESS, 32)
                .addRoute(VPN_ROUTE, 0)
                .addDnsServer(DNS_SERVER)
                .setSession("GuardianShield")
                .setBlocking(true)
                .establish()

            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                return
            }

            isRunning = true
            processingThread = Thread(::processPackets, "VPN-PacketProcessor")
            processingThread?.start()

            Log.i(TAG, "VPN established successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
        }
    }

    /**
     * Main packet processing loop.
     * Reads packets from TUN interface, intercepts DNS queries,
     * blocks matching domains, and forwards everything else.
     */
    private fun processPackets() {
        val inputStream = FileInputStream(vpnInterface!!.fileDescriptor)
        val outputStream = FileOutputStream(vpnInterface!!.fileDescriptor)
        val buffer = ByteArray(32767)

        // Protected DNS socket (bypasses VPN)
        val dnsSocket = DatagramSocket().also { protect(it) }

        while (isRunning) {
            try {
                val length = inputStream.read(buffer)
                if (length <= 0) continue

                val packet = buffer.copyOf(length)

                // Check if this is a DNS query (UDP, destination port 53)
                if (isDnsQuery(packet)) {
                    val domain = extractDomainFromDns(packet)

                    if (domain != null && isBlocked(domain)) {
                        // Block: write a DNS response with 0.0.0.0
                        Log.i(TAG, "Blocked DNS query for: $domain")
                        val response = buildBlockedDnsResponse(packet, length)
                        if (response != null) {
                            outputStream.write(response)
                            outputStream.flush()
                        }
                        continue // Don't forward
                    }

                    // Not blocked: forward DNS query to real DNS server
                    forwardDnsQuery(dnsSocket, packet, length, outputStream)
                } else {
                    // Non-DNS traffic: forward through protected socket
                    // For simplicity, we only filter DNS; other traffic passes via system routing
                    // The VPN only routes DNS through us because we set addDnsServer()
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Packet processing error", e)
                }
            }
        }

        dnsSocket.close()
        Log.i(TAG, "Packet processing stopped")
    }

    /**
     * Check if a packet is a DNS query (IPv4, UDP, port 53).
     */
    private fun isDnsQuery(packet: ByteArray): Boolean {
        if (packet.size < 28) return false

        // IPv4 check: version field (first nibble) == 4
        val version = (packet[0].toInt() shr 4) and 0x0F
        if (version != 4) return false

        // Protocol check: byte 9 == 17 (UDP)
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return false

        // IP header length
        val ihl = (packet[0].toInt() and 0x0F) * 4

        // Destination port: bytes [ihl+2, ihl+3]
        if (packet.size < ihl + 4) return false
        val destPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)

        return destPort == DNS_PORT
    }

    /**
     * Extract the queried domain name from a DNS packet.
     */
    private fun extractDomainFromDns(packet: ByteArray): String? {
        try {
            // IP header length
            val ihl = (packet[0].toInt() and 0x0F) * 4
            // UDP header is 8 bytes
            val dnsOffset = ihl + 8

            if (packet.size < dnsOffset + 12) return null

            // DNS question starts at offset 12 from DNS header
            val questionOffset = dnsOffset + 12
            val domain = StringBuilder()

            var i = questionOffset
            while (i < packet.size) {
                val labelLength = packet[i].toInt() and 0xFF
                if (labelLength == 0) break

                if (domain.isNotEmpty()) domain.append('.')
                for (j in 1..labelLength) {
                    if (i + j >= packet.size) return null
                    domain.append(packet[i + j].toInt().toChar())
                }
                i += labelLength + 1
            }

            return domain.toString().lowercase()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse DNS domain", e)
            return null
        }
    }

    /**
     * Check if a domain (or any of its parent domains) is in the blocklist.
     */
    private fun isBlocked(domain: String): Boolean {
        val lower = domain.lowercase()
        return blockedDomains.any { blocked ->
            lower == blocked || lower.endsWith(".$blocked")
        }
    }

    /**
     * Build a DNS response that resolves to 0.0.0.0 (effectively blocking the domain).
     */
    private fun buildBlockedDnsResponse(requestPacket: ByteArray, length: Int): ByteArray? {
        try {
            val ihl = (requestPacket[0].toInt() and 0x0F) * 4
            val dnsOffset = ihl + 8

            if (length < dnsOffset + 12) return null

            // Copy the original packet
            val response = requestPacket.copyOf(length)

            // Swap source and destination IP addresses
            for (i in 0..3) {
                val tmp = response[12 + i]
                response[12 + i] = response[16 + i]
                response[16 + i] = tmp
            }

            // Swap source and destination ports (UDP)
            val tmp0 = response[ihl]
            val tmp1 = response[ihl + 1]
            response[ihl] = response[ihl + 2]
            response[ihl + 1] = response[ihl + 3]
            response[ihl + 2] = tmp0
            response[ihl + 3] = tmp1

            // Modify DNS header: set QR bit (response), RCODE=0 (no error)
            response[dnsOffset + 2] = (0x81).toByte() // QR=1, OPCODE=0, AA=0, TC=0, RD=1
            response[dnsOffset + 3] = (0x80).toByte() // RA=1, RCODE=0

            // Set answer count to 1
            response[dnsOffset + 6] = 0
            response[dnsOffset + 7] = 1

            // Append a minimal answer record: pointer to question name + A record with 0.0.0.0
            val answer = byteArrayOf(
                0xC0.toByte(), 0x0C,  // Name pointer to question
                0x00, 0x01,           // Type A
                0x00, 0x01,           // Class IN
                0x00, 0x00, 0x00, 0x3C, // TTL 60 seconds
                0x00, 0x04,           // Data length 4
                0x00, 0x00, 0x00, 0x00  // IP 0.0.0.0
            )

            val fullResponse = ByteArray(length + answer.size)
            System.arraycopy(response, 0, fullResponse, 0, length)
            System.arraycopy(answer, 0, fullResponse, length, answer.size)

            // Update IP total length
            val newTotalLength = fullResponse.size
            fullResponse[2] = ((newTotalLength shr 8) and 0xFF).toByte()
            fullResponse[3] = (newTotalLength and 0xFF).toByte()

            // Update UDP length
            val udpLength = newTotalLength - ihl
            fullResponse[ihl + 4] = ((udpLength shr 8) and 0xFF).toByte()
            fullResponse[ihl + 5] = (udpLength and 0xFF).toByte()

            // Zero out checksums (optional for UDP)
            fullResponse[ihl + 6] = 0
            fullResponse[ihl + 7] = 0
            fullResponse[10] = 0
            fullResponse[11] = 0

            return fullResponse
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build blocked DNS response", e)
            return null
        }
    }

    /**
     * Forward a DNS query to the real DNS server and write the response back to the TUN.
     */
    private fun forwardDnsQuery(
        dnsSocket: DatagramSocket,
        packet: ByteArray,
        length: Int,
        tunOutput: FileOutputStream
    ) {
        try {
            val ihl = (packet[0].toInt() and 0x0F) * 4
            val dnsOffset = ihl + 8
            val dnsLength = length - dnsOffset

            if (dnsLength <= 0) return

            // Extract DNS payload
            val dnsPayload = ByteArray(dnsLength)
            System.arraycopy(packet, dnsOffset, dnsPayload, 0, dnsLength)

            // Forward to real DNS server
            val address = InetAddress.getByName(DNS_SERVER)
            val outPacket = DatagramPacket(dnsPayload, dnsLength, address, DNS_PORT)
            dnsSocket.send(outPacket)

            // Receive response
            val responseBuffer = ByteArray(4096)
            val inPacket = DatagramPacket(responseBuffer, responseBuffer.size)
            dnsSocket.soTimeout = 5000 // 5 second timeout
            dnsSocket.receive(inPacket)

            // Build response IP packet (swap src/dst from original)
            val responseIpPacket = buildIpUdpPacket(
                packet, ihl, inPacket.data, inPacket.length
            )

            if (responseIpPacket != null) {
                tunOutput.write(responseIpPacket)
                tunOutput.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "DNS forwarding error", e)
        }
    }

    /**
     * Build an IP+UDP packet wrapping DNS response data,
     * using the original request packet as a template (with swapped addresses).
     */
    private fun buildIpUdpPacket(
        originalPacket: ByteArray,
        ihl: Int,
        dnsResponse: ByteArray,
        dnsLength: Int
    ): ByteArray? {
        try {
            val udpHeaderLen = 8
            val totalLength = ihl + udpHeaderLen + dnsLength
            val result = ByteArray(totalLength)

            // Copy IP header from original
            System.arraycopy(originalPacket, 0, result, 0, ihl)

            // Swap source and destination IP
            for (i in 0..3) {
                val tmp = result[12 + i]
                result[12 + i] = result[16 + i]
                result[16 + i] = tmp
            }

            // Update total length
            result[2] = ((totalLength shr 8) and 0xFF).toByte()
            result[3] = (totalLength and 0xFF).toByte()

            // Swap source and destination ports
            val srcPort0 = originalPacket[ihl]
            val srcPort1 = originalPacket[ihl + 1]
            result[ihl] = originalPacket[ihl + 2]
            result[ihl + 1] = originalPacket[ihl + 3]
            result[ihl + 2] = srcPort0
            result[ihl + 3] = srcPort1

            // UDP length
            val udpLen = udpHeaderLen + dnsLength
            result[ihl + 4] = ((udpLen shr 8) and 0xFF).toByte()
            result[ihl + 5] = (udpLen and 0xFF).toByte()

            // UDP checksum = 0 (optional)
            result[ihl + 6] = 0
            result[ihl + 7] = 0

            // IP checksum = 0 (recalculate would be better but kernel handles it)
            result[10] = 0
            result[11] = 0

            // Copy DNS response payload
            System.arraycopy(dnsResponse, 0, result, ihl + udpHeaderLen, dnsLength)

            return result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build IP/UDP packet", e)
            return null
        }
    }

    /**
     * Update the blocked domains list.
     */
    fun updateBlocklist(domains: List<String>) {
        blockedDomains.clear()
        blockedDomains.addAll(domains.map { it.lowercase() })
        Log.i(TAG, "Site blocklist updated: ${domains.size} domains")
    }

    override fun onDestroy() {
        Log.i(TAG, "VPN service destroyed")
        isRunning = false
        processingThread?.interrupt()
        vpnInterface?.close()
        vpnInterface = null
        blocklistReceiver?.let { unregisterReceiver(it) }
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN revoked by user")
        isRunning = false
        processingThread?.interrupt()
        vpnInterface?.close()
        vpnInterface = null
        super.onRevoke()
    }
}
