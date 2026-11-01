package eu.kanade.tachiyomi

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** K2: the host-app hook that feeds AppInfo's getters. */
class AppInfoVersionTest {

    /** The holder is process-global: restore the unset defaults so the
     * defaults test in ModelTypesTest sees them regardless of the order
     * JUnit runs the two classes in the shared test JVM. */
    @After
    fun restoreDefaults() {
        AppInfo.setVersionInfo(0, "")
    }

    @Test
    fun `host-set version values are reported`() {
        AppInfo.setVersionInfo(207, "9.9.9-test")
        assertEquals(207, AppInfo.getVersionCode())
        assertEquals("9.9.9-test", AppInfo.getVersionName())
    }
}
