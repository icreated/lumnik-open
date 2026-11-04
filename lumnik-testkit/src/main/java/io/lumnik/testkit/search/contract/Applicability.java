package io.lumnik.testkit.search.contract;

import io.lumnik.common.text.Text;

public sealed interface Applicability {
    record Required() implements Applicability {}

    record NotApplicable(String reason) implements Applicability {
        public NotApplicable {
            if (Text.blank(reason)) {
                throw new IllegalArgumentException("NotApplicable requires a non-blank reason");
            }
        }
    }
}
