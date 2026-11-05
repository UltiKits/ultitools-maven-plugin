package com.ultikits.ulticloud;

/**
 * The confirmation that a destructive goal requires before it sends any request.
 *
 * <p>The given string must equal the expected one exactly: no trimming, no case folding,
 * no prefix match. The expected string names the target ({@code <identifyString>} for a
 * module, {@code <identifyString>@<version>} for a version), so a confirmation written for
 * one target can never authorise another.</p>
 */
public final class Confirmation {

    /** The user property that carries the confirmation. */
    public static final String PROPERTY = "ultitools.confirm";

    private Confirmation() {
    }

    /**
     * Checks the confirmation.
     *
     * @param action   what the goal is about to do, for the message
     * @param expected the exact string that confirms this target
     * @param given    the configured confirmation, or null
     * @throws IllegalArgumentException when {@code given} is not exactly {@code expected};
     *                                  the message states the expected form and never echoes a credential
     */
    public static void require(String action, String expected, String given) {
        if (expected == null || expected.isEmpty()) {
            throw new IllegalStateException("expected confirmation is empty");
        }
        if (given != null && given.equals(expected)) {
            return;
        }
        throw new IllegalArgumentException((given == null ? "缺少确认串" : "确认串与目标不一致")
                + "：" + action + "必须显式确认，请加 -D" + PROPERTY + "=" + expected + "（须完全一致，区分大小写）。没有发送任何请求");
    }
}
