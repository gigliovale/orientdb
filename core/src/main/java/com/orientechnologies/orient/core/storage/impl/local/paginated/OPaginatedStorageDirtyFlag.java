/*
  *
  *  *  Copyright 2014 Orient Technologies LTD (info(at)orientechnologies.com)
  *  *
  *  *  Licensed under the Apache License, Version 2.0 (the "License");
  *  *  you may not use this file except in compliance with the License.
  *  *  You may obtain a copy of the License at
  *  *
  *  *       http://www.apache.org/licenses/LICENSE-2.0
  *  *
  *  *  Unless required by applicable law or agreed to in writing, software
  *  *  distributed under the License is distributed on an "AS IS" BASIS,
  *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  *  *  See the License for the specific language governing permissions and
  *  *  limitations under the License.
  *  *
  *  * For more information: http://www.orientechnologies.com
  *
  */

package com.orientechnologies.orient.core.storage.impl.local.paginated;

import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.exception.OStorageException;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author Andrey Lomakin (a.lomakin-at-orientechnologies.com)
 * @since 5/6/14
 */
public class OPaginatedStorageDirtyFlag {
  private final String dirtyFilePath;

  private File             dirtyFile;
  private RandomAccessFile dirtyFileData;
  private FileChannel      channel;
  private FileLock         fileLock;

  private volatile boolean dirtyFlag;
  private volatile boolean indexRebuildScheduled;

  private final Lock lock = new ReentrantLock();

  public OPaginatedStorageDirtyFlag(String dirtyFilePath) {
    this.dirtyFilePath = dirtyFilePath;
  }

  public void create() throws IOException {
    lock.lock();
    try {
      dirtyFile = new File(dirtyFilePath);

      if (dirtyFile.exists()) {
        final boolean fileDeleted = dirtyFile.delete();

        if (!fileDeleted)
          throw new IllegalStateException("Cannot delete file : " + dirtyFilePath);
      }

      final boolean fileCreated = dirtyFile.createNewFile();
      if (!fileCreated)
        throw new IllegalStateException("Cannot create file : " + dirtyFilePath);

      dirtyFileData = new RandomAccessFile(dirtyFile, "rwd");
      channel = dirtyFileData.getChannel();

      if (OGlobalConfiguration.FILE_LOCK.getValueAsBoolean()) {
        lockFile();
      }

      dirtyFlag = true;
      indexRebuildScheduled = false;

      writeState(dirtyFlag, indexRebuildScheduled);
    } finally {
      lock.unlock();
    }
  }

  private void lockFile() throws IOException {
    try {
      fileLock = channel.tryLock();
    } catch (OverlappingFileLockException e) {
      OLogManager.instance().warn(this, "Database is open by another process");
    }

    if (fileLock == null)
      throw new OStorageException("Can not open storage it is acquired by other process");
  }

  public boolean exists() {
    lock.lock();
    try {
      return new File(dirtyFilePath).exists();
    } finally {
      lock.unlock();
    }
  }

  public void open() throws IOException {
    lock.lock();
    try {
      dirtyFile = new File(dirtyFilePath);

      if (!dirtyFile.exists()) {
        final boolean fileCreated = dirtyFile.createNewFile();

        if (!fileCreated)
          throw new IllegalStateException("Cannot create file : " + dirtyFilePath);

        dirtyFileData = new RandomAccessFile(dirtyFile, "rwd");
        channel = dirtyFileData.getChannel();

        writeState(false, false);
      }

      dirtyFileData = new RandomAccessFile(dirtyFile, "rwd");
      channel = dirtyFileData.getChannel();

      if (OGlobalConfiguration.FILE_LOCK.getValueAsBoolean()) {
        lockFile();
      }

      channel.position(0);

      if (channel.size() < 2) {
        ByteBuffer buffer = ByteBuffer.allocate(1);
        readByteBuffer(buffer, channel);

        buffer.position(0);
        dirtyFlag = buffer.get() > 0;
        writeState(dirtyFlag, indexRebuildScheduled);
      } else {
        ByteBuffer buffer = ByteBuffer.allocate(2);
        readByteBuffer(buffer, channel);

        buffer.position(0);

        dirtyFlag = buffer.get() > 0;
        indexRebuildScheduled = buffer.get() > 0;
      }

    } finally {
      lock.unlock();
    }
  }

  public void close() throws IOException {
    lock.lock();
    try {
      if (dirtyFile == null) {
        return;
      }

      if (dirtyFile.exists()) {
        if (fileLock != null) {
          fileLock.release();
          fileLock = null;
        }

        dirtyFileData.close();
      }

    } finally {
      lock.unlock();
    }
  }

  public void delete() throws IOException {
    lock.lock();
    try {
      if (dirtyFile == null)
        return;

      if (dirtyFile.exists()) {

        if (fileLock != null) {
          fileLock.release();
          fileLock = null;
        }

        channel.close();
        dirtyFileData.close();

        boolean deleted = dirtyFile.delete();
        while (!deleted) {
          deleted = !dirtyFile.exists() || dirtyFile.delete();
        }
      }
    } finally {
      lock.unlock();
    }
  }

  public void makeDirty() throws IOException {
    if (dirtyFlag)
      return;

    lock.lock();
    try {
      if (dirtyFlag)
        return;

      dirtyFlag = true;

      writeState(dirtyFlag, indexRebuildScheduled);
    } finally {
      lock.unlock();
    }
  }

  public void clearDirty() throws IOException {
    if (!dirtyFlag)
      return;

    lock.lock();
    try {
      if (!dirtyFlag)
        return;

      dirtyFlag = false;

      writeState(dirtyFlag, indexRebuildScheduled);
    } finally {
      lock.unlock();
    }
  }

  public void scheduleIndexRebuild() throws IOException {
    if (indexRebuildScheduled)
      return;

    lock.lock();
    try {
      if (indexRebuildScheduled)
        return;

      indexRebuildScheduled = true;

      writeState(dirtyFlag, indexRebuildScheduled);
    } finally {
      lock.unlock();
    }
  }

  public void clearIndexRebuild() throws IOException {
    if (!indexRebuildScheduled)
      return;

    lock.lock();
    try {
      if (!indexRebuildScheduled)
        return;

      indexRebuildScheduled = false;

      writeState(dirtyFlag, indexRebuildScheduled);
    } finally {
      lock.unlock();
    }
  }

  public boolean isDirty() {
    return dirtyFlag;
  }

  public boolean isIndexRebuildScheduled() {
    return indexRebuildScheduled;
  }

  private void writeByteBuffer(ByteBuffer buffer, FileChannel channel) throws IOException {
    int bytesToWrite = buffer.limit();

    int written = 0;
    while (written < bytesToWrite) {
      written += channel.write(buffer, written);
    }
  }

  private void readByteBuffer(ByteBuffer buffer, FileChannel channel) throws IOException {
    int bytesToRead = buffer.limit();

    int read = 0;
    while (read < bytesToRead) {
      int r = channel.read(buffer);

      if (r == -1)
        throw new EOFException("End of file is reached");

      read += r;
    }
  }

  private void writeState(boolean dirtyFlag, boolean indexRebuildScheduled) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(2);
    buffer.put(dirtyFlag ? (byte) 1 : 0);
    buffer.put(indexRebuildScheduled ? (byte) 1 : 0);

    channel.position(0);
    buffer.position(0);

    writeByteBuffer(buffer, channel);
  }

}
