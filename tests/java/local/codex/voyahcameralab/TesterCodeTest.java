package local.codex.voyahcameralab;

public final class TesterCodeTest {
  static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
  public static void main(String[] args) {
    TesterCode code = TesterCode.parse("K7M2QX9P");
    check(code != null && code.secret.equals("K7M2QX9P"), "Eight letters and digits make a code");
    check(TesterCode.parse("k7m2-qx9p").secret.equals("K7M2QX9P") && TesterCode.parse(" K7M2 QX9P ").secret.equals("K7M2QX9P"), "Case, dashes and spaces are forgiven");
    check(TesterCode.parse("O1IL0000").secret.equals("01110000"), "O, I and L are read as 0 and 1");
    check(TesterCode.parse("K7M2QX9") == null && TesterCode.parse("K7M2QX9PP") == null, "Seven or nine characters are refused");
    check(TesterCode.parse("K7M2QX9U") == null && TesterCode.parse("K7M2QX9#") == null && TesterCode.parse("К7М2QX9P") == null, "Foreign letters are refused");
    check(TesterCode.parse("") == null && TesterCode.parse(null) == null, "Nothing is not a code");
    System.out.println("PASS: tester codes are eight letters and digits, typing slips forgiven");
  }
}
