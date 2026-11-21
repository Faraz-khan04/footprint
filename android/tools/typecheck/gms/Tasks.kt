package com.google.android.gms.tasks

import java.util.concurrent.TimeUnit

abstract class Task<T>
object Tasks { @JvmStatic fun <T> await(task: Task<T>, timeout: Long, unit: TimeUnit): T = TODO() }
