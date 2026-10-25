package com.gamestock.backend.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** All listings share funded production matching; retain every execution extreme. */
@EnabledIfSystemProperty(named="market.candle.output",matches=".+")
class MarketCandleAuditTest {
    @Test void auditAllListings() throws Exception {
        var reports=new ArrayList<Map<String,Object>>();
        for(String seedText:System.getProperty("market.candle.seeds","20261024,7,91").split(",")) {
            var setup=new MarketSimulationHarness.Setup();
            setup.seed=Long.parseLong(seedText);
            setup.sessionMillis=Long.getLong("market.candle.minutes",60)*60_000;
            setup.observationMillis=Long.getLong("market.candle.observationMillis",2000);
            if(setup.sessionMillis<120_000 || setup.observationMillis<100 || setup.observationMillis%100!=0)
                throw new IllegalArgumentException("Use at least two minutes and a positive 100ms-multiple observation interval");
            var candles=new TreeMap<String,TreeMap<Long,long[]>>();
            setup.onFill=(elapsed,fill)->{
                String code=MarketSimulationHarness.CODES[(int)fill.buy().stock-1];
                long minute=elapsed/60_000;
                long[] bar=candles.computeIfAbsent(code,k->new TreeMap<>()).computeIfAbsent(minute,
                        k->new long[]{minute,fill.price(),fill.price(),fill.price(),fill.price(),0});
                bar[2]=Math.max(bar[2],fill.price());bar[3]=Math.min(bar[3],fill.price());
                bar[4]=fill.price();bar[5]+=fill.quantity();
            };
            var paths=MarketSimulationHarness.run(setup);
            for(String code:MarketSimulationHarness.CODES) {
                var bars=candles.getOrDefault(code,new TreeMap<>());
                assertTrue(paths.get(code).fills()>0,"No paid executions for "+code);
                assertTrue(paths.get(code).longestQuiet()<120_000,"Prolonged trading stall for "+code);
                double wick=0;int moving=0,doji=0,longWick=0;
                for(var bar:bars.values()) {
                    if(bar[0]==0)continue;
                    long range=bar[2]-bar[3],body=Math.abs(bar[4]-bar[1]);
                    if(body==0){doji++;continue;}
                    double ratio=1-body/(double)range;wick+=ratio;moving++;
                    if(ratio>=.8)longWick++;
                }
                double average=moving==0?0:wick/moving;
                System.out.printf(Locale.ROOT,"CANDLE %d %s wick=%.3f doji=%d long=%d moving=%d %s%n",
                        setup.seed,code,average,doji,longWick,moving,paths.get(code));
                reports.add(Map.of("seed",setup.seed,"code",code,"wick",average,"doji",doji,
                        "longWick",longWick,"moving",moving,"path",paths.get(code),"candles",bars.values()));
            }
        }
        Path output=Path.of(System.getProperty("market.candle.output"));
        Files.createDirectories(output.toAbsolutePath().getParent());
        new ObjectMapper().writeValue(output.toFile(),reports);
    }
}
