package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderServiceBotQueueTest {
    private static final BotActivityEngine.Activity[] EMPTY=new BotActivityEngine.Activity[0];
    private static final class Fixture implements AutoCloseable {
        final PersistenceWorker persistence=mock(PersistenceWorker.class);
        final MarketMetrics metrics=new MarketMetrics();
        final List<Integer> started=new ArrayList<>();
        final List<CompletableFuture<BotActivityEngine.Activity[]>> writes=new ArrayList<>();
        final OrderService service;
        Fixture() {
            when(persistence.acceptingWrites()).thenReturn(true);
            when(persistence.submitBot(anyInt(),anyString(),eq(BotActivityEngine.Activity[].class),eq(1),any()))
                    .thenAnswer(call->{
                        started.add(call.getArgument(0));
                        var result=new CompletableFuture<BotActivityEngine.Activity[]>();writes.add(result);return result;
                    });
            service=new OrderService(mock(MarketService.class),persistence,metrics,mock(ApplicationEventPublisher.class));
        }
        public void close(){service.close();}
    }

    @Test void competingGroupsWaitInOrderAndRepeatedTicksShareOneBoundedRequest() {
        try(var f=new Fixture()) {
            var first=f.service.submitBotBatch(0);
            var second=f.service.submitBotBatch(1);
            var third=f.service.submitBotBatch(2);
            var fourth=f.service.submitBotBatch(3);
            for(int i=0;i<100;i++) {
                assertSame(first,f.service.submitBotBatch(0));
                assertSame(second,f.service.submitBotBatch(1));
            }
            assertEquals(List.of(0),f.started);
            assertEquals(4,f.metrics.snapshot().get("bot.pendingGroups"));
            assertFalse(second.isDone());
            f.writes.get(0).complete(EMPTY);
            assertEquals(List.of(0,1),f.started);
            var nextFirst=f.service.submitBotBatch(0);
            assertNotSame(first,nextFirst);
            f.writes.get(1).complete(EMPTY);
            f.writes.get(2).complete(EMPTY);
            f.writes.get(3).complete(EMPTY);
            f.writes.get(4).complete(EMPTY);
            assertEquals(List.of(0,1,2,3,0),f.started);
            assertTrue(List.of(first,second,third,fourth,nextFirst).stream().allMatch(CompletableFuture::isDone));
            assertEquals(0,f.metrics.snapshot().get("bot.pendingGroups"));
            assertEquals(5,f.metrics.snapshot().get("bot.batches.completed"));
        }
    }

    @Test void failedWriteReleasesTheNextGroupAndItsCallerSeesTheError() {
        try(var f=new Fixture()) {
            var first=f.service.submitBotBatch(0);var second=f.service.submitBotBatch(1);
            var error=new IllegalStateException("test write failed");
            f.writes.get(0).completeExceptionally(error);
            assertSame(error,assertThrows(CompletionException.class,first::join).getCause());
            assertEquals(List.of(0,1),f.started);
            var activity=new BotActivityEngine.Activity(1,"TR",1,2,true);
            f.writes.get(1).complete(new BotActivityEngine.Activity[]{activity});
            assertEquals(List.of(activity),second.join());
            assertEquals(1,f.metrics.snapshot().get("bot.batches.failed"));
        }
    }

    @Test void concurrentTicksStillStartOneWriteAndRetainAllFourGroups() throws Exception {
        try(var f=new Fixture()) {
            var callers=Executors.newFixedThreadPool(8);
            try {
                var submissions=new ArrayList<Callable<CompletableFuture<List<BotActivityEngine.Activity>>>>();
                for(int i=0;i<100;i++) {
                    int shard=i%4;submissions.add(()->f.service.submitBotBatch(shard));
                }
                var requests=new HashSet<CompletableFuture<List<BotActivityEngine.Activity>>>();
                for(var result:callers.invokeAll(submissions))requests.add(result.get(5,TimeUnit.SECONDS));
                assertEquals(4,requests.size());assertEquals(1,f.started.size());
                for(int i=0;i<4;i++)f.writes.get(i).complete(EMPTY);
                assertEquals(Set.of(0,1,2,3),new HashSet<>(f.started));assertEquals(4,f.started.size());
                assertTrue(requests.stream().allMatch(CompletableFuture::isDone));
            } finally {callers.shutdownNow();}
        }
    }

    @Test void pausedStorageRejectsWaitingGroupsWithoutStartingMoreWrites() {
        try(var f=new Fixture()) {
            var active=f.service.submitBotBatch(0);var waiting=f.service.submitBotBatch(1);
            when(f.persistence.acceptingWrites()).thenReturn(false);
            f.writes.get(0).complete(EMPTY);
            assertEquals(List.of(),active.join());
            assertTrue(waiting.isCompletedExceptionally());
            assertEquals(List.of(),f.service.submitBotBatch(2).join());
            assertEquals(List.of(0),f.started);
        }
    }

    @Test void shutdownRejectsWaitingGroupsAndAllowsTheActiveWriteToFinish() {
        try(var f=new Fixture()) {
            var active=f.service.submitBotBatch(0);var waiting=f.service.submitBotBatch(1);
            f.service.close();
            assertTrue(waiting.isCompletedExceptionally());assertFalse(active.isDone());
            f.writes.get(0).complete(EMPTY);
            assertEquals(List.of(),active.join());assertEquals(List.of(0),f.started);
        }
    }

    @Test void immediateSubmissionFailureDoesNotLeaveTheGroupStuck() {
        try(var f=new Fixture()) {
            when(f.persistence.submitBot(anyInt(),anyString(),eq(BotActivityEngine.Activity[].class),eq(1),any()))
                    .thenThrow(new RejectedExecutionException("test queue full"));
            assertTrue(f.service.submitBotBatch(0).isCompletedExceptionally());
            assertTrue(f.service.submitBotBatch(0).isCompletedExceptionally());
            assertEquals(0,f.metrics.snapshot().get("bot.pendingGroups"));
        }
    }
}
