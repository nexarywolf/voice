package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.InteractionEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface InteractionDao {
    @Query("SELECT * FROM interaction_history ORDER BY timestamp DESC")
    fun getAllInteractions(): Flow<List<InteractionEntry>>

    @Query("SELECT * FROM interaction_history ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentInteractions(limit: Int): Flow<List<InteractionEntry>>

    @Query("SELECT * FROM interaction_history ORDER BY timestamp DESC")
    suspend fun getAllInteractionsList(): List<InteractionEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInteraction(entry: InteractionEntry): Long

    @Query("DELETE FROM interaction_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM interaction_history")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM interaction_history")
    fun getCount(): Flow<Int>
}
