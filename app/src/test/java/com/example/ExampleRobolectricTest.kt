package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.engine.AutomationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("P.R Automation", appName)
  }

  @Test
  fun `test automation state transitions`() {
    AutomationState.start()
    assertEquals("RUNNING", AutomationState.status)
    assertTrue(AutomationState.running)

    AutomationState.pause()
    assertEquals("PAUSED", AutomationState.status)
    assertTrue(AutomationState.paused)

    AutomationState.resume()
    assertEquals("RUNNING", AutomationState.status)

    AutomationState.stop()
    assertEquals("STOPPED", AutomationState.status)
  }

  @Test
  fun `test root state flag and fallback`() {
    AutomationState.isRooted = false
    org.junit.Assert.assertFalse(AutomationState.isRooted)
    AutomationState.isRooted = true
    assertTrue(AutomationState.isRooted)
    AutomationState.isRooted = false
  }
}
