/* IOManager.java
 * 
 * Class used as a proxy for I/O operations
 * (c) 2006 Andrea Spadaccini
 *
 * This file is part of the EduMIPS64 project, and is released under the GNU
 * General Public License.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package edumips64.core;
import java.util.*;
import java.io.*;

/** Class used as a proxy for I/O operations.
 *  This class handles input/output from/to files, including stdin, stdout and
 *  stderr.
 *  @author Andrea Spadaccini */
public class IOManager {
	// Modes
	// Five bits are used for the mode. The first two are used to understand if
	// the file has to be opened read-only, write-only or read-write.
	// The third bit is used to know if the file should be created if it
	// doesn't exist. The last two are used to understand if we must append the
	// written data to the end of the file or if we must truncate the file.
	//
	// It is mandatory to specify if the file must be opened in read-only or
	// write-only mode.
	// The default mode between O_APPEND and O_TRUNC is O_TRUNC.
	//
	// These modes can be ORed together as in the standard POSIX open syscall().
	
	/** Open the file in read-only mode */
	public final int O_RDONLY = 0x01; 	// 00001

	/** Open the file in write-only mode */
	public final int O_WRONLY = 0x02;  	// 00010

	/** Open the file in read/write mode */
	public final int O_RDWR = 0x03;		// 00011

	/** Create the file if it doesn't exist */
	public final int O_CREAT = 0x04;	// 00100
	
	/** Append text to the end of the file */
	public final int O_APPEND = 0x08;	// 01000

	/** Truncate the file if it exists */
	public final int O_TRUNC = 0x10;	// 10000

	private Map<Integer, Reader> ins;
	private Map<Integer, Writer> outs;

	private int next_descriptor;

	/** Closes all the open files */
	public void reset() throws IOException {
		edumips64.Main.logger.debug("IOManager: resetting... next_fd = " + next_descriptor);
		while(next_descriptor > 3)
			close(next_descriptor--);
		edumips64.Main.logger.debug("IOManager: resetted. next_fd = " + next_descriptor);
	}

	public IOManager() {
		ins = new HashMap<Integer, Reader>();
		outs = new HashMap<Integer, Writer>();

		// We set the next descriptor to 3, because 0 is stdin, 1 is stdout and
		// 2 is stderr
		next_descriptor = 3;
	}

	/** Closes the specified file descriptor.
	 *  @param fd the file descriptor to close
	 */
	public int close(int fd) throws IOException,  java.io.FileNotFoundException {
		edumips64.Main.logger.debug("call to close() with fd = " + fd);
		int ret = -1;
		boolean in = ins.containsKey(fd);
		boolean out = outs.containsKey(fd);
		if(in) {
			edumips64.Main.logger.debug("found open input stream");
			Reader r = ins.get(fd);
			r.close();
			ins.remove(fd);
			ret = 0;
		}
		if(out) {
			edumips64.Main.logger.debug("found open output stream");
			Writer w = outs.get(fd);
			w.close();
			outs.remove(fd);
			ret = 0;
		}
		return ret;
	}

	/** Opens the specified file, with the specified flags
	 *  @param pathname pathname of the file to open
	 *  @param flags combination of the flags O_RDONLY, O_WRONLY, O_RDWR, O_CREAT, O_APPEND, O_TRUNC 
	 */
	public int open(String pathname, int flags) throws java.io.FileNotFoundException, IOException, IOManagerException {
		// TODO: gestire combinazioni non valide tipo O_RDONLY || O_APPEND?
		if(((flags & O_RDONLY) != O_RDONLY) && (flags & O_WRONLY) != O_WRONLY)
			throw new IOManagerException("NOOPENMODESPECIFIED");

		// By default, FileWriter creates the file if it doesn't exist, so we
		// must check if the user hasn't specified O_CREAT, has specified
		// O_WRONLY (or O_RDWR) and the file does't exist

		if((flags & O_CREAT) == O_CREAT) {
			edumips64.Main.logger.debug("flags & O_CREAT = " + O_CREAT );
		}

		if(((flags & O_CREAT) != O_CREAT) && ((flags & O_WRONLY) == O_WRONLY)) {
			edumips64.Main.logger.debug("No O_CREAT, but O_WRONLY. We must check if the file exists");
			File temp = new File(pathname);
			if(!temp.exists())
				throw new FileNotFoundException();
		}

        // The user can't open with the O_CREAT flag a file that could be read.
        
        if(((flags & O_CREAT) == O_CREAT) && ((flags & O_RDONLY) == O_RDONLY)) {
			edumips64.Main.logger.debug("Trying to open in read mode a file that might not exist.");
			File temp = new File(pathname);
			if(!temp.exists())
                throw new IOManagerException("OPENREADANDCREATE");
        }


		boolean append = false;
		if((flags & O_APPEND) == O_APPEND) {
			edumips64.Main.logger.debug("flags & O_APPEND = " + O_APPEND );
			append = true;
		}

		if((flags & O_RDONLY) == O_RDONLY) {
			edumips64.Main.logger.debug("flags & O_RDONLY = " + O_RDONLY );
			Reader r = new FileReader(pathname);
			ins.put(next_descriptor, r);
		}

		if((flags & O_WRONLY) == O_WRONLY) {
			edumips64.Main.logger.debug("flags & O_WRONLY = " + O_WRONLY);
			Writer w = new FileWriter(pathname, append);
			outs.put(next_descriptor, w);
		}

		// TODO: gestire creat, trunc
		return next_descriptor++;
	}

	/** Writes some bytes to a file, reading them from memory.
	 *  @param fd the file descriptor identifying the file
	 *  @param address the address to read the data from
	 *  @param count the number of bytes to write
	 */
	public int write(int fd, long address, int count) throws IOManagerException, IOException {
		// Let's verify if we've got a valid file descriptor
		if(fd <= 2 && fd == 0) {
			// We can write only to descriptors 1 (stdout) and 2 (stderr)
			edumips64.Main.logger.debug("Attempt to write to stdin");
			throw new IOManagerException("WRITETOSTDIN");
		}
		else if (fd > 2 && !outs.containsKey(fd)) {
			edumips64.Main.logger.debug("File descriptor " + fd + " not valid.");
			throw new IOManagerException("FILENOTOPENED");
		}

		byte[] bytes_array = new byte[count];
		MemoryElement memEl = null;
		StringBuffer buff = null ;
		try {
			buff = new StringBuffer();
			int posInWord = 0;
			for(int i = 0; i < count; ++i) {
				if(i % 8 == 0) {
					posInWord = 0;
					edumips64.Main.logger.debug("write(): getting a new cell at address " + address);
					memEl = Memory.getInstance().getCell((int)address);
					address += 8;
				}
				byte rb = (byte)memEl.readByte(posInWord++);
				bytes_array[i] = rb;
				buff.append((char)rb);
			}
		}
		catch(MemoryElementNotFoundException e) {
			throw new IOManagerException("OUTOFMEMORY");
		}

		// Write to stdout or to a classic file
		if(fd <= 2)
			edumips64.Main.ioFrame.write(bytes_array);
		else {
			Writer w = outs.get(fd);
			w.write(new String(bytes_array));
		}

		edumips64.Main.logger.debug("Wrote " + buff.toString() + " to fd " + fd);
		return buff.length();
	}

	/** Reads some bytes from a file, writing them to memory.
	 *  @param fd the file descriptor identifying the file
	 *  @param address the address to write the data to
	 *  @param count the number of bytes to read
	 */
	public int read(int fd, long address, int count) throws IOManagerException, java.io.FileNotFoundException, IOException {
		StringBuffer buff = new StringBuffer();
		if(fd <= 2 && fd > 0) {
			edumips64.Main.logger.debug("Attempt to read from stdout/stderr");
			throw new IOManagerException("READFROMSTDOUT");
		}
		else if(fd > 2 && !ins.containsKey(fd)) {
			edumips64.Main.logger.debug("File descriptor " + fd + " not valid.");
			throw new IOManagerException("FILENOTOPENED");
		}

		String read_str = null;
		// Different behaviour for stdin and a normal file
		if(fd == 0) 
			read_str = edumips64.Main.ioFrame.read(count);
		else {
			Reader r = ins.get(fd);
			for(int i = 0; i < count; ++i) {
				int read_byte = r.read();
				if(read_byte < 0)
					// EOF reached
					break;
				buff.append((char)read_byte);
			}
			read_str = buff.toString();
			buff.setLength(0);
		}

		edumips64.Main.logger.debug("Read the string " + read_str + " from fd " + fd);

		MemoryElement memEl = null;
		try {
			int posInWord = 0;
			for(int i = 0; i < read_str.length(); ++i) {
				if(i % 8 == 0) {
					posInWord = 0;
					edumips64.Main.logger.debug("read(): getting a new cell at address " + address);
					memEl = Memory.getInstance().getCell((int)address);
					address += 8;
				}

				int rb = (int)read_str.charAt(i);
				memEl.writeByte(rb, posInWord++);
				buff.append((char)rb);
			}
			edumips64.Main.logger.debug("Wrote " + buff.toString() + " to memory");
			return buff.length();
		}
		catch(MemoryElementNotFoundException e) {
			throw new IOManagerException("OUTOFMEMORY");
		}
		catch(IrregularWriteOperationException e) {
			e.printStackTrace();
		}
		return -1;
	}
}
