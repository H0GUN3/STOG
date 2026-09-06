package com.stog.backend.auth;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Locale;

@Converter
public class AuthProviderConverter
    implements AttributeConverter<AuthProvider, String> {
    @Override
    public String convertToDatabaseColumn(AuthProvider provider) {
        return provider == null ? null : provider.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public AuthProvider convertToEntityAttribute(String value) {
        return value == null ? null : AuthProvider.valueOf(value.toUpperCase(Locale.ROOT));
    }
}
