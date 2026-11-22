package com.seastreams.tv.core.util

import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * A fixed pool of [threads] daemon threads named [name] with an unbounded queue. Idle threads leave after
 * [keepAliveSeconds], so a singleton that owns one holds no thread while unused.
 */
fun daemonExecutor(threads: Int, name: String, keepAliveSeconds: Long = 30): Executor =
    ThreadPoolExecutor(threads, threads, keepAliveSeconds, TimeUnit.SECONDS, LinkedBlockingQueue()) {
        Thread(it, name).apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }
