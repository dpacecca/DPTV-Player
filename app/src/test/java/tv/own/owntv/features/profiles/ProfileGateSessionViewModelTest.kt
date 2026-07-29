package tv.own.owntv.features.profiles

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ProfileGateSessionViewModel unit tests (docs/internationalization.md, "Unit tests → Profile gate").
 *
 * Behavioural coverage of the session transitions. The structural guarantee — no `SavedStateHandle`
 * and no `rememberSaveable` for the authentication flag — is enforced by the class simply not
 * referencing either; the class is intentionally tiny so that absence is reviewable at a glance.
 */
class ProfileGateSessionViewModelTest {

    @Test
    fun `gate starts unauthenticated`() {
        val vm = ProfileGateSessionViewModel()
        assertFalse(vm.gatePassed)
        assertFalse(vm.addingProfile)
        assertFalse(vm.switchProfileRequested)
    }

    @Test
    fun `markGatePassed authenticates and clears a pending switch`() {
        val vm = ProfileGateSessionViewModel()
        vm.requestSwitchProfile()
        vm.markGatePassed()
        assertTrue(vm.gatePassed)
        assertFalse(vm.switchProfileRequested)
    }

    @Test
    fun `requestSwitchProfile drops past the gate and forces it open for a single unpinned profile`() {
        val vm = ProfileGateSessionViewModel()
        vm.markGatePassed()
        vm.requestSwitchProfile()
        assertFalse(vm.gatePassed)
        assertTrue(vm.switchProfileRequested)
    }

    @Test
    fun `cancelSwitchProfileRequest returns past the gate`() {
        val vm = ProfileGateSessionViewModel()
        vm.requestSwitchProfile()
        vm.cancelSwitchProfileRequest()
        assertTrue(vm.gatePassed)
        assertFalse(vm.switchProfileRequested)
    }

    @Test
    fun `add-profile flow opens and cancels back to the gate without authenticating`() {
        val vm = ProfileGateSessionViewModel()
        vm.startAddingProfile()
        assertTrue(vm.addingProfile)
        assertFalse(vm.switchProfileRequested)
        vm.cancelAddingProfile()
        assertFalse(vm.addingProfile)
        assertFalse(vm.gatePassed)
    }

    @Test
    fun `completing add-profile onboarding authenticates as the new profile`() {
        val vm = ProfileGateSessionViewModel()
        vm.startAddingProfile()
        vm.completeAddingProfile()
        assertFalse(vm.addingProfile)
        assertTrue(vm.gatePassed)
    }
}