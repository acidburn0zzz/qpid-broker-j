/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.qpid.server.protocol.v0_10;

import static org.apache.qpid.server.protocol.v0_10.ServerConnection.State.CLOSE_RCVD;

import java.security.AccessControlException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.qpid.server.common.ServerPropertyNames;
import org.apache.qpid.server.configuration.CommonProperties;
import org.apache.qpid.server.properties.ConnectionStartProperties;
import org.apache.qpid.server.model.Broker;
import org.apache.qpid.server.model.NamedAddressSpace;
import org.apache.qpid.server.model.port.AmqpPort;
import org.apache.qpid.server.security.SubjectCreator;
import org.apache.qpid.server.security.auth.AuthenticationResult.AuthenticationStatus;
import org.apache.qpid.server.security.auth.SubjectAuthenticationResult;
import org.apache.qpid.server.security.auth.sasl.SaslNegotiator;
import org.apache.qpid.server.security.auth.sasl.SaslSettings;
import org.apache.qpid.server.transport.*;
import org.apache.qpid.server.util.ConnectionScopedRuntimeException;
import org.apache.qpid.server.virtualhost.VirtualHostUnavailableException;

/*

Method ConnectionDelegate.connectionClose(ServerConnection, ConnectionClose) is already overridden in class org.apache.qpid.server.protocol.v0_10.ServerConnectionDelegate. Method will not be pushed down to that class.



 */
public class ServerConnectionDelegate extends MethodDelegate<ServerConnection> implements ProtocolDelegate<ServerConnection>
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerConnectionDelegate.class);

    private List<Object> _locales;
    private List<Object> _mechanisms;

    private final Broker _broker;
    private int _maxNoOfChannels;
    private Map<String,Object> _clientProperties;
    private final SubjectCreator _subjectCreator;
    private int _maximumFrameSize;

    private boolean _compressionSupported;
    private volatile SaslNegotiator _saslNegotiator;

    public void control(ServerConnection conn, Method method)
    {
        method.dispatch(conn, this);
    }

    public void command(ServerConnection conn, Method method)
    {
        method.dispatch(conn, this);
    }

    public void error(ServerConnection conn, ProtocolError error)
    {
        conn.exception(new ConnectionException(error.getMessage()));
    }

    public void handle(ServerConnection conn, Method method)
    {
        conn.dispatch(method);
    }

    @Override public void connectionHeartbeat(ServerConnection conn, ConnectionHeartbeat hearbeat)
    {
        // do nothing
    }

    protected void sendConnectionCloseOkAndCloseSender(ServerConnection conn)
    {
        conn.connectionCloseOk();
        conn.getSender().close();
    }

    @Override public void connectionCloseOk(ServerConnection conn, ConnectionCloseOk ok)
    {
        conn.getSender().close();
    }

    @Override public void sessionDetached(ServerConnection conn, SessionDetached dtc)
    {
        ServerSession ssn = conn.getSession(dtc.getChannel());
        if (ssn != null)
        {
            ssn.setDetachCode(dtc.getCode());
            conn.unmap(ssn);
            ssn.closed();
        }
    }

    public void writerIdle(final ServerConnection connection)
    {
        connection.doHeartBeat();
    }

    enum ConnectionState
    {
        INIT,
        AWAIT_START_OK,
        AWAIT_SECURE_OK,
        AWAIT_TUNE_OK,
        AWAIT_OPEN,
        OPEN
    }

    private volatile ConnectionState _state = ConnectionState.INIT;
    private volatile SubjectAuthenticationResult _successfulAuthenticationResult;


    public ServerConnectionDelegate(Broker<?> broker, SubjectCreator subjectCreator)
    {
        this(createConnectionProperties(broker), Collections.singletonList((Object)"en_US"), broker, subjectCreator);
    }

    private ServerConnectionDelegate(Map<String, Object> properties,
                                     List<Object> locales,
                                     Broker<?> broker,
                                     SubjectCreator subjectCreator)
    {
        _clientProperties = properties;
        _mechanisms = (List) subjectCreator.getMechanisms();
        _locales = locales;

        _broker = broker;
        _maxNoOfChannels = broker.getConnection_sessionCountLimit();
        _subjectCreator = subjectCreator;
        _maximumFrameSize = Math.min(0xffff, broker.getNetworkBufferSize());
    }


    public final ConnectionState getState()
    {
        return _state;
    }


    private void assertState(final ServerConnection conn, final ConnectionState requiredState)
    {
        if(_state != requiredState)
        {
            String replyText = "Command Invalid, expected " + requiredState + " but was " + _state;
            conn.sendConnectionClose(ConnectionCloseCode.FRAMING_ERROR, replyText);
            conn.closeAndIgnoreFutureInput();
            throw new ConnectionScopedRuntimeException(replyText);
        }
    }

    @Override
    public void init(final ServerConnection conn, final ProtocolHeader hdr)
    {
        ServerConnection serverConnection = (ServerConnection) conn;
        assertState(serverConnection, ConnectionState.INIT);
        serverConnection.send(new ProtocolHeader(1, 0, 10));
        serverConnection.sendConnectionStart(_clientProperties, _mechanisms, _locales);
        _state = ConnectionState.AWAIT_START_OK;
    }

    private static List<String> getFeatures(Broker<?> broker)
    {
        String brokerDisabledFeatures = System.getProperty(Broker.PROPERTY_DISABLED_FEATURES);
        final List<String> features = new ArrayList<String>();
        if (brokerDisabledFeatures == null || !brokerDisabledFeatures.contains(ServerPropertyNames.FEATURE_QPID_JMS_SELECTOR))
        {
            features.add(ServerPropertyNames.FEATURE_QPID_JMS_SELECTOR);
        }

        return Collections.unmodifiableList(features);
    }

    private static Map<String, Object> createConnectionProperties(final Broker<?> broker)
    {
        final Map<String,Object> map = new HashMap<String,Object>();
        // Federation tag is used by the client to identify the broker instance
        map.put(ServerPropertyNames.FEDERATION_TAG, broker.getId().toString());
        final List<String> features = getFeatures(broker);
        if (features != null && features.size() > 0)
        {
            map.put(ServerPropertyNames.QPID_FEATURES, features);
        }

        map.put(ServerPropertyNames.PRODUCT, CommonProperties.getProductName());
        map.put(ServerPropertyNames.VERSION, CommonProperties.getReleaseVersion());
        map.put(ServerPropertyNames.QPID_BUILD, CommonProperties.getBuildVersion());
        map.put(ServerPropertyNames.QPID_INSTANCE_NAME, broker.getName());
        map.put(ConnectionStartProperties.QPID_MESSAGE_COMPRESSION_SUPPORTED, String.valueOf(broker.isMessageCompressionEnabled()));
        map.put(ConnectionStartProperties.QPID_VIRTUALHOST_PROPERTIES_SUPPORTED, String.valueOf(broker.isVirtualHostPropertiesNodeEnabled()));
        map.put(ConnectionStartProperties.QPID_QUEUE_LIFETIME_SUPPORTED, Boolean.TRUE.toString());

        return map;
    }

    @Override
    public void connectionSecureOk(final ServerConnection conn, final ConnectionSecureOk ok)
    {
        ServerConnection serverConnection = (ServerConnection) conn;
        assertState(serverConnection, ConnectionState.AWAIT_SECURE_OK);
        secure(serverConnection, ok.getResponse());
    }

    protected void secure(final ServerConnection sconn, final byte[] response)
    {
        SubjectAuthenticationResult authResult = _successfulAuthenticationResult;
        byte[] challenge = null;
        if (authResult == null)
        {
            authResult = _subjectCreator.authenticate(_saslNegotiator, response);
            challenge = authResult.getChallenge();
        }

        if (AuthenticationStatus.SUCCESS.equals(authResult.getStatus()))
        {
            _successfulAuthenticationResult = authResult;
            if (challenge == null || challenge.length == 0)
            {
                sconn.sendConnectionTune(getChannelMax(), getFrameMax(), 0, getHeartbeatMax());
                sconn.setAuthorizedSubject(authResult.getSubject());
                _state = ConnectionState.AWAIT_TUNE_OK;
                disposeSaslNegotiator();
            }
            else
            {
                sconn.sendConnectionSecure(authResult.getChallenge());
                _state = ConnectionState.AWAIT_SECURE_OK;
            }
        }
        else if (AuthenticationStatus.CONTINUE.equals(authResult.getStatus()))
        {
            sconn.sendConnectionSecure(authResult.getChallenge());
            _state = ConnectionState.AWAIT_SECURE_OK;
        }
        else
        {
            connectionAuthFailed(sconn, authResult.getCause());
        }
    }

    @Override
    public void connectionClose(ServerConnection conn, ConnectionClose close)
    {
        final ServerConnection sconn = (ServerConnection) conn;
        sconn.closeCode(close);
        sconn.setState(CLOSE_RCVD);
        sendConnectionCloseOkAndCloseSender(conn);
    }


    public void connectionOpen(ServerConnection conn, ConnectionOpen open)
    {
        final ServerConnection sconn = (ServerConnection) conn;
        assertState(sconn, ConnectionState.AWAIT_OPEN);
        NamedAddressSpace addressSpace;
        String vhostName;
        if(open.hasVirtualHost())
        {
            vhostName = open.getVirtualHost();
        }
        else
        {
            vhostName = "";
        }

        AmqpPort port = (AmqpPort) sconn.getPort();
        addressSpace = port.getAddressSpace(vhostName);



        if(addressSpace != null)
        {
            if (!addressSpace.isActive())
            {
                sconn.setState(ServerConnection.State.CLOSING);
                final String redirectHost = addressSpace.getRedirectHost(port);
                if(redirectHost == null)
                {
                    sconn.sendConnectionClose(ConnectionCloseCode.CONNECTION_FORCED,
                                                     "Virtual host '" + vhostName + "' is not active");
                }
                else
                {
                    sconn.invoke(new ConnectionRedirect(redirectHost, new ArrayList<Object>()));
                }
                return;
            }

            try
            {
                sconn.setVirtualHost(addressSpace);
                if(!addressSpace.authoriseCreateConnection(sconn.getAmqpConnection()))
                {
                    sconn.setState(ServerConnection.State.CLOSING);
                    sconn.sendConnectionClose(ConnectionCloseCode.CONNECTION_FORCED, "Connection not authorized");
                    return;
                }
            }
            catch (AccessControlException | VirtualHostUnavailableException e)
            {
                sconn.setState(ServerConnection.State.CLOSING);
                sconn.sendConnectionClose(ConnectionCloseCode.CONNECTION_FORCED, e.getMessage());
                return;
            }

            sconn.setState(ServerConnection.State.OPEN);
            _state = ConnectionState.OPEN;
            sconn.invoke(new ConnectionOpenOk(Collections.emptyList()));
        }
        else
        {
            sconn.setState(ServerConnection.State.CLOSING);
            sconn.sendConnectionClose(ConnectionCloseCode.INVALID_PATH,
                                             "Unknown virtualhost '" + vhostName + "'");
        }

    }

    @Override
    public void connectionTuneOk(final ServerConnection conn, final ConnectionTuneOk ok)
    {
        ServerConnection sconn = (ServerConnection) conn;
        assertState(sconn, ConnectionState.AWAIT_TUNE_OK);
        int okChannelMax = ok.getChannelMax();
        int okMaxFrameSize = ok.getMaxFrameSize();

        if (okChannelMax > getChannelMax())
        {
            LOGGER.error("Connection '" + sconn.getConnectionId() + "' being severed, " +
                    "client connectionTuneOk returned a channelMax (" + okChannelMax +
                    ") above the server's offered limit (" + getChannelMax() +")");

            //Due to the error we must forcefully close the connection without negotiation
            sconn.closeAndIgnoreFutureInput();
            return;
        }

        if(okMaxFrameSize > getFrameMax())
        {
            LOGGER.error("Connection '" + sconn.getConnectionId() + "' being severed, " +
                         "client connectionTuneOk returned a frameMax (" + okMaxFrameSize +
                         ") above the server's offered limit (" + getFrameMax() +")");

            //Due to the error we must forcefully close the connection without negotiation
            sconn.closeAndIgnoreFutureInput();

            return;
        }
        else if(okMaxFrameSize > 0 && okMaxFrameSize < Constant.MIN_MAX_FRAME_SIZE)
        {
            LOGGER.error("Connection '" + sconn.getConnectionId() + "' being severed, " +
                         "client connectionTuneOk returned a frameMax (" + okMaxFrameSize +
                         ") below the minimum permitted size (" + Constant.MIN_MAX_FRAME_SIZE +")");

            //Due to the error we must forcefully close the connection without negotiation
            sconn.closeAndIgnoreFutureInput();
            return;
        }
        else if(okMaxFrameSize == 0)
        {
            okMaxFrameSize = getFrameMax();
        }

        if(ok.hasHeartbeat() && ok.getHeartbeat() > 0)
        {
            int heartbeat = ok.getHeartbeat();
            long readerIdle = 2000L * heartbeat;
            long writerIdle = 1000L * heartbeat;
            sconn.getAmqpConnection().initialiseHeartbeating(writerIdle, readerIdle);
        }

        //0 means no implied limit, except available server resources
        //(or that forced by protocol limitations [0xFFFF])
        sconn.setChannelMax(okChannelMax == 0 ? getChannelMax() : okChannelMax);
        sconn.setMaxFrameSize(okMaxFrameSize);
        _state = ConnectionState.AWAIT_OPEN;
    }

    private int getChannelMax()
    {
        return _maxNoOfChannels;
    }

    private int getFrameMax()
    {
        return _maximumFrameSize;
    }

    @Override
    public void sessionDetach(ServerConnection conn, SessionDetach dtc)
    {
        // To ensure a clean detach, we stop any remaining subscriptions. Stop ensures
        // that any in-progress delivery (QueueRunner) is completed before the stop
        // completes.
        stopAllSubscriptions(conn, dtc);
        ServerSession ssn = conn.getSession(dtc.getChannel());
        ((ServerSession)ssn).setClose(true);
        sessionDetachSuper(conn, dtc);
    }

    private void sessionDetachSuper(ServerConnection conn, SessionDetach dtc)
    {
        ServerSession ssn = conn.getSession(dtc.getChannel());
        ssn.sessionDetached(dtc.getName(), ssn.getDetachCode() == null? SessionDetachCode.NORMAL: ssn.getDetachCode());
        conn.unmap(ssn);
        ssn.closed();
    }

    private void stopAllSubscriptions(ServerConnection conn, SessionDetach dtc)
    {
        final ServerSession ssn = (ServerSession) conn.getSession(dtc.getChannel());
        final Collection<ConsumerTarget_0_10> subs = ssn.getSubscriptions();
        for (ConsumerTarget_0_10 subscription_0_10 : subs)
        {
            subscription_0_10.stop();
        }
    }


    @Override
    public void sessionAttach(final ServerConnection conn, final SessionAttach atc)
    {
        ServerConnection serverConnection = (ServerConnection) conn;
        assertState(serverConnection, ConnectionState.OPEN);

        ServerSessionDelegate serverSessionDelegate = new ServerSessionDelegate();

        final ServerSession serverSession =
                new ServerSession(conn, serverSessionDelegate, new Binary(atc.getName()), 0);
        final Session_0_10 session = new Session_0_10(((ServerConnection) conn).getAmqpConnection(), atc.getChannel(),
                                                      serverSession);
        session.create();
        serverSession.setModelObject(session);

        if(isSessionNameUnique(atc.getName(), conn))
        {
            serverConnection.map(serverSession, atc.getChannel());
            serverConnection.registerSession(serverSession);
            serverSession.sendSessionAttached(atc.getName());
            serverSession.setState(ServerSession.State.OPEN);
        }
        else
        {
            serverSession.invoke(new SessionDetached(atc.getName(), SessionDetachCode.SESSION_BUSY));
            serverSession.closed();
        }
    }

    private boolean isSessionNameUnique(final byte[] name, final ServerConnection conn)
    {
        final ServerConnection sconn = (ServerConnection) conn;
        final Principal authorizedPrincipal = sconn.getAuthorizedPrincipal();
        final String userId = authorizedPrincipal == null ? "" : authorizedPrincipal.getName();

        final Iterator<? extends org.apache.qpid.server.model.Connection<?>> connections =
                        ((ServerConnection)conn).getAddressSpace().getConnections().iterator();
        while(connections.hasNext())
        {
            final AMQPConnection<?> amqConnectionModel = (AMQPConnection<?>) connections.next();

            final String userName = amqConnectionModel.getAuthorizedPrincipal() == null
                    ? ""
                    : amqConnectionModel.getAuthorizedPrincipal().getName();
            if (userId.equals(userName) && amqConnectionModel.hasSessionWithName(name))
            {
                return false;
            }
        }
        return true;
    }

    @Override
    public void connectionStartOk(ServerConnection conn, ConnectionStartOk ok)
    {
        ServerConnection serverConnection = (ServerConnection)conn;
        assertState(serverConnection, ConnectionState.AWAIT_START_OK);
        _clientProperties = ok.getClientProperties();
        if(_clientProperties != null)
        {
            Object compressionSupported =
                    _clientProperties.get(ConnectionStartProperties.QPID_MESSAGE_COMPRESSION_SUPPORTED);
            if (compressionSupported != null)
            {
                _compressionSupported = Boolean.parseBoolean(String.valueOf(compressionSupported));

            }
            final AMQPConnection_0_10 protocolEngine = serverConnection.getAmqpConnection();
            protocolEngine.setClientId(getStringClientProperty(ConnectionStartProperties.CLIENT_ID_0_10));
            protocolEngine.setClientProduct(getStringClientProperty(ConnectionStartProperties.PRODUCT));
            protocolEngine.setClientVersion(getStringClientProperty(ConnectionStartProperties.VERSION_0_10));
            protocolEngine.setRemoteProcessPid(getStringClientProperty(ConnectionStartProperties.PID));
        }


        serverConnection.setLocale(ok.getLocale());
        String mechanism = ok.getMechanism();

        if (mechanism == null || mechanism.length() == 0)
        {
            serverConnection.sendConnectionClose(ConnectionCloseCode.CONNECTION_FORCED,
                                                 "No Sasl mechanism was specified");
            return;
        }

        _saslNegotiator = _subjectCreator.createSaslNegotiator(mechanism,
                                                               (SaslSettings) serverConnection.getAmqpConnection());
        if (_saslNegotiator == null)
        {
            serverConnection.sendConnectionClose(ConnectionCloseCode.CONNECTION_FORCED,
                                                 "No SaslServer could be created for mechanism: " + mechanism);
        }
        else
        {
            secure(serverConnection, ok.getResponse());
        }
    }

    private String getStringClientProperty(final String name)
    {
        return (_clientProperties == null || _clientProperties.get(name) == null) ? null : String.valueOf(_clientProperties.get(name));
    }

    public Map<String,Object> getClientProperties()
    {
        return _clientProperties;
    }

    public String getClientId()
    {
        return _clientProperties == null ? null : (String) _clientProperties.get(ConnectionStartProperties.CLIENT_ID_0_10);
    }

    public String getClientVersion()
    {
        return _clientProperties == null ? null : (String) _clientProperties.get(ConnectionStartProperties.VERSION_0_10);
    }

    public String getClientProduct()
    {
        return _clientProperties == null ? null : (String) _clientProperties.get(ConnectionStartProperties.PRODUCT);
    }

    public String getRemoteProcessPid()
    {
        return (_clientProperties == null || _clientProperties.get(ConnectionStartProperties.PID) == null) ? null : String.valueOf(_clientProperties.get(ConnectionStartProperties.PID));
    }

    protected int getHeartbeatMax()
    {
        int delay = (Integer)_broker.getAttribute(Broker.CONNECTION_HEART_BEAT_DELAY);
        return delay == 0 ? 0xFFFF : delay;
    }

    public boolean isCompressionSupported()
    {
        return _compressionSupported && _broker.isMessageCompressionEnabled();
    }

    private void connectionAuthFailed(final ServerConnection conn, Exception e)
    {
        ServerConnection serverConnection = (ServerConnection)conn;
        if (e != null)
        {
            serverConnection.exception(e);
        }
        serverConnection.sendConnectionClose(ConnectionCloseCode.CONNECTION_FORCED, e == null ? "Authentication failed" : e.getMessage());
        disposeSaslNegotiator();
    }

    private void disposeSaslNegotiator()
    {
        _saslNegotiator.dispose();
        _saslNegotiator = null;
    }
}
