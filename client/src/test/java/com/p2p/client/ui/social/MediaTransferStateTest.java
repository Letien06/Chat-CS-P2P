package com.p2p.client.ui.social;

import com.p2p.common.social.WireMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class MediaTransferStateTest {
    @TempDir Path dir;
    @Test void onlyExactLengthAndChecksumCanBeVerified()throws Exception{
        Path part=dir.resolve("download.part");Files.writeString(part,"hello");String hash="2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
        assertEquals(hash,MediaTransfers.verify(part,5,hash));assertThrows(MediaTransfers.HashMismatchException.class,()->MediaTransfers.verify(part,4,hash));
        Files.writeString(part,"jello");assertThrows(MediaTransfers.HashMismatchException.class,()->MediaTransfers.verify(part,5,hash));assertFalse(Files.exists(dir.resolve("download")));
    }
    @Test void closedTransfersReleaseExecutorsAndIgnoreLateEvents()throws Exception{
        MediaTransfers media=new MediaTransfers(()->null,e->fail("Unexpected error after close"));media.close();media.close();
        for(String name:List.of("io","uploadWorker","watchdog")){var field=MediaTransfers.class.getDeclaredField(name);field.setAccessible(true);ExecutorService executor=(ExecutorService)field.get(media);assertTrue(executor.isShutdown());assertTrue(executor.awaitTermination(2,TimeUnit.SECONDS));}
        assertDoesNotThrow(()->media.event(WireMessage.event("MEDIA_DOWNLOAD_COMPLETE",Map.of("transferId","stale-session"))));
    }
}
