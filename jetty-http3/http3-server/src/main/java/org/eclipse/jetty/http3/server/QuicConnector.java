package org.eclipse.jetty.http3.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

import org.eclipse.jetty.http3.quic.QuicConfig;
import org.eclipse.jetty.http3.quic.QuicConnection;
import org.eclipse.jetty.http3.quic.QuicConnectionId;
import org.eclipse.jetty.http3.quic.quiche.LibQuiche;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.AbstractNetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.IO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuicConnector extends AbstractNetworkConnector
{
    protected static final Logger LOG = LoggerFactory.getLogger(QuicConnector.class);

    private Selector selector;
    private DatagramChannel channel;
    private QuicConfig quicConfig;

    private final Map<QuicConnectionId, QuicEndPoint> endpoints = new ConcurrentHashMap<>();
    private final Deque<Command> commands = new ArrayDeque<>();

    public QuicConnector(Server server) throws IOException
    {
        super(server, null, null, null, 0);
    }

    public QuicConfig getQuicConfig()
    {
        return quicConfig;
    }

    public void setQuicConfig(QuicConfig quicConfig)
    {
        this.quicConfig = quicConfig;
    }

    @Override
    public void open() throws IOException
    {
        if (quicConfig == null)
            throw new IllegalStateException("QUIC config cannot be null");

        this.selector = Selector.open();
        this.channel = DatagramChannel.open();
        this.channel.configureBlocking(false);
        this.channel.register(selector, SelectionKey.OP_READ);
        this.channel.bind(bindAddress());
        getScheduler().schedule(this::fireTimeoutNotificationIfNeeded, 100, TimeUnit.MILLISECONDS);

        getExecutor().execute(() ->
        {
            while (true)
            {
                try
                {
                    selectOnce();
                }
                catch (IOException e)
                {
                    LOG.error("error during selection", e);
                }
            }
        });
    }

    private void fireTimeoutNotificationIfNeeded()
    {
        boolean timedOut = endpoints.values().stream().map(QuicEndPoint::hasTimedOut).findFirst().orElse(false);
        if (timedOut)
            selector.wakeup();
        getScheduler().schedule(this::fireTimeoutNotificationIfNeeded, 100, TimeUnit.MILLISECONDS);
    }

    private void selectOnce() throws IOException
    {
        int selected = selector.select();
        if (Thread.interrupted())
            throw new IOException("Selector thread was interrupted");

        if (selected == 0)
        {
            LOG.debug("no selected key; a QUIC connection has timed out");
            processTimeout();
            return;
        }

        Iterator<SelectionKey> selectorIt = selector.selectedKeys().iterator();
        while (selectorIt.hasNext())
        {
            SelectionKey key = selectorIt.next();
            selectorIt.remove();
            LOG.debug("Processing selected key {}", key);
            boolean needWrite = false;

            if (key.isReadable())
            {
                needWrite |= processReadableKey();
            }

            if (key.isWritable())
            {
                needWrite |= processWritableKey();
            }

            int ops = SelectionKey.OP_READ | (needWrite ? SelectionKey.OP_WRITE : 0);
            LOG.debug("setting key interest to " + ops);
            key.interestOps(ops);
        }
    }

    private void processTimeout() throws IOException
    {
        boolean needWrite = false;
        Iterator<QuicEndPoint> it = endpoints.values().iterator();
        while (it.hasNext())
        {
            QuicEndPoint quicEndPoint = it.next();
            if (quicEndPoint.hasTimedOut())
            {
                LOG.debug("connection has timed out: " + quicEndPoint);
                boolean closed = quicEndPoint.getQuicConnection().isConnectionClosed();
                if (closed)
                {
                    it.remove();
                    LOG.debug("connection closed due to timeout; remaining connections: " + endpoints);
                }
                QuicTimeoutCommand quicTimeoutCommand = new QuicTimeoutCommand(getByteBufferPool(), quicEndPoint.getQuicConnection(), channel, quicEndPoint.getLastPeer(), quicEndPoint.getTimeoutSetter(), closed);
                if (!quicTimeoutCommand.execute())
                {
                    commands.offer(quicTimeoutCommand);
                    needWrite = true;
                }
            }
        }
        //TODO: re-registering might leak some memory, check that
        channel.register(selector, SelectionKey.OP_READ | (needWrite ? SelectionKey.OP_WRITE : 0));
    }

    private boolean processWritableKey() throws IOException
    {
        boolean needWrite = false;
        LOG.debug("key is writable, commands = " + commands);
        while (!commands.isEmpty())
        {
            Command command = commands.poll();
            LOG.debug("executing command " + command);
            boolean finished = command.execute();
            LOG.debug("executed command; finished? " + finished);
            if (!finished)
            {
                commands.offer(command);
                needWrite = true;
                break;
            }
        }
        return needWrite;
    }

    private boolean processReadableKey() throws IOException
    {
        boolean needWrite = false;
        ByteBufferPool bufferPool = getByteBufferPool();

        ByteBuffer buffer = bufferPool.acquire(LibQuiche.QUICHE_MIN_CLIENT_INITIAL_LEN, true);
        SocketAddress peer = channel.receive(buffer);
        buffer.flip();

        QuicConnectionId connectionId = QuicConnectionId.fromPacket(buffer);
        QuicEndPoint endPoint = endpoints.get(connectionId);
        if (endPoint == null)
        {
            LOG.debug("got packet for a new connection");
            // new connection
            ByteBuffer newConnectionNegotiationToSend = bufferPool.acquire(LibQuiche.QUICHE_MIN_CLIENT_INITIAL_LEN, true);
            QuicConnection acceptedQuicConnection = QuicConnection.tryAccept(quicConfig, peer, buffer, newConnectionNegotiationToSend);
            if (acceptedQuicConnection == null)
            {
                LOG.debug("new connection negotiation");
                ChannelWriteCommand channelWriteCommand = new ChannelWriteCommand(bufferPool, newConnectionNegotiationToSend, channel, peer);
                if (!channelWriteCommand.execute())
                {
                    commands.offer(channelWriteCommand);
                    needWrite = true;
                }
            }
            else
            {
                LOG.debug("new connection accepted");
                endPoint = new QuicEndPoint(getScheduler(), acceptedQuicConnection);
                endpoints.put(connectionId, endPoint);
                QuicWriteCommand quicWriteCommand = new QuicWriteCommand(bufferPool, acceptedQuicConnection, channel, peer, endPoint.getTimeoutSetter());
                if (!quicWriteCommand.execute())
                {
                    commands.offer(quicWriteCommand);
                    needWrite = true;
                }
            }
        }
        else
        {
            LOG.debug("got packet for an existing connection: " + connectionId + " - buffer: p=" + buffer.position() + " r=" + buffer.remaining());
            // existing connection
            endPoint.handlePacket(buffer, peer);
            QuicWriteCommand quicWriteCommand = new QuicWriteCommand(bufferPool, endPoint.getQuicConnection(), channel, peer, endPoint.getTimeoutSetter());
            if (!quicWriteCommand.execute())
            {
                commands.offer(quicWriteCommand);
                needWrite = true;
            }
        }
        return needWrite;
    }

    @Override
    public void close()
    {
        IO.close(channel);
        channel = null;
        IO.close(selector);
        selector = null;
    }

    private SocketAddress bindAddress()
    {
        String host = getHost();
        if (host == null)
            host = "0.0.0.0";
        int port = getPort();
        if (port < 0)
            throw new IllegalArgumentException("port cannot be negative: " + port);
        return new InetSocketAddress(host, port);
    }

    @Override
    public Object getTransport()
    {
        return channel;
    }

    @Override
    public boolean isOpen()
    {
        DatagramChannel channel = this.channel;
        return channel != null && channel.isOpen();
    }

    @Override
    protected void accept(int acceptorID)
    {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " has its own accepting mechanism");
    }



    private interface Command
    {
        boolean execute() throws IOException;
    }

    private static class QuicTimeoutCommand implements Command
    {
        private final QuicWriteCommand quicWriteCommand;
        private final boolean close;
        private boolean timeoutCalled;

        public QuicTimeoutCommand(ByteBufferPool bufferPool, QuicConnection quicConnection, DatagramChannel channel, SocketAddress lastPeer, LongConsumer timeoutSetter, boolean close)
        {
            this.close = close;
            this.quicWriteCommand = new QuicWriteCommand("timeout", bufferPool, quicConnection, channel, lastPeer, timeoutSetter);
        }

        @Override
        public boolean execute() throws IOException
        {
            if (!timeoutCalled)
            {
                LOG.debug("notifying quiche of timeout");
                quicWriteCommand.quicConnection.onTimeout();
            }
            timeoutCalled = true;
            boolean written = quicWriteCommand.execute();
            if (!written)
                return false;
            if (close)
                quicWriteCommand.quicConnection.close();
            return true;
        }
    }

    private static class QuicWriteCommand implements Command
    {
        private final String cmdName;
        private final ByteBufferPool bufferPool;
        private final QuicConnection quicConnection;
        private final DatagramChannel channel;
        private final SocketAddress peer;
        private final LongConsumer timeoutConsumer;

        private ByteBuffer buffer;

        public QuicWriteCommand(ByteBufferPool bufferPool, QuicConnection quicConnection, DatagramChannel channel, SocketAddress peer, LongConsumer timeoutConsumer)
        {
            this("write", bufferPool, quicConnection, channel, peer, timeoutConsumer);
        }

        public QuicWriteCommand(String cmdName, ByteBufferPool bufferPool, QuicConnection quicConnection, DatagramChannel channel, SocketAddress peer, LongConsumer timeoutConsumer)
        {
            this.cmdName = cmdName;
            this.bufferPool = bufferPool;
            this.quicConnection = quicConnection;
            this.channel = channel;
            this.peer = peer;
            this.timeoutConsumer = timeoutConsumer;
        }

        @Override
        public boolean execute() throws IOException
        {
            LOG.debug("executing {} command", cmdName);
            if (buffer != null)
            {
                int channelSent = channel.send(buffer, peer);
                LOG.debug("resuming sending to channel made it send {} bytes", channelSent);
                if (channelSent == 0)
                {
                    LOG.debug("executed {} command; channel sending(1) could not be done", cmdName);
                    return false;
                }
                buffer.clear();
            }
            else
            {
                LOG.debug("fresh command execution");
                buffer = bufferPool.acquire(LibQuiche.QUICHE_MIN_CLIENT_INITIAL_LEN, true);
            }

            while (true)
            {
                int quicSent = quicConnection.send(buffer);
                LOG.debug("quiche wants to send {} bytes", quicSent);
                timeoutConsumer.accept(quicConnection.nextTimeout());
                if (quicSent == 0)
                {
                    LOG.debug("executed {} command; all done", cmdName);
                    bufferPool.release(buffer);
                    buffer = null;
                    return true;
                }
                buffer.flip();
                int channelSent = channel.send(buffer, peer);
                LOG.debug("channel sent {} bytes", channelSent);
                if (channelSent == 0)
                {
                    LOG.debug("executed {} command; channel sending(2) could not be done", cmdName);
                    return false;
                }
                buffer.clear();
            }
        }
    }

    private static class ChannelWriteCommand implements Command
    {
        private final ByteBufferPool bufferPool;
        private final ByteBuffer buffer;
        private final DatagramChannel channel;
        private final SocketAddress peer;

        private ChannelWriteCommand(ByteBufferPool bufferPool, ByteBuffer buffer, DatagramChannel channel, SocketAddress peer)
        {
            this.bufferPool = bufferPool;
            this.buffer = buffer;
            this.channel = channel;
            this.peer = peer;
        }

        @Override
        public boolean execute() throws IOException
        {
            LOG.debug("executing channel write command");
            int sent = channel.send(buffer, peer);
            if (sent == 0)
            {
                LOG.debug("executed channel write command; channel sending could not be done");
                return false;
            }
            bufferPool.release(buffer);
            LOG.debug("executed channel write command; all done");
            return true;
        }
    }

}
