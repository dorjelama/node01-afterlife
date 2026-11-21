package dev.node01.afterlife

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fans live chunks -- here, the camera view's JPEG frames -- out to every
 * connected viewer.
 *
 * Each viewer gets its own bounded queue and a full queue simply drops the
 * chunk, so one slow viewer on bad Wi-Fi can never grow memory or stall the
 * camera.
 */
class Hub(private val capacity: Int) {

    private val queues = CopyOnWriteArrayList<BlockingQueue<ByteArray>>()

    /** Lets the producer skip work nobody is watching. */
    val hasSubscribers: Boolean
        get() = queues.isNotEmpty()

    fun subscribe(): BlockingQueue<ByteArray> =
        ArrayBlockingQueue<ByteArray>(capacity).also { queues += it }

    fun unsubscribe(queue: BlockingQueue<ByteArray>) {
        queues -= queue
    }

    fun publish(chunk: ByteArray) {
        for (queue in queues) queue.offer(chunk)
    }
}
