package com.gamestock.backend.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** Won amounts and epoch seconds cannot be silently rounded or parsed from strings. */
public final class StrictLongDeserializer extends JsonDeserializer<Long> {
    @Override public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT)
            return (Long) context.handleUnexpectedToken(Long.class, parser);
        return parser.getLongValue();
    }
}
