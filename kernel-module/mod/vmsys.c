#include <linux/module.h>

#include <linux/syscalls.h>
#include <linux/kernel.h>
#include <linux/module.h>
#include <linux/fs.h>
#include <asm/uaccess.h>
#include <asm/unistd.h>
#include <asm/fcntl.h>
#include <asm/errno.h>
#include <asm/compat.h>
#include <linux/types.h>
#include <linux/dirent.h>
#include <linux/slab.h>
#include <linux/unistd.h>
#include <linux/time.h>
#include <linux/compat.h>

// Manual definitions - we dont have exported ia32 symbols. 
// TODO: can enumerate index if compat_sys_clock_settime is known... 
#if (__NR_clock_settime==227)
#define __NR_ia32_clock_settime 264
#define __NR_ia32_adjtimex 124
#else
#warning "Cannot hook ia32 table"
#endif


// Definitions taken from: /boot/System.map
#define ADDR_COMPAT_SYS_CLOCK_SETTIME 0xffffffff810ab830ul //ffffffff810ab830 T compat_sys_clock_settime
#define ADDR_SYS_CALL_TABLE 0xffffffff818003a0ul //ffffffff818003a0 R sys_call_table
#define ADDR_I32_SYS_CALL_TABLE 0xffffffff81803ca8ul //ffffffff81803ca8 r ia32_sys_call_table


//
// Sources:
// syscall table hooking, memory protection: [http://memset.wordpress.com/2010/12/03/syscall-hijacking-kernel-2-6-systems/]
// getdents64 hooking: [http://www.s0ftpj.org/bfi/dev/BFi13-dev-22]
// put_user, get_user: [http://www.ibm.com/developerworks/linux/library/l-kernel-memory-access/index.html]
// advanced hooking:   [http://www.gilgalab.com.br/hacking/programming/linux/2013/01/11/Hooking-Linux-3-syscalls/]
// another hooking:    [http://syprog.blogspot.cz/2011/10/hijack-linux-system-calls-part-iii.html]
//

int init_module(void);
void cleanup_module(void);
static int device_open(struct inode *, struct file *);
static int device_release(struct inode *, struct file *);
static ssize_t device_read(struct file *, char *, size_t, loff_t *);
static ssize_t device_write(struct file *, const char *, size_t, loff_t *);

void prepare_hack(void);
void enable_hack(void);
void disable_hack(void);
void enable_hack_ia32(void);
void disable_hack_ia32(void);

// original function pointer will be stored here
asmlinkage int (*orig_getdents)(unsigned int fd, struct linux_dirent64 *dirp, unsigned int count);
asmlinkage int (*orig_settimeofday)(const struct timeval *tv, const struct timezone *tz);
asmlinkage int (*orig_clock_settime)(clockid_t clk_id, const struct timespec *tp);
asmlinkage int (*orig_adjtimex)(struct timex *buf);

// ia32 hooks:
// .quad compat_sys_clock_settime
// .quad compat_sys_adjtimex
asmlinkage long (*orig_compat_sys_clock_settime)(clockid_t which_clock, struct compat_timespec __user *tp);
asmlinkage long (*orig_compat_sys_adjtimex)(struct compat_timex __user *utp);

#define SUCCESS 0
#define DEVICE_NAME "chardev"	/* Dev name as it appears in /proc/devices   */
#define BUF_LEN 80		/* Max length of the message from the device */

static int Major;		/* Major number assigned to our device driver */
static int Device_Open = 0;	/* Is device open?  
				 * Used to prevent multiple access to device */
static char msg[BUF_LEN];	/* The msg the device will give when asked */
static char *msg_Ptr;

// buffer for data written to special file
static char wmsg[BUF_LEN];	/* The msg the device will give when asked */

static struct file_operations fops = {
	.read = device_read,
	.write = device_write,
	.open = device_open,
	.release = device_release
};

#define ENABLE_HACK "zapni_hack"
#define DISABLE_HACK "vypni_hack"
unsigned int success;
unsigned int hacked;

unsigned int success_ia32;
unsigned int hacked_ia32;

unsigned long *sys_call_table;
unsigned long *ia32_sys_call_table;

// stime, settimeofday
asmlinkage int hacked_settimeofday(const struct timeval *tv, const struct timezone *tz){
	printk(KERN_INFO "Call to settimeofday, tv=%p, tz=%p\n", tv, tz);
	return -1;
}

// adjtimex - ntpdate uses this one
asmlinkage int hacked_adjtimex(struct timex *buf){
	printk(KERN_INFO "Call to adjtimex, buf=%p\n", buf);
	return -1;
}

// date util
asmlinkage int hacked_clock_settime(clockid_t clk_id, const struct timespec *tp){
        if (clk_id == CLOCK_REALTIME){
            printk(KERN_INFO "Call to clock_settime(CLOCK_REALTIME, tp=%p)\n", tp);
            return -1;
        } else {
            printk(KERN_INFO "Call to clock_settime(%d, tp=%p)\n", clk_id, tp);
            return orig_clock_settime(clk_id, tp);
        }
}

// adjtimex - ntpdate uses this one
asmlinkage long hacked_compat_sys_adjtimex(struct compat_timex __user *utp){
    printk(KERN_INFO "Call to compat_adjtimex, utp=%p\n", utp);
    return -1;
}

asmlinkage long hacked_compat_sys_clock_settime(clockid_t which_clock, struct compat_timespec __user *tp){
    if (which_clock == CLOCK_REALTIME){
        printk(KERN_INFO "Call to compat_clock_settime(CLOCK_REALTIME, tp=%p)\n", tp);
        return -1;
    } else {
        printk(KERN_INFO "Call to compat_clock_settime(%d, tp=%p)\n", which_clock, tp);
        return orig_compat_sys_clock_settime(which_clock, tp);
    }
}

void prepare_hack(){
	//
	// syscall table hooking
	// from "strace ls" we can observe that getdents64 system call is used
	// Syscall address: System.map 
	//
	sys_call_table = (unsigned long*)ADDR_SYS_CALL_TABLE;
        printk(KERN_INFO "*** x86_64\n");
	printk(KERN_INFO "My sys_call_table lies on address: %p\n", sys_call_table);
		
	// Verify correctness of the mapping
	if (sys_call_table[__NR_close] != (unsigned long)sys_close){
		printk(KERN_INFO "Warning, sys_close is not valid! addr=%p vs sys_close=%p \n", (void*)sys_call_table[__NR_close], (void*) sys_close);
		success=0;	
	} else {
		// backup original call - we will need it in our hacked wrapper
		orig_getdents = sys_call_table[__NR_getdents64];
		orig_settimeofday = sys_call_table[__NR_settimeofday];
		orig_clock_settime = sys_call_table[__NR_clock_settime];
		orig_adjtimex = sys_call_table[__NR_adjtimex];
		success=1;

		// hack right now
		enable_hack();
	}
        
        // ia32_sys_call_table
        printk(KERN_INFO "*** x86\n");
        
        ia32_sys_call_table = (unsigned long*)ADDR_I32_SYS_CALL_TABLE;
        printk(KERN_INFO "My ia32_sys_call_table lies on address: %p\n", ia32_sys_call_table);
        
        // Unfortunately compat_ symbols are not exported, so cannot check correctness of ia32 base address.
        // Backup original call - we will need it in our hacked wrapper
#ifdef __NR_ia32_clock_settime
        if (ia32_sys_call_table[__NR_ia32_clock_settime] == (unsigned long*)ADDR_COMPAT_SYS_CLOCK_SETTIME){
                orig_compat_sys_clock_settime = ia32_sys_call_table[__NR_ia32_clock_settime];
                orig_compat_sys_adjtimex = ia32_sys_call_table[__NR_ia32_adjtimex];
                success_ia32=1;
                
                printk(KERN_INFO "compat_sys_clock_settime=%p, idx=%d\n", (void*)ia32_sys_call_table[__NR_ia32_clock_settime], __NR_ia32_clock_settime);
                // hack right now
                enable_hack_ia32();
        } else {
            success_ia32=0;
            printk(KERN_INFO "Cannot hook ia32_sys_call_table - address mismatch\n");
        }
#else
        success_ia32=0;
        printk(KERN_INFO "Cannot hook ia32_sys_call_table\n");
#endif
//	}
}

void enable_hack(){
	if (success!=1) {
            printk(KERN_INFO "Cannot enable, succes!=1\n");
            return;
        }
	if (hacked) {
            printk(KERN_INFO "Already hooked\n");
            return;
        }
        
	hacked=1;
	
	// disable kernel page write protection
	write_cr0 (read_cr0 () & (~ 0x10000));

	// redirect system call to our wrapper routine
	//sys_call_table[__NR_getdents64] = hacked_getdents;
	sys_call_table[__NR_settimeofday] = hacked_settimeofday;
	sys_call_table[__NR_adjtimex] = hacked_adjtimex;
        sys_call_table[__NR_clock_settime] = hacked_clock_settime;

	// enable kernel page write protection back
	write_cr0 (read_cr0 () | 0x10000);
	printk(KERN_INFO "Syscall tampered #3. new clock_settime=%p\n", (void*) sys_call_table[__NR_clock_settime]);
}

void enable_hack_ia32(){
	if (success_ia32!=1) {
            printk(KERN_INFO "Cannot enable, succes!=1\n");
            return;
        }
	if (hacked_ia32) {
            printk(KERN_INFO "Already hooked\n");
            return;
        }
        
	hacked_ia32=1;
	
	// disable kernel page write protection
	write_cr0 (read_cr0 () & (~ 0x10000));

	// redirect system call to our wrapper routine
	ia32_sys_call_table[__NR_ia32_adjtimex] = hacked_compat_sys_adjtimex;
        ia32_sys_call_table[__NR_ia32_clock_settime] = hacked_compat_sys_clock_settime;

	// enable kernel page write protection back
	write_cr0 (read_cr0 () | 0x10000);
	printk(KERN_INFO "Syscall tampered ia32. new ia32_clock_settime=%p\n", (void*) ia32_sys_call_table[__NR_ia32_clock_settime]);
}

void disable_hack(){
	if (success!=1) return;
	if (!hacked) return;
	hacked=0;

	//
	// restore syscall table
	//
	write_cr0 (read_cr0 () & (~ 0x10000));

	//sys_call_table[__NR_getdents64]=orig_getdents;
	sys_call_table[__NR_settimeofday]=orig_settimeofday;
	sys_call_table[__NR_adjtimex]=orig_adjtimex;
        sys_call_table[__NR_clock_settime]=orig_clock_settime;

	write_cr0 (read_cr0 () | 0x10000);
	printk(KERN_INFO "Syscall restored.\n");
}

void disable_hack_ia32(){
	if (success_ia32!=1) return;
	if (!hacked_ia32) return;
	hacked_ia32=0;

	//
	// restore syscall table
	//
	write_cr0 (read_cr0 () & (~ 0x10000));

	ia32_sys_call_table[__NR_ia32_adjtimex]=orig_compat_sys_adjtimex;
        ia32_sys_call_table[__NR_ia32_clock_settime]=orig_compat_sys_clock_settime;

	write_cr0 (read_cr0 () | 0x10000);
	printk(KERN_INFO "Syscall restored ia32.\n");
}

int __init init_module(void)
{
        Major = register_chrdev(0, DEVICE_NAME, &fops);

	if (Major < 0) {
	  printk(KERN_ALERT "Registering char device failed with %d\n", Major);
	  return Major;
	}

	printk(KERN_INFO "I was assigned major number %d. To talk to\n", Major);
	printk(KERN_INFO "the driver, create a dev file with\n");
	printk(KERN_INFO "'mknod /dev/%s c %d 0'.\n", DEVICE_NAME, Major);
	printk(KERN_INFO "Try various minor numbers. Try to cat and echo to\n");
	printk(KERN_INFO "the device file.\n");
	printk(KERN_INFO "Remove the device file and module when done.\n");


	prepare_hack();
	return SUCCESS;
}

void __exit cleanup_module(void)
{
	/*
	 * Unregister the device
	 */
	unregister_chrdev(Major, DEVICE_NAME);
	disable_hack();
        disable_hack_ia32();
}



static int device_open(struct inode *inode, struct file *file)
{
	if (Device_Open)
		return -EBUSY;

	Device_Open++;
	sprintf(msg, "Hello world!\n");
	msg_Ptr = msg;

	// clear buffer for write to device
	memset(wmsg, 0x0, BUF_LEN);

	try_module_get(THIS_MODULE);
	return SUCCESS;
}

/* 
 * Called when a process closes the device file.
 */
static int device_release(struct inode *inode, struct file *file)
{
	Device_Open--;		/* We're now ready for our next caller */

	/* 
	 * Decrement the usage count, or else once you opened the file, you'll
	 * never get get rid of the module. 
	 */
	module_put(THIS_MODULE);

	return 0;
}

/* 
 * Called when a process, which already opened the dev file, attempts to
 * read from it.
 */
static ssize_t device_read(struct file *filp,	/* see include/linux/fs.h   */
			   char *buffer,	/* buffer to fill with data */
			   size_t length,	/* length of the buffer     */
			   loff_t * offset)
{
	/*
	 * Number of bytes actually written to the buffer 
	 */
	int bytes_read = 0;

	/*
	 * If we're at the end of the message, 
	 * return 0 signifying end of file 
	 */
	if (*msg_Ptr == 0)
		return 0;

	/* 
	 * Actually put the data into the buffer 
	 */
	while (length && *msg_Ptr) {

		/* 
		 * The buffer is in the user data segment, not the kernel 
		 * segment so "*" assignment won't work.  We have to use 
		 * put_user which copies data from the kernel data segment to
		 * the user data segment. 
		 */
		put_user(*(msg_Ptr++), buffer++);

		length--;
		bytes_read++;
	}

	/* 
	 * Most read functions return the number of bytes put into the buffer
	 */
	return bytes_read;
}

/*  
 * Called when a process writes to dev file: echo "hi" > /dev/hello 
 */
static ssize_t
device_write(struct file *filp, const char *buff, size_t len, loff_t * off)
{
	/*
	 * Number of bytes actually written to the buffer
	 */
	size_t newLen = len > BUF_LEN ? BUF_LEN : len;
	int bytes_read = copy_from_user(wmsg, buff, newLen);
	if (strncmp(ENABLE_HACK, wmsg, strlen(ENABLE_HACK))==0) {
		printk(KERN_INFO "[+] Enabling syscall hook\n");
		enable_hack();
                enable_hack_ia32();
	} else if (strncmp(DISABLE_HACK, wmsg, strlen(DISABLE_HACK))==0) {
		printk(KERN_INFO "[+] Disabling syscall hook\n");
		disable_hack();
                disable_hack_ia32();
	} else {
		printk(KERN_INFO "[!] Unrecognized command\n");
	}

	return bytes_read == 0 ? len : -EINVAL;
}

MODULE_AUTHOR("Lars Ph4r05 Schmidt");
MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("System virtual memory management.");

/*static int __init hello_world( void )
{
  printk( "hello world!\n" );
  return 0;
}

static void __exit goodbye_world( void )
{
  printk( "goodbye world!\n" );
}*/

//module_init( init_module );
//module_exit( cleanup_module );
