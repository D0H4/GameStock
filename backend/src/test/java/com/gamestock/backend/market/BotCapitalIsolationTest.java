package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BotCapitalIsolationTest {
    @Test void tradersWithoutCashOrSharesCannotObtainBuyingPowerFromOtherAccounts() {
        var setup=new MarketSimulationHarness.Setup();
        setup.broke=Set.of("GOV","GI","OW");setup.sessionMillis=120_000;
        var paths=MarketSimulationHarness.run(setup);
        for(String code:setup.broke) assertEquals(0,paths.get(code).fills(),code+" must not receive an automatic subsidy");
        assertTrue(paths.get("UMA").fills()>0,"Funded participants can still trade independently");
    }
}
