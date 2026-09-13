package net.elfradio.d31system;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class NoticeRuntimeTest {
    private NoticeRepository.Item item(String key) {
        return new NoticeRepository.Item(key,"org.telegram.messenger","标题","正文",key+"-event",null,10);
    }

    @Test public void failedTaskAndFailedLoggerDoNotKillFollowingTask() {
        AtomicInteger errors=new AtomicInteger(), completed=new AtomicInteger();
        NoticeTasks tasks=new NoticeTasks(error -> { errors.incrementAndGet(); throw new IllegalStateException(); });
        long token=tasks.start();
        tasks.run(token,() -> { throw new SecurityException("不应记录的内容"); });
        tasks.run(token,() -> { throw new NoClassDefFoundError(); });
        tasks.run(token,completed::incrementAndGet);
        assertEquals(2,errors.get()); assertEquals(1,completed.get());
    }

    @Test public void filteredSameKeyRemovesMessageAndBanner() {
        AtomicInteger changes=new AtomicInteger();
        NoticeRepository repo=new NoticeRepository(null,() -> 100,changes::incrementAndGet);
        repo.update("key",item("key"),true);
        assertEquals(1,repo.items().size()); assertNotNull(repo.currentBanner());
        repo.update("key",null,false);
        assertTrue(repo.items().isEmpty()); assertNull(repo.currentBanner());
        assertEquals(2,changes.get());
    }

    @Test public void telephoneTransitionsPublishAndClearBannerWithoutPolling() {
        AtomicInteger changes=new AtomicInteger();
        NoticeRepository repo=new NoticeRepository(null,() -> 100,changes::incrementAndGet);
        assertFalse(repo.callIdle());
        repo.callState(true); repo.callState(true);
        assertEquals(1,changes.get());
        repo.post(item("key"),true);
        int before=changes.get();
        repo.callState(false);
        assertEquals(before+1,changes.get()); assertNull(repo.currentBanner()); assertFalse(repo.callIdle());
        repo.callState(true);
        assertEquals(before+2,changes.get()); assertTrue(repo.callIdle()); assertNull(repo.currentBanner());
    }

    @Test public void notificationPublishFailureDoesNotEscapeMutation() {
        NoticeRepository repo=new NoticeRepository(null,() -> 100,() -> { throw new SecurityException(); });
        repo.update("key",item("key"),true);
        repo.update("key",null,false);
        assertTrue(repo.items().isEmpty());
    }

    @Test public void blockedReadCannotCommitAfterDestroyOrIntoNewSession() throws Exception {
        NoticeTasks tasks=new NoticeTasks(error -> { throw new AssertionError(error); });
        NoticeRepository repo=new NoticeRepository(null,() -> 100,() -> {});
        long old=tasks.start();
        CountDownLatch started=new CountDownLatch(1), release=new CountDownLatch(1);
        AtomicReference<Throwable> failure=new AtomicReference<>();
        AtomicInteger commits=new AtomicInteger();
        Thread read=new Thread(() -> {
            try {
                tasks.run(old,() -> {
                    started.countDown();
                    try { if(!release.await(3,TimeUnit.SECONDS)) throw new AssertionError("读取等待超时"); }
                    catch(InterruptedException e) { throw new AssertionError(e); }
                    if(tasks.commit(old,() -> repo.post(item("old"),true))) commits.incrementAndGet();
                });
            } catch(Throwable error) { failure.set(error); }
        });
        read.start();
        try {
            assertTrue(started.await(3,TimeUnit.SECONDS));
            // 来电回调不被锁外的阻塞读取拖住。
            assertTrue(tasks.commit(old,() -> repo.callState(false)));
            tasks.stop(old,repo::disconnected);
            long fresh=tasks.start();
            assertTrue(tasks.commit(fresh,() -> repo.post(item("new"),true)));
            tasks.stop(old,repo::disconnected);
            release.countDown(); read.join(3000);
            assertFalse(read.isAlive()); assertNull(failure.get()); assertEquals(0,commits.get());
            assertEquals("new",repo.items().get(0).key);
            assertTrue(tasks.accepts(fresh));
        } finally { release.countDown(); read.join(3000); }
    }

    @Test public void disconnectRejectsQueuedTasksAndClearsPublishedState() {
        NoticeRepository repo=new NoticeRepository(null,() -> 100,() -> {});
        NoticeTasks tasks=repo.tasks;
        long token=tasks.start();
        tasks.commit(token,() -> { repo.post(item("key"),true); repo.callState(true); });
        tasks.stop(token,repo::disconnected);
        tasks.run(token,() -> repo.post(item("late"),true));
        assertTrue(repo.items().isEmpty()); assertNull(repo.currentBanner()); assertFalse(repo.callIdle());
        assertFalse(tasks.commit(token,() -> fail("销毁后不应提交")));
    }
}
