package moe.shizuku.manager.adb

import android.util.Log
import moe.shizuku.manager.adb.AdbProtocol.ADB_AUTH_RSAPUBLICKEY
import moe.shizuku.manager.adb.AdbProtocol.ADB_AUTH_SIGNATURE
import moe.shizuku.manager.adb.AdbProtocol.ADB_AUTH_TOKEN
import moe.shizuku.manager.adb.AdbProtocol.A_AUTH
import moe.shizuku.manager.adb.AdbProtocol.A_CLSE
import moe.shizuku.manager.adb.AdbProtocol.A_CNXN
import moe.shizuku.manager.adb.AdbProtocol.A_MAXDATA
import moe.shizuku.manager.adb.AdbProtocol.A_OKAY
import moe.shizuku.manager.adb.AdbProtocol.A_OPEN
import moe.shizuku.manager.adb.AdbProtocol.A_STLS
import moe.shizuku.manager.adb.AdbProtocol.A_STLS_VERSION
import moe.shizuku.manager.adb.AdbProtocol.A_VERSION
import moe.shizuku.manager.adb.AdbProtocol.A_WRTE
import rikka.core.util.BuildUtils
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket

private const val TAG = "AdbInteractiveShell"

/** Hard upper bound for an incoming ADB payload (defensive; adbd never exceeds 256 KiB). */
private const val MAX_PAYLOAD_LIMIT = 1048576

/**
 * aMiNo Shell: a long-lived ADB connection holding an interactive `shell:` stream.
 *
 * Unlike [AdbClient] (one command -> stream closes), this class keeps both the
 * ADB connection and the shell stream open so the in-app terminal stays paired
 * with the device as long as the user wants.
 */
class AdbInteractiveShell(private val host: String, private val port: Int, private val key: AdbKey) : Closeable {

    private lateinit var socket: Socket
    private lateinit var plainInputStream: DataInputStream
    private lateinit var plainOutputStream: DataOutputStream

    private var useTls = false

    private lateinit var tlsSocket: SSLSocket
    private lateinit var tlsInputStream: DataInputStream
    private lateinit var tlsOutputStream: DataOutputStream

    private val inputStream get() = if (useTls) tlsInputStream else plainInputStream
    private val outputStream get() = if (useTls) tlsOutputStream else plainOutputStream

    private val closed = AtomicBoolean(false)
    private val shellOpen = AtomicBoolean(false)
    private val localId = 1
    private val remoteId = AtomicInteger(-1)
    private val writerLock = Any()
    private var readerThread: Thread? = null

    /**
     * Perform the ADB CNXN handshake (with TLS upgrade + auth when required).
     * Blocks until the connection is established.
     */
    fun connect() {
        val socket = Socket()
        val address = InetSocketAddress(host, port)
        socket.connect(address, 6000)

        socket.tcpNoDelay = true
        try {
            socket.keepAlive = true
        } catch (e: Exception) {
        }
        plainInputStream = DataInputStream(socket.getInputStream())
        plainOutputStream = DataOutputStream(socket.getOutputStream())

        write(A_CNXN, A_VERSION, A_MAXDATA, "host::")

        var message = read()
        if (message.command == A_STLS) {
            if (!BuildUtils.atLeast29) {
                error("TLS adb is not supported before Android 9")
            }
            write(A_STLS, A_STLS_VERSION, 0)

            val sslContext = key.sslContext
            tlsSocket = sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
            tlsSocket.startHandshake()
            Log.d(TAG, "TLS handshake succeeded.")

            tlsInputStream = DataInputStream(tlsSocket.inputStream)
            tlsOutputStream = DataOutputStream(tlsSocket.outputStream)
            useTls = true

            message = read()
            // The key may not be authorized yet (revoked / fresh install) -> AUTH can arrive over TLS too
            if (message.command == A_AUTH) {
                message = completeAuth(message)
            }
        } else if (message.command == A_AUTH) {
            message = completeAuth(message)
        }

        if (message.command != A_CNXN) error("not A_CNXN")
    }

    /** Answer A_AUTH TOKEN: signature first; if not accepted, publish the public key. */
    private fun completeAuth(token: AdbMessage): AdbMessage {
        if (token.arg0 != ADB_AUTH_TOKEN) error("not A_AUTH ADB_AUTH_TOKEN")
        write(A_AUTH, ADB_AUTH_SIGNATURE, 0, key.sign(token.data))

        val second = read()
        if (second.command == A_CNXN) return second

        // not paired yet -> send public key, device may show the pairing/allow dialog
        write(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, key.adbPublicKey)
        return read()
    }

    /**
     * Open an interactive `shell:` stream and start the reader loop.
     * The stream (and the underlying connection) stays open until [close] is
     * called or the device drops the connection.
     */
    fun openShell(
        onOutput: ((ByteArray) -> Unit)? = null,
        onClosed: ((Throwable?) -> Unit)? = null
    ) {
        check(!shellOpen.get()) { "shell already open" }

        write(A_OPEN, localId, 0, "shell:")

        val first = read()
        when (first.command) {
            A_OKAY -> remoteId.set(first.arg0)
            A_CLSE -> {
                onClosed?.invoke(AdbException("device refused shell stream"))
                return
            }
            else -> {
                onClosed?.invoke(AdbException("not A_OKAY after A_OPEN"))
                return
            }
        }
        shellOpen.set(true)

        val t = Thread({
            var endError: Throwable? = null
            try {
                while (!closed.get()) {
                    val message = read()
                    when (message.command) {
                        A_WRTE -> {
                            val rid = message.arg0
                            remoteId.compareAndSet(-1, rid)
                            if (message.data_length > 0 && message.data != null) {
                                onOutput?.invoke(message.data)
                            }
                            write(A_OKAY, localId, rid)
                        }
                        A_OKAY -> {
                            // ack for our writes
                            remoteId.compareAndSet(-1, message.arg0)
                        }
                        A_CLSE -> break
                    }
                }
            } catch (e: Throwable) {
                if (!closed.get()) endError = e
            } finally {
                shellOpen.set(false)
                onClosed?.invoke(endError)
            }
        }, "amino-shell-reader")
        t.isDaemon = true
        t.start()
        readerThread = t
    }

    /** Send raw text (a command line, with trailing '\n') to the shell stdin. */
    fun send(text: String) {
        val rid = remoteId.get()
        if (rid <= 0) throw AdbException("shell stream is not open")
        val data = text.toByteArray(Charsets.UTF_8)
        synchronized(writerLock) {
            // ADB protocol: arg0 = sender's stream id (ours), arg1 = recipient's stream id (adbd's).
            // adbd routes A_WRTE via find_local_socket(msg.arg1) — putting the ids the other way
            // round makes adbd silently DROP the command (verified against AOSP adb.cpp).
            write(A_WRTE, localId, rid, data)
        }
    }

    private fun write(command: Int, arg0: Int, arg1: Int, data: ByteArray? = null) =
        write(AdbMessage(command, arg0, arg1, data))

    private fun write(command: Int, arg0: Int, arg1: Int, data: String) =
        write(AdbMessage(command, arg0, arg1, data))

    private fun write(message: AdbMessage) {
        synchronized(writerLock) {
            outputStream.write(message.toByteArray())
            outputStream.flush()
        }
        Log.d(TAG, "write ${message.toStringShort()}")
    }

    private fun read(): AdbMessage {
        val buffer = ByteBuffer.allocate(AdbMessage.HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)

        inputStream.readFully(buffer.array(), 0, 24)

        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val dataLength = buffer.int
        val checksum = buffer.int
        val magic = buffer.int
        val data: ByteArray?
        if (dataLength < 0 || dataLength > MAX_PAYLOAD_LIMIT) {
            throw AdbException("bad ADB data_length $dataLength")
        }
        if (dataLength > 0) {
            data = ByteArray(dataLength)
            inputStream.readFully(data, 0, dataLength)
        } else {
            data = null
        }
        val message = AdbMessage(command, arg0, arg1, dataLength, checksum, magic, data)
        message.validateOrThrow()
        Log.d(TAG, "read ${message.toStringShort()}")
        return message
    }

    val isOpen: Boolean get() = shellOpen.get() && !closed.get()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            readerThread?.interrupt()
        } catch (e: Exception) {
        }
        try {
            if (useTls) tlsOutputStream.write(AdbMessage(A_CLSE, localId, remoteId.get(), null).toByteArray())
            else plainOutputStream.write(AdbMessage(A_CLSE, localId, remoteId.get(), null).toByteArray())
        } catch (e: Exception) {
        }
        try {
            plainInputStream.close()
        } catch (e: Throwable) {
        }
        try {
            plainOutputStream.close()
        } catch (e: Exception) {
        }
        try {
            socket.close()
        } catch (e: Exception) {
        }
        if (useTls) {
            try {
                tlsInputStream.close()
            } catch (e: Throwable) {
            }
            try {
                tlsOutputStream.close()
            } catch (e: Exception) {
            }
            try {
                tlsSocket.close()
            } catch (e: Exception) {
            }
        }
    }
}
