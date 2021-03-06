package net.f4fs.filesystem;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import net.f4fs.config.FSStatConfig;
import net.f4fs.filesystem.event.listeners.SyncFileEventListener;
import net.f4fs.filesystem.event.listeners.WriteFileEventListener;
import net.f4fs.filesystem.fsfilemonitor.FSFileMonitor;
import net.f4fs.filesystem.partials.AMemoryPath;
import net.f4fs.filesystem.partials.MemoryDirectory;
import net.f4fs.filesystem.partials.MemoryFile;
import net.f4fs.filesystem.partials.MemorySymLink;
import net.f4fs.filesystem.util.FSFileUtils;
import net.f4fs.fspeer.FSPeer;
import net.f4fs.fspeer.FSResizePeerMapChangeListener;
import net.f4fs.persistence.archive.VersionArchiver;
import net.fusejna.DirectoryFiller;
import net.fusejna.ErrorCodes;
import net.fusejna.FuseException;
import net.fusejna.StructFuseFileInfo.FileInfoWrapper;
import net.fusejna.StructStat.StatWrapper;
import net.fusejna.StructStatvfs.StatvfsWrapper;
import net.fusejna.StructTimeBuffer;
import net.fusejna.types.TypeMode.ModeWrapper;
import net.fusejna.util.FuseFilesystemAdapterFull;
import net.tomp2p.peers.Number160;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * 
 * 
 * <b>Description of idea</b>: storing each path as [location key: hash('keys')], [content key: hash(path)] and [value: path]
 * and storing the path a second time as [location key: path] and [value: file_content]
 * like this it is possible to get all keys with new GetBuilder(hash('keys')).keys() --> returns a map with all [hash(path), path]
 * 
 */

public class P2PFS
        extends FuseFilesystemAdapterFull {

    /**
     * Root directory relative to the mount point
     */
    private final MemoryDirectory rootDirectory;

    /**
     * Logger instance
     */
    private final Logger          logger = LoggerFactory.getLogger(P2PFS.class);

    private FSFileMonitor         fsFileMonitor;

    private ExecutorService       executorService;

    private FSPeer                peer;

    /**
     * Creates a new instance of this file system.
     * Enables logging
     * 
     * @param pPeer Peer which mounts this filesystem
     * 
     * @throws IOException
     */
    public P2PFS(FSPeer pPeer)
            throws IOException {

        this.peer = pPeer;

        rootDirectory = new MemoryDirectory("/", this.peer);


        WriteFileEventListener writeFileEventListener = new WriteFileEventListener();
        SyncFileEventListener syncFileEventListener = new SyncFileEventListener();

        this.fsFileMonitor = new FSFileMonitor(this, this.peer);
        this.fsFileMonitor.addEventListener(writeFileEventListener);
        this.fsFileMonitor.addEventListener(syncFileEventListener);
        this.executorService = Executors.newCachedThreadPool();

        // Difference between execute and submit:
        // A task queued with execute() that generates some Throwable will cause the
        // UncaughtExceptionHandler for the Thread running the task to be invoked.
        // The default UncaughtExceptionHandler, which typically prints the Throwable
        // stack trace to System.err, will be invoked if no custom handler has been installed.
        // On the other hand, a Throwable generated by a task queued with submit()
        // will bind the Throwable to the Future that was produced from the call to
        // submit(). Calling get() on that Future will throw an ExecutionException with
        // the original Throwable as its cause (accessible by calling getCause()
        // on the ExecutionException).
        // See http://stackoverflow.com/questions/3929342/choose-between-executorservices-submit-and-executorservices-execute
        this.executorService.execute(this.fsFileMonitor);
        
        
        // Enables dynamic resizing of the FS.
        dynamicFsSize();
        super.log(false);
    }

    /**
     * Check file access permissions. All path segments get
     * checked for the provided access permissions
     * 
     * @param path Path to access
     * @param access Access mode flags
     */
    @Override
    public int access(final String path, final int access) {
        return 0;
    }

    /**
     * Removes the mount point directory on disk.
     * Gets called in {@link net.fusejna.FuseFilesystem#_destroy} after the Filesystem
     * is unmounted.
     */
    @Override
    public void afterUnmount(final File mountPoint) {
        if (mountPoint.exists()) {
            FSFileUtils.deleteFileOrFolder(mountPoint);
        }

        // shutdown file monitor
        try {
            logger.info("Attempt to shutdown thread executor service");
            this.fsFileMonitor.terminate();
            this.executorService.shutdown();
            this.executorService.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            logger.info("Tasks interrupted");
        } finally {
            if (!this.executorService.isTerminated()) {
                logger.info("Cancel non-finished tasks");
            }
            this.executorService.shutdownNow();
            logger.info("Shutdown of executor service finished");
        }
    }

    /**
     * Gets called in {@link net.fusejna.FuseFilesystem#mount} before FuseJna
     * explicitly mounts the file system
     */
    @Override
    public void beforeMount(final File mountPoint) {
    }

    /**
     * Used to map a block number offset in a file to
     * the physical block offset on the block device
     * backing the file system. This is intended for
     * filesystems that are stored on an actual block
     * device, with the 'blkdev' option passed.
     */
    @Override
    public int bmap(final String path, final FileInfoWrapper info) {
        return 0;
    }


    /**
     * Change permissions represented by mode on the
     * file / directory / simlink / device on path
     * 
     * @param path The path to the file of which permissions should be changed
     * @param mode Permissions which should get applied
     */
    @Override
    public int chmod(final String path, final ModeWrapper mode) {
        // TODO: set permissions
        // TODO: how to store file permissions in dht?
        return 0;
    }

    /**
     * Changes the ownership
     * of the file / directory / simlink / device specified at path
     * 
     * @param path Path to file to change ownership
     * @param uid Id of the user
     * @param gid Id of the group
     */
    @Override
    public int chown(final String path, final long uid, final long gid) {
        // TODO: set ownership
        // TODO: how to store file ownership in dht?
        return 0;
    }

    /**
     * Create a file with the path indicated, then open a
     * handle for reading and/or writing with the supplied
     * mode flags. Can also return a file handle like open()
     * as part of the call.
     * 
     * @param path Path to file to create
     * @param mode Create mask
     * @param info Open mode flags
     */
    @Override
    public int create(final String path, final ModeWrapper mode, final FileInfoWrapper info) {

        if (getPath(path) != null) {
            this.logger.warn("File on path " + path + " could not be created. A file with the same name already exists (Error code " + -ErrorCodes.EEXIST() + ").");
            return -ErrorCodes.EEXIST();
        }
        final AMemoryPath parent = getParentPath(path);
        if (parent instanceof MemoryDirectory) {
            String fileName = FSFileUtils.getLastComponent(path);
            // check if it is a file based on the filename
            if (FSFileUtils.isFile(fileName)) {
                ((MemoryDirectory) parent).mkfile(FSFileUtils.getLastComponent(path));

                try {
                    // check if file does not exist yet in the DHT
                    if (null == this.peer.getPath(Number160.createHash(path))) {
                        // create file if it does not exist yet
                        MemoryFile createdFile = (MemoryFile) ((MemoryDirectory) parent).find(FSFileUtils.getLastComponent(path));

                        if (!FSFileUtils.isContainedInVersionFolder(createdFile)) {
                            // NOTE: we only add the file to the monitor
                            // if it is not a version. This due to the procedure
                            // how the versions get put into the vDHT: Version files get written
                            // directly into the vDHT, then the FSFileSyncer creates them locally.
                            // If we would not check this here, an infinite number of version folders
                            // would be created in each other, containing the version of the version (of the version, ...)
                            this.fsFileMonitor.addMonitoredFile(path, createdFile.getContent());
                        }
                    }
                } catch (ClassNotFoundException | InterruptedException | IOException e) {
                    this.logger.error("Failed to add a monitored file '" + path + "'. Check if it exists already in the DHT failed. Message: " + e.getMessage());
                    e.printStackTrace();
                }
            } else {
                ((MemoryDirectory) parent).mkdir(FSFileUtils.getLastComponent(path));
            }

            return 0;
        }

        this.logger.warn("File on path " + path + " could not be created. No such file or directory (Error code " + -ErrorCodes.ENOENT() + ").");
        return -ErrorCodes.ENOENT();
    }

    /**
     * Sets different statistics about the entity located at path
     * like remaining capacity in the given StatWrapper.
     * 
     * @param path The path to the file of which the information gets obtained
     * @param stat The wrapper in which the particular information gets stored
     */
    @Override
    public int getattr(final String path, final StatWrapper stat) {
        final AMemoryPath p = getPath(path);
        if (p != null) {
            p.getattr(stat);
            return 0;
        }
        return -ErrorCodes.ENOENT();
    }

    /**
     * Get path to parent from provided path
     * 
     * @param path Path of which to get the path to its parent
     * @return The parent path
     */
    private AMemoryPath getParentPath(final String path) {
        return rootDirectory.find(path.substring(0, path.lastIndexOf("/")));
    }

    public AMemoryPath getPath(final String path) {
        return rootDirectory.find(path);
    }

    @Override
    public int mkdir(final String path, final ModeWrapper mode) {
        if (getPath(path) != null) {
            return -ErrorCodes.EEXIST();
        }
        final AMemoryPath parent = getParentPath(path);
        if (parent instanceof MemoryDirectory) {
            ((MemoryDirectory) parent).mkdir(FSFileUtils.getLastComponent(path));
            // add dir to fsFileMonitor
            this.fsFileMonitor.addMonitoredFile(path, ByteBuffer.allocate(0));
            return 0;
        }

        return -ErrorCodes.ENOENT();
    }

    /**
     * Checks whether the user has the correct access rights
     * to open the file on the provided path
     * 
     * @param path The path of the file to check
     * @param info The FileInfoWrapper which has to be updated
     */
    @Override
    public int open(final String path, final FileInfoWrapper info) {
        // ensures that file will be read even if the file is empty
        AMemoryPath filePath = getPath(path);
        if (filePath instanceof MemoryFile) {
            MemoryFile file = (MemoryFile) filePath;

            // read only file contents when empty on disk
            // to force loading of the content from the DHT
            if (file.getContent().capacity() == 0) {
                read(path, ByteBuffer.allocate((int) FSStatConfig.BIGGER.getBsize()), FSStatConfig.BIGGER.getBsize(), 0, null);
            }
        } else if (filePath instanceof MemorySymLink) {
            MemorySymLink symLink = (MemorySymLink) filePath;
            AMemoryPath target = getPath(symLink.getTarget());

            if (target instanceof MemoryFile) {
                MemoryFile targetFile = (MemoryFile) target;
                // read only file contents when empty on disk
                // to force loading of the content from the DHT
                if (targetFile.getContent().capacity() == 0) {
                    read(targetFile.getPath(), ByteBuffer.allocate((int) FSStatConfig.BIGGER.getBsize()), FSStatConfig.BIGGER.getBsize(), 0, null);
                }
            }
        }

        return 0;
    }

    @Override
    public int read(final String path, final ByteBuffer buffer, final long size, final long offset, final FileInfoWrapper info) {
        final AMemoryPath p = getPath(path);

        if (p == null) {
            this.logger.warn("Failed to read file on " + path + ". No such file or directory (Error code " + -ErrorCodes.ENOENT() + ").");
            return -ErrorCodes.ENOENT();
        }
        if ((p instanceof MemoryDirectory)) {
            this.logger.warn("Failed to read file on " + path + ". Path is a directory (Error code " + -ErrorCodes.EISDIR() + ").");
            return -ErrorCodes.EISDIR();
        }

        ByteBuffer monitoredFile = this.fsFileMonitor.getFileContent(path);
        if (null != monitoredFile) {
            final int bytesToRead = (int) Math.min(monitoredFile.capacity() - offset, size);
            final byte[] bytesRead = new byte[bytesToRead];

            monitoredFile.position((int) offset);
            monitoredFile.get(bytesRead, 0, bytesToRead);
            buffer.put(bytesRead);
            monitoredFile.position(0); // Rewind

            this.logger.info("Read contents from file on path '" + path + "' from file monitor");
            return bytesToRead;
        }

        this.logger.info("Read file on path " + path);
        return ((MemoryFile) p).read(buffer, size, offset);
    }

    @Override
    public int readdir(final String path, final DirectoryFiller filler) {
        final AMemoryPath p = getPath(path);
        if (p == null) {
            return -ErrorCodes.ENOENT();
        }
        if (!(p instanceof MemoryDirectory)) {
            return -ErrorCodes.ENOTDIR();
        }
        ((MemoryDirectory) p).read(filler);
        return 0;
    }

    /**
     * Writes the name of the target corresponding to the
     * symlink on <code>path</code> to the provided ByteBuffer. <br>
     * <b style="color:red">NOTE: Symbolic-link support requires only readlink and symlink.
     * FUSE itself will take care of tracking symbolic links in paths, so your
     * path-evaluation code doesn't need to worry about it.</b>
     * 
     * @param path The path to the symlink
     * @param buffer The buffer to which the target filename should be written to
     * @param size Size
     */
    @Override
    public int readlink(final String path, final ByteBuffer buffer, final long size) {
        final AMemoryPath p = getPath(path);

        if (p == null) {
            return -ErrorCodes.ENOENT();
        }
        if (!(p instanceof MemorySymLink)) {
            return -ErrorCodes.EINVAL();
        }

        MemorySymLink symlink = (MemorySymLink) p;
        buffer.put(symlink.getExistingPath().getName().getBytes());

        return 0;
    }

    /**
     * Renames the element on path to newName
     * 
     * @param path Old absolute path to file
     * @param newName New absolute path to file
     */
    @Override
    public int rename(final String path, final String newName) {
        AMemoryPath p = getPath(path);
        if (p == null) {
            return -ErrorCodes.ENOENT();
        }
        final AMemoryPath newParent = getParentPath(newName);
        if (newParent == null) {
            return -ErrorCodes.ENOENT();
        }
        if (!(newParent instanceof MemoryDirectory)) {
            return -ErrorCodes.ENOTDIR();
        }

        MemoryDirectory oldParentDir = p.getParent();
        oldParentDir.deleteChild(p);
        p.setParent(null);
        
        // remove old file if still contained in fileMonitor
        if (this.fsFileMonitor.getMonitoredFilePaths().contains(path)) {
            this.fsFileMonitor.removeMonitoredFile(path);
        }
        
        try {
            this.peer.removePath(Number160.createHash(path));
            this.peer.removeData(Number160.createHash(path));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        
        // Add old memoryPath to new directory (parent)
        MemoryDirectory newParentDir = (MemoryDirectory) newParent;
        p.setName(FSFileUtils.getLastComponent(newName));
        newParentDir.addMemoryPath(p);
        p.setParent(newParentDir);
        
        
        // put renamed file
        if (p instanceof MemoryDirectory) {
            this.fsFileMonitor.addMonitoredFile(p.getPath(), ByteBuffer.allocate(0));
        } else if (p instanceof MemorySymLink) {
            MemorySymLink symLink = (MemorySymLink) p;
            this.fsFileMonitor.addMonitoredFile(symLink.getPath(), symLink.getContents());
        } else if (p instanceof MemoryFile) {
            MemoryFile file = (MemoryFile) p;
            this.fsFileMonitor.addMonitoredFile(file.getPath(), file.getContent());
        }
        
        logger.info("Moved file from '" + path + "' to '" + newName + "'");
        
        return 0;
    }

    @Override
    public int rmdir(final String path) {
        final AMemoryPath p = getPath(path);
        if (p == null) {
            return -ErrorCodes.ENOENT();
        }
        if (!(p instanceof MemoryDirectory)) {
            return -ErrorCodes.ENOTDIR();
        }

        if (!((MemoryDirectory) p).getContents().isEmpty()) {
            return -ErrorCodes.ENOTEMPTY();
        }

        // remove file from fsMonitor to prevent store it after deletion
        if (this.fsFileMonitor.getMonitoredFilePaths().contains(p.getPath())) {
            this.fsFileMonitor.removeMonitoredFile(p.getPath());
        }
        
        // remove file from the DHT
        p.delete();        
        
        return 0;
    }

    /**
     * A symbolic link <code>target</code> is created to <code>path</code>.
     * (<code>target</code> is the name of the file created, <code>path</code> is the string used in creating the symbolic link) <br>
     * <b style="color:red">NOTE: Symbolic-link support requires only readlink and symlink.
     * FUSE itself will take care of tracking symbolic links in paths, so your
     * path-evaluation code doesn't need to worry about it.</b>
     * 
     * @param path The already existing file to which the link should be created
     * @param target The path of the symlink which points to <code>path</code>
     */
    @Override
    public int symlink(final String path, final String target) {
        final AMemoryPath existingPath = getPath(FSFileUtils.getLastComponent(path));
        if (null == existingPath || target.isEmpty()) {
            this.logger.warn("Could not create symlink '" + target + "' on file '" + path + "'. No such file or directory or target path is empty");
            return -ErrorCodes.ENOENT();
        }

        final AMemoryPath newParent = getParentPath(target);
        if (newParent == null) {
            this.logger.warn("Could not create symlink '" + target + "' on file '" + path + "'. Parent directory does not exist");
            return -ErrorCodes.ENOENT();
        }
        if (!(newParent instanceof MemoryDirectory)) {
            this.logger.warn("Could not create symlink '" + target + "' on file '" + path + "'. Parent is not a direcotry");
            return -ErrorCodes.ENOTDIR();
        }

        MemoryDirectory parentDir = (MemoryDirectory) newParent;
        parentDir.symlink(existingPath, FSFileUtils.getLastComponent(target));

        // add symlink to monitored files
        MemorySymLink symlink = (MemorySymLink) parentDir.find(FSFileUtils.getLastComponent(target));
        this.fsFileMonitor.addMonitoredFile(symlink.getPath(), symlink.getContents());

        return 0;
    }

    @Override
    public int truncate(final String path, final long offset) {
        final AMemoryPath p = getPath(path);
        if (p == null) {
            return -ErrorCodes.ENOENT();
        }
        if (!(p instanceof MemoryFile)) {
            return -ErrorCodes.EISDIR();
        }
        ((MemoryFile) p).truncate(offset);

        // overwrite monitored content and update countdown
        this.fsFileMonitor.addMonitoredFile(path, ((MemoryFile) p).getContent());

        return 0;
    }

    @Override
    public int unlink(final String path) {
        final AMemoryPath p = getPath(path);
        if (p == null) {
            return -ErrorCodes.ENOENT();
        }

        // Note: this must be before p.delete() as it will
        // remove the path from the DHT and the version folder
        // can not be constructed anymore
        if (!FSFileUtils.isDirectory(p) && !FSFileUtils.isContainedInVersionFolder(p) && !FSFileUtils.isVersionFolder(p)) {
            try {
                VersionArchiver archiver = new VersionArchiver();
                archiver.removeVersions(this.peer, Number160.createHash(path));

                // remove version folder on local disk
                String versionFolder = archiver.getVersionFolder(Number160.createHash(path));
                p.getParent().deleteChild(p.getParent().find(versionFolder));
            } catch (ClassNotFoundException | IOException | InterruptedException e) {
                e.printStackTrace();
            }
        }


        // remove file from fsMonitor to prevent store it after deletion
        if (this.fsFileMonitor.getMonitoredFilePaths().contains(p.getPath())) {
            this.fsFileMonitor.removeMonitoredFile(p.getPath());
        }
       
        p.delete();

        return 0;
    }

    @Override
    public int write(final String path, final ByteBuffer buf, final long bufSize, final long writeOffset,
            final FileInfoWrapper wrapper) {
        final AMemoryPath p = getPath(path);
        if (p == null) {
            this.logger.warn("Could not write to file on path " + path + ". No such file or directory (Error code " + -ErrorCodes.ENOENT() + ").");
            return -ErrorCodes.ENOENT();
        }
        if (!(p instanceof MemoryFile)) {
            this.logger.warn("Could not write to file on path " + path + ". Path is a directory (Error code " + -ErrorCodes.EISDIR() + ").");
            return -ErrorCodes.EISDIR();
        }

        int returnCode = ((MemoryFile) p).write(buf, bufSize, writeOffset);

        // overwrite monitored content and update countdown
        this.fsFileMonitor.addMonitoredFile(p.getPath(), ((MemoryFile) p).getContent());

        return returnCode;
    }

    @Override
    public int statfs(final String path, final StatvfsWrapper wrapper) {
        wrapper.bsize(FSStatConfig.RESIZE.getBsize()); // block size of 4000 bytes
        wrapper.blocks(FSStatConfig.RESIZE.getBlocks()); 
        wrapper.bfree(FSStatConfig.RESIZE.getBfree());
        wrapper.bavail(FSStatConfig.RESIZE.getBavail());
        wrapper.files(FSStatConfig.RESIZE.getFiles());
        wrapper.ffree(FSStatConfig.RESIZE.getFfree());
        return 0;
    }

    /**
     * Change the access and modification times of a file with nanosecond resolution
     *
     * @param path to file
     * @param wrapper of time
     * @return
     */
    @Override
    public int utimens(String path, StructTimeBuffer.TimeBufferWrapper wrapper) {
        AMemoryPath p = getPath(path);

        if (null == p) {
            this.logger.warn("Could not touch file on path '" + path + "'. No such file found.");
            return -ErrorCodes.ENOENT();
        }

        p.setLastAccessTimestamp(wrapper.ac_sec());
        p.setLastModificationTimestamp(wrapper.ac_sec());

        return 0;
    }

    /**
     * Creates the provided mount point if it does not exists already.
     * Then mounts the filesystem at the mountpoint
     * 
     * @param mountPoint The mountpoint where to mount the FS
     * @return The mounted P2PFS
     * 
     * @throws FuseException
     */
    public P2PFS mountAndCreateIfNotExists(String mountPoint)
            throws FuseException {
        File file = new File(mountPoint);
        if (!file.exists()) {
            this.logger.info("Created mount point directory at path " + mountPoint + ".");
            file.mkdir();
        }

        this.mount(file);

        return this;
    }

    /**
     * Returns a set of paths which are saved on the local FS
     * 
     * @return All paths to locally existent files
     */
    public Set<String> getAllPaths() {
        Set<String> allPaths = new HashSet<>();
        allPaths.add("/");
        allPaths.addAll(getDirSubPaths(rootDirectory));
        return allPaths;
    }

    public Set<String> getMonitoredFilePaths() {
        return this.fsFileMonitor.getMonitoredFilePaths();
    }

    /**
     * Returns all child paths of the given directory
     * 
     * @param dir The directory of which to get its children paths
     * @return A set containing all child paths
     */
    protected Set<String> getDirSubPaths(MemoryDirectory dir) {
        Set<String> allPaths = new HashSet<>();

        for (AMemoryPath path : dir.getContents()) {
            if (path instanceof MemoryDirectory) {
                Set<String> dirSubPaths = getDirSubPaths((MemoryDirectory) path);
                if (dirSubPaths.size() == 0) {
                    // this directory is empty
                    allPaths.add(path.getPath());
                } else {
                    // directory ios not empty
                    allPaths.addAll(getDirSubPaths((MemoryDirectory) path));
                }
            } else {
                allPaths.add(path.getPath());
            }
        }

        return allPaths;
    }
    
    private void dynamicFsSize() {
        FSResizePeerMapChangeListener peerMapChangeListener = new FSResizePeerMapChangeListener(peer.getPeerDHT());
        peer.getPeerDHT().peerBean().peerMap().addPeerMapChangeListener(peerMapChangeListener);
    }
}
