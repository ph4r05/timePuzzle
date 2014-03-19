

#include <Timer.h>
#include "printf.h"
#include "defs.h"

module BlinkNodeIDC {
  uses interface Boot;
  uses interface Leds;
  uses interface Timer<TMilli> as Timer0;
  uses interface Timer<TMilli> as TimerStart;
  uses interface Receive as BlinkMsgReceive;
}
implementation {
  enum {
	TIMER_START=2000,
	TIMER_STEP=2000,
	TIMER_DELIM=250,
	MAX_STEPS=2,				// Max node ID = 2^(3*MAX_STEPS)
	LEDS_MASK=0x7, 
	LEDS_CNT=3,
	MORSE_BUFF_SIZE = 1024,
  };
  
  // Morse code transformation table.
  static char * MORSE[] = {
  	".-","-...","-.-.","-..", ".", "..-.", "--.",
    "....", "..", ".---", "-.-", ".-..", "--",
    "-.", "---", ".--.", "--.-", ".-.", "...", "-",
    "..-", "...-", ".--", "-..-", "-.--", "--.."};

  // Messages count in array.
  static int MSG_COUNT = 4;
  
  // Messages to display.
  static char * MSG[] = {"skus prijit drive", 
  						 "prijd jeste drive", 
  						 "prijd fakt drive",
  						 "iapologize for nothing"};
  
  // Buffer for conversion ASCII to morse representation.
  char buff[MORSE_BUFF_SIZE];
  
  // Current position during blinking the message.
  uint16_t curPos=0;
  uint16_t maxPos=0;
  
  // Blinking state.
  uint8_t blinkState=0;
  uint8_t curMsg;
  
  void setLeds(uint16_t val) {
    if (val & 0x01)
      call Leds.led0On();
    else 
      call Leds.led0Off();
    if (val & 0x02)
      call Leds.led1On();
    else
      call Leds.led1Off();
    if (val & 0x04)
      call Leds.led2On();
    else
      call Leds.led2Off();
  }
	
  event void Boot.booted() {
	//call InitTimer.startOneShot(TIMER_START);
  }
  
  // Converts given ascii message to morse in buffer  
  // Example: "e ee e" -> ".,. .,." 
  void convertMessage(const char * src){
  	int len = strlen(src); 
	int max = len > MORSE_BUFF_SIZE ? MORSE_BUFF_SIZE : len;
	int i=0;
	
	// Erase whole string, set zeros for string manipulation functions.
	memset(buff, 0, MORSE_BUFF_SIZE);
	
	for(i=0; i<max; i++){
		uint8_t idx  = src[i] - 'a';
		uint8_t nidx = ((i+1) >= max) ? 99 : (src[i+1] - 'a');
		printf("c:%d;nc:%d\n", idx, nidx);
		
		if (src[i]==' '){
			// Represent space between words as comma character.
			strcat(buff, ","); 
			
		} else if (idx>=0 && idx<=26)  {			
			// Append morse code repr. for given character. Appending.
			strcat(buff, MORSE[idx]);
			
			// Check if append space (inter word spaces). If the next character is 
			// word, add space.
			if (nidx>=0 && nidx<=26){
				strcat(buff, " ");
			}
		}
	}
  }
  
  // Starts blinking of the appropriate message.
  void startBlinking() {
  		uint16_t wStart = 0;
  		uint16_t wEnd = 0;
  		int16_t wCnt = -1;
  		uint16_t wi = 0;
  		uint16_t wlen = 0;
  		
  		uint16_t wState=1; 
  		char * p = NULL;
  		
  		uint8_t wordNum = 0;
  		char tmpBuff[256];
  		
		curPos=0;
		blinkState=1;
		
		// Illegality check.
		if (curMsg >= MSG_COUNT){
			printf("Dafuq?\n");
			return;
		}
		
		// Stop current blinking
		call Timer0.stop();
		setLeds(0);
		
		printf("New msg...\n");
		printfflush();
		
		// Which word to send.
		// Each node blinks a particular word.
		if (TOS_NODE_ID==19){
			wordNum=0;
		} else if (TOS_NODE_ID==40){
			wordNum=1;
		} else if (TOS_NODE_ID==43){
			wordNum=2;
		} else {
			printf("tosNodeId illegal %d\n", TOS_NODE_ID);
			printfflush();
			return;
		}
		
		printf("msgId=%d wordNum=%d\n", curMsg, wordNum);
		memset(tmpBuff, 0x0, 256);
		
		// Which message to send, extract particular word.
		p = MSG[curMsg];
		wlen = strlen(MSG[curMsg]);
		for(wi=0; wi<=wlen; wi++){
			// What is current character?
			// We also count on zero terminating character finishing the last word.
			char c = MSG[curMsg][wi];
			bool isWord = c >= 'a' && c <= 'z';
			
			// Word start after there was a space.
			if (isWord && wState==1){
				wState=2; // state for new word.
				wStart=wi;
				wCnt+=1;
				p=MSG[curMsg] + wi;
			}
			
			// Inside word we are not interested in anything.
			if (isWord) {
				continue;
			}
			
			// End of the word of our interest.
			if (!isWord && wState==2){
				if (wCnt==wordNum){
					wEnd=wi;
					break;
				} else {
					// Not in our interest, just switch state.
					wState=1;
				}
			}
		}
		
		if ((wEnd-wStart) > 0){
			// Copy string to temporary buffer.
			strncpy(tmpBuff, p, wEnd-wStart);
			//printf("word[%c] start=%d end=%d\n", tmpBuff[0], wStart, wEnd);
			printf("start=%d end=%d\n", wStart, wEnd);
		
			// Convert to morse code.
			convertMessage(tmpBuff);
			maxPos = strlen(buff);
			
			//printf("morse[%s] max=%d\n", buff, maxPos);
			printf("morse[sorry] max=%d\n", maxPos);
		
			// Initial timer delay before starting blinking.
			call Timer0.startOneShot(1000);
		} else {
			printf("Something went wring during parsing start=%d end=%d wcnt=%d s%d buff=%p p=%p", wStart, wEnd, wCnt, wState, tmpBuff, p);
		}
  }
  
  void task startBlinkingTask(){
  	printf("Start blinking task...\n");
  	printfflush();
  	
  	startBlinking();
  }
  
  event void Timer0.fired() {	
	// If blinking is done.
	if (maxPos <= curPos){
		curPos=0;
		blinkState=1;
		
		printf("End of blinking\n");
		printfflush();
		
		// Start blinking again after some time of inactivity.
		call Timer0.startOneShot(10000);
		return;
	}
	
	// blinkstate==1 -> starting to blink given character
	// blinkstate==2 -> end of blicking period and moving to the next one
	
	// Normal blinking procedure. Blink curPos and move forward.
	if (blinkState==1){
		// Set blink timeout corresponding to the given character.
		bool blink=TRUE;
		uint16_t timeout=10;
		if (buff[curPos]=='.'){
			timeout=500;  // dot, short signal
		} else if (buff[curPos]=='-'){
			timeout=1000; // dash, long signal
		} else if (buff[curPos]==' '){
			timeout=2000; // space between characters
			blink=FALSE;
		} else if (buff[curPos]==','){
			timeout=3000; // space between words
			blink=FALSE;
		} 
		
		//printf("Blink state 1, timeout=%d, char '%c'\n", timeout, buff[curPos]);
		
		if (blink){
			setLeds(7);
		}
		
		blinkState=2;
		call Timer0.startOneShot(timeout);
		return;
	}
	
	// End of the blink signal.
	if (blinkState==2){
		setLeds(0);
		
		curPos+=1;
		blinkState=1;
		
		call Timer0.startOneShot(500);
		
		//printf("Blink state 2\n");
	}
  }
  
  event void TimerStart.fired() {
  	 post startBlinkingTask();
  }
  
  event message_t *BlinkMsgReceive.receive(message_t *msg, void *payload,  uint8_t len) {
  		
  		uint8_t tMsgId;
  		BlinkMsg * ptr = (BlinkMsg*) payload;
  		
  		
  		// Hard-coded message codes. 
  		// Poses some obfuscation to the victim. Idea: should be hard
  		// to hack the node by sending all messages to it - consider it
  		// as "keys" for the message to blink.
  		if (ptr->msgId == 0x087fd75){
  			tMsgId=0;
  		} else if (ptr->msgId == 0xff5ddbb){
  			tMsgId=1;
  		} else if (ptr->msgId == 0x73906cd){
  			tMsgId=2;
  		} else if (ptr->msgId == 0x5f77baa){
  			tMsgId=3;
  		} else {
  			printf("Unknown message received; id: %ld\n", ptr->msgId);
  			return msg;
  		}	
  		
  		curMsg = tMsgId;
  		printf("Message changed. New msgId=%d\n", curMsg);
  		
  		// Delay since server may send multiple of these just to be sure I got it.
  		call TimerStart.startOneShot(5000);
  		return msg;
  }	
} 
