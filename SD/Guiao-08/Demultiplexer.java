import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import Connections.TaggedConnection;
import Connections.TaggedConnection.Frame;

public class Demultiplexer implements AutoCloseable{
    private Map<Integer, Entry> buffers;
    private ReentrantLock lockBuffers; //lock para o map
    private TaggedConnection connection;
    private IOException exception = null; //para que a thread continue a receber dados que já lá estavam se houver uma exceção

    private class Entry {
        final Condition cond = lockBuffers.newCondition();
        final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    }

    private Entry get(int tag) {
        Entry e = this.buffers.get(tag);

        if (e == null) {
            this.buffers.put(tag, new Entry());
            e = this.buffers.get(tag);
        }

        return e;
    }

    public Demultiplexer (TaggedConnection conn) {
        this.buffers = new HashMap<>();
        this.lockBuffers = new ReentrantLock();
        this.connection = conn;
    }

    // receber um frame e encaminhá-lo para o respetivo buffer
    public void start() throws IOException {
        new Thread(() -> {
            try {
                while (true) {
                    Frame frame = this.connection.receive();

                    try {
                        lockBuffers.lock();

                        Entry e = get(frame.tag);
                        e.queue.add(frame.data);
                        e.cond.signal(); //notificar apenas a thread respetiva
                    } finally {
                        lockBuffers.unlock();
                    }
                }
            } catch (IOException e) {
                try {
                    lockBuffers.lock();

                    this.exception = e;
                    this.buffers.forEach((k,v) -> v.cond.signalAll()); //notificar todas as threads se houver um erro
                } finally {
                    lockBuffers.unlock();
                }

            }
        }).start();
    }

    public void send(Frame frame) throws IOException{
        this.send(frame.tag, frame.data);
    }

    public void send(int tag, byte[] data) throws IOException{
        this.connection.send(tag, data);
    }

    public byte[] receive(int tag) throws IOException, InterruptedException{
        try {
            lockBuffers.lock();

            Entry e = get(tag);

            while (e.queue.isEmpty() && this.exception == null) {
                e.cond.await();
            }

            if (!e.queue.isEmpty()) {
                return e.queue.poll();
            }
            else {
                throw this.exception;
            }
        } finally {
            lockBuffers.unlock();
        }
    }

    public void close() throws IOException{
        this.connection.close();
    }
}
