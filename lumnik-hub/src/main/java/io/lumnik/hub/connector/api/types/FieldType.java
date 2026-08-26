package io.lumnik.hub.connector.api.types;

import java.util.List;

public sealed interface FieldType {
  record StringField(int maxLength)              implements FieldType {}
  record IntegerField(int bits)                  implements FieldType {}
  record DecimalField(int precision, int scale)  implements FieldType {}
  record DateField()                             implements FieldType {}
  record TimestampField()                        implements FieldType {}
  record BooleanField()                          implements FieldType {}
  record JsonField()                             implements FieldType {}
  record EnumField(List<String> values)          implements FieldType {}
  record BinaryField()                           implements FieldType {}
}
