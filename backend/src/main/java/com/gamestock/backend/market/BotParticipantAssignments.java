package com.gamestock.backend.market;

import java.util.*;

/** A listing-count change must not orphan inventory owned under an older assignment. */
final class BotParticipantAssignments {
    private BotParticipantAssignments() { }

    static List<BotActivityEngine.Participant> assign(List<BotProfile> profiles,Map<String,Long> ids,
                                                    List<Long> stocks,Map<Long,Set<Long>> heldStocks) {
        if(stocks.isEmpty())return List.of();
        Set<Long> listed=new HashSet<>(stocks);
        List<BotActivityEngine.Participant> result=new ArrayList<>();
        for(int i=0;i<profiles.size();i++) {
            BotProfile profile=profiles.get(i);
            long user=ids.get(profile.username());
            Set<Long> watched=new TreeSet<>(heldStocks.getOrDefault(user,Set.of()));
            watched.retainAll(listed);
            watched.add(stocks.get(i%stocks.size()));
            for(long stock:watched)
                result.add(new BotActivityEngine.Participant(user,profile,stock,watched.size()));
        }
        return List.copyOf(result);
    }
}
