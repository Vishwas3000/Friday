package dev.layatest.agent

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import org.json.JSONObject

/**
 * Line-delimited JSON over an abstract unix socket, reached from the computer with
 * `adb forward tcp:8765 localabstract:layatest_agent`.
 */
class Bridge(private val svc: AgentService) {
    private val server = LocalServerSocket(SOCKET)
    @Volatile private var stopped = false
    // a big stack: the tree export recurses once per UI depth level
    private val thread = Thread(null, ::acceptLoop, "laya-bridge", 8L shl 20)

    fun start() = thread.start()

    fun stop() {
        stopped = true
        runCatching { LocalSocket().connect(LocalSocketAddress(SOCKET)) } // unblocks accept()
        runCatching { server.close() }
    }

    private fun acceptLoop() {
        while (!stopped) {
            val socket = runCatching { server.accept() }.getOrNull() ?: return
            // Other apps on the phone can connect to abstract sockets too; only adb (shell) or root may drive the phone.
            val uid = socket.peerCredentials.uid
            if (stopped || uid != SHELL_UID && uid != ROOT_UID) {
                if (!stopped) Log.w(TAG, "refused connection from uid $uid")
                socket.close()
                continue
            }
            socket.use { serve(it) }
        }
    }

    private fun serve(socket: LocalSocket) {
        val reader = socket.inputStream.bufferedReader()
        val writer = socket.outputStream.bufferedWriter()
        while (!stopped) {
            val line = runCatching { reader.readLine() }.getOrNull() ?: return
            val reply = runCatching { svc.handle(JSONObject(line)) }
                .getOrElse { JSONObject().put("error", it.toString()) }
            writer.write(reply.toString())
            writer.newLine()
            writer.flush()
        }
    }

    companion object {
        const val SOCKET = "layatest_agent"
        private const val TAG = "LayaBridge"
        private const val SHELL_UID = 2000
        private const val ROOT_UID = 0
    }
}
