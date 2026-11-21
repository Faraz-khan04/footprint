package com.google.android.gms.fitness.data

import java.util.concurrent.TimeUnit

class LocalDataType { companion object { @JvmField val TYPE_STEP_COUNT_DELTA: LocalDataType = TODO() } }
class LocalField { companion object { @JvmField val FIELD_STEPS: LocalField = TODO() } }
class LocalValue { fun asInt(): Int = TODO() }
class LocalDataPoint {
    fun getStartTime(unit: TimeUnit): Long = TODO()
    fun getEndTime(unit: TimeUnit): Long = TODO()
    fun getValue(field: LocalField): LocalValue = TODO()
}
class LocalDataSet { val dataPoints: List<LocalDataPoint> get() = TODO() }   // Java getDataPoints()
class LocalBucket { val dataSets: List<LocalDataSet> get() = TODO() }   // Java getDataSets()
