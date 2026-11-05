package local.codex.voyahcameralab;

public final class PowerHoldPolicyTest {
  private static int checks;
  private static void check(boolean condition, String reason) {
    checks++;
    if (!condition) throw new AssertionError(reason);
  }
  private static PowerHoldPolicy.Snapshot sample(int sw, int time, String property,
      String gear, int soc, int warning, String boot) {
    return new PowerHoldPolicy.Snapshot(sw, time, property, gear, soc, warning, boot);
  }
  private static PowerHoldPolicy.Snapshot off() {
    return sample(0, 15, "", "Parking", 15, 0, "boot-a");
  }
  private static PowerHoldPolicy.ReleaseDecision decision(PowerHoldPolicy.Snapshot current) {
    return new PowerHoldPolicy.Lease(off(), 120).releaseDecision(current);
  }
  private static void controllerCoordination() throws Exception {
    final class Journal { boolean pending; String owner = ""; }
    Journal journal = new Journal();
    PowerHoldPolicy.OwnershipGuard oldActivity = new PowerHoldPolicy.OwnershipGuard("old-activity");
    PowerHoldPolicy.OwnershipGuard recreatedActivity = new PowerHoldPolicy.OwnershipGuard("new-activity");
    java.util.concurrent.CountDownLatch firstStatus = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch commitAllowed = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch newCallReady = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch newEntered = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
    Thread oldCall = new Thread(() -> {
      try {
        oldActivity.serialized(() -> {
          check(oldActivity.mayStart(journal.pending), "Old Activity initially sees no journal");
          firstStatus.countDown();
          try { commitAllowed.await(); } catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
          journal.pending = true; journal.owner = "old-activity";
          return null;
        });
      } catch (Throwable caught) { failure.set(caught); }
    });
    Thread newCall = new Thread(() -> {
      try {
        newCallReady.countDown();
        recreatedActivity.serialized(() -> {
          newEntered.countDown();
          check(!recreatedActivity.mayStart(journal.pending), "Fresh pending journal blocks even a controller constructed before commit");
          check(!recreatedActivity.ownsJournal(journal.pending, journal.owner), "New Activity cannot mutate or clear old Activity's journal");
          return null;
        });
      } catch (Throwable caught) { failure.set(caught); }
    });
    oldCall.start();
    check(firstStatus.await(2, java.util.concurrent.TimeUnit.SECONDS), "Old status snapshot reached");
    newCall.start();
    check(newCallReady.await(2, java.util.concurrent.TimeUnit.SECONDS), "New controller attempts operation");
    try {
      check(!newEntered.await(100, java.util.concurrent.TimeUnit.MILLISECONDS), "Operations from two controller instances share one lock");
    } finally { commitAllowed.countDown(); }
    oldCall.join(2000); newCall.join(2000);
    check(!oldCall.isAlive() && !newCall.isAlive(), "Both serialized calls complete");
    if (failure.get() != null) throw new AssertionError("Controller coordination failed", failure.get());
    check(oldActivity.ownsJournal(journal.pending, journal.owner), "Original session retains its journal after recreated controller checks");
    // Simulate a later session: the stale old controller must re-read ownership,
    // including before cleanup, rather than using construction-time recovered state.
    journal.owner = "new-activity";
    check(!oldActivity.ownsJournal(journal.pending, journal.owner), "Stale controller loses mutation/clear authority on owner change");
    check(recreatedActivity.ownsJournal(journal.pending, journal.owner), "Only exact current session may mutate journal");
    PowerHoldPolicy.Lease lostJournal = new PowerHoldPolicy.Lease(off(), 120);
    PowerHoldPolicy.Snapshot active = sample(1, 4, "on", "Parking", 80, 0, "boot-a");
    check(lostJournal.releaseDecision(active) == PowerHoldPolicy.ReleaseDecision.RELEASE, "Old local lease initially matches");
    if (!oldActivity.ownsJournal(journal.pending, journal.owner)) lostJournal.revokeWriteAuthority();
    journal.owner = "old-activity";
    check(lostJournal.releaseDecision(active) == PowerHoldPolicy.ReleaseDecision.REVOKED,
        "Lost journal owner permanently revokes local write authority even if journal owner later matches");
    journal.pending = false;
    check(!oldActivity.ownsJournal(journal.pending, "old-activity"), "Missing pending journal cannot authorize stale cleanup");
    check(recreatedActivity.mayStart(journal.pending), "Completed old cleanup permits a new explicit acquire");
    check("timeout".equals(oldActivity.serialized(System.nanoTime() - 1,
        () -> "incorrectly executed", () -> "timeout")), "Expired lock deadline cannot launch an operation");
  }
  public static void main(String[] args) {
    // Removing a guard, confusing queued with ECU active, or restoring a changed lease
    // must fail these tests. Fixtures are hand-selected OEM boundary values.
    check(PowerHoldPolicy.rawMinutes(120) == 4, "Two hours encodes raw4");
    int[] valid = {30, 60, 90, 120, 150, 180};
    int[] raw = {1, 2, 3, 4, 5, 6};
    for (int i = 0; i < valid.length; i++)
      check(PowerHoldPolicy.rawMinutes(valid[i]) == raw[i], "Finite duration encoding");
    for (int invalid : new int[]{-1, 0, 15, 31, 210, 450, Integer.MAX_VALUE}) {
      boolean rejected = false;
      try { PowerHoldPolicy.rawMinutes(invalid); } catch (IllegalArgumentException expected) { rejected = true; }
      check(rejected, "Reject unsupported or infinite duration " + invalid);
    }
    check(PowerHoldPolicy.canAcquire(off()), "P and exact minimum SOC permits confirmed off");
    check(!PowerHoldPolicy.canAcquire(sample(1, 4, "off", "Parking", 80, 0, "boot-a")), "Never acquire existing active hold");
    check(!PowerHoldPolicy.canAcquire(sample(0, 4, "on", "Parking", 80, 0, "boot-a")), "Never acquire existing armed hold");
    for (int sw : new int[]{-1, 2})
      check(!PowerHoldPolicy.canAcquire(sample(sw, 4, "", "Parking", 80, 0, "boot-a")), "Unknown switch denied");
    check(!PowerHoldPolicy.canAcquire(sample(0, 16, "", "Parking", 80, 0, "boot-a")), "Invalid duration denied");
    PowerHoldPolicy.Snapshot h97x = sample(0, -1, "off", "Parking", 80, 0, "boot-a");
    check(PowerHoldPolicy.canAcquire(h97x), "H97X never reports TIME, the hold must still start");
    PowerHoldPolicy.Lease unreported = new PowerHoldPolicy.Lease(h97x, 30);
    check(unreported.releaseTime() == 1, "An unreported duration is never written back as -1");
    check(PowerHoldPolicy.state(sample(1, -1, "on", "Parking", 80, 0, "boot-a")) == PowerHoldPolicy.State.ACTIVE, "Active hold reads active with TIME -1");
    check(PowerHoldPolicy.state(sample(0, -1, "on", "Parking", 80, 0, "boot-a")) == PowerHoldPolicy.State.ARMED, "Prepared hold reads prepared with TIME -1");
    check(unreported.releaseDecision(sample(0, -1, "off", "Parking", 80, 0, "boot-a")) == PowerHoldPolicy.ReleaseDecision.ENDED, "Owner cancellation ends the lease without a write");
    check(unreported.releaseDecision(sample(1, -1, "on", "Parking", 80, 0, "boot-a")) == PowerHoldPolicy.ReleaseDecision.REVOKED, "Authority is not regained after cancellation");
    PowerHoldPolicy.Lease prepared = new PowerHoldPolicy.Lease(h97x, 30);
    check(prepared.releaseDecision(sample(0, -1, "on", "Parking", 80, 0, "boot-a")) == PowerHoldPolicy.ReleaseDecision.RELEASE, "A prepared own hold is released");
    check(prepared.releaseDecision(sample(1, -1, "on", "Parking", 80, 0, "boot-a")) == PowerHoldPolicy.ReleaseDecision.RELEASE, "An active own hold is released");
    for (int soc : new int[]{-1, 0, 14, 101})
      check(!PowerHoldPolicy.canAcquire(sample(0, 4, "", "Parking", soc, 0, "boot-a")), "Unsafe SOC denied");
    for (String gear : new String[]{"Drive", "Unknown", "", "Reverse"})
      check(!PowerHoldPolicy.canAcquire(sample(0, 4, "", gear, 80, 0, "boot-a")), "Only Parking accepted");
    for (int warning : new int[]{1, 2})
      check(!PowerHoldPolicy.canAcquire(sample(0, 4, "", "Parking", 80, warning, "boot-a")), "OEM warning cannot be defeated");
    check(!PowerHoldPolicy.canAcquire(sample(0, 4, null, "Parking", 80, 0, "boot-a")), "Unreadable property denied");
    check(!PowerHoldPolicy.canAcquire(sample(0, 4, "", "Parking", 80, 0, "")), "Unknown boot identity denied");
    check(PowerHoldPolicy.state(off()) == PowerHoldPolicy.State.OFF, "Confirmed off");
    PowerHoldPolicy.Snapshot armed = sample(0, 4, "on", "Parking", 80, 0, "boot-a");
    PowerHoldPolicy.Snapshot active = sample(1, 4, "on", "Parking", 80, 0, "boot-a");
    check(PowerHoldPolicy.state(armed) == PowerHoldPolicy.State.ARMED, "Prepared property is not ECU active");
    check(PowerHoldPolicy.state(active) == PowerHoldPolicy.State.ACTIVE, "Only ECU switch1 is active");
    check(PowerHoldPolicy.state(sample(-1, 4, "", "Parking", 80, 0, "boot-a")) == PowerHoldPolicy.State.UNAVAILABLE, "Unknown is not off");
    check(PowerHoldPolicy.stateAfterQueued(off()) == PowerHoldPolicy.State.REQUESTED, "Setter queue does not imply armed or active");
    check(decision(armed) == PowerHoldPolicy.ReleaseDecision.RELEASE, "Own armed lease can be released");
    check(decision(active) == PowerHoldPolicy.ReleaseDecision.RELEASE, "Own active lease can be released");
    check(decision(sample(0, 4, "off", "Parking", 80, 0, "boot-a")) == PowerHoldPolicy.ReleaseDecision.ENDED, "OEM ended hold is never reenabled or restored");
    check(decision(sample(1, 6, "on", "Parking", 80, 0, "boot-a")) == PowerHoldPolicy.ReleaseDecision.CHANGED, "External duration change prevents cleanup");
    check(decision(sample(1, 4, "on", "Parking", 80, 0, "boot-b")) == PowerHoldPolicy.ReleaseDecision.CHANGED, "New boot invalidates lease");
    check(decision(sample(1, 4, "custom", "Parking", 80, 0, "boot-a")) == PowerHoldPolicy.ReleaseDecision.CHANGED, "External property change prevents cleanup");
    check(decision(sample(1, 4, "on", "Drive", 80, 0, "boot-a")) == PowerHoldPolicy.ReleaseDecision.CHANGED, "Gear exit revokes automatic restore");
    check(decision(sample(1, 4, "on", "Parking", 14, 1, "boot-a")) == PowerHoldPolicy.ReleaseDecision.CHANGED, "Low battery warning revokes ownership");
    check(decision(sample(-1, 4, "on", "Parking", 80, 0, "boot-a")) == PowerHoldPolicy.ReleaseDecision.UNKNOWN,
        "Missing switch cache is uncertainty, not proof that ownership changed");
    check(decision(sample(1, -1, "on", "Parking", 80, 0, "boot-a")) == PowerHoldPolicy.ReleaseDecision.UNKNOWN,
        "Missing duration cache cannot discard cleanup journal");
    check(decision(sample(1, 4, "on", "Parking", -1, 0, "boot-a")) == PowerHoldPolicy.ReleaseDecision.UNKNOWN,
        "Missing SOC cache cannot discard cleanup journal");
    // A pending journal after a failed acquire is diagnostic evidence, not a renewable
    // authority to adopt an unrelated later hold with the same duration/property.
    PowerHoldPolicy.Snapshot[] revokingObservations = {
        sample(0, 4, "off", "Parking", 80, 0, "boot-a"),
        sample(1, 6, "on", "Parking", 80, 0, "boot-a"),
        sample(1, 4, "custom", "Parking", 80, 0, "boot-a"),
        sample(1, 4, "on", "Drive", 80, 0, "boot-a"),
        sample(1, 4, "on", "Parking", 14, 1, "boot-a")
    };
    for (PowerHoldPolicy.Snapshot changed : revokingObservations) {
      for (boolean initiallyMatched : new boolean[]{false, true}) {
        PowerHoldPolicy.Lease tracked = new PowerHoldPolicy.Lease(off(), 120);
        if (initiallyMatched) check(tracked.releaseDecision(armed) == PowerHoldPolicy.ReleaseDecision.RELEASE,
            "Lease can start with confirmed own state");
        check(tracked.releaseDecision(changed) != PowerHoldPolicy.ReleaseDecision.RELEASE,
            "Ended or external observation blocks release");
        check(tracked.releaseDecision(active) != PowerHoldPolicy.ReleaseDecision.RELEASE,
            "Later matching user hold never restores revoked write authority");
        check(tracked.releaseDecision(armed) != PowerHoldPolicy.ReleaseDecision.RELEASE,
            "Revocation remains latched across repeated matching observations");
      }
    }
    for (PowerHoldPolicy.Snapshot unavailable : new PowerHoldPolicy.Snapshot[]{
        sample(-1, 4, "on", "Parking", 80, 0, "boot-a"),
        sample(1, -1, "on", "Parking", 80, 0, "boot-a"),
        sample(1, 4, "on", "Parking", -1, 0, "boot-a")}) {
      PowerHoldPolicy.Lease uncertain = new PowerHoldPolicy.Lease(off(), 120);
      check(uncertain.releaseDecision(armed) == PowerHoldPolicy.ReleaseDecision.RELEASE,
          "Confirmed lease before cache interruption");
      check(uncertain.releaseDecision(unavailable) == PowerHoldPolicy.ReleaseDecision.UNKNOWN,
          "Unknown cache only suspends judgement");
      check(uncertain.releaseDecision(armed) == PowerHoldPolicy.ReleaseDecision.RELEASE,
          "Unknown cache alone does not permanently revoke authority");
    }
    PowerHoldPolicy.Snapshot pendingOriginalTime = sample(0, 15, "on", "Parking", 80, 0, "boot-a");
    PowerHoldPolicy.Lease queued = new PowerHoldPolicy.Lease(off(), 120);
    check(queued.releaseDecision(pendingOriginalTime) == PowerHoldPolicy.ReleaseDecision.UNKNOWN,
        "Queued own request may retain original duration until asynchronous cache update");
    check(queued.releaseDecision(armed) == PowerHoldPolicy.ReleaseDecision.RELEASE,
        "Requested duration arriving after initial cache lag establishes own state");
    check(queued.releaseDecision(pendingOriginalTime) == PowerHoldPolicy.ReleaseDecision.CHANGED,
        "Return to original duration after requested duration was seen is external change");
    check(queued.releaseDecision(active) == PowerHoldPolicy.ReleaseDecision.REVOKED,
        "Later matching duration cannot undo observed duration change");
    PowerHoldPolicy.Lease endedWhilePending = new PowerHoldPolicy.Lease(off(), 120);
    check(endedWhilePending.releaseDecision(pendingOriginalTime) == PowerHoldPolicy.ReleaseDecision.UNKNOWN,
        "Pending original duration gives no release authority");
    check(endedWhilePending.releaseDecision(off()) == PowerHoldPolicy.ReleaseDecision.ENDED,
        "Confirmed OFF revokes even before own requested duration was ever seen");
    check(endedWhilePending.releaseDecision(active) == PowerHoldPolicy.ReleaseDecision.REVOKED,
        "A new matching external hold after pending OFF is never adopted");
    PowerHoldPolicy.Lease failedReply = new PowerHoldPolicy.Lease(off(), 120);
    check(failedReply.releaseDecision(active) == PowerHoldPolicy.ReleaseDecision.RELEASE,
        "Matching status can resolve a lost reply before any definitive cancellation");
    check(failedReply.releaseDecision(off()) == PowerHoldPolicy.ReleaseDecision.ENDED,
        "OFF after resolved lost reply revokes authority");
    check(failedReply.releaseDecision(active) == PowerHoldPolicy.ReleaseDecision.REVOKED,
        "External matching hold after failed reply and OFF remains protected");
    check(off().sameAs(PowerHoldPolicy.Snapshot.parse(off().wire())), "Snapshot round trip preserves empty property");
    check("0\t15\t15\t0\tParking\t\tboot-a".equals(off().wire()), "Protocol serializes the exact independently specified original snapshot");
    check(armed.sameAs(PowerHoldPolicy.Snapshot.parse("0\t4\t80\t0\tParking\tb24\tboot-a")), "Fixed wire fixture reads on property and duration correctly");
    PowerHoldPolicy.Snapshot original = sample(0, 0, "arbitrary\tточное значение", "Parking", 100, -1, "boot-a");
    check(original.sameAs(PowerHoldPolicy.Snapshot.parse(original.wire())), "Exact original property preserved without argument injection");
    check(!off().sameAs(sample(0, 4, "", "Parking", 15, 0, "boot-a")), "CAS detects changed duration");
    for (String malformed : new String[]{"", "0\t4", "x\t4\t80\t0\tParking\t\tboot-a", "0\t4\t80\t0\tParking\t%%%\tboot-a", "0\t4\t80\t0\tParking\t\tboot-a\nextra"}) {
      boolean rejected = false;
      try { PowerHoldPolicy.Snapshot.parse(malformed); } catch (IllegalArgumentException expected) { rejected = true; }
      check(rejected, "Malformed helper snapshot rejected");
    }
    String journal = off().wire();
    check(PowerHoldPolicy.journalExpired(journal, sample(0, 15, "", "Parking", 80, 0, "boot-b")),
        "A journal from an earlier boot can be retired after a read-only status");
    check(PowerHoldPolicy.journalExpired(journal, sample(1, 4, "on", "Parking", 80, 0, "boot-b")),
        "Retiring needs no particular state: it writes nothing to the vehicle");
    check(!PowerHoldPolicy.journalExpired(journal, sample(1, 4, "on", "Parking", 80, 0, "boot-a")),
        "The same boot keeps the journal: another process may still own this hold");
    check(!PowerHoldPolicy.journalExpired(journal, null), "No status never retires a journal");
    check(!PowerHoldPolicy.journalExpired(journal, sample(0, 15, "", "Parking", 80, 0, "")),
        "Unknown boot never retires a journal");
    for (String unreadable : new String[]{null, "", "not-a-snapshot"})
      check(!PowerHoldPolicy.journalExpired(unreadable, sample(0, 15, "", "Parking", 80, 0, "boot-b")),
          "An unreadable journal stays visible instead of being cleared");
    try { controllerCoordination(); } catch (Exception failure) { throw new AssertionError(failure); }
    System.out.println("PASS: Power Hold policy (" + checks + " checks), no hardware actions");
  }
}
