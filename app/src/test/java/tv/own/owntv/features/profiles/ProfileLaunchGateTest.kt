package tv.own.owntv.features.profiles

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for the cold-start ordering between DataStore and Room. */
class ProfileLaunchGateTest {
    @Test
    fun `active locked id arriving before Room cannot compose the shell`() {
        assertFalse(
            shellMayCompose(
                profileState = ProfileLoadState.Loading,
                activeProfileId = 42L,
                gatePassed = false,
                gateRequired = true,
            ),
        )
        assertFalse(
            shellMayCompose(
                profileState = ProfileLoadState.Loaded(emptyList()),
                activeProfileId = 42L,
                gatePassed = false,
                gateRequired = true,
            ),
        )
        assertFalse(
            shellMayCompose(
                profileState = ProfileLoadState.Loaded(listOf(fakeProfile(42L))),
                activeProfileId = 42L,
                gatePassed = false,
                gateRequired = true,
            ),
        )
        assertTrue(
            shellMayCompose(
                profileState = ProfileLoadState.Loaded(listOf(fakeProfile(42L))),
                activeProfileId = 42L,
                gatePassed = true,
                gateRequired = true,
            ),
        )
    }

    @Test
    fun `loaded empty result cannot enter shell even when stale id is already active`() {
        assertFalse(shellMayCompose(ProfileLoadState.Loaded(emptyList()), 7L, gatePassed = true, gateRequired = false))
    }

    @Test
    fun `an active id absent from a loaded list cannot enter shell`() {
        assertFalse(
            shellMayCompose(
                ProfileLoadState.Loaded(listOf(fakeProfile(1L))),
                activeProfileId = 7L,
                gatePassed = true,
                gateRequired = false,
            ),
        )
    }

    private fun fakeProfile(id: Long) = tv.own.owntv.core.database.entity.ProfileEntity(
        id = id,
        name = "Test",
        avatarColor = 0,
        avatarId = 0,
    )
}
