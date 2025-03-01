package net.sdfgsdfg

import java.io.File

fun String.shell(outputFile: File? = null): String = runCatching {
    val process = ProcessBuilder("bash", "-c", this).apply {
        redirectErrorStream(true)
    }.start()

    val output = process.inputStream.bufferedReader().readText().trim()
    val exitCode = process.waitFor()

    // Print raw process exit code
    println("🔎 Shell command: $this | Exit code: $exitCode")

    // Append output to file if needed
    outputFile?.appendText("$output\n")

    // Log and return output
    return output.also { println("🔸 Output: ${it.ifEmpty { "[ No output ]" }}") }
}.getOrElse {
    println("❌ Shell command failed: ${it.localizedMessage} \n\n\n $this \n\n 🙇 \n.")
    return ""
}


