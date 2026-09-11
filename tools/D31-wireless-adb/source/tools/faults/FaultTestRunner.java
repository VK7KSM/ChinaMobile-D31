package net.elfradio.d31bootstrap.faults;

import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

/** Independent host runner makes assumptions visible instead of counting them as passes. */
public final class FaultTestRunner {
    public static void main(String[] args) throws Exception {
        Class<?>[] tests = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) tests[i] = Class.forName(args[i]);
        final int[] assumptions = new int[1];
        JUnitCore junit = new JUnitCore();
        junit.addListener(new RunListener() {
            @Override public void testAssumptionFailure(Failure failure) { assumptions[0]++; }
        });
        Result result = junit.run(tests);
        for (Failure failure : result.getFailures()) System.out.println(failure.getTrace());
        System.out.println("{\"run\":" + result.getRunCount() + ",\"failed\":" + result.getFailureCount()
                + ",\"ignored\":" + result.getIgnoreCount() + ",\"assumptions\":" + assumptions[0]
                + ",\"passed\":" + (result.getRunCount() - result.getFailureCount() - assumptions[0])
                + ",\"elapsedMs\":" + result.getRunTime() + "}");
        System.exit(result.wasSuccessful() ? 0 : 1);
    }
    private FaultTestRunner() { }
}
