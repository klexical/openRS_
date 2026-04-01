package com.openrs.dash

import com.openrs.dash.update.AppVersion
import org.junit.Assert.*
import org.junit.Test

class AppVersionTest {

    // ── Tag parsing — valid tags ────────────────────────────────────────────

    @Test
    fun `parse stable release tag`() {
        val v = AppVersion.fromTagName("android-v2.2.6")
        assertNotNull(v)
        assertEquals(2, v!!.major)
        assertEquals(2, v.minor)
        assertEquals(6, v.patch)
        assertNull(v.rc)
        assertTrue(v.isStable)
    }

    @Test
    fun `parse RC release tag`() {
        val v = AppVersion.fromTagName("android-v2.2.6-rc.5")
        assertNotNull(v)
        assertEquals(2, v!!.major)
        assertEquals(2, v.minor)
        assertEquals(6, v.patch)
        assertEquals(5, v.rc)
        assertFalse(v.isStable)
    }

    @Test
    fun `parse rc1 tag`() {
        val v = AppVersion.fromTagName("android-v1.0.0-rc.1")
        assertNotNull(v)
        assertEquals(1, v!!.major)
        assertEquals(0, v.minor)
        assertEquals(0, v.patch)
        assertEquals(1, v.rc)
    }

    @Test
    fun `parse high version numbers`() {
        val v = AppVersion.fromTagName("android-v10.20.30")
        assertNotNull(v)
        assertEquals(10, v!!.major)
        assertEquals(20, v.minor)
        assertEquals(30, v.patch)
    }

    @Test
    fun `parse high RC number`() {
        val v = AppVersion.fromTagName("android-v3.0.0-rc.99")
        assertNotNull(v)
        assertEquals(99, v!!.rc)
    }

    // ── Tag parsing — invalid tags ──────────────────────────────────────────

    @Test
    fun `reject tag without android- prefix`() {
        assertNull(AppVersion.fromTagName("v2.2.6"))
    }

    @Test
    fun `reject firmware tag`() {
        assertNull(AppVersion.fromTagName("firmware-v1.0.0"))
    }

    @Test
    fun `reject tag with only two version parts`() {
        assertNull(AppVersion.fromTagName("android-v2.2"))
    }

    @Test
    fun `reject tag with extra parts`() {
        assertNull(AppVersion.fromTagName("android-v2.2.6.1"))
    }

    @Test
    fun `reject tag with non-numeric version`() {
        assertNull(AppVersion.fromTagName("android-va.b.c"))
    }

    @Test
    fun `reject empty string`() {
        assertNull(AppVersion.fromTagName(""))
    }

    @Test
    fun `reject bare rc suffix without dot number`() {
        assertNull(AppVersion.fromTagName("android-v2.2.6-rc"))
    }

    @Test
    fun `reject tag with wrong rc format`() {
        // Missing dot between rc and number
        assertNull(AppVersion.fromTagName("android-v2.2.6-rc5"))
    }

    // ── Display name ────────────────────────────────────────────────────────

    @Test
    fun `displayName for stable version`() {
        val v = AppVersion(2, 2, 6)
        assertEquals("2.2.6", v.displayName)
    }

    @Test
    fun `displayName for RC version`() {
        val v = AppVersion(2, 2, 6, rc = 5)
        assertEquals("2.2.6-rc.5", v.displayName)
    }

    // ── Comparison — same base version ──────────────────────────────────────

    @Test
    fun `stable equals stable same version`() {
        val a = AppVersion(2, 2, 6)
        val b = AppVersion(2, 2, 6)
        assertEquals(0, a.compareTo(b))
    }

    @Test
    fun `stable is greater than RC of same version`() {
        val stable = AppVersion(2, 2, 6)
        val rc = AppVersion(2, 2, 6, rc = 9)
        assertTrue(stable > rc)
    }

    @Test
    fun `RC of same version is less than stable`() {
        val rc = AppVersion(2, 2, 6, rc = 5)
        val stable = AppVersion(2, 2, 6)
        assertTrue(rc < stable)
    }

    @Test
    fun `higher RC beats lower RC of same version`() {
        val rc3 = AppVersion(2, 2, 6, rc = 3)
        val rc5 = AppVersion(2, 2, 6, rc = 5)
        assertTrue(rc5 > rc3)
    }

    @Test
    fun `same RC equals same RC`() {
        val a = AppVersion(2, 2, 6, rc = 3)
        val b = AppVersion(2, 2, 6, rc = 3)
        assertEquals(0, a.compareTo(b))
    }

    // ── Comparison — different base versions ────────────────────────────────

    @Test
    fun `higher major wins`() {
        val a = AppVersion(3, 0, 0)
        val b = AppVersion(2, 9, 9)
        assertTrue(a > b)
    }

    @Test
    fun `higher minor wins`() {
        val a = AppVersion(2, 3, 0)
        val b = AppVersion(2, 2, 9)
        assertTrue(a > b)
    }

    @Test
    fun `higher patch wins`() {
        val a = AppVersion(2, 2, 7)
        val b = AppVersion(2, 2, 6)
        assertTrue(a > b)
    }

    @Test
    fun `newer version RC is still greater than older stable`() {
        // 2.3.0-rc.1 > 2.2.6 (stable)
        val newRc = AppVersion(2, 3, 0, rc = 1)
        val oldStable = AppVersion(2, 2, 6)
        assertTrue(newRc > oldStable)
    }

    @Test
    fun `older version stable is less than newer version RC`() {
        val oldStable = AppVersion(2, 2, 5)
        val newRc = AppVersion(2, 2, 6, rc = 1)
        assertTrue(oldStable < newRc)
    }

    // ── Comparison — edge cases ─────────────────────────────────────────────

    @Test
    fun `rc1 is the lowest RC`() {
        val rc1 = AppVersion(1, 0, 0, rc = 1)
        val rc2 = AppVersion(1, 0, 0, rc = 2)
        assertTrue(rc1 < rc2)
    }

    @Test
    fun `version 0_0_1 is greater than 0_0_0`() {
        val a = AppVersion(0, 0, 1)
        val b = AppVersion(0, 0, 0)
        assertTrue(a > b)
    }

    // ── Sorting ─────────────────────────────────────────────────────────────

    @Test
    fun `versions sort correctly`() {
        val versions = listOf(
            AppVersion(2, 2, 6),          // stable
            AppVersion(2, 2, 6, rc = 3),  // rc.3
            AppVersion(2, 2, 5),          // older stable
            AppVersion(2, 3, 0, rc = 1),  // newer rc
            AppVersion(2, 2, 6, rc = 1),  // rc.1
        )
        val sorted = versions.sorted()
        assertEquals(AppVersion(2, 2, 5), sorted[0])
        assertEquals(AppVersion(2, 2, 6, rc = 1), sorted[1])
        assertEquals(AppVersion(2, 2, 6, rc = 3), sorted[2])
        assertEquals(AppVersion(2, 2, 6), sorted[3])
        assertEquals(AppVersion(2, 3, 0, rc = 1), sorted[4])
    }

    // ── Round-trip parsing ──────────────────────────────────────────────────

    @Test
    fun `parse and display round-trips for stable`() {
        val tag = "android-v2.2.6"
        val v = AppVersion.fromTagName(tag)!!
        assertEquals("2.2.6", v.displayName)
    }

    @Test
    fun `parse and display round-trips for RC`() {
        val tag = "android-v2.2.6-rc.5"
        val v = AppVersion.fromTagName(tag)!!
        assertEquals("2.2.6-rc.5", v.displayName)
    }
}
