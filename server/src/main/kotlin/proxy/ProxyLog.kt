package proxy

import net.sdfgsdfg.ClientInfo
import org.slf4j.Logger
import org.slf4j.MDC

internal object ProxyLog {
    const val red = "\u001B[31m"
    const val green = "\u001B[32m"
    const val yellow = "\u001B[33m"
    const val blue = "\u001B[34m"
    const val cyan = "\u001B[36m"
    const val dim = "\u001B[2m"
    const val reset = "\u001B[0m"

    fun clientTag(info: ClientInfo, rdns: String? = null): Pair<String, String> {
        val suffix = rdns?.takeIf { it.isNotBlank() && it != info.clientIp }
        val plain = buildString {
            append("[CLIENT] ip=").append(info.clientIp)
            append(" remote=").append(info.remoteIp)
            info.cfIp?.let { append(" cf=").append(it) }
            append(" src=").append(info.source)
            suffix?.let { append(" rdns=").append(it) }
        }
        val color = buildString {
            append("${cyan}[CLIENT]${reset} ip=${dim}").append(info.clientIp).append(reset)
            append(" remote=${dim}").append(info.remoteIp).append(reset)
            info.cfIp?.let { append(" cf=${dim}").append(it).append(reset) }
            append(" src=${dim}").append(info.source).append(reset)
            suffix?.let { append(" rdns=${dim}").append(it).append(reset) }
        }
        return plain to color
    }

    fun write(logger: Logger, plain: String, color: String, warn: Boolean = false) {
        MDC.put("log_prefix", plain)
        MDC.put("log_prefix_color", color)
        try {
            if (warn) logger.warn("") else logger.info("")
        } finally {
            MDC.remove("log_prefix")
            MDC.remove("log_prefix_color")
        }
    }
}

internal fun String.kv(value: String, quote: Boolean = true): String =
    if (quote) "$this='$value'" else "$this=$value"

internal fun String.kvDim(value: String, quote: Boolean = true): String =
    if (quote) "$this=${ProxyLog.dim}'$value'${ProxyLog.reset}" else "$this=${ProxyLog.dim}$value${ProxyLog.reset}"

internal fun Int.statusColor(): String = when {
    this >= 500 -> ProxyLog.red
    this >= 400 -> ProxyLog.yellow
    this >= 300 -> ProxyLog.yellow
    else -> ProxyLog.green
}
