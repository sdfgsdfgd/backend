package net.sdfgsdfg

import io.ktor.server.application.ApplicationCall
import net.sdfgsdfg.data.model.OpsViewerDto
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Paths

private const val issueWriteCapability = "issues:write"
private const val ownerUserId = "kaan"
private const val ownerRole = "admin"
private const val guestUserId = "guest"
private const val guestRole = "guest"
private const val ownerIpCacheTtlMs = 30_000L
private val ownerPublicIpFile = Paths.get(
    System.getenv("OWNER_PUBLIC_IP_FILE")?.trim()?.takeIf { it.isNotBlank() }
        ?: "/home/x/Desktop/SCRIPTS/public_ip.txt"
)

@Volatile private var ownerIpv4Cache: String? = null
@Volatile private var ownerIpv6Cache: String? = null
@Volatile private var ownerIpCacheAtMs: Long = 0

internal fun ApplicationCall.opsViewer(): OpsViewerDto {
    val client = clientInfo()
    val ownerProof = ownerNetworkProof(client.clientIp)
    val proofs = listOfNotNull(ownerProof)
    return if (ownerProof != null) {
        OpsViewerDto(
            userId = ownerUserId,
            displayName = ownerUserId,
            role = ownerRole,
            proofs = proofs,
            capabilities = listOf(issueWriteCapability),
            issueWrite = true,
            clientHint = client.clientIp.redactedIp(),
            source = client.source,
        )
    } else {
        OpsViewerDto(
            userId = guestUserId,
            displayName = guestUserId,
            role = guestRole,
            proofs = proofs,
            clientHint = client.clientIp.redactedIp(),
            source = client.source,
        )
    }
}

internal fun OpsViewerDto.canWriteIssues() = issueWrite || issueWriteCapability in capabilities

internal fun isOwnerNetworkIp(ip: String) = ownerNetworkProof(ip) != null

private fun ownerNetworkProof(ip: String): String? = when {
    ip == "127.0.0.1" || ip == "::1" -> "loopback"
    ip.startsWith("192.168.1.") -> "lan"
    ip.matchesCurrentOwnerPublicIp() -> "owner_network"
    else -> null
}

private fun String.matchesCurrentOwnerPublicIp(): Boolean {
    val (ipv4, ipv6) = currentOwnerPublicIps()
    return if (contains(":")) {
        ipv6 != null && sameIpv6Prefix64(this, ipv6)
    } else {
        ipv4 != null && this == ipv4
    }
}

private fun currentOwnerPublicIps(): Pair<String?, String?> {
    val now = System.currentTimeMillis()
    if (now - ownerIpCacheAtMs < ownerIpCacheTtlMs) return ownerIpv4Cache to ownerIpv6Cache

    var v4: String? = null
    var v6: String? = null
    runCatching { Files.readString(ownerPublicIpFile) }.getOrNull().orEmpty()
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { line ->
            when {
                line.startsWith("ipv4=") -> v4 = line.removePrefix("ipv4=")
                line.startsWith("ipv6=") -> v6 = line.removePrefix("ipv6=")
                line.contains(":") -> v6 = line
                line.count { it == '.' } == 3 -> v4 = line
            }
        }
    if (v4 != null || v6 != null) {
        ownerIpv4Cache = v4
        ownerIpv6Cache = v6
        ownerIpCacheAtMs = now
    }
    return v4 to v6
}

private fun sameIpv6Prefix64(left: String, right: String): Boolean {
    val leftBytes = runCatching { InetAddress.getByName(left).address }.getOrNull() ?: return false
    val rightBytes = runCatching { InetAddress.getByName(right).address }.getOrNull() ?: return false
    if (leftBytes.size != 16 || rightBytes.size != 16) return false
    return (0 until 8).all { leftBytes[it] == rightBytes[it] }
}

private fun String.redactedIp(): String = when {
    contains(":") -> split(':').filter { it.isNotBlank() }.take(4).joinToString(":").ifBlank { "ipv6" } + "::/64"
    else -> split('.').takeIf { it.size == 4 }?.let { "${it[0]}.${it[1]}.x.x" } ?: this
}
