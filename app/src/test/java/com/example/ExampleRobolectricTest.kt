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

  @Test
  fun `test standalone AnswerEngine without AccessibilityService`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    
    // Arithmetic question test
    val options = listOf(
      com.example.engine.QOpt("10", 0, android.graphics.Rect(0, 0, 100, 100)),
      com.example.engine.QOpt("25", 1, android.graphics.Rect(0, 100, 100, 200)),
      com.example.engine.QOpt("30", 2, android.graphics.Rect(0, 200, 100, 300)),
      com.example.engine.QOpt("40", 3, android.graphics.Rect(0, 300, 100, 400))
    )
    val quizData = com.example.engine.QuizData("What is 15 + 10?", options, "fp_test_1")
    val solvedIndex = com.example.engine.AnswerEngine.solve(context, quizData)
    assertEquals(1, solvedIndex) // 25 is index 1

    // Option letter question test
    val letterOptions = listOf(
      com.example.engine.QOpt("Apple", 0, android.graphics.Rect(0, 0, 100, 100)),
      com.example.engine.QOpt("Banana", 1, android.graphics.Rect(0, 100, 100, 200)),
      com.example.engine.QOpt("Cherry", 2, android.graphics.Rect(0, 200, 100, 300))
    )
    val letterQuiz = com.example.engine.QuizData("Select option: B", letterOptions, "fp_test_2")
    val solvedLetterIndex = com.example.engine.AnswerEngine.solve(context, letterQuiz)
    assertEquals(1, solvedLetterIndex) // B is index 1
  }

  @Test
  fun `test TestAutomationConfig authorization`() {
    com.example.engine.TestAutomationConfig.authorizedPackages.clear()
    assertTrue(com.example.engine.TestAutomationConfig.isPackageAuthorized("com.test.quiz"))
    
    com.example.engine.TestAutomationConfig.authorizePackage("com.authorized.test")
    assertTrue(com.example.engine.TestAutomationConfig.isPackageAuthorized("com.authorized.test"))
    org.junit.Assert.assertFalse(com.example.engine.TestAutomationConfig.isPackageAuthorized("com.unauthorized.app"))
    com.example.engine.TestAutomationConfig.authorizedPackages.clear()
  }

  @Test
  fun `test RootAutomationDaemon start and stop lifecycle`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    AutomationState.start()
    com.example.engine.RootAutomationDaemon.start(context)
    com.example.engine.RootAutomationDaemon.stop()
    AutomationState.stop()
    assertEquals("STOPPED", AutomationState.status)
  }
}
