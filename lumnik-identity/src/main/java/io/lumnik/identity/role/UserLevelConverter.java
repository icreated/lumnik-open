package io.lumnik.identity.role;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class UserLevelConverter implements AttributeConverter<UserLevel, String> {

    @Override
    public String convertToDatabaseColumn(UserLevel attribute) {
        return attribute == null ? null : attribute.getCode();
    }

    @Override
    public UserLevel convertToEntityAttribute(String dbData) {
        return dbData == null ? null : UserLevel.fromCode(dbData);
    }
}
