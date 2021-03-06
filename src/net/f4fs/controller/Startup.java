package net.f4fs.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.simple.parser.ParseException;

import net.f4fs.bootstrapserver.BootstrapServerAccess;
import net.f4fs.config.Config;
import net.f4fs.config.FSStatConfig;
import net.f4fs.filesystem.P2PFS;
import net.f4fs.fspeer.FSPeer;
import net.f4fs.util.DhtOperationsCommand;
import net.f4fs.util.IpAddressJsonParser;
import net.f4fs.util.ShutdownHookThread;


/**
 * Handles startup of the system
 * 
 * @author retwet
 */
public class Startup {

    private BootstrapServerAccess bootstrapServerAccess;
    private FSPeer                fsPeer;

    public Startup() {
        bootstrapServerAccess = new BootstrapServerAccess();
        fsPeer = new FSPeer();
    }

    /**
     * Starts the system normal, getting the peers connected to
     * the DHT (if some exist) from the bootstrap server
     */
    public void startWithBootstrapServer() {

        List<Map<String, String>> ipList = new ArrayList<>();
        try {
            ipList = IpAddressJsonParser.parse(bootstrapServerAccess.getIpAddressList());
        } catch (ParseException e) {
            e.printStackTrace();
        }

        start(ipList);
    }

    /**
     * Starts the system if the IP and port of the bootstrap peer is known in advance
     * 
     * @param connectionIP The IP address of the bootstrap peer
     * @param connectionPort The port of the bootstrap peer
     */
    public void startWithoutBootstrapServer(String connectionIP, String connectionPort) {
        List<Map<String, String>> ipList = new ArrayList<Map<String, String>>();

        Map<String, String> connection = new HashMap<String, String>();
        connection.put("address", connectionIP);
        connection.put("port", connectionPort);

        ipList.add(connection);

        start(ipList);
    }

    /**
     * Start the peer as bootstrap peer if the size of the IP list is zero
     * or connect the peer to the DHT if the size of the IP list > 0
     * and start the file system
     * 
     * @param ipList of IP/port pairs of peers connected to the DHT
     */
    private void start(List<Map<String, String>> ipList) {
        int nrOfIpAddresses = ipList.size();

        // Connect to other peers if any are available, otherwise start as bootstrap peer
        try {
            boolean success = false;

            if (nrOfIpAddresses == 0) {
                fsPeer.startAsBootstrapPeer();
                success = true;
            } else {
                int counter = 0;

                while (!success && (counter < nrOfIpAddresses)) {
                    success = fsPeer.startPeer(ipList.get(counter).get("address"),
                            Integer.parseInt(ipList.get(counter).get("port")));
                    counter++;
                }
            }

            if (!success) {
                fsPeer.shutdown();
                System.out.println("[Shutdown]: Bootstrap failed");
                System.exit(1);
            }

            // Add shutdown hook so that IP address gets removed from server when
            // user does not terminate program correctly on
            Runtime.getRuntime().addShutdownHook(new ShutdownHookThread(fsPeer.getIp(), Config.DEFAULT.getPort()));
            
            // send keep alive messages to bootstrap server to update entries in available peers
            bootstrapServerAccess.keepAlive.setIp(fsPeer.getIp()).setPort(Config.DEFAULT.getPort());
            bootstrapServerAccess.feelTheRhythmFeelTheRhyme_ItBobsledTime();

            // Set initial size of FS according to the number of connected peers.
            FSStatConfig.RESIZE.initialFsSize(nrOfIpAddresses + 1);
            // start file system with the connected peer
            new P2PFS(fsPeer).mountAndCreateIfNotExists(Config.DEFAULT.getMountPoint());

            // maybe start command line interface
            if (Config.DEFAULT.getStartCommandLineInterface()) {
                DhtOperationsCommand.startCommandLineInterface(fsPeer);
            }

            fsPeer.shutdown();
        } catch (Exception pEx) {
            pEx.printStackTrace();
        }
    }
}
