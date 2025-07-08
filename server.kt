// Segcache implementation in Kotlin as a standalone server
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*

// Represents a slot in the hash table
data class HashSlot(
    var segmentId: Int = -1,
    var offset: Int = -1,
    val frequency: AtomicInteger = AtomicInteger(0), // 8-bit counter, using AtomicInteger for simplicity
    var tag: Int = 0 // 12-bit tag for validation
)

// Represents a bucket in the hash table
class HashBucket {
    val lock = AtomicBoolean(false) // Spin lock
    val usage = AtomicInteger(0) // Number of occupied slots (0-7)
    val lastAccess = AtomicInteger(0) // Last access timestamp
    val cas = AtomicLong(0) // Compare-and-swap value
    val slots = Array(7) { HashSlot() }
    var overflow: HashBucket? = null // For chaining if all slots are full
}

// Represents a fixed-size segment for storing objects
class Segment(val size: Int = 1024 * 1024) { // 1 MB
    private val data = ByteArray(size)
    private var writePos = 0
    val creationTime = System.currentTimeMillis()

    fun hasSpace(objectSize: Int): Boolean {
        return writePos + 4 + objectSize < size // 4 bytes for size
    }

    fun write(value: ByteArray): Pair<Int, Int> { // Returns (offset, size)
        if (!hasSpace(value.size)) {
            throw IllegalStateException("Segment is full")
        }
        val offset = writePos
        data.writeInt(writePos, value.size)
        writePos += 4
        System.arraycopy(value, 0, data, writePos, value.size)
        writePos += value.size
        return Pair(offset, value.size)
    }

    fun read(offset: Int): ByteArray {
        val size = data.readInt(offset)
        val value = ByteArray(size)
        System.arraycopy(data, offset + 4, value, 0, size)
        return value
    }

    private fun ByteArray.writeInt(index: Int, value: Int) {
        this[index] = (value ushr 24).toByte()
        this[index + 1] = (value ushr 16).toByte()
        this[index + 2] = (value ushr 8).toByte()
        this[index + 3] = value.toByte()
    }

    private fun ByteArray.readInt(index: Int): Int {
        return ((this[index].toInt() and 0xFF) shl 24) or
               ((this[index + 1].toInt() and 0xFF) shl 16) or
               ((this[index + 2].toInt() and 0xFF) shl 8) or
               (this[index + 3].toInt() and 0xFF)
    }
}

// Main Segcache class
class Segcache(private val maxMemory: Long) {
    private val hashTable = Array(100000) { HashBucket() } // Example size
    private val ttlBuckets = Array(1024) { mutableListOf<Segment>() }
    private val segments = mutableListOf<Segment>()
    private val lock = ReentrantLock()

    // Simplified TTL approximation
    private fun approxTtl(ttl: Long): Int {
        return (ttl / 1000).toInt() % 1024 // Maps TTL to one of 1024 buckets
    }

    fun put(key: String, value: ByteArray, ttl: Long) {
        val bucketIndex = approxTtl(ttl)
        var segment = ttlBuckets[bucketIndex].find { it.hasSpace(value.size) }
        if (segment == null) {
            lock.lock()
            try {
                segment = Segment()
                segments.add(segment)
                ttlBuckets[bucketIndex].add(segment)
            } finally {
                lock.unlock()
            }
        }
        val (offset, _) = segment.write(value)
        val hash = key.hashCode()
        val hbIndex = (hash ushr 20) % hashTable.size
        val bucket = hashTable[hbIndex]
        while (bucket.lock.compareAndSet(false, true)) {
            try {
                val slotIndex = bucket.slots.indexOfFirst { it.segmentId == -1 }
                if (slotIndex != -1) {
                    bucket.slots[slotIndex].apply {
                        segmentId = segments.indexOf(segment)
                        this.offset = offset
                        tag = hash and 0xFFF
                        frequency.set(0)
                    }
                    bucket.usage.incrementAndGet()
                    return
                }
                // Handle overflow or eviction (simplified)
            } finally {
                bucket.lock.set(false)
            }
        }
    }

    fun get(key: String): ByteArray? {
        val hash = key.hashCode()
        val hbIndex = (hash ushr 20) % hashTable.size
        var bucket: HashBucket? = hashTable[hbIndex]
        while (bucket != null) {
            while (bucket.lock.compareAndSet(false, true)) {
                try {
                    for (slot in bucket.slots) {
                        if (slot.segmentId != -1 && slot.tag == (hash and 0xFFF)) {
                            val segment = segments[slot.segmentId]
                            return segment.read(slot.offset)
                        }
                    }
                } finally {
                    bucket.lock.set(false)
                }
            }
            bucket = bucket.overflow
        }
        return null
    }

    fun startExpirationThread() {
        Thread {
            while (true) {
                val currentTime = System.currentTimeMillis()
                for (i in 0 until 1024) {
                    val bucket = ttlBuckets[i]
                    synchronized(bucket) {
                        val iterator = bucket.iterator()
                        while (iterator.hasNext()) {
                            val segment = iterator.next()
                            if (currentTime > segment.creationTime + (i * 1000L)) {
                                iterator.remove()
                            }
                        }
                    }
                }
                Thread.sleep(1000)
            }
        }.start()
    }
}

// Ktor server setup
fun main() {
    val cache = Segcache(1024 * 1024 * 1024) // 1 GB max memory
    cache.startExpirationThread()

    embeddedServer(Netty, port = 8080) {
        routing {
            get("/get/{key}") {
                val key = call.parameters["key"]!!
                val value = cache.get(key)
                if (value != null) {
                    call.respondText(value.toString(Charsets.UTF_8))
                } else {
                    call.respondText("Not found", status = HttpStatusCode.NotFound)
                }
            }
            post("/set") {
                val key = call.request.queryParameters["key"]!!
                val value = call.receive<String>().toByteArray(Charsets.UTF_8)
                val ttl = call.request.queryParameters["ttl"]?.toLong() ?: 0
                cache.put(key, value, ttl)
                call.respondText("OK")
            }
        }
    }.start(wait = true)
}