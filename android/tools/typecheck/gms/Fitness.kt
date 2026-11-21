package com.google.android.gms.fitness

import android.content.Context
import com.google.android.gms.fitness.data.LocalDataType
import com.google.android.gms.fitness.request.LocalDataReadRequest
import com.google.android.gms.fitness.result.LocalDataReadResponse
import com.google.android.gms.tasks.Task

object FitnessLocal { @JvmStatic fun getLocalRecordingClient(context: Context): LocalRecordingClient = TODO() }
interface LocalRecordingClient {
    fun subscribe(dataType: LocalDataType): Task<Void>
    fun readData(request: LocalDataReadRequest): Task<LocalDataReadResponse>
    companion object { const val LOCAL_RECORDING_CLIENT_MIN_VERSION_CODE = 0 }
}
