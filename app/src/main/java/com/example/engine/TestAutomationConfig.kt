package com.example.engine

/**
 * TestAutomationConfig exposes a DEBUG / TEST configuration for authorized testing.
 *
 * This configuration allows target test applications, QA test harnesses, and automated
 * test suites to explicitly permit and coordinate with P.R Automation, rather than
 * relying on any evasion or anti-detection bypass mechanisms.
 */
object TestAutomationConfig {
    /**
     * Intent action that an authorized target test app or QA runner can broadcast
     * or query to verify automation status and authorization.
     */
    const val ACTION_AUTOMATION_AUTH = "com.example.automation.AUTH_STATUS"

    /**
     * Extra boolean indicating if the target application explicitly authorizes automation.
     */
    const val EXTRA_IS_AUTHORIZED = "is_authorized"

    /**
     * Extra string containing the test authorization token or package ID.
     */
    const val EXTRA_AUTH_TOKEN = "auth_token"

    /**
     * When set to true (default for testing), automation executes with full diagnostic logging
     * and explicit authorization verification.
     */
    @Volatile
    var testModeEnabled: Boolean = true

    /**
     * Set of package names or prefixes explicitly authorized for automated test execution.
     */
    val authorizedPackages = mutableSetOf<String>()

    /**
     * Verifies if a given package is authorized for automation testing.
     */
    fun isPackageAuthorized(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (authorizedPackages.isEmpty()) return true // Permit user-selected package when none restricted
        return authorizedPackages.contains(packageName)
    }

    /**
     * Authorizes a test package.
     */
    fun authorizePackage(packageName: String) {
        if (packageName.isNotBlank()) {
            authorizedPackages.add(packageName.trim())
            AutomationState.log("Authorized test package: ${packageName.trim()}")
        }
    }
}
