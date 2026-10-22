package com.gamestock.backend.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** Quantities must arrive as JSON integers; never truncate a decimal into a real trade. */
public final class StrictIntegerDeserializer extends JsonDeserializer<Integer> {
    /** Optional fields may be absent/null, but a provided value must still be an integer. */
    public static final class Nullable extends JsonDeserializer<Integer> {
        @Override public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return new StrictIntegerDeserializer().deserialize(parser, context);
        }
    }
    @Override public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT)
            return (Integer) context.handleUnexpectedToken(Integer.class, parser);
        return parser.getIntValue();
    }
    @Override public Integer getNullValue(DeserializationContext context) throws com.fasterxml.jackson.databind.JsonMappingException {
        return context.reportInputMismatch(Integer.class, "정수 값이 필요합니다.");
    }
}
