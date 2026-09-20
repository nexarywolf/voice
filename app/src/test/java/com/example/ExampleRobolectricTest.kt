package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.voice.SpanishNumberParser
import com.example.voice.VoiceCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Contador por Voz", appName)
    }

    @Test
    fun `spanish parser increments with uno`() {
        val cmd = SpanishNumberParser.parse("uno")
        assertTrue(cmd is VoiceCommand.Increment)
        assertEquals(1, (cmd as VoiceCommand.Increment).amount)
    }

    @Test
    fun `spanish parser sets direct number 150`() {
        val cmd = SpanishNumberParser.parse("150")
        assertTrue(cmd is VoiceCommand.SetDirect)
        assertEquals(150, (cmd as VoiceCommand.SetDirect).targetValue)
    }

    @Test
    fun `spanish parser parses words ciento cincuenta`() {
        val cmd = SpanishNumberParser.parse("ciento cincuenta")
        assertTrue(cmd is VoiceCommand.SetDirect)
        assertEquals(150, (cmd as VoiceCommand.SetDirect).targetValue)
    }

    @Test
    fun `spanish parser resets with reset el contador`() {
        val cmd = SpanishNumberParser.parse("reset el contador")
        assertTrue(cmd is VoiceCommand.Reset)
    }

    @Test
    fun `spanish parser handles call mode always increment`() {
        val cmd = SpanishNumberParser.parse("150", callModeAlwaysIncrement = true)
        assertTrue(cmd is VoiceCommand.Increment)
        assertEquals(1, (cmd as VoiceCommand.Increment).amount)
    }
}
