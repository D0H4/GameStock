package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BotParticipantAssignmentsTest {
    @Test void addingListingsKeepsEveryLegacyPositionUnderActiveManagement() {
        var profiles=BotProfile.activePopulation(91,6020);
        Map<String,Long> ids=new HashMap<>();
        Map<Long,Set<Long>> holdings=new HashMap<>();
        for(int i=0;i<profiles.size();i++) {
            ids.put(profiles.get(i).username(),(long)i+1);
            holdings.put((long)i+1,Set.of((long)(i%15)+1));
        }
        List<Long> stocks=java.util.stream.LongStream.rangeClosed(1,17).boxed().toList();
        var assigned=BotParticipantAssignments.assign(profiles,ids,stocks,holdings);
        Set<String> pairs=new HashSet<>();
        for(var p:assigned)assertTrue(pairs.add(p.id()+":"+p.stock()),"No duplicate decisions for a position");
        for(int i=0;i<profiles.size();i++) {
            long user=i+1,oldStock=i%15+1,newStock=i%17+1;
            assertTrue(pairs.contains(user+":"+oldStock),"Legacy shares must not be stranded");
            assertTrue(pairs.contains(user+":"+newStock),"New listings retain their participants");
        }
        Map<Long,Long> counts=assigned.stream().collect(java.util.stream.Collectors.groupingBy(
                BotActivityEngine.Participant::id,java.util.stream.Collectors.counting()));
        for(var p:assigned)assertEquals(counts.get(p.id()).intValue(),p.watchedSymbols(),"Share allocation budget across watched stocks");
        assertEquals(assigned,BotParticipantAssignments.assign(profiles,ids,stocks,holdings));
    }

    @Test void existingPrimaryHoldingsAreNotDuplicatedAndUnknownListingsAreExcluded() {
        var profile=BotProfile.defaults(7).get(0);
        var assigned=BotParticipantAssignments.assign(List.of(profile),Map.of(profile.username(),1L),
                List.of(10L,20L),Map.of(1L,Set.of(10L,99L)));
        assertEquals(List.of(new BotActivityEngine.Participant(1,profile,10,1)),assigned);
    }
}
