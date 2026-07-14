package com.banupham.appviewcamera.gateway.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [CameraEntity::class], version = 1, exportSchema = false)
abstract class GatewayDatabase : RoomDatabase() {
    abstract fun cameraDao(): CameraDao

    companion object {
        fun create(context: Context): GatewayDatabase = Room.databaseBuilder(
            context.applicationContext,
            GatewayDatabase::class.java,
            "camera_gateway.db"
        ).build()
    }
}
