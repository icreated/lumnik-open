package io.lumnik.testkit.search.contract;

public sealed interface Applicability {
    record Required() implements Applicability {}

    record NotApplicable(String reason) implements Applicability {
        public NotApplicable {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("NotApplicable requires a non-blank reason");
            }
        }
    }
}
