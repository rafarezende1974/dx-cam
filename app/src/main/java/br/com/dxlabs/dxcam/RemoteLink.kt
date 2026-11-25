package br.com.dxlabs.dxcam

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * DX Cam v2 - Etapa 1: vinculo remoto entre dois aparelhos na rede
 * local (Wi-Fi do evento, hotspot ou roteador proprio). Sem nuvem e
 * sem internet: o aparelho-camera abre um servidor, o aparelho-
 * controle (tablet) conecta com IP + codigo de pareamento.
 *
 * Protocolo por pacotes: [tipo 1 byte][tamanho 4 bytes][conteudo].
 * Tipo 'J' = quadro JPEG da previa; tipo 'T' = linha de texto
 * (AUTH, comandos REC_START/REC_STOP/PHOTO e linhas STATE).
 * O envio de quadros nunca bloqueia a camera: o servidor guarda so o
 * quadro mais recente e uma thread propria escreve no soquete.
 */
object RemoteProtocol {
    const val PORT: Int = 47777
    const val TYPE_FRAME: Byte = 'J'.code.toByte()
    const val TYPE_TEXT: Byte = 'T'.code.toByte()

    fun writePacket(out: DataOutputStream, type: Byte, payload: ByteArray) {
        synchronized(out) {
            out.writeByte(type.toInt())
            out.writeInt(payload.size)
            out.write(payload)
            out.flush()
        }
    }

    fun writeText(out: DataOutputStream, text: String) {
        writePacket(out, TYPE_TEXT, text.toByteArray(Charsets.UTF_8))
    }

    /** Le um pacote; null quando a conexao fecha. */
    fun readPacket(input: DataInputStream): Pair<Byte, ByteArray>? {
        return try {
            val type = input.readByte()
            val length = input.readInt()
            if (length < 0 || length > 4_000_000) return null
            val payload = ByteArray(length)
            input.readFully(payload)
            Pair(type, payload)
        } catch (e: Exception) {
            null
        }
    }
}

class RemoteServer(
    private val pairingCode: String,
    private val listener: Listener
) {
    interface Listener {
        fun onClientConnected()
        fun onClientDisconnected()
        fun onCommand(text: String)
    }

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var clientConnected: Boolean = false
        private set

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var clientOut: DataOutputStream? = null

    @Volatile
    private var latestFrame: ByteArray? = null

    fun start() {
        if (running) return
        running = true
        Thread { acceptLoop() }.start()
        Thread { frameLoop() }.start()
    }

    fun stop() {
        running = false
        try {
            clientSocket?.close()
        } catch (e: Exception) {
        }
        try {
            serverSocket?.close()
        } catch (e: Exception) {
        }
        clientConnected = false
        clientOut = null
    }

    /** Guarda o quadro mais novo; a thread de envio cuida do resto. */
    fun submitFrame(jpeg: ByteArray) {
        latestFrame = jpeg
    }

    fun sendText(text: String) {
        val out = clientOut ?: return
        try {
            RemoteProtocol.writeText(out, text)
        } catch (e: Exception) {
            // A thread de leitura percebe a queda e avisa.
        }
    }

    private fun frameLoop() {
        var sent: ByteArray? = null
        while (running) {
            val frame = latestFrame
            val out = clientOut
            if (frame != null && frame !== sent && out != null) {
                try {
                    RemoteProtocol.writePacket(
                        out, RemoteProtocol.TYPE_FRAME, frame
                    )
                    sent = frame
                } catch (e: Exception) {
                    // Leitura avisa a desconexao.
                }
            }
            try {
                Thread.sleep(40)
            } catch (e: Exception) {
            }
        }
    }

    private fun acceptLoop() {
        try {
            val server = ServerSocket(RemoteProtocol.PORT)
            serverSocket = server
            while (running) {
                val socket = try {
                    server.accept()
                } catch (e: Exception) {
                    break
                }
                handleClient(socket)
            }
        } catch (e: Exception) {
            running = false
        }
    }

    private fun handleClient(socket: Socket) {
        // Um controle por vez: conexao nova derruba a anterior.
        try {
            clientSocket?.close()
        } catch (e: Exception) {
        }
        clientSocket = socket
        Thread {
            try {
                val input = DataInputStream(
                    BufferedInputStream(socket.getInputStream())
                )
                val out = DataOutputStream(
                    BufferedOutputStream(socket.getOutputStream())
                )
                val first = RemoteProtocol.readPacket(input)
                val authText = first?.let {
                    if (it.first == RemoteProtocol.TYPE_TEXT) {
                        String(it.second, Charsets.UTF_8)
                    } else {
                        ""
                    }
                } ?: ""
                if (authText != "AUTH $pairingCode") {
                    RemoteProtocol.writeText(out, "ERR codigo")
                    socket.close()
                    return@Thread
                }
                clientOut = out
                clientConnected = true
                RemoteProtocol.writeText(out, "OK DX Cam")
                listener.onClientConnected()
                while (running && !socket.isClosed) {
                    val packet = RemoteProtocol.readPacket(input) ?: break
                    if (packet.first == RemoteProtocol.TYPE_TEXT) {
                        listener.onCommand(String(packet.second, Charsets.UTF_8))
                    }
                }
            } catch (e: Exception) {
                // Caiu no meio: o finally avisa.
            } finally {
                clientConnected = false
                clientOut = null
                try {
                    socket.close()
                } catch (e: Exception) {
                }
                listener.onClientDisconnected()
            }
        }.start()
    }
}

class RemoteClient(
    private val listener: Listener
) {
    interface Listener {
        fun onConnected()
        fun onFrame(jpeg: ByteArray)
        fun onText(text: String)
        fun onDisconnected(message: String)
    }

    @Volatile
    var connected: Boolean = false
        private set

    private var socket: Socket? = null
    private var out: DataOutputStream? = null

    fun connect(ip: String, pairingCode: String) {
        Thread {
            try {
                val created = Socket(ip, RemoteProtocol.PORT)
                created.soTimeout = 0
                socket = created
                val input = DataInputStream(
                    BufferedInputStream(created.getInputStream())
                )
                val output = DataOutputStream(
                    BufferedOutputStream(created.getOutputStream())
                )
                out = output
                RemoteProtocol.writeText(output, "AUTH $pairingCode")
                val reply = RemoteProtocol.readPacket(input)
                val replyText = reply?.let {
                    if (it.first == RemoteProtocol.TYPE_TEXT) {
                        String(it.second, Charsets.UTF_8)
                    } else {
                        ""
                    }
                } ?: ""
                if (!replyText.startsWith("OK")) {
                    try {
                        created.close()
                    } catch (e: Exception) {
                    }
                    listener.onDisconnected(
                        if (replyText.startsWith("ERR")) {
                            "Codigo de pareamento recusado."
                        } else {
                            "Resposta inesperada da camera."
                        }
                    )
                    return@Thread
                }
                connected = true
                listener.onConnected()
                while (connected && !created.isClosed) {
                    val packet = RemoteProtocol.readPacket(input) ?: break
                    when (packet.first) {
                        RemoteProtocol.TYPE_FRAME -> listener.onFrame(packet.second)
                        RemoteProtocol.TYPE_TEXT ->
                            listener.onText(String(packet.second, Charsets.UTF_8))
                    }
                }
                if (connected) {
                    connected = false
                    listener.onDisconnected("A camera encerrou a conexao.")
                }
            } catch (e: Exception) {
                if (connected) {
                    connected = false
                    listener.onDisconnected("Conexao perdida: ${e.message}")
                } else {
                    listener.onDisconnected(
                        "Nao conectou em $ip: confira IP, Wi-Fi e se a " +
                            "camera esta armada. (${e.message})"
                    )
                }
            }
        }.start()
    }

    fun sendCommand(command: String) {
        val output = out ?: return
        try {
            RemoteProtocol.writeText(output, "CMD $command")
        } catch (e: Exception) {
            // A thread de leitura avisa a queda.
        }
    }

    fun close() {
        connected = false
        try {
            socket?.close()
        } catch (e: Exception) {
        }
        socket = null
        out = null
    }
}
