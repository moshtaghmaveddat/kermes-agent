package ai.kermes.schedule

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.coroutines.CoroutineContext

/**
 * One coroutine per scheduled job. Each loops:
 *   1. compute next fire time via cron-utils
 *   2. delay until then
 *   3. run the agent with the configured prompt
 *   4. send the output through the configured DeliverySink
 *
 * If a schedule's cron is invalid it's skipped with a warning.
 */
class Scheduler(
    private val agentRunner: AgentRunner,
    private val sink: DeliverySink,
    private val zone: ZoneId = ZoneId.systemDefault(),
    coroutineContext: CoroutineContext = Dispatchers.Default,
) : AutoCloseable {

    /** Invoked by the scheduler when an entry fires. */
    fun interface AgentRunner {
        suspend fun run(entry: ScheduleEntry): String
    }

    private val log = LoggerFactory.getLogger(Scheduler::class.java)
    private val scope = CoroutineScope(coroutineContext)
    private val jobs = mutableListOf<Job>()

    private val cronParser = CronParser(
        CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX),
    )

    fun start(entries: List<ScheduleEntry>) {
        scope.launch {
            supervisorScope {
                for (entry in entries) {
                    val cron = try {
                        cronParser.parse(entry.cron)
                    } catch (e: Exception) {
                        log.warn("skip schedule '{}' — bad cron '{}': {}", entry.id, entry.cron, e.message)
                        continue
                    }
                    val job = launch { runLoop(entry, ExecutionTime.forCron(cron)) }
                    jobs.add(job)
                }
            }
        }
    }

    private suspend fun runLoop(entry: ScheduleEntry, exec: ExecutionTime) {
        log.info("schedule '{}' active: cron='{}'", entry.id, entry.cron)
        while (scope.isActive) {
            val now = ZonedDateTime.now(zone)
            val nextOpt = exec.nextExecution(now)
            if (!nextOpt.isPresent) {
                log.warn("schedule '{}' has no future fire times; stopping", entry.id)
                return
            }
            val next = nextOpt.get()
            val wait = Duration.between(now, next).toMillis().coerceAtLeast(0)
            log.debug("schedule '{}' sleeping {}ms until {}", entry.id, wait, next)
            delay(wait)

            try {
                val output = agentRunner.run(entry)
                sink.deliver(entry, next.toInstant(), output)
                log.info("schedule '{}' delivered to '{}'", entry.id, entry.deliver)
            } catch (e: Exception) {
                log.error("schedule '{}' failed: {}", entry.id, e.message, e)
                runCatching {
                    sink.deliver(entry, next.toInstant(), "ERROR: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    override fun close() {
        jobs.forEach { it.cancel() }
        scope.coroutineContext[Job]?.cancel()
    }
}
