package com.gamestock.backend.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamestock.backend.market.DerivativeModels;
import com.gamestock.backend.market.MarketModels;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OpenApiSpecTest {
    private final ObjectMapper json = new ObjectMapper();
    private JsonNode specification() throws Exception {
        try (var input = getClass().getResourceAsStream("/openapi/gamestock-v1.json")) {
            assertNotNull(input);
            return json.readTree(input);
        }
    }

    @Test void publishedSpecificationCoversEveryEndpointAndNoPhantomRoutes() throws Exception {
        JsonNode document = specification();
        assertEquals("3.0.3", document.path("openapi").asText());
        Set<String> actual = new HashSet<>(), documented = new HashSet<>(), operationIds = new HashSet<>();
        for (Class<?> controller : Set.of(OpenApiController.class, OpenApiKeyController.class)) {
            String prefix = controller.getAnnotation(RequestMapping.class).value()[0];
            for (var method : controller.getDeclaredMethods()) {
                var mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) continue;
                for (var verb : mapping.method()) for (String path : mapping.value())
                    actual.add(verb.name().toLowerCase() + " " + prefix + path);
            }
        }
        document.path("paths").fields().forEachRemaining(path -> path.getValue().fields().forEachRemaining(operation -> {
            documented.add(operation.getKey() + " " + path.getKey());
            assertTrue(operationIds.add(operation.getValue().path("operationId").asText()), "operationId must be unique");
            if (!"get".equals(operation.getKey()) && path.getKey().startsWith("/api/openapi/v1/")) {
                assertTrue(operation.getValue().path("parameters").toString().contains("IdempotencyKey"));
                assertEquals("ApiKey", operation.getValue().path("security").get(0).fieldNames().next());
            }
        }));
        assertEquals(actual, documented);
        assertEquals(22, actual.size());
    }

    @Test void allSchemaReferencesResolveAndResponseFieldsStayDocumented() throws Exception {
        JsonNode document = specification();
        checkReferences(document, document);
        Map<String, Class<?>> records = new LinkedHashMap<>();
        records.put("Stock", MarketModels.Stock.class);
        records.put("TradingRestriction", MarketModels.TradingRestriction.class);
        records.put("StockPosition", MarketModels.Position.class);
        records.put("Portfolio", MarketModels.Portfolio.class);
        records.put("StockOrder", MarketModels.ActiveOrder.class);
        records.put("StockOrderResult", MarketModels.OrderResult.class);
        records.put("StockOrderHistory", MarketModels.OrderHistory.class);
        records.put("OrderBook", MarketModels.OrderBook.class);
        records.put("OrderBookLevel", MarketModels.OrderBookLevel.class);
        records.put("Candle", MarketModels.ChartCandle.class);
        records.put("Constituent", DerivativeModels.Constituent.class);
        records.put("EtfQuote", DerivativeModels.EtfQuote.class);
        records.put("FutureQuote", DerivativeModels.FutureQuote.class);
        records.put("OptionCycle", DerivativeModels.OptionCycle.class);
        records.put("OptionContract", DerivativeModels.OptionContract.class);
        records.put("DerivativeSnapshot", DerivativeModels.DerivativeSnapshot.class);
        records.put("DerivativePosition", DerivativeModels.Position.class);
        records.put("DerivativeTrade", DerivativeModels.Trade.class);
        records.put("DerivativeAccount", DerivativeModels.Account.class);
        records.put("DerivativeOrderResult", DerivativeModels.OrderResult.class);
        records.put("Account", OpenApiModels.Account.class);
        records.put("Quotes", OpenApiModels.Quotes.class);
        records.put("KeyInfo", OpenApiModels.KeyInfo.class);
        records.put("IssuedKey", OpenApiModels.IssuedKey.class);
        records.put("ApiError", OpenApiModels.ApiError.class);
        records.put("Receipt", OpenApiModels.Receipt.class);
        records.put("Cancellation", OpenApiModels.Cancellation.class);
        for (var record : records.entrySet()) {
            Set<String> expected = new HashSet<>();
            Arrays.stream(record.getValue().getRecordComponents()).forEach(field -> expected.add(field.getName()));
            Set<String> documented = new HashSet<>();
            document.path("components").path("schemas").path(record.getKey()).path("properties").fieldNames().forEachRemaining(documented::add);
            assertEquals(expected, documented, record.getKey());
        }
    }

    @Test void everyPublishedOrderExamplePassesRequestValidation() throws Exception {
        var examples = specification().path("paths").path("/api/openapi/v1/orders").path("post")
                .path("requestBody").path("content").path("application/json").path("examples");
        assertEquals(8, examples.size());
        var strict = OpenApiWebConfig.strictMapper(json);
        for (var example : examples) strict.treeToValue(example.path("value"), OpenApiModels.Order.class).validate();
    }

    private void checkReferences(JsonNode root, JsonNode node) {
        if (node.isObject() && node.has("$ref")) {
            String reference = node.path("$ref").asText();
            assertTrue(reference.startsWith("#/"));
            assertFalse(root.at(reference.substring(1)).isMissingNode(), reference);
        }
        node.forEach(child -> checkReferences(root, child));
    }
}
