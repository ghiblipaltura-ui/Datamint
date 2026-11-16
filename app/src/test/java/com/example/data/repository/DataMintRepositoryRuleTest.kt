package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class DataMintRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun ensureProfile_recordSession_and_requestPayout_succeedsForOwner() = runBlocking {
        val uniqueEmail = "alice_${UUID.randomUUID()}@test.com"
        val uid = signInTestUser(uniqueEmail)
        val repo = DataMintRepository(firestore, auth)

        val profileResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.ensureUserProfile("Alice Seller")
        }
        assertTrue("Profile create failed: ${profileResult.exceptionOrNull()}", profileResult.isSuccess)
        assertEquals(uid, profileResult.getOrThrow().userId)

        val sessionResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.recordSessionAndCreditWallet(
                networkType = "5G",
                mbShared = 200.0,
                estimatedEarningsInr = 25.0,
                avgSpeedMbps = 64.5,
                durationSeconds = 180,
                status = "completed"
            )
        }
        assertTrue("Session record failed: ${sessionResult.exceptionOrNull()}", sessionResult.isSuccess)

        val updatedProfile = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.getUserProfile(uid).getOrThrow()
        }
        assertNotNull(updatedProfile)
        assertEquals(200.0, updatedProfile!!.totalMbSold, 0.01)
        assertEquals(25.0, updatedProfile.availableBalanceInr, 0.01)

        val payoutResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.requestPayout(
                amountInr = 1.0,
                method = "UPI",
                destination = "alice@okaxis",
                note = "Minimum ₹1 payout test"
            )
        }
        assertTrue("Payout request failed: ${payoutResult.exceptionOrNull()}", payoutResult.isSuccess)
        val payoutId = payoutResult.getOrThrow()

        val cancelResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.cancelPayout(payoutId)
        }
        assertTrue("Cancel payout failed: ${cancelResult.exceptionOrNull()}", cancelResult.isSuccess)
    }

    @Test
    fun listings_marketplace_enforcesOwnershipAndActiveQuery() = runBlocking {
        val aliceUid = signInTestUser("alice_${UUID.randomUUID()}@test.com")
        val aliceRepo = DataMintRepository(firestore, auth)

        val listingId = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.createListing(
                sellerAlias = "AliceNode",
                packageTitle = "1 GB Unused 5G",
                mbAmount = 1000,
                priceInr = 8.5,
                networkType = "5G"
            ).getOrThrow()
        }

        val bobUid = signInTestUser("bob_${UUID.randomUUID()}@test.com")
        val bobRepo = DataMintRepository(firestore, auth)

        val activeListings = withTimeout(FLOW_TIMEOUT_MS) {
            bobRepo.observeActiveListings(bobUid).first { list -> list.any { it.id == listingId } }
        }
        assertTrue(activeListings.any { it.id == listingId && it.userId == aliceUid })

        // Cross-user update must fail with PERMISSION_DENIED
        val crossUpdate = withTimeout(DEFAULT_TIMEOUT_MS) {
            bobRepo.updateListingStatus(listingId, "paused")
        }
        assertTrue(crossUpdate.isFailure)
        val fsEx = crossUpdate.exceptionOrNull()?.unwrapFirestoreException()
        assertNotNull("Expected FirebaseFirestoreException", fsEx)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
    }

    @Test
    fun observeSessions_unauthenticatedUser_failsWithPermissionDenied() = runBlocking {
        val uid = signInTestUser("carol_${UUID.randomUUID()}@test.com")
        auth.signOut()
        val repo = DataMintRepository(firestore, auth)

        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repo.observeSessions(uid).first()
            }
            fail("Expected PERMISSION_DENIED for unauthenticated user")
        } catch (e: Exception) {
            val fsEx = e.unwrapFirestoreException()
            assertNotNull("Expected nested FirebaseFirestoreException, got $e", fsEx)
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
        }
    }

    @Test
    fun crossUser_profileRead_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser("alice2_${UUID.randomUUID()}@test.com")
        val aliceRepo = DataMintRepository(firestore, auth)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.ensureUserProfile("Alice").getOrThrow()
        }

        signInTestUser("bob2_${UUID.randomUUID()}@test.com")
        val bobRepo = DataMintRepository(firestore, auth)
        val result = withTimeout(DEFAULT_TIMEOUT_MS) {
            bobRepo.getUserProfile(aliceUid)
        }
        assertTrue(result.isFailure)
        val fsEx = result.exceptionOrNull()?.unwrapFirestoreException()
        assertNotNull(fsEx)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3000L
    }
}
