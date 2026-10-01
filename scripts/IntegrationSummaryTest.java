/** Run: javac -d <temp-dir> scripts/IntegrationSummary{,Test}.java; java -cp <temp-dir> IntegrationSummaryTest */
class IntegrationSummaryTest {
    private static void check(String expected, String input) {
        if (!expected.equals(IntegrationSummary.sanitize(input))) throw new AssertionError("sanitizer mismatch");
    }
    public static void main(String[] args) {
        check("failed at <ip> and <ip>", "failed at 192.0.2.10 and 2001:db8::1");
        check("[<ip>] <ip> <ip>", "[2001:db8::2] ::ffff:192.0.2.10 ::1");
        check("<ip>", "2001:db8::1%synthetic0");
        check("id=<uuid> host=<host> number=<n>",
                "id=00000000-0000-4000-8000-000000000001 host=fw-synthetic.example.test number=1234567");
        check("first line", "first line\r\nsecond line 192.0.2.10");
        check("", "");
        check("count=123456", "count=123456");
        check("x".repeat(200), "x".repeat(250));
        check(("x".repeat(194) + " <host>").substring(0, 200), "x".repeat(194) + " fw-synthetic.example.test");
        System.out.println("IntegrationSummary sanitizer: PASS");
    }
}
