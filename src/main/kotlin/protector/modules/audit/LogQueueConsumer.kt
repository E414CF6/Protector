package protector.modules.audit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import protector.core.config.ProtectorConfig
import protector.core.storage.AuditLogRepository
import protector.core.storage.PendingAuditEvent
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger

class LogQueueConsumer(
    private val repository: AuditLogRepository,
    private val configProvider: () -> ProtectorConfig,
    private val logger: Logger
) {
    private val queue = ConcurrentLinkedQueue<PendingAuditEvent>()
    private val running = AtomicBoolean(false)
    private var scope: CoroutineScope? = null
    private var workerJob: Job? = null

    val totalProcessed = AtomicLong(0L)

    fun pendingCount(): Int = queue.size

    fun enqueue(event: PendingAuditEvent) {
        if (!configProvider().auditEnabled) return
        queue.add(event)
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        workerJob = newScope.launch {
            while (isActive && running.get()) {
                val cfg = configProvider()
                if (queue.isNotEmpty()) {
                    flushBatch(cfg.consumerBatchSize)
                }
                delay(cfg.consumerFlushIntervalMs)
            }
        }
    }

    fun flushNow(): Int {
        var count = 0
        while (queue.isNotEmpty()) {
            val flushed = flushBatch(configProvider().consumerBatchSize)
            if (flushed == 0) break
            count += flushed
        }
        return count
    }

    fun shutdownAndDrain() {
        running.set(false)
        workerJob?.cancel()
        scope?.cancel()
        scope = null
        workerJob = null
        val drained = flushNow()
        if (drained > 0) {
            logger.info("Drained $drained pending audit events to SQLite.")
        }
    }

    private fun flushBatch(maxBatch: Int): Int {
        val batch = ArrayList<PendingAuditEvent>(minOf(maxBatch, 512))
        while (batch.size < maxBatch) {
            val item = queue.poll() ?: break
            batch.add(item)
        }
        if (batch.isEmpty()) return 0
        return try {
            repository.insertBatch(batch)
            totalProcessed.addAndGet(batch.size.toLong())
            batch.size
        } catch (ex: Throwable) {
            logger.warning("Failed to flush audit batch (${batch.size} items): ${ex.message}")
            0
        }
    }
}
