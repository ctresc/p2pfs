package net.f4fs.filesystem.event.listeners;

import java.io.IOException;

import net.f4fs.filesystem.event.events.AEvent;
import net.f4fs.filesystem.event.events.CompleteWriteEvent;
import net.f4fs.filesystem.util.FSFileUtils;
import net.f4fs.persistence.archive.VersionArchiver;
import net.tomp2p.peers.Number160;
import net.tomp2p.storage.Data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Writes the file to the DHT when complete.
 * 
 * @author Raphael
 *
 */
public class WriteFileEventListener
        implements IEventListener {

    private final Logger logger = LoggerFactory.getLogger(WriteFileEventListener.class);

    protected VersionArchiver   archiver;

    public WriteFileEventListener() {
        this.archiver = new VersionArchiver();
    }

    @Override
    public void handleEvent(AEvent pEvent) {
        if (!(pEvent instanceof CompleteWriteEvent)) {
            return;
        }

        CompleteWriteEvent writeEvent = (CompleteWriteEvent) pEvent;

        if (0 != writeEvent.getContent().capacity()) {
            try {
                Data oldData = writeEvent.getFsPeer().getData(Number160.createHash(writeEvent.getPath()));

                // data is a already a byte array, no need to get the object of it
                if (null != oldData && oldData.toBytes().length > 0 &&
                        !FSFileUtils.isDirectory(writeEvent.getFilesystem().getPath(writeEvent.getPath()))) {
                    this.archiver.archive(writeEvent.getFsPeer(), Number160.createHash(writeEvent.getPath()), oldData);
                }
            } catch (ClassNotFoundException | IOException | InterruptedException e) {
                this.logger.error("Could not archive file on path '" + writeEvent.getPath() + "'. An error occurred during fetching old data. Message: " + e.getMessage());
            }
        }

        try {
            writeEvent.getFsPeer().putData(Number160.createHash(writeEvent.getPath()), new Data(writeEvent.getContent().array()));
            writeEvent.getFsPeer().putPath(Number160.createHash(writeEvent.getPath()), new Data(writeEvent.getPath()));
        } catch (ClassNotFoundException | InterruptedException | IOException e) {
            this.logger.error("Could not save whole file on path '" + writeEvent.getPath() + "'. An error occurred during saving to DHT. Message: " + e.getMessage());
        }

        logger.info("Wrote whole file on path '" + writeEvent.getPath() + "' containing '" + writeEvent.getContent().capacity() + "' bytes to DHT");
    }

    @Override
    public String getEventName() {
        return CompleteWriteEvent.eventName;
    }

}
