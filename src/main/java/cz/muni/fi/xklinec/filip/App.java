package cz.muni.fi.xklinec.filip;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.tinyos.message.Message;
import net.tinyos.message.MessageListener;
import net.tinyos.message.MoteIF;
import net.tinyos.message.PrintfMsg;
import net.tinyos.packet.BuildSource;
import net.tinyos.packet.PhoenixSource;
import org.slf4j.LoggerFactory;

/**
 * Hello world!
 *
 */
public class App 
{
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(App.class);
    
    //                                                        
    // Message codes.                                           0123456789abcde
    public static final int MSG_CODE_DRIVE  = Integer.parseInt("087fd75", 16); // 0
    public static final int MSG_CODE_JESTE  = Integer.parseInt("ff5ddbb", 16); // 1
    public static final int MSG_CODE_FAKT   = Integer.parseInt("73906cd", 16); // 2
    public static final int MSG_CODE_SECRET = Integer.parseInt("5f77baa", 16); // 3
    public static final int AM_PINGMSG = 11;
    public static final int NODES=3;
    
    public static final int[] NODE_TO_ID = new int[] {19, 40, 43};
    public static final String[] NODES_DESC = new String[] {
        "serial@/dev/mote_telos19:telosb",
        "serial@/dev/mote_telos40:telosb",
        "serial@/dev/mote_telos43:telosb"
    };
    
    private boolean running = true;
    private FileOutputStream printfs[];
    
    /**
     * Main program loop.
     * @param arg 
     * @throws java.lang.InterruptedException 
     */
    public void doMain(String[] arg) throws InterruptedException, FileNotFoundException{
        running=true;
        
        printfs = new FileOutputStream[NODES];
        MoteIF motes[] = new MoteIF[NODES];
        
        // Connect to motes, wuhaa.
        for(int k=0; k<NODES; k++){
            printfs[k] = new FileOutputStream(String.format("eacirc_%02d.txt", k));
            motes[k] = connectToNodeSilent(NODES_DESC[k], k);
        }
        
        log.info("Main app initialized");
       
        // Do this infinitelly.
        int lastState = -1;
        long lastStateChange = 0;
        while(running){
            int curState = getCurState();
            long curTime = System.currentTimeMillis();
            
            // Send state change if new state is here or 30 minutes passed from the last one.
            if (curState!=lastState || (curTime - lastStateChange) > (1000*60*30)){
                log.info(String.format("Emit new state, cur=%d last=%d curTime=%d last=%d", curState, lastState, curTime, lastStateChange));
                
                // Update state so wont cycle.
                lastState = curState;
                lastStateChange = curTime;
                
                // Send messages corresponding to given state.
                long msgCode=MSG_CODE_DRIVE;
                switch(curState){
                    case 0: msgCode = MSG_CODE_DRIVE; break;
                    case 1: msgCode = MSG_CODE_JESTE; break;
                    case 2: msgCode = MSG_CODE_FAKT; break;
                    case 3: msgCode = MSG_CODE_SECRET; break;
                }
                
                // Send this message to all node, multiple times (2).
                for(int retry=0; retry<2; retry++){
                    for(int node=0; node<NODES; node++){
                        
                        BlinkMsg blinkMsg = new BlinkMsg();
                        blinkMsg.set_msgId(msgCode);
                        
                        // If empty -> reconnect.
                        if (motes[node]==null){
                            motes[node] = connectToNodeSilent(NODES_DESC[node], node);
                        }
                        
                        if (motes[node]==null){
                            continue;
                        }
                        
                        try {
                            log.info("Going to send message, messageId " + msgCode + " to node " + node);
                            motes[node].send(0xffff, blinkMsg);
                            log.info("Message sent");
                        } catch (IOException ex) {
                            log.error("Crap! Something broken with node " + node, ex);
                            
                            try {
                                motes[node].getSource().shutdown();
                                motes[node] = null;
                                Thread.sleep(5000); // 5 sec sleep.
                            } catch(Exception ex2){
                                log.error("Crap again! Cannot shut down node", ex2);
                            }
                             
                            motes[node] = connectToNodeSilent(NODES_DESC[node], node);
                        }
                    }
                }
            }
            
            Thread.sleep(15000); // 15 sec sleep.
        }
    }
    
    /**
     * Returns current state depending on current time.
     * @return 
     */
    public int getCurState(){
        Calendar c = Calendar.getInstance();
        int hour = c.get(Calendar.HOUR_OF_DAY);
        int min  = c.get(Calendar.MINUTE);
        
        if ((hour < 4 && hour >= 0) || (hour>=10)){
            return 0;
        } else if (hour>=9){
            return 1;
        } else if (hour>=8){
            return 2;
        } else if (hour>=4){
            return 3;
        }
        
        return 0;
    }
    
    /**
     * Received message from TinyOS.
     * @param nodeId
     * @param msg 
     */
    public synchronized void msgReceived(int nodeId, Message msg) {
        if (!(msg instanceof PrintfMsg)) {
            log.error("Unknown message received[" + nodeId + "]: " + msg);
            return;
        }
        
        int node = 0;
        FileOutputStream fos = null;
        
        try {
            node = NODE_TO_ID[nodeId];
            fos = printfs[nodeId];
            
            // date formater for human readable date format
            SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
            Calendar calendar = Calendar.getInstance();
            StringBuilder tstampString = new StringBuilder();
            long timestamp = System.currentTimeMillis();
            
            tstampString.append("# TS[")
                        .append(timestamp)
                        .append("]; F[")
                        .append(formatter.format(calendar.getTime()))
                        .append("] ");
            final String tstamp = tstampString.toString();
            
            final PrintfMsg pm = (PrintfMsg) msg;
            for (int i = 0; i < PrintfMsg.totalSize_buffer(); i++) {
                char nextChar = (char) (pm.getElement_buffer(i));
                if (nextChar != 0) {
                    fos.write(nextChar);
                    if (nextChar=='\n') fos.write(tstamp.getBytes("UTF-8"));
                }
            }
            
            fos.flush();
            
        } catch (IOException ex) {
            log.error("Exception in msgReceived, node=" + node + "; fos=" + fos, ex);
        }
    }
    
    public MoteIF connectToNodeSilent(String source, int nodeId){
        try {
            return connectToNode(source, nodeId);
        } catch (Exception e){
            log.error("Exception in connect", e);
        }
        
        return null;
    }
    
    /**
     * Connect to Tinyos node.
     * 
     * @param source
     * @return 
     */
    public MoteIF connectToNode(String source, final int nodeId){
        // build custom error mesenger - store error messages from tinyos to logs directly
        TOSLogMessenger messenger = new TOSLogMessenger();
        // instantiate phoenix source
        PhoenixSource phoenix = BuildSource.makePhoenix(source, messenger);
        MoteIF moteInterface = null;
        
        // phoenix is not null, can create packet source and mote interface
        if (phoenix != null) {
            // loading phoenix
            moteInterface = new MoteIF(phoenix);
        } else {
            throw new IllegalArgumentException("Connection to some node was not successfull, path: " + source);
        }
        
        // Register printf listener here (abstraction problem but is quite simple & convnient).
        log.info("Registering node " + source + " printf listener; moteIf=" + moteInterface);

        // Register printf listener.
        moteInterface.registerListener(new PrintfMsg(), new MessageListener() {
            @Override
            public void messageReceived(int i, Message msg) {
                msgReceived(nodeId, msg);
            }
        });
        
        return moteInterface;
    }
    
    public static void printByte(PrintStream p, int b) {
        if (b>=0x20 && b <=0x7E){
            char c = (char) (b & 0xff);
            p.print(c);
        } else {
            String bs = Integer.toHexString(b & 0xff).toUpperCase();
            if (b >=0 && b < 16)
                p.print("0");
            p.print(bs + " ");
        }
    }

    public static void printPacket(PrintStream p, byte[] packet, int from, int count) {
	for (int i = from; i < count; i++)
	    printByte(p, packet[i]);
    }

    public static void printPacket(PrintStream p, byte[] packet) {
	printPacket(p, packet, 0, packet.length);
    }
    
    public static void main( String[] args ){
        System.out.println("Starting...");
        
        try {
            log.info("Starting main app");
            (new App()).doMain(args);
        } catch (InterruptedException ex) {
            Logger.getLogger(App.class.getName()).log(Level.SEVERE, null, ex);
        } catch (FileNotFoundException ex) {
            Logger.getLogger(App.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
}
