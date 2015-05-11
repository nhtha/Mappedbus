/* 
* Copyright 2015 Caplogic AB. 
* 
* Licensed under the Apache License, Version 2.0 (the "License"); 
* you may not use this file except in compliance with the License. 
* You may obtain a copy of the License at 
* 
* http://www.apache.org/licenses/LICENSE-2.0 
* 
* Unless required by applicable law or agreed to in writing, software 
* distributed under the License is distributed on an "AS IS" BASIS, 
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
* See the License for the specific language governing permissions and 
* limitations under the License. 
*/
package se.caplogic.mappedbus;

import se.caplogic.mappedbus.MappedBus.Commit;
import se.caplogic.mappedbus.MappedBus.FileStructure;
import se.caplogic.mappedbus.MappedBus.Length;
import se.caplogic.mappedbus.MappedBus.Rollback;

/**
 * Class for reading from the MappedBus.
 *
 */
public class MappedBusReader {

	private final long NUM_TIMEOUT_ITERATIONS = 1000000000;
	
	private final MemoryMappedFile mem;

	private final long fileSize;
	
	private final int recordSize;

	private int timeoutMilliseconds = 2000;

	private long limit = FileStructure.Data;

	private long initialLimit;
	
	private boolean typeRead;

;
	/**
	 * Creates a new reader.
	 *
	 * @param fileName the name of the memory mapped file
	 * @param fileSize the maximum size of the file
	 * @param recordSize the maximum size of a record (excluding status flags and meta data)
	 */
	public MappedBusReader(String fileName, long fileSize, int recordSize) {
		this.fileSize = fileSize;
		this.recordSize = recordSize;	
		try {
			mem = new MemoryMappedFile(fileName, fileSize);
			initialLimit = mem.getLongVolatile(FileStructure.Limit);
		} catch(Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	/**
	 * Sets the time for a reader to wait for a record to be committed.
	 *
	 * When the timeout occurs the reader will mark the record as "rolled back" and
	 * the record is ignored.
	 * 
	 * @param timeoutMilliseconds the timeout in milliseconds
	 */
	public void setTimeoutMilliseconds(int timeoutMilliseconds) {
		this.timeoutMilliseconds = timeoutMilliseconds;
	}
	
	/**
	 * Indicates whether there's a new record available.
	 *
	 * @return true, if there's a new record available, otherwise false
	 */
	public boolean hasNext() {
		if (limit >= fileSize) {
			throw new RuntimeException("End of file was reached");
		}
		return mem.getLongVolatile(FileStructure.Limit) > limit;
	}
	
	public boolean next() {
		long start = 0;
		while(true) {
			for (long i=0; i < NUM_TIMEOUT_ITERATIONS; i++) {
				int commit = mem.getIntVolatile(limit);
				int rollback = mem.getIntVolatile(limit + Length.Commit);
				if(rollback == Rollback.Set) {
					limit += Length.RecordHeader + recordSize;
					return false;
				}
				if (commit == Commit.Set) {
					limit += Length.StatusFlags;
					return true;
				}
			}
			if(start == 0) {
				start = System.currentTimeMillis();
			} else {
				if (System.currentTimeMillis() - start > timeoutMilliseconds) {
					mem.putIntVolatile(limit + Length.Commit, Rollback.Set);
					limit += Length.RecordHeader + recordSize;
					return false;
				}
			}
		}
	}

	/**
	 * Reads the message type.
	 *
	 * @return an integer specifying the message type
	 */
	public int readType() {
		typeRead = true;
		int type = mem.getInt(limit);
		limit += Length.Metadata;
		return type;
	}

	/**
	 * Reads the next message.
	 *
	 * @param message the message object to populate
	 * @return the message object
	 */
	public Message readMessage(Message message) {
		if (!typeRead) {
			readType();
		}
		typeRead = false;
		message.read(mem, limit);
		limit += recordSize;
		return message;
	}

	/**
	 * Reads the next buffer of data.
	 * 
	 * @param dst the input buffer
	 * @param offset the offset in the buffer of the first byte to read data into
	 * @return the length of the record that was read
	 */
	public int readBuffer(byte[] dst, int offset) {
		int length = mem.getInt(limit);
		limit += Length.Metadata;
		mem.getBytes(limit, dst, offset, length);
		limit += recordSize;
		return length;
	}
	
	/**
	 * Indicates whether all records available when the reader was created have been read.
	 *
	 * @return true, if all records available from the start was read, otherwise false
	 */
	public boolean hasRecovered() {
		return limit >= initialLimit;
	}
}