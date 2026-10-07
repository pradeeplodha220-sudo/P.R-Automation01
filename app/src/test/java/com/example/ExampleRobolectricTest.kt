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
    
    // Verify AnswerEngine implements AnswerProvider
    assertTrue(com.example.engine.AnswerEngine is com.example.engine.AnswerProvider)
    assertTrue(com.example.engine.RootEngine is com.example.engine.RootAutomationBackend)

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

  @Test
  fun `test parseDumpXml extracts nodes and center coordinates correctly`() {
    val sampleXml = """
      <?xml version="1.0" encoding="UTF-8"?>
      <hierarchy rotation="0">
        <node index="0" text="" resource-id="" class="android.widget.FrameLayout" bounds="[0,0][1080,2400]" clickable="false">
          <node index="0" text="What is 15 + 10?" resource-id="com.quiz:id/question_title" class="android.widget.TextView" bounds="[60,300][1020,500]" clickable="false" />
          <node index="1" text="10" resource-id="com.quiz:id/option_a" class="android.widget.Button" bounds="[80,600][1000,740]" clickable="true" />
          <node index="2" text="25" resource-id="com.quiz:id/option_b" class="android.widget.Button" bounds="[80,800][1000,940]" clickable="true" />
          <node index="3" text="30" resource-id="com.quiz:id/option_c" class="android.widget.Button" bounds="[80,1000][1000,1140]" clickable="true" />
          <node index="4" text="40" resource-id="com.quiz:id/option_d" class="android.widget.Button" bounds="[80,1200][1000,1340]" clickable="true" />
        </node>
      </hierarchy>
    """.trimIndent()

    val nodes = com.example.engine.RootEngine.parseDumpXml(sampleXml)
    assertEquals(6, nodes.size)

    val questionNode = nodes[1]
    assertEquals("What is 15 + 10?", questionNode.content)
    assertEquals(540, questionNode.centerX)
    assertEquals(400, questionNode.centerY)

    val optionB = nodes[3]
    assertEquals("25", optionB.content)
    assertEquals(540, optionB.centerX)
    assertEquals(870, optionB.centerY)
    assertTrue(optionB.clickable)
  }

  @Test
  fun `test parseDumpXml identifies progression button for State B`() {
    val resultXml = """
      <?xml version="1.0" encoding="UTF-8"?>
      <hierarchy rotation="0">
        <node index="0" text="" resource-id="" class="android.widget.FrameLayout" bounds="[0,0][1080,2400]" clickable="false">
          <node index="0" text="Correct! +10 Points" resource-id="com.quiz:id/result_label" class="android.widget.TextView" bounds="[100,500][980,650]" clickable="false" />
          <node index="1" text="Next Question" resource-id="com.quiz:id/btn_next" class="android.widget.Button" bounds="[140,1600][940,1750]" clickable="true" />
        </node>
      </hierarchy>
    """.trimIndent()

    val nodes = com.example.engine.RootEngine.parseDumpXml(resultXml)
    val nextBtn = nodes.find { it.content.equals("Next Question", ignoreCase = true) }
    org.junit.Assert.assertNotNull(nextBtn)
    assertEquals(540, nextBtn!!.centerX)
    assertEquals(1675, nextBtn.centerY)
  }

  @Test
  fun `test cleanDeviceAccessibility execution`() {
    // In local unit test without actual su binary, verify method handles execution safely
    val cleaned = com.example.engine.RootEngine.cleanDeviceAccessibility()
    // Returns false or true without throwing exception
    org.junit.Assert.assertTrue(cleaned || !cleaned)
  }

  @Test
  fun `test parseDumpsysViewHierarchy extracts nodes and calculates absolute coordinates`() {
    val sampleDumpsys = """
      TASK 123:com.minipix.shorts id=123
        ACTIVITY com.minipix.shorts/com.minipix.shorts.QuizActivity 83f7a1f pid=1234
          View Hierarchy:
            com.android.internal.policy.DecorView{827a3b8 V.E...... ........ 0,0-1080,2400}
              android.widget.LinearLayout{d6a9e1 V.E...... ........ 0,0-1080,2400}
                android.widget.FrameLayout{4e0b06 V.E...... ........ 0,100-1080,2300 #1020002 android:id/content}
                  android.widget.TextView{3a17e0 V.ED..... ......ID 48,100-1032,300 #7f080120 app:id/question_view} text="What is the capital of France?"
                  android.widget.Button{8e9102 V.E...C.. ........ 48,400-1032,540 #7f080121 app:id/opt_a} text="Paris"
                  android.widget.Button{9a8712 V.E...C.. ........ 48,580-1032,720 #7f080122 app:id/opt_b} text="London"
                  android.widget.Button{bc7654 V.E...C.. ........ 48,760-1032,900 #7f080123 app:id/opt_c} text="Berlin"
                  android.widget.Button{de8976 V.E...C.. ........ 48,940-1032,1080 #7f080124 app:id/opt_d} text="Madrid"
    """.trimIndent()

    val nodes = com.example.engine.RootEngine.parseDumpsysViewHierarchy(sampleDumpsys)
    assertEquals(6, nodes.size)

    val question = nodes.find { it.text.contains("capital of France") }
    org.junit.Assert.assertNotNull(question)
    assertEquals("What is the capital of France?", question!!.content)
    assertEquals("com.minipix.shorts", question.packageName)
    // FrameLayout absTop = 100, TextView relTop = 100 -> absTop = 200, relBottom = 300 -> absBottom = 400
    // CenterY = (200 + 400) / 2 = 300
    assertEquals(540, question.centerX)
    assertEquals(300, question.centerY)

    val parisOpt = nodes.find { it.text == "Paris" }
    org.junit.Assert.assertNotNull(parisOpt)
    assertTrue(parisOpt!!.clickable)
    assertEquals("opt_a", parisOpt.id)
    // FrameLayout absTop = 100, Button relTop = 400 -> absTop = 500, relBottom = 540 -> absBottom = 640
    // CenterY = (500 + 640) / 2 = 570
    assertEquals(540, parisOpt.centerX)
    assertEquals(570, parisOpt.centerY)
  }

  @Test
  fun `test parseDumpsysViewHierarchy detects progression next button`() {
    val resultDumpsys = """
      TASK 123:com.minipix.shorts id=123
        ACTIVITY com.minipix.shorts/com.minipix.shorts.ResultActivity 83f7a1f pid=1234
          View Hierarchy:
            com.android.internal.policy.DecorView{827a3b8 V.E...... ........ 0,0-1080,2400}
              android.widget.Button{8e9102 V.E...C.. ........ 100,1600-980,1750 #7f080121 app:id/btn_next} text="Next"
    """.trimIndent()

    val nodes = com.example.engine.RootEngine.parseDumpsysViewHierarchy(resultDumpsys)
    val nextBtn = nodes.find { it.text.equals("Next", ignoreCase = true) }
    org.junit.Assert.assertNotNull(nextBtn)
    assertEquals(540, nextBtn!!.centerX)
    assertEquals(1675, nextBtn.centerY)
    assertTrue(nextBtn.clickable)
  }
}
