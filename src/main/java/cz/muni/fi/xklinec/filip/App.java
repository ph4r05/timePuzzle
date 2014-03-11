package cz.muni.fi.xklinec.filip;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.SocketException;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Random;
import java.util.TimeZone;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.tinyos.message.Message;
import net.tinyos.message.MessageListener;
import net.tinyos.message.MoteIF;
import net.tinyos.message.PrintfMsg;
import net.tinyos.packet.BuildSource;
import net.tinyos.packet.PhoenixSource;
import org.apache.commons.net.ntp.NTPUDPClient;
import org.apache.commons.net.ntp.NtpV3Packet;
import org.apache.commons.net.ntp.TimeInfo;
import org.apache.commons.net.ntp.TimeStamp;
import org.slf4j.LoggerFactory;

/**
 * Hello world!
 *
 */
public class App 
{
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(App.class);
    private static final NumberFormat numberFormat = new java.text.DecimalFormat("0.00");
    private static final String NTP_SERVER = "time.fi.muni.cz";
    
    //                                                        
    // Message codes.                                           0123456789abcde
    public static final int MSG_CODE_DRIVE  = Integer.parseInt("087fd75", 16); // 0
    public static final int MSG_CODE_JESTE  = Integer.parseInt("ff5ddbb", 16); // 1
    public static final int MSG_CODE_FAKT   = Integer.parseInt("73906cd", 16); // 2
    
    // Make a little headache to the enemy in case of reversing // 3
    public static final int MSG_CODE_SECRET[] = new int[] { 
        Integer.parseInt("5f17b5a", 16),
        Integer.parseInt("1077b24", 16),
        Integer.parseInt("5f12baa", 16),
        Integer.parseInt("5667baa", 16),
        Integer.parseInt("5ffcbaa", 16),
        Integer.parseInt("5f77baa", 16), // 5 is the correct one! 5f77baa
        Integer.parseInt("af012aa", 16),
        Integer.parseInt("5f0cb1a", 16),
        Integer.parseInt("9fdeb4a", 16),
        Integer.parseInt("5ff3baa", 16),
        Integer.parseInt("4fe6b6a", 16),
        Integer.parseInt("5f12baa", 16),
        Integer.parseInt("5c77ba6", 16),
        Integer.parseInt("1fc7b3a", 16),
        Integer.parseInt("5b771aa", 16),
        Integer.parseInt("5f34b0a", 16),
        Integer.parseInt("4c77b0a", 16),
    }; 
    
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
    private TimeZone timeZone;
    
    /**
     * Main program loop.
     * @param arg 
     * @throws java.lang.InterruptedException 
     */
    public void doMain(String[] arg) throws InterruptedException, FileNotFoundException{
        running=true;
        
        timeZone = TimeZone.getTimeZone("Europe/Prague");
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
            // NTP request 
            long ntp = getNTPMilli();
            if (ntp<=0){
                Thread.sleep(5000);
                continue;
            }
            
            int curState = getCurState(ntp);
            long curTime = ntp; //System.currentTimeMillis();
            
            // Send state change if new state is here or 30 minutes passed from the last one.
            if (curState!=lastState || (curTime - lastStateChange) > (1000*60*30)){
                log.info(String.format("Emit new state, cur=%d last=%d curTime=%d system=%d last=%d", curState, lastState, curTime, System.currentTimeMillis(), lastStateChange));
                
                // Try to update system time?
                
                
                // Update state so wont cycle.
                lastState = curState;
                lastStateChange = curTime;
                
                // Send messages corresponding to given state.
                long msgCode=MSG_CODE_DRIVE;
                switch(curState){
                    case 0: msgCode = MSG_CODE_DRIVE; break;
                    case 1: msgCode = MSG_CODE_JESTE; break;
                    case 2: msgCode = MSG_CODE_FAKT; break;
                    case 3: msgCode = MSG_CODE_SECRET[5]; break;
                }
                
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(ntp);
                c.setTimeZone(timeZone);
                
                // Send this message to all node, multiple times (2).
                for(int retry=0; retry<2; retry++){
                    for(int node=0; node<NODES; node++){
                        // Msg code seeded random
                        Random rndMsg = new Random(curState + 19l*node + 29l*c.get(Calendar.HOUR_OF_DAY));
                        Random rnd    = new Random();
                        
                        BlinkMsg blinkMsg = new BlinkMsg();
                        blinkMsg.set_msgId(msgCode);            // original message code.
                        blinkMsg.set_msgTid(rndMsg.nextLong()); // random deceiving code based on curstate && nodeid && hour.
                        //blinkMsg.set_msgVal(msgCode*5);         // messageCode based deceiving number.
                        
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
            
            Thread.sleep(60000); // 60 sec sleep.
        }
    }
    
    /**
     * Returns current state depending on current time.
     * @return 
     */
    public int getCurState(long time){
        Calendar c = Calendar.getInstance();
        c.setTimeZone(timeZone);
        c.setTimeInMillis(time);
        
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
    
    /**
     * Process <code>TimeInfo</code> object and print its details.
     * @param info <code>TimeInfo</code> object.
     * @return 
     */
    public static long processResponse(TimeInfo info){
        NtpV3Packet message = info.getMessage();
        TimeStamp xmitNtpTime = message.getTransmitTimeStamp();
        return xmitNtpTime.getTime();
        
        /*int stratum = message.getStratum();
        String refType;
        if (stratum <= 0) {
            refType = "(Unspecified or Unavailable)";
        } else if (stratum == 1) {
            refType = "(Primary Reference; e.g., GPS)"; // GPS, radio clock, etc.
        } else {
            refType = "(Secondary Reference; e.g. via NTP or SNTP)";
        }
        
        // stratum should be 0..15...
        System.out.println(" Stratum: " + stratum + " " + refType);
        int version = message.getVersion();
        int li = message.getLeapIndicator();
        System.out.println(" leap=" + li + ", version="
                + version + ", precision=" + message.getPrecision());

        System.out.println(" mode: " + message.getModeName() + " (" + message.getMode() + ")");
        int poll = message.getPoll();
        // poll value typically btwn MINPOLL (4) and MAXPOLL (14)
        System.out.println(" poll: " + (poll <= 0 ? 1 : (int) Math.pow(2, poll))
                + " seconds" + " (2 ** " + poll + ")");
        double disp = message.getRootDispersionInMillisDouble();
        System.out.println(" rootdelay=" + numberFormat.format(message.getRootDelayInMillisDouble())
                + ", rootdispersion(ms): " + numberFormat.format(disp));

        int refId = message.getReferenceId();
        String refAddr = NtpUtils.getHostAddress(refId);
        String refName = null;
        if (refId != 0) {
            if (refAddr.equals("127.127.1.0")) {
                refName = "LOCAL"; // This is the ref address for the Local Clock
            } else if (stratum >= 2) {
                // If reference id has 127.127 prefix then it uses its own reference clock
                // defined in the form 127.127.clock-type.unit-num (e.g. 127.127.8.0 mode 5
                // for GENERIC DCF77 AM; see refclock.htm from the NTP software distribution.
                if (!refAddr.startsWith("127.127")) {
                    try {
                        InetAddress addr = InetAddress.getByName(refAddr);
                        String name = addr.getHostName();
                        if (name != null && !name.equals(refAddr)) {
                            refName = name;
                        }
                    } catch (UnknownHostException e) {
                        // some stratum-2 servers sync to ref clock device but fudge stratum level higher... (e.g. 2)
                        // ref not valid host maybe it's a reference clock name?
                        // otherwise just show the ref IP address.
                        refName = NtpUtils.getReferenceClock(message);
                    }
                }
            } else if (version >= 3 && (stratum == 0 || stratum == 1)) {
                refName = NtpUtils.getReferenceClock(message);
                // refname usually have at least 3 characters (e.g. GPS, WWV, LCL, etc.)
            }
            // otherwise give up on naming the beast...
        }
        if (refName != null && refName.length() > 1) {
            refAddr += " (" + refName + ")";
        }
        System.out.println(" Reference Identifier:\t" + refAddr);

        TimeStamp refNtpTime = message.getReferenceTimeStamp();
        System.out.println(" Reference Timestamp:\t" + refNtpTime + "  " + refNtpTime.toDateString());

        // Originate Time is time request sent by client (t1)
        TimeStamp origNtpTime = message.getOriginateTimeStamp();
        System.out.println(" Originate Timestamp:\t" + origNtpTime + "  " + origNtpTime.toDateString());

        long destTime = info.getReturnTime();
        // Receive Time is time request received by server (t2)
        TimeStamp rcvNtpTime = message.getReceiveTimeStamp();
        System.out.println(" Receive Timestamp:\t" + rcvNtpTime + "  " + rcvNtpTime.toDateString());

        // Transmit time is time reply sent by server (t3)
        TimeStamp xmitNtpTime = message.getTransmitTimeStamp(); 
        System.out.println(" Transmit Timestamp:\t" + xmitNtpTime + "  " + xmitNtpTime.toDateString());

        // Destination time is time reply received by client (t4)
        TimeStamp destNtpTime = TimeStamp.getNtpTime(destTime);
        System.out.println(" Destination Timestamp:\t" + destNtpTime + "  " + destNtpTime.toDateString());

        info.computeDetails(); // compute offset/delay if not already done
        Long offsetValue = info.getOffset();
        Long delayValue = info.getDelay();
        String delay = (delayValue == null) ? "N/A" : delayValue.toString();
        String offset = (offsetValue == null) ? "N/A" : offsetValue.toString();

        System.out.println(" Roundtrip delay(ms)=" + delay
                + ", clock offset(ms)=" + offset); // offset in ms*/
    }

    public static long getNTPMilli(){
        NTPUDPClient client = new NTPUDPClient();
        // We want to timeout if a response takes longer than 10 seconds
        client.setDefaultTimeout(10000);
        long ret = -1;
        
        try {
            client.open();
            System.out.println();
            try {
                InetAddress hostAddr = InetAddress.getByName(NTP_SERVER);
                log.info("Srvr: " + hostAddr.getHostName() + "/" + hostAddr.getHostAddress());
                
                TimeInfo info = client.getTime(hostAddr);
                ret = processResponse(info);
                
            } catch (IOException ioe) {
                log.error("Exception in NTPMilli()", ioe);
            }
        } catch (SocketException e) {
            log.error("SocketException", e);
        }

        client.close();
        return ret;
    }
}
