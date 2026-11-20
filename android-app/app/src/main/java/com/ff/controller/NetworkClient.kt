package com.ff.controller

import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * Talks to the PC server over 127.0.0.1:5555 (tunnelled by `adb reverse`), newline-delimited JSON.
 * - sendState(): conflated, only the newest controller state matters
 * - sendEvent(): queued, never dropped (text, key presses, touchpad)
 * - receives: pong (latency) and rumble messages from the PC
 */
class NetworkClient(private val host: String = "127.0.0.1", private val port: Int = 5555) {
    @Volatile var pingMs = -1
        private set
    @Volatile var onMessage: ((String) -> Unit)? = null
    @Volatile private var lastRx = 0L
    @Volatile private var rxDead = false
    @Volatile private var running = true

    /** True while the PC server answers (hello / pong). */
    val connected: Boolean get() = System.currentTimeMillis() - lastRx < 3500

    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private val events = ArrayDeque<String>()
    private var pending: String? = null
    private var last = IDLE

    init {
        thread(isDaemon = true, name = "net") {
            while (running) {
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(host, port), 1500)
                        s.tcpNoDelay = true
                        rxDead = false
                        startReader(s)
                        val out = s.getOutputStream()
                        var lastPing = 0L
                        while (running && !rxDead) {
                            val batch = ArrayList<String>()
                            lock.withLock {
                                if (events.isEmpty() && pending == null) cond.await(500, TimeUnit.MILLISECONDS)
                                while (events.isNotEmpty()) batch.add(events.removeFirst())
                                val p = pending
                                pending = null
                                if (p != null) {
                                    last = p
                                    batch.add(p)
                                } else if (batch.isEmpty()) {
                                    batch.add(last) // keep-alive
                                }
                                Unit
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastPing > 1000) {
                                lastPing = now
                                batch.add("""{"t":"ping","ts":$now,"p":$pingMs}""")
                            }
                            val sb = StringBuilder()
                            for (m in batch) sb.append(m).append('\n')
                            out.write(sb.toString().toByteArray(Charsets.UTF_8))
                            out.flush()
                        }
                    }
                } catch (e: Exception) {
                    // reconnect below
                }
                if (running) Thread.sleep(1000)
            }
        }
    }

    private fun startReader(s: Socket) {
        thread(isDaemon = true, name = "net-rx") {
            try {
                val br = s.getInputStream().bufferedReader(Charsets.UTF_8)
                while (running) {
                    val line = br.readLine() ?: break
                    lastRx = System.currentTimeMillis()
                    handleLine(line)
                }
            } catch (e: Exception) {
                // closed
            }
            rxDead = true
        }
    }

    private fun handleLine(line: String) {
        try {
            val j = JSONObject(line)
            if (j.optString("t") == "pong") {
                pingMs = (System.currentTimeMillis() - j.optLong("ts")).toInt().coerceIn(0, 9999)
                return
            }
        } catch (e: Exception) {
            return
        }
        onMessage?.invoke(line)
    }

    fun sendState(json: String) {
        lock.withLock { pending = json; cond.signalAll() }
    }

    fun sendEvent(json: String) {
        lock.withLock {
            if (events.size > 400) events.removeFirst()
            events.addLast(json)
            cond.signalAll()
        }
    }

    fun stop() {
        running = false
        lock.withLock { cond.signalAll() }
    }

    companion object {
        const val IDLE = """{"t":"s","mode":"pad","ms":0,"lx":0,"ly":0,"rx":0,"ry":0,"lt":0,"rt":0,"b":[]}"""
    }
}
