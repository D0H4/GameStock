package com.gamestock.backend.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/** Opt-in, database-free replay of funded production matching, never a prescribed price path. */
@EnabledIfSystemProperty(named="market.replay.output",matches=".+")
class MarketReplayExportTest {
    @Test void exportExecutedCandles() throws Exception {
        var setup=new MarketSimulationHarness.Setup();
        String code=System.getProperty("market.replay.code","UMA");
        if(!Set.of(MarketSimulationHarness.CODES).contains(code))throw new IllegalArgumentException("Unknown symbol");
        setup.symbols=Set.of(code);
        setup.seed=Long.getLong("market.replay.seed",20261024L);
        setup.sessionMillis=Long.getLong("market.replay.minutes",60L)*60_000;
        // One-second OHLC retains every matched extreme and execution order for partial-bar replay.
        var seconds=new TreeMap<Long,long[]>();
        boolean trace=Boolean.getBoolean("market.replay.trace");
        var profiles=trace?BotProfile.activePopulation(setup.seed,6020):List.<BotProfile>of();
        var executions=new ArrayList<List<Object>>();
        var consensus=new ArrayList<List<Object>>();
        if(trace)setup.onConsensus=(elapsed,states)->states.forEach((id,state)->consensus.add(List.of(elapsed,state.value())));
        setup.onFill=(elapsed,fill)->{
            long at=elapsed/1000*1000;
            long[] bar=seconds.computeIfAbsent(at,key->new long[]{at,fill.price(),fill.price(),fill.price(),fill.price(),0});
            bar[2]=Math.max(bar[2],fill.price());bar[3]=Math.min(bar[3],fill.price());
            bar[4]=fill.price();bar[5]+=fill.quantity();
            if(trace)executions.add(List.of(elapsed,fill.price(),fill.quantity(),fill.taker().side,
                    fill.taker().user==MarketSimulationHarness.LP?"LP":profiles.get((int)fill.taker().user-1).strategy().name(),
                    fill.maker().user==MarketSimulationHarness.LP?"LP":profiles.get((int)fill.maker().user-1).strategy().name()));
        };
        var result=MarketSimulationHarness.run(setup);
        MarketSimulationHarness.print("REPLAY",result);
        Path output=Path.of(System.getProperty("market.replay.output")).toAbsolutePath();
        Files.createDirectories(output.getParent());
        Path temporary=Files.createTempFile(output.getParent(),"replay-",".json");
        new ObjectMapper().writeValue(temporary.toFile(),Map.of(
                "code",code,"seed",setup.seed,"durationMs",setup.sessionMillis,
                "version",System.getProperty("market.replay.version","after"),
                "reference",MarketSimulationHarness.REFERENCE,"seconds",seconds.values(),
                "source","BotActivityEngine + BatchOrderBook; isolated funded accounts"));
        Files.move(temporary,output,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        if(trace)new ObjectMapper().writeValue(Path.of(output+".trades.json").toFile(),executions);
        if(trace)new ObjectMapper().writeValue(Path.of(output+".consensus.json").toFile(),consensus);
    }
}
