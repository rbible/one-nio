/*
 * Copyright 2015 Odnoklassniki Ltd, Mail.Ru Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package one.nio.net;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.channels.SelectionKey;

public class Session implements Closeable {
    public static final int READABLE   = SelectionKey.OP_READ;
    public static final int WRITEABLE  = SelectionKey.OP_WRITE;
    public static final int CLOSING    = 0x18;
    public static final int EVENT_MASK = 0xff;
    public static final int SSL        = 0x100;

    public static final int ACTIVE = 0;
    public static final int IDLE   = 1;
    public static final int STALE  = 2;

    protected Socket socket;
    protected Selector selector;
    protected int slot;
    protected int events;
    protected int eventsToListen;
    protected boolean closing;
    protected WriteQueue writeQueue;
    protected volatile long lastAccessTime;

    public Session(Socket socket) {
        this.socket = socket;
        this.eventsToListen = READABLE;
        this.lastAccessTime = System.currentTimeMillis();
    }

    public final String getRemoteHost() {
        InetSocketAddress address = socket.getRemoteAddress();
        return address == null ? null : address.getAddress().getHostAddress();
    }

    public final long lastAccessTime() {
        return lastAccessTime;
    }

    public int checkStatus(long currentTime, long keepAlive) {
        long lastAccessTime = this.lastAccessTime;
        if (lastAccessTime < currentTime - keepAlive) {
            if (writeQueue == null) {
                return IDLE;
            } else if (lastAccessTime < currentTime - keepAlive * 8) {
                return STALE;
            }
        }
        return ACTIVE;
    }

    @Override
    public synchronized void close() {
        if (socket.isOpen()) {
            closing = true;
            writeQueue = null;
            selector.unregister(this);
            socket.close();
        }
    }

    public synchronized void scheduleClose() {
        if (writeQueue == null) {
            close();
        } else {
            closing = true;
        }
    }

    public synchronized void getQueueStats(long[] stats) {
        int length = 0;
        long bytes = 0;
        for (WriteQueue head = writeQueue; head != null; head = head.next) {
            length++;
            bytes += head.count;
        }
        stats[0] = length;
        stats[1] = bytes;
    }

    public void listen(int newEventsToListen) {
        if (newEventsToListen != eventsToListen) {
            eventsToListen = newEventsToListen;
            selector.listen(this, newEventsToListen & EVENT_MASK);
        }
    }

    public int read(byte[] data, int offset, int count) throws IOException {
        int bytesRead = socket.read(data, offset, count);
        if (bytesRead >= 0) {
            listen(READABLE);
            return bytesRead;
        } else {
            listen(SSL | WRITEABLE);
            return 0;
        }
    }

    public synchronized void write(byte[] data, int offset, int count) throws IOException {
        if (writeQueue == null) {
            int bytesWritten = socket.write(data, offset, count);
            if (bytesWritten < count) {
                int newEventsToListen;
                if (bytesWritten >= 0) {
                    offset += bytesWritten;
                    count -= bytesWritten;
                    newEventsToListen = WRITEABLE;
                } else {
                    newEventsToListen = SSL | READABLE;
                }
                writeQueue = new WriteQueue(data, offset, count);
                listen(newEventsToListen);
            }
        } else if (!closing) {
            WriteQueue tail = writeQueue;
            while (tail.next != null) {
                tail = tail.next;
            }
            tail.next = new WriteQueue(data, offset, count);
        } else {
            throw new SocketException("Socket closed");
        }
    }

    protected synchronized void processWrite() throws Exception {
        for (WriteQueue head = writeQueue; head != null; head = head.next) {
            int bytesWritten = socket.write(head.data, head.offset, head.count);
            if (bytesWritten < head.count) {
                int newEventsToListen;
                if (bytesWritten >= 0) {
                    head.offset += bytesWritten;
                    head.count -= bytesWritten;
                    newEventsToListen = WRITEABLE;
                } else {
                    newEventsToListen = SSL | READABLE;
                }
                writeQueue = head;
                listen(newEventsToListen);
                return;
            } else if (closing) {
                close();
                return;
            }
        }
        writeQueue = null;
        listen(READABLE);
    }

    protected void processRead(byte[] buffer) throws Exception {
        read(buffer, 0, buffer.length);
    }

    public void process(byte[] buffer) throws Exception {
        lastAccessTime = Long.MAX_VALUE;

        if (eventsToListen >= SSL) {
            if ((events & READABLE) != 0) processWrite();
            if ((events & WRITEABLE) != 0) processRead(buffer);
        } else {
            if ((events & WRITEABLE) != 0) processWrite();
            if ((events & READABLE) != 0) processRead(buffer);
        }

        if ((events & CLOSING) != 0) {
            close();
        }

        lastAccessTime = System.currentTimeMillis();
    }

    private static class WriteQueue {
        byte[] data;
        int offset;
        int count;
        WriteQueue next;

        WriteQueue(byte[] data, int offset, int count) {
            this.data = data;
            this.offset = offset;
            this.count = count;
        }
    }
}
