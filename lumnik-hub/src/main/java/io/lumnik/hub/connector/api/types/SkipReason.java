package io.lumnik.hub.connector.api.types;

import java.util.List;

public sealed interface SkipReason {
  record MappingFailed(String field, String message)           implements SkipReason {}
  record ValidationFailed(List<String> violations)             implements SkipReason {}
  record WriteFailed(String entity, String cause)              implements SkipReason {}
  record Duplicate(String businessKey)                         implements SkipReason {}
  record PolicyExcluded(String policyName)                     implements SkipReason {}
  record MalformedSource(String file, long line, String message) implements SkipReason {}
}
