package com.example.data.repository

import android.content.Context
import androidx.core.content.FileProvider
import com.example.data.local.AppDatabase
import com.example.data.local.InteractionDao
import com.example.data.model.InteractionEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CounterRepository(
    private val dao: InteractionDao,
    private val context: Context
) {
    val allInteractions: Flow<List<InteractionEntry>> = dao.getAllInteractions()
    val recentInteractions: Flow<List<InteractionEntry>> = dao.getRecentInteractions(15)
    val totalCount: Flow<Int> = dao.getCount()

    suspend fun recordInteraction(
        spokenText: String,
        actionType: String,
        previousValue: Int,
        newValue: Int,
        source: String
    ): InteractionEntry = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        val formattedDate = dateFormat.format(Date(now))
        val delta = newValue - previousValue

        val entry = InteractionEntry(
            timestamp = now,
            formattedDateTime = formattedDate,
            spokenText = spokenText,
            actionType = actionType,
            previousValue = previousValue,
            newValue = newValue,
            delta = delta,
            source = source
        )
        dao.insertInteraction(entry)
        entry
    }

    suspend fun deleteInteraction(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteById(id)
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        dao.clearAll()
    }

    suspend fun generateCsvString(): String = withContext(Dispatchers.IO) {
        val list = dao.getAllInteractionsList()
        val sb = StringBuilder()
        sb.append("ID,Fecha y Hora,Texto Reconocido,Accion,Valor Previo,Nuevo Valor,Diferencia,Origen\n")
        for (item in list) {
            val escapedText = "\"${item.spokenText.replace("\"", "\"\"")}\""
            val escapedSource = "\"${item.source.replace("\"", "\"\"")}\""
            sb.append("${item.id},\"${item.formattedDateTime}\",$escapedText,${item.actionType},${item.previousValue},${item.newValue},${item.delta},$escapedSource\n")
        }
        sb.toString()
    }

    suspend fun exportCsvFile(): File = withContext(Dispatchers.IO) {
        val csvContent = generateCsvString()
        val cacheDir = File(context.cacheDir, "exports")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        val fileName = "contador_voz_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.csv"
        val file = File(cacheDir, fileName)
        file.writeText(csvContent, Charsets.UTF_8)
        file
    }

    fun getFileUri(file: File) = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )

    companion object {
        @Volatile
        private var INSTANCE: CounterRepository? = null

        fun getInstance(context: Context): CounterRepository {
            return INSTANCE ?: synchronized(this) {
                val db = AppDatabase.getInstance(context)
                val instance = CounterRepository(db.interactionDao(), context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
