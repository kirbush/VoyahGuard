package local.codex.voyahcameralab;

public final class LaunchPolicyTest {
  private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  public static void main(String[] args){
    LaunchPolicy policy=new LaunchPolicy();
    policy.newLaunch(true);
    check(policy.isPending(),"Auto-start waits until frames and permission are available");
    check(policy.takeAutoRecord(),"First ready frame consumes this launch request");
    check(!policy.takeAutoRecord(),"Later frames and resume cannot restart a stopped recording");
    policy.newLaunch(true);policy.stop();
    check(!policy.takeAutoRecord(),"Stop before first frame cancels pending startup");
    policy.newLaunch(false);
    check(!policy.takeAutoRecord(),"Preview-only launch remains available");
    policy.newLaunch(true);
    check(policy.takeAutoRecord(),"Explicit new launch can rearm auto-record");
    policy.restore(false);
    check(!policy.takeAutoRecord(),"Activity recreation after explicit stop stays stopped");
    policy.restore(true);
    check(policy.takeAutoRecord(),"Recreation while still waiting for permission preserves the one request");
    System.out.println("PASS: one-shot launch recording and explicit Stop");
  }
}
