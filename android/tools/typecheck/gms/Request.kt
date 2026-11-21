package com.google.android.gms.fitness.request

import com.google.android.gms.fitness.data.LocalDataType
import java.util.concurrent.TimeUnit

class LocalDataReadRequest {
    class Builder {
        fun aggregate(dataType: LocalDataType): Builder = TODO()
        fun bucketByTime(duration: Int, unit: TimeUnit): Builder = TODO()
        fun setTimeRange(start: Long, end: Long, unit: TimeUnit): Builder = TODO()
        fun build(): LocalDataReadRequest = TODO()
    }
}
