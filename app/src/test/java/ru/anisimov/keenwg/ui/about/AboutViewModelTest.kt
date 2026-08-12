package ru.anisimov.keenwg.ui.about

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AboutViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `expert mode changes only after explicit owner action`() = runTest(dispatcher) {
        val source = MutableStateFlow(false)
        val vm = AboutViewModel(source) { source.value = it }

        advanceUntilIdle()
        assertFalse(vm.expertMode.value)
        vm.setExpertMode(true)
        advanceUntilIdle()
        assertTrue(vm.expertMode.value)
    }
}
