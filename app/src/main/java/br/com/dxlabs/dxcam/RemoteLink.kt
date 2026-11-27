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
    const val TYPE_AUDIO: Byte = 'A'.code.toByte()

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

    /**
     * Le um pacote; null quando a conexao fecha. O motivo exato da
     * queda (EOF, excecao ou pacote fora de sincronia) vai para
     * onError quando informado — diagnostico do vinculo.
     */
    fun readPacket(
        input: DataInputStream,
        onError: ((String) -> Unit)? = null
    ): Pair<Byte, ByteArray>? {
        return try {
            val type = input.readByte()
            val length = input.readInt()
            if (length < 0 || length > 4_000_000) {
                onError?.invoke(
                    "pacote invalido: tipo=${type.toInt()} tamanho=$length"
                )
                return null
            }
            val payload = ByteArray(length)
            input.readFully(payload)
            Pair(type, payload)
        } catch (e: Exception) {
            onError?.invoke(
                "${e.javaClass.simpleName}: ${e.message ?: "sem detalhe"}"
            )
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
        /** Etapa 4 (lapela): um aparelho conectou como microfone. */
        fun onMicConnected()
        fun onMicDisconnected()
        fun onMicText(text: String)
        fun onAudio(pcm: ByteArray)
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
    private var micSocket: Socket? = null

    @Volatile
    var micConnected: Boolean = false
        private set

    /** Motivo da ultima queda do controle (diagnostico do vinculo). */
    @Volatile
    var lastDisconnectReason: String = ""

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
            micSocket?.close()
        } catch (e: Exception) {
        }
        try {
            serverSocket?.close()
        } catch (e: Exception) {
        }
        clientConnected = false
        micConnected = false
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
        Thread {
            var isMic = false
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
                isMic = authText == "AUTH $pairingCode MIC"
                if (!isMic && authText != "AUTH $pairingCode") {
                    RemoteProtocol.writeText(out, "ERR codigo")
                    socket.close()
                    return@Thread
                }
                RemoteProtocol.writeText(out, "OK DX Cam")
                if (isMic) {
                    // Um microfone por vez: conexao nova derruba a outra.
                    try {
                        micSocket?.close()
                    } catch (e: Exception) {
                    }
                    micSocket = socket
                    micConnected = true
                    listener.onMicConnected()
                    while (running && !socket.isClosed) {
                        val packet = RemoteProtocol.readPacket(input) ?: break
                        when (packet.first) {
                            RemoteProtocol.TYPE_AUDIO ->
                                listener.onAudio(packet.second)
                            RemoteProtocol.TYPE_TEXT ->
                                listener.onMicText(
                                    String(packet.second, Charsets.UTF_8)
                                )
                        }
                    }
                } else {
                    // Um controle por vez: conexao nova derruba a outra.
                    try {
                        clientSocket?.close()
                    } catch (e: Exception) {
                    }
                    clientSocket = socket
                    clientOut = out
                    clientConnected = true
                    listener.onClientConnected()
                    while (running && !socket.isClosed) {
                        val packet = RemoteProtocol.readPacket(input) { reason ->
                            lastDisconnectReason = reason
                        } ?: break
                        if (packet.first == RemoteProtocol.TYPE_TEXT) {
                            listener.onCommand(
                                String(packet.second, Charsets.UTF_8)
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                // Caiu no meio: o finally avisa.
            } finally {
                try {
                    socket.close()
                } catch (e: Exception) {
                }
                if (isMic) {
                    if (micSocket === socket) {
                        micConnected = false
                        micSocket = null
                        listener.onMicDisconnected()
                    }
                } else {
                    if (clientSocket === socket) {
                        clientConnected = false
                        clientOut = null
                        clientSocket = null
                        listener.onClientDisconnected()
                    }
                }
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

    /** Motivo da ultima queda (diagnostico do vinculo). */
    @Volatile
    var lastError: String = ""

    private var socket: Socket? = null
    private var out: DataOutputStream? = null

    // Todos os envios passam por esta fila: nada de rede na thread
    // principal, e a ordem dos pacotes e a da chamada.
    private val writer =
        java.util.concurrent.Executors.newSingleThreadExecutor()

    fun connect(ip: String, pairingCode: String, role: String = "") {
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
                val auth = if (role.isEmpty()) {
                    "AUTH $pairingCode"
                } else {
                    "AUTH $pairingCode $role"
                }
                RemoteProtocol.writeText(output, auth)
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
                    val packet = RemoteProtocol.readPacket(input) { reason ->
                        lastError = reason
                    } ?: break
                    when (packet.first) {
                        RemoteProtocol.TYPE_FRAME -> listener.onFrame(packet.second)
                        RemoteProtocol.TYPE_TEXT ->
                            listener.onText(String(packet.second, Charsets.UTF_8))
                    }
                }
                if (connected) {
                    connected = false
                    listener.onDisconnected(
                        "A camera encerrou a conexao. ($lastError)"
                    )
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
        writer.execute {
            val output = out
            if (output == null) {
                failSend()
                return@execute
            }
            try {
                RemoteProtocol.writeText(output, "CMD $command")
            } catch (e: Exception) {
                lastError = "envio: ${e.javaClass.simpleName}: ${e.message}"
                failSend()
            }
        }
    }

    /** Envio falhou: encerra e avisa uma vez (a leitura nao repete). */
    private fun failSend() {
        if (!connected) return
        connected = false
        try {
            socket?.close()
        } catch (e: Exception) {
        }
        listener.onDisconnected(
            "Falha ao enviar o comando: conexao caiu. ($lastError)"
        )
    }

    /** Texto puro, sem prefixo (ex.: o HELLO da lapela). */
    fun sendRawText(text: String) {
        writer.execute {
            val output = out ?: return@execute
            try {
                RemoteProtocol.writeText(output, text)
            } catch (e: Exception) {
                // A thread de leitura avisa a queda.
            }
        }
    }

    /** Etapa 4: trecho de audio PCM da lapela para a camera. */
    fun sendAudio(pcm: ByteArray) {
        writer.execute {
            val output = out ?: return@execute
            try {
                RemoteProtocol.writePacket(
                    output, RemoteProtocol.TYPE_AUDIO, pcm
                )
            } catch (e: Exception) {
                // A thread de leitura avisa a queda.
            }
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
        try {
            writer.shutdown()
        } catch (e: Exception) {
        }
    }
}
