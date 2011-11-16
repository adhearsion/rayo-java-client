package com.rayo.client;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.time.DateFormatUtils;

import com.rayo.client.auth.AuthenticationHandler;
import com.rayo.client.auth.AuthenticationListener;
import com.rayo.client.auth.SimpleAuthenticationHandler;
import com.rayo.client.filter.XmppObjectExtensionNameFilter;
import com.rayo.client.filter.XmppObjectFilter;
import com.rayo.client.filter.XmppObjectIdFilter;
import com.rayo.client.filter.XmppObjectNameFilter;
import com.rayo.client.io.SimpleXmppReader;
import com.rayo.client.io.SimpleXmppWriter;
import com.rayo.client.io.XmppReader;
import com.rayo.client.io.XmppWriter;
import com.rayo.client.listener.StanzaListener;
import com.rayo.client.response.FilterCleaningResponseHandler;
import com.rayo.client.response.ResponseHandler;
import com.rayo.client.xmpp.extensions.Extension;
import com.rayo.client.xmpp.stanza.Error;
import com.rayo.client.xmpp.stanza.Error.Condition;
import com.rayo.client.xmpp.stanza.Error.Type;
import com.rayo.client.xmpp.stanza.Stanza;
import com.rayo.client.xmpp.stanza.XmppObject;

public class SimpleXmppConnection implements XmppConnection {

	private XmppReader reader;
	private XmppWriter writer;
	private ConnectionConfiguration config;
	private Socket socket;
	private String serviceName;
	private String connectionId;
	
	private String username;
	private String resource;
	
	private AuthenticationHandler authenticationHandler;
	private boolean loggingIn;
	private boolean connected;
	
	private int DEFAULT_TIMEOUT = XmppObjectFilter.DEFAULT_TIMEOUT;
	
	private List<XmppConnectionListener> listeners = new ArrayList<XmppConnectionListener>();
	
	public SimpleXmppConnection(String serviceName) {
		
		this(serviceName, null);
	}
	
	public SimpleXmppConnection(String serviceName, Integer port) {
		
		this.serviceName = serviceName;
		
		//TODO: Lots of things to be handled. Security, compression, proxies. All already done in Smack. Reuse!!
		this.config = new ConnectionConfiguration(serviceName, port);
		
		authenticationHandler = new SimpleAuthenticationHandler(this);
		
		reader = new SimpleXmppReader();
	}
	
	@Override
	public ConnectionConfiguration getConfiguration() {
		
		return config;
	}
	
	@Override
	public boolean isConnected() {
		
		return connected;
	}
	
	@Override
	public boolean isAuthenticated() {

		return authenticationHandler.isAuthenticated();
	}
	
	@Override
	public void connect() throws XmppException {
		connect(5);
	}
	
	@Override
	public void connect(int timeout) throws XmppException {

        String host = config.getHostname();
        int port = config.getPort();
        try {
        	this.socket = new Socket(host, port);
        } catch (UnknownHostException uhe) {
            throw new XmppException(String.format("Could not connect to %s:%s",host,port), Error.Condition.remote_server_timeout);            
        } catch (IOException ioe) {
            throw new XmppException(String.format("Error while connecting to %s:%s",host,port), Error.Condition.service_unavailable, ioe);
        }
        initConnection(timeout);		
	}
	
	private void initConnection(int timeout) throws XmppException {

		if (connected) {
			return;
		}
		
		try {			
			initIO();
			initAuth();
			startReader(); // Blocks until we get an open stream
			final CountDownLatch latch = new CountDownLatch(1);
			
			XmppConnectionListener connectionListener =new  XmppConnectionAdapter() {
				@Override
				public void connectionEstablished(String connectionId) {
					connected = true;
					latch.countDown();
				}
			};			
			reader.addXmppConnectionListener(connectionListener);
			openStream();
			
			try {
				latch.await(timeout, TimeUnit.SECONDS);
			} catch (InterruptedException e1) {
			}
			reader.removeXmppConnectionListener(connectionListener);
			
			if (!connected) {
				throw new XmppException(new Error(Condition.service_unavailable, Type.cancel, "Could not connect to server."));
			}
			//TODO: Keep alive

			// Wait a little bit to let connection id get populated on the listener
			try {
				Thread.sleep(150);
			} catch (Exception e) {}

		} catch (XmppException xmpe) {
			disconnect();
			throw xmpe;
		}
	}
	
	private void initAuth() {

        addAuthenticationListener(authenticationHandler);
	}

	@Override
	public void disconnect() throws XmppException {

		if (!connected) {
			return;
		}
		
		connected = false;
				
		if (writer != null) { writer.close(); }
		if (reader != null) { reader.close(); }
		
		// We close the socket first as otherwise closing the reader may enter a deadlock with the
		// threads that are listening for socket data, specially if there is no incoming activity 
		// from the socket
		// TODO: Check if implementing keep alive solves this issue
		try {
			System.out.println("Closing XMPP socket connection");
			socket.close();
		} catch (IOException e) {
			e.printStackTrace();
		}

		// Wait a little bit for cleanup
		try {
			Thread.sleep(150);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
				
		cleanup();
	}
	
	@Override
	public void send(XmppObject object) throws XmppException {

		if (object == null) {
			return;
		}
		if (!connected) {
			throw new XmppException(new Error(Condition.service_unavailable, Type.cancel, "Not connected to the server. You need to connect first."));
		}
		if (!loggingIn && !authenticationHandler.isAuthenticated()) {
			throw new XmppException(new Error(Condition.not_authorized, Type.cancel, "Not authenticated. You need to authenticate first."));			
		}
    	System.out.println(String.format("[OUT] [%s] [%s]", DateFormatUtils.format(new Date(),"hh:mm:ss.SSS"),object));
		writer.write(object);
		
		for (XmppConnectionListener listener: listeners) {
			listener.messageSent(object);
		}
	}

	@Override
	public void send(XmppObject object, ResponseHandler handler) throws XmppException {

		// This wrapping response handler will remove the filter once we get the result from the server
		// This helps to clean up resources
		FilterCleaningResponseHandler filterHandler = new FilterCleaningResponseHandler(handler,this);
		XmppObjectIdFilter filter = new XmppObjectIdFilter(object.getId(), filterHandler);
		filterHandler.setFilter(filter);
        addFilter(filter);
        send(object);
	}
	
	@Override
	public XmppObject sendAndWait(XmppObject object) throws XmppException {

		return sendAndWait(object, DEFAULT_TIMEOUT);
	}
	
	@Override
	public XmppObject sendAndWait(XmppObject object, int timeout) throws XmppException {

		XmppObjectIdFilter filter = new XmppObjectIdFilter(object.getId());
        addFilter(filter);
        send(object);
        XmppObject response = filter.poll(timeout);
        removeFilter(filter);
        return response;
	}	
	
	private void openStream() throws XmppException {

		writer.openStream(serviceName);
	}

	private void startReader() throws XmppException {
		
		reader.addXmppConnectionListener(new XmppConnectionAdapter() {
			
			@Override
			public void connectionReset(String connectionId) {

				try {
					openStream();
				} catch (XmppException e) {
					e.printStackTrace();
				}
			}
			
			@Override
			public void connectionEstablished(String connectionId) {

				setConnectionId(connectionId);
			}
			
			@Override
			public void connectionError(String connectionId, Exception e) {

				try {
					disconnect();
				} catch (XmppException xe) {
					xe.printStackTrace();
				}
			}
		});
		
		reader.start();
	}

	private void initIO() throws XmppException {

		try {
	        reader.init(new BufferedReader(
	        		new InputStreamReader(socket.getInputStream(), "UTF-8")));
	        writer = new SimpleXmppWriter(new BufferedWriter(
	        		new OutputStreamWriter(socket.getOutputStream(), "UTF-8")));
		} catch (IOException ioe) {
			throw new XmppException("Could not initialise IO system", Error.Condition.remote_server_error, ioe);
		}
	}

	@Override
	public void login(String username, String password, String resourceName) throws XmppException {
		login(username, password, resourceName, 5);
	}

	@Override
	public void login(String username, String password, String resourceName, int timeout) throws XmppException {

		loggingIn = true;
		authenticationHandler.login(username, password, resourceName, timeout);
		
		loggingIn = false;
		this.username = username;
		this.resource = resourceName;
	}

	@Override
	public XmppObject waitFor(String node) throws XmppException {

		return waitFor(node, DEFAULT_TIMEOUT);
	}
	
	@Override
	public XmppObject waitFor(String node, Integer timeout) throws XmppException {

		XmppObjectNameFilter filter = null;
		try {
			filter = new XmppObjectNameFilter(node);
	        addFilter(filter);
	
	        XmppObject response = null;
	        if (timeout != null) {
	        	response = filter.poll(timeout);
	        } else {
	        	response = filter.poll();
	        }
	        if (response == null) {
	        	throw new XmppException(String.format("Timed out while waiting for [%s]",node));
	        }	        
	        return response;
		} finally {
			removeFilter(filter);
		}

	}

	@Override
	public Extension waitForExtension(String extensionName) throws XmppException {

		return waitForExtension(extensionName, DEFAULT_TIMEOUT);
	}
	
	@Override
	public Extension waitForExtension(String extensionName, Integer timeout) throws XmppException {

		XmppObjectExtensionNameFilter filter = null;
		try {
			filter = new XmppObjectExtensionNameFilter(extensionName);
	        addFilter(filter);
	
	        XmppObject response = null;
	        if (timeout != null) {
	        	response = filter.poll(timeout);
	        } else {
	        	response = filter.poll();
	        }
	        if (response == null) {
	        	throw new XmppException(String.format("Timed out while waiting for [%s]",extensionName));
	        }
	        return ((Stanza<?>)response).getExtension();
		} finally {
			removeFilter(filter);
		}

	}
	
    private void cleanup() {
    	
    	config = null;
    	socket = null;
    	reader = null;
    	writer = null;
    	connectionId = null;
    	serviceName = null;
    	username = null;
    	resource = null;
    	connected = false;
    	loggingIn = false;
    }
    
    @Override
    public void addStanzaListener(StanzaListener stanzaListener) {

    	if (reader != null) {
    		reader.addStanzaListener(stanzaListener);
    	}
    }
    
    @Override
    public void removeStanzaListener(StanzaListener stanzaListener) {
    	
    	if (reader != null) {
    		reader.removeStanzaListener(stanzaListener);
    	}
    }
    
    @Override
    public void addAuthenticationListener(AuthenticationListener authListener) {

    	if (reader != null) {
    		reader.addAuthenticationListener(authListener);
    	}
    }
    
    @Override
    public void removeAuthenticationListener(AuthenticationListener authListener) {
    	
    	if (reader != null) {
    		reader.removeAuthenticationListener(authListener);
    	}
    }
    
    @Override
    public void addXmppConnectionListener(XmppConnectionListener connectionListener) {

    	// Bad smell. Two lists with almost the same listeners. This needs refactoring
    	listeners.add(connectionListener);
    	reader.addXmppConnectionListener(connectionListener);
    }
    
    @Override
    public void removeXmppConnectionListener(XmppConnectionListener connectionListener) {

    	// Bad smell. Two lists with almost the same listeners. This needs refactoring
    	listeners.remove(connectionListener);
    	reader.removeXmppConnectionListener(connectionListener);
    }
    
    @Override
    public void addFilter(XmppObjectFilter filter) {

    	if (reader != null) {
    		reader.addFilter(filter);
    	}
    }
    
    @Override
    public void removeFilter(XmppObjectFilter filter) {

    	if (reader != null) {
    		reader.removeFilter(filter);
    	}
    }   
    
    void setConnectionId(String connectionId) {
    	
    	this.connectionId = connectionId;
    }
    
    @Override
    public String getConnectionId() {
    	
    	return connectionId;
    }
    
    @Override
    public String getServiceName() {

    	return serviceName;
    }
    
    @Override
    public String getResource() {

    	return resource;
    }
    
    @Override
    public String getUsername() {

    	return username;
    }
    
    public void setDefaultTimeout(int timeout) {
    	
    	this.DEFAULT_TIMEOUT = timeout;
    }
}
