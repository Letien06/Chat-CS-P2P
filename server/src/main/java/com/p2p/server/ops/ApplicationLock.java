package com.p2p.server.ops;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.Set;
/** Process-wide data ownership shared by the server and offline maintenance tools. */
public final class ApplicationLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private ApplicationLock(FileChannel channel,FileLock lock) {
        this.channel=channel;
        this.lock=lock;
    }
    public static ApplicationLock acquire(Path directory)throws IOException {
        Files.createDirectories(directory);
        Path root=directory.toRealPath(),file=root.resolve(".server.lock");
        if(Files.isSymbolicLink(file))throw new IOException("Application lock must not be a symlink");
        FileChannel channel=FileChannel.open(file,Set.of(StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS));
        try {
            FileLock lock;
            try {
                lock=channel.tryLock();
            }
            catch(OverlappingFileLockException e) {
                throw new IOException("Data directory is in use; stop the server first",e);
            }
            if(lock==null)throw new IOException("Data directory is in use; stop the server first");
            return new ApplicationLock(channel,lock);
        }
        catch(Throwable e) {
            channel.close();
            throw e;
        }
    }
    public void close()throws IOException {
        try {
            if(lock.isValid())lock.release();
        }
        finally {
            channel.close();
        }
    }
}
