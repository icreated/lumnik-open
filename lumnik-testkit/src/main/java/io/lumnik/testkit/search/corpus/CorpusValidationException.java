package io.lumnik.testkit.search.corpus;

import java.util.List;

public class CorpusValidationException extends RuntimeException {

    public CorpusValidationException(List<String> problems) {
        super(String.join("; ", problems));
    }
}
