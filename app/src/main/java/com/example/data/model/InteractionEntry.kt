package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "interaction_history")
data class InteractionEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val formattedDateTime: String,
    val spokenText: String,
    val actionType: String,
    val previousValue: Int,
    val newValue: Int,
    val delta: Int,
    val source: String
)
