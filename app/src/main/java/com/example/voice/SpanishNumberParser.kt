package com.example.voice

import java.util.Locale

sealed class VoiceCommand {
    object Reset : VoiceCommand()
    data class Increment(val amount: Int = 1) : VoiceCommand()
    data class Decrement(val amount: Int = 1) : VoiceCommand()
    data class SetDirect(val targetValue: Int) : VoiceCommand()
    object None : VoiceCommand()
}

/**
 * Parser de números y comandos en español.
 *
 * Maneja:
 *  - Dígitos directos: "150", "42", "+5", "-3"
 *  - Palabra a número: "ciento cincuenta", "cuarenta y dos", "mil doscientos"
 *  - Comandos: "reset", "reinicia", "más uno", "menos cinco", "suma 10"
 *  - Variantes con/sin acentos y errores comunes de dictado ("cinquenta" por "cincuenta")
 *  - Números grandes: "mil", "dos mil", "mil quinientos"
 */
object SpanishNumberParser {

    // =========================
    // Tablas de palabras
    // =========================

    private val units = mapOf(
        "cero" to 0,
        "un" to 1,
        "uno" to 1,
        "una" to 1,
        "unos" to 1,
        "unas" to 1,
        "dos" to 2,
        "tres" to 3,
        "cuatro" to 4,
        "cinco" to 5,
        "seis" to 6,
        "siete" to 7,
        "ocho" to 8,
        "nueve" to 9,
        "diez" to 10,
        "once" to 11,
        "doce" to 12,
        "trece" to 13,
        "catorce" to 14,
        "quince" to 15,
        "dieciseis" to 16, "dieciséis" to 16,
        "diecisiete" to 17,
        "dieciocho" to 18,
        "diecinueve" to 19,
        "veinte" to 20,
        "veintiun" to 21, "veintiuno" to 21, "veintiuna" to 21,
        "veintidos" to 22, "veintidós" to 22,
        "veintitres" to 23, "veintitrés" to 23,
        "veinticuatro" to 24,
        "veinticinco" to 25,
        "veintiseis" to 26, "veintiséis" to 26,
        "veintisiete" to 27,
        "veintiocho" to 28,
        "veintinueve" to 29
    )

    private val tens = mapOf(
        "diez" to 10,
        "veinte" to 20,
        "treinta" to 30,
        "cuarenta" to 40,
        "cincuenta" to 50,
        "sesenta" to 60,
        "setenta" to 70,
        "ochenta" to 80,
        "noventa" to 90
    )

    private val hundreds = mapOf(
        "cien" to 100,
        "ciento" to 100,
        "doscientos" to 200, "doscientas" to 200,
        "trescientos" to 300, "trescientas" to 300,
        "cuatrocientos" to 400, "cuatrocientas" to 400,
        "quinientos" to 500, "quinientas" to 500,
        "seiscientos" to 600, "seiscientas" to 600,
        "setecientos" to 700, "setecientas" to 700,
        "ochocientos" to 800, "ochocientas" to 800,
        "novecientos" to 900, "novecientas" to 900
    )

    // Errores comunes de dictado (reconocedor offline a veces se equivoca).
    private val commonMisspellings = mapOf(
        "cinquenta" to "cincuenta",
        "cincuenta" to "cincuenta",
        "cuarentai" to "cuarenta y",
        "treintai" to "treinta y",
        "ciento cincuenta" to "ciento cincuenta",
        "siento" to "ciento",
        "sien" to "cien",
        "kien" to "cien",
        "kiento" to "ciento",
        "ciento cinquenta" to "ciento cincuenta",
        "sesenta" to "sesenta",
        "setenta" to "setenta",
        "ochocientos" to "ochocientos",
        "novecientos" to "novecientos"
    )

    // =========================
    // API pública
    // =========================

    fun parse(rawText: String, callModeAlwaysIncrement: Boolean = false): VoiceCommand {
        val clean = normalize(rawText)

        if (clean.isBlank()) return VoiceCommand.None

        // --- Reset ---
        if (clean == "cero" ||
            clean.startsWith("reset") ||
            clean.contains("reinicia") ||
            clean.contains("reiniciar") ||
            clean.contains("volver a cero") ||
            clean.contains("borrar contador") ||
            clean.contains("borrar cuenta") ||
            clean.contains("empezar de cero") ||
            clean == "cero por favor"
        ) {
            return VoiceCommand.Reset
        }

        // --- Decremento relativo ---
        // "menos uno", "resta dos", "restar tres", "-1", "quita 5", "baja 10"
        if (clean.startsWith("menos") || clean.startsWith("resta") ||
            clean.startsWith("restar") || clean.startsWith("-") ||
            clean.startsWith("quita") || clean.startsWith("quitar") ||
            clean.startsWith("baja ")
        ) {
            val prefix = listOf("menos", "resta", "restar", "-", "quita", "quitar", "baja")
                .firstOrNull { clean.startsWith(it) } ?: "menos"
            val remainder = clean.removePrefix(prefix).trim()
            val num = extractNumber(remainder) ?: 1
            return VoiceCommand.Decrement(num)
        }

        // --- Incremento relativo ---
        // "más uno", "mas dos", "suma cinco", "sumar 5", "aumenta 10",
        // "+1", "añade 3", "agrega 4", "sube 2"
        if (clean.startsWith("más") || clean.startsWith("mas") ||
            clean.startsWith("suma") || clean.startsWith("sumar") ||
            clean.startsWith("aumenta") || clean.startsWith("aumentar") ||
            clean.startsWith("+") ||
            clean.startsWith("añade") || clean.startsWith("añadir") ||
            clean.startsWith("anade") || clean.startsWith("anadir") ||
            clean.startsWith("agrega") || clean.startsWith("agregar") ||
            clean.startsWith("sube") || clean.startsWith("subir")
        ) {
            val prefix = listOf(
                "más", "mas", "suma", "sumar", "aumenta", "aumentar",
                "+", "añade", "añadir", "anade", "anadir",
                "agrega", "agregar", "sube", "subir"
            ).firstOrNull { clean.startsWith(it) } ?: "más"
            val remainder = clean.removePrefix(prefix).trim()
            val num = extractNumber(remainder) ?: 1
            return VoiceCommand.Increment(num)
        }

        // --- Acciones simples sin número ---
        if (clean == "aumenta" || clean == "aumentar" ||
            clean == "incrementa" || clean == "incrementar" ||
            clean == "siguiente" || clean == "otro" || clean == "otra" ||
            clean == "sube" || clean == "suma" || clean == "mas" || clean == "más"
        ) {
            return VoiceCommand.Increment(1)
        }

        // --- Decremento simple ("resta", "menos", "baja") ---
        if (clean == "resta" || clean == "menos" || clean == "baja" || clean == "baja uno") {
            return VoiceCommand.Decrement(1)
        }

        // --- ¿Hay un número en la frase? ---
        val detectedNumber = extractNumber(clean)

        if (detectedNumber != null) {
            // Comportamiento por defecto (callModeAlwaysIncrement=false):
            //   "uno" / "1"      → +1
            //   "80", "150"      → fijar contador a ese valor (SetDirect)
            //
            // Comportamiento con callModeAlwaysIncrement=true (modo llamada,
            // pensado para contar en voz alta "uno, dos, tres" → +1 +1 +1):
            //   "uno" / "1"      → +1
            //   "dos", "tres"   → +1 (porque en una llamada estás contando
            //                          items, no dictando un valor exacto)
            //   "80", "150"      → fijar contador (números grandes NO se
            //                          tratan como "+1" — sería un error
            //                          hacer +1 cuando el usuario dice 80)
            if (callModeAlwaysIncrement && detectedNumber in 1..10) {
                return VoiceCommand.Increment(1)
            }

            return if (detectedNumber == 1) {
                VoiceCommand.Increment(1)
            } else {
                VoiceCommand.SetDirect(detectedNumber)
            }
        }

        return VoiceCommand.None
    }

    /**
     * Extrae un número entero de un texto en español.
     * Soporta dígitos arábigos y palabras (compuestas o no).
     * Devuelve null si no encuentra ningún número.
     */
    fun extractNumber(text: String): Int? {
        val clean = normalize(text)
        if (clean.isEmpty()) return null

        // 1) Coincidencia directa de dígitos: "150", "42", "+5"
        //    Buscamos el número más largo de la frase.
        val digitMatches = Regex("""\d+""").findAll(clean).toList()
        if (digitMatches.isNotEmpty()) {
            val largest = digitMatches.maxBy { it.value.length }
            return largest.value.toIntOrNull()
        }

        // 2) Reconocimiento por palabras.
        //    "ciento cincuenta" → 150
        //    "cuarenta y dos"   → 42
        //    "mil doscientos"   → 1200
        //    "dos mil"          → 2000
        val words = clean.split(Regex("""\s+""")).filter { it.isNotBlank() && it != "y" }

        var total = 0          // acumulado para "mil"
        var current = 0        // acumulado en el grupo actual
        var foundAny = false

        for (w in words) {
            when {
                w == "mil" -> {
                    foundAny = true
                    if (current == 0) current = 1
                    total += current * 1000
                    current = 0
                }
                hundreds.containsKey(w) -> {
                    foundAny = true
                    current += hundreds[w]!!
                }
                tens.containsKey(w) -> {
                    foundAny = true
                    current += tens[w]!!
                }
                units.containsKey(w) -> {
                    foundAny = true
                    current += units[w]!!
                }
                w.toIntOrNull() != null -> {
                    // Por si el dictado trae dígitos mezclados con palabras
                    foundAny = true
                    current += w.toInt()
                }
                else -> {
                    // Palabra desconocida: la ignoramos pero seguimos.
                    // Así "pon ciento cincuenta" se sigue procesando.
                }
            }
        }

        val result = total + current
        return if (foundAny && result > 0) result else null
    }

    // =========================
    // Utilidades
    // =========================

    /**
     * Normaliza el texto de entrada:
     *  - lower-case
     *  - quita puntuación
     *  - corrige errores de dictado comunes
     *  - colapsa espacios
     */
    private fun normalize(rawText: String): String {
        var s = rawText.lowercase(Locale.ROOT)

        // Quitar puntuación común del dictado
        s = s.replace(".", "")
            .replace(",", "")
            .replace("!", "")
            .replace("¡", "")
            .replace("?", "")
            .replace("¿", "")
            .replace(":", "")
            .replace(";", "")

        // Corregir errores de dictado más comunes (palabra → palabra correcta)
        for ((bad, good) in commonMisspellings) {
            if (s.contains(bad) && bad != good) {
                s = s.replace(bad, good)
            }
        }

        // "ciento cincuenta" a veces llega como "ciento cincuenta y" o con "y" extra
        // Eso ya lo gestiona el split + filter "y".

        // Colapsar espacios
        s = s.replace(Regex("""\s+"""), " ").trim()

        return s
    }
}
