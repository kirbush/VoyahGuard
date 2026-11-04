package local.codex.voyahcameralab;

/** Hardware-independent contract checks, run by :app:corePolicyTest. */
public final class AllPolicyTests {
  public static void main(String[] args) throws Exception {
    PolicyTest.main(args);
    LaunchPolicyTest.main(args);
    StoragePolicyTest.main(args);
    SegmentPolicyTest.main(args);
    RecordingStoreTest.main(args);
    PowerHoldPolicyTest.main(args);
    System.out.println("PASS: all Sentry Lite software policies (hardware compatibility untested)");
  }
}
