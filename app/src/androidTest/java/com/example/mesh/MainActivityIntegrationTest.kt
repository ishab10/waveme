package com.example.mesh

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityIntegrationTest {

    @Test
    fun test_MainActivity_displaysPeersList_whenPermissionsAreGranted() {
        // For this test to pass, you must grant the required permissions to the app on the test device or emulator.
        ActivityScenario.launch(MainActivity::class.java)
        onView(withId(R.id.peersRecyclerView)).check(matches(isDisplayed()))
    }
}