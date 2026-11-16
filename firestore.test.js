const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function pastDate() {
  return new Date(Date.now() - 2000);
}

function validUserProfile(uid) {
  const now = pastDate();
  return {
    userId: uid,
    displayName: "Alice Seller",
    wifiOnly: true,
    dailyCapMb: 1000,
    minBatteryPct: 25,
    ratePer100MbInr: 0.85,
    totalMbSold: 0.0,
    availableBalanceInr: 0.0,
    pendingPayoutInr: 0.0,
    totalWithdrawnInr: 0.0,
    createdAt: now,
    updatedAt: now,
  };
}

function validSession(uid) {
  const now = pastDate();
  return {
    userId: uid,
    networkType: "Wi-Fi",
    mbShared: 120.5,
    estimatedEarningsInr: 1.02,
    avgSpeedMbps: 42.3,
    durationSeconds: 300,
    status: "completed",
    createdAt: now,
    updatedAt: now,
  };
}

function validPayout(uid) {
  const now = pastDate();
  return {
    userId: uid,
    amountInr: 50.0,
    method: "UPI",
    destination: "alice@upi",
    status: "pending_settlement",
    createdAt: now,
    updatedAt: now,
  };
}

function validListing(uid, status = "active") {
  const now = pastDate();
  return {
    userId: uid,
    sellerAlias: "Alice_Node",
    packageTitle: "500 MB Fast 5G Pool",
    mbAmount: 500,
    priceInr: 4.5,
    networkType: "5G",
    status,
    createdAt: now,
    updatedAt: now,
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

// --- Unauthenticated rejections ---
test("Unauthenticated user cannot read or write users, sessions, payouts, or listings", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).set(validUserProfile(ALICE_UID)));
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("sessions").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("payouts").get());
  await assertFails(unauthDb.collection("listings").where("status", "==", "active").get());
});

// --- User Profile ---
test("Owner can create and update their own UserProfile; shadow fields rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const profileRef = aliceDb.collection("users").doc(ALICE_UID);

  await assertSucceeds(profileRef.set(validUserProfile(ALICE_UID)));
  await assertSucceeds(profileRef.get());

  // Valid partial update (including 50 GB daily cap)
  await assertSucceeds(
    profileRef.update({
      dailyCapMb: 50000,
      updatedAt: pastDate(),
    })
  );

  // Valid ₹1/MB (₹100 per 100 MB) update succeeds
  await assertSucceeds(
    profileRef.update({
      ratePer100MbInr: 100.0,
      updatedAt: pastDate(),
    })
  );

  // Out-of-bounds rate update must fail
  await assertFails(
    profileRef.update({
      ratePer100MbInr: 150.0,
      updatedAt: pastDate(),
    })
  );

  // Shadow update with ghost field must fail
  await assertFails(
    profileRef.update({
      isAdmin: true,
      updatedAt: pastDate(),
    })
  );
});

test("Cross-user access to UserProfile is rejected", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("users").doc(BOB_UID).set(validUserProfile(BOB_UID));
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(aliceDb.collection("users").doc(BOB_UID).get());
  await assertFails(aliceDb.collection("users").doc(BOB_UID).update({ dailyCapMb: 500, updatedAt: pastDate() }));
});

// --- Bandwidth Sessions ---
test("Owner can create and list sessions, but cannot update sessions or read another user's sessions", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const sessRef = aliceDb.collection("users").doc(ALICE_UID).collection("sessions").doc("sess_1");

  await assertSucceeds(sessRef.set(validSession(ALICE_UID)));
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("sessions").get());
  await assertFails(sessRef.update({ mbShared: 999.0, updatedAt: pastDate() }));

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("sessions").get());
});

// --- Payout Requests ---
test("Owner can create payout (min ₹1.00) and cancel once; terminal state lock prevents reopening", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const payRef = aliceDb.collection("users").doc(ALICE_UID).collection("payouts").doc("pay_1");

  // Minimum ₹1.00 payout succeeds
  await assertSucceeds(payRef.set({ ...validPayout(ALICE_UID), amountInr: 1.0 }));
  await assertSucceeds(
    payRef.update({
      status: "cancelled",
      updatedAt: pastDate(),
    })
  );
  // Below ₹1.00 payout must fail
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("payouts").doc("pay_low").set({
      ...validPayout(ALICE_UID),
      amountInr: 0.5,
    })
  );
  // Terminal state lock: cannot revert cancelled -> pending_settlement
  await assertFails(
    payRef.update({
      status: "pending_settlement",
      updatedAt: pastDate(),
    })
  );
  // Cannot delete payout audit trail
  await assertFails(payRef.delete());
});

// --- Community Marketplace Listings ---
test("Marketplace listings enforce query alignment, ownership, and terminal state lock", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  const aliceListing = aliceDb.collection("listings").doc("list_1");
  await assertSucceeds(aliceListing.set(validListing(ALICE_UID, "active")));

  // Unfiltered list query MUST fail (rules are not filters)
  await assertFails(bobDb.collection("listings").get());

  // Filtered active marketplace query succeeds
  await assertSucceeds(bobDb.collection("listings").where("status", "==", "active").get());

  // Bob cannot modify Alice's listing
  await assertFails(bobDb.collection("listings").doc("list_1").update({ priceInr: 1.0, updatedAt: pastDate() }));
  await assertFails(bobDb.collection("listings").doc("list_1").delete());

  // Alice closes listing -> terminal state lock prevents further updates
  await assertSucceeds(aliceListing.update({ status: "closed", updatedAt: pastDate() }));
  await assertFails(aliceListing.update({ status: "active", updatedAt: pastDate() }));
});
