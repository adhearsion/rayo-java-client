package com.rayo.client.io;

import java.io.Reader;
import java.net.SocketException;
import java.util.Collection;
import java.util.Date;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.lang.time.DateFormatUtils;
import org.xmlpull.mxp1.MXParser;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import com.rayo.client.XmppConnectionListener;
import com.rayo.client.auth.AuthenticationListener;
import com.rayo.client.filter.XmppObjectFilter;
import com.rayo.client.listener.StanzaListener;
import com.rayo.client.util.XmppObjectParser;
import com.rayo.client.xmpp.stanza.AbstractXmppObject;
import com.rayo.client.xmpp.stanza.Error;
import com.rayo.client.xmpp.stanza.Error.Condition;
import com.rayo.client.xmpp.stanza.Error.Type;
import com.rayo.client.xmpp.stanza.IQ;
import com.rayo.client.xmpp.stanza.Message;
import com.rayo.client.xmpp.stanza.Presence;
import com.rayo.client.xmpp.stanza.XmppObject;
import com.rayo.client.xmpp.stanza.sasl.Challenge;
import com.rayo.client.xmpp.stanza.sasl.Success;

public class XmppReaderWorker implements Runnable {
	
	private XmlPullParser parser;
	private String connectionId;
	
	private boolean done;
	
	private Reader reader;
	
	private Collection<XmppConnectionListener> listeners = new ConcurrentLinkedQueue<XmppConnectionListener>();
	private Collection<StanzaListener> stanzaListeners = new ConcurrentLinkedQueue<StanzaListener>();
	private Collection<AuthenticationListener> authListeners = new ConcurrentLinkedQueue<AuthenticationListener>();
	private Collection<XmppObjectFilter> filters = new ConcurrentLinkedQueue<XmppObjectFilter>();
	
	private ExecutorService executorService;
	
	public XmppReaderWorker() {
		
		executorService = Executors.newCachedThreadPool();
	}
	
	@Override
	public void run() {

		parse();
	}
	
	public void addXmppConnectionListener(XmppConnectionListener listener) {

		listeners.add(listener);
	}
	
	public void removeXmppConnectionListener(XmppConnectionListener listener) {

		listeners.remove(listener);
	}
	
	public void addStanzaListener(StanzaListener listener) {
		
		stanzaListeners.add(listener);
	}
	
	public void removeStanzaListener(StanzaListener listener) {
		
		stanzaListeners.remove(listener);
	}
	
    public void addAuthenticationListener(AuthenticationListener authListener) {

    	authListeners.add(authListener);
    }
    
    public void removeAuthenticationListener(AuthenticationListener authListener) {
    	
    	authListeners.remove(authListener);
    }
    
    public void addFilter(XmppObjectFilter filter) {

    	filters.add(filter);
    }
    
    public void removeFilter(XmppObjectFilter filter) {

    	filters.remove(filter);
    }  
	
    public void resetParser(Reader reader) {
    	
        try {
        	this.reader = reader;
            parser = new MXParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
            parser.setInput(reader);
        }
        catch (XmlPullParserException xppe) {
            xppe.printStackTrace();
        }
    }
    
    /**
     * Parse top-level packets in order to process them further.
     *
     * @param thread the thread that is being used by the reader to parse incoming packets.
     */
    private void parse() {
    	
        try {
            int eventType = parser.getEventType();            
            do {
                if (eventType == XmlPullParser.START_TAG) {
                    if (parser.getName().equals("message")) {
                    	final Message message = XmppObjectParser.parseMessage(parser);
                    	log(message);
                    	for (final StanzaListener listener: stanzaListeners) {
            	    		executorService.execute(new Runnable() {								
            					@Override
            					public void run() {
                            		try {
                            			listener.onMessage(message);
        	                		} catch (Exception e) {
        	                			e.printStackTrace();
        	                			handleError(new Error(Condition.undefined_condition, Type.cancel, String.format("Error on client listener: %s - %s",e.getClass(),e.getMessage())));  
        	                		}                    		
            					}
            				});                    		
                    	}
                    	filter(message);
                    } else if (parser.getName().equals("iq")) {
                    	final IQ iq = XmppObjectParser.parseIQ(parser);
                    	if (iq.hasChild("error")) {
                    		handleError(iq.getError());
                    	}
                    	log(iq);
                    	for (final StanzaListener listener: stanzaListeners) {
            	    		executorService.execute(new Runnable() {								
            					@Override
            					public void run() {
                            		try {
                            			listener.onIQ(iq);
                            		} catch (Exception e) {
                            			e.printStackTrace();
                            			handleError(new Error(Condition.undefined_condition, Type.cancel, String.format("Error on client listener: %s - %s",e.getClass(),e.getMessage())));  
                            		}                 		
            					}
            				});                    		
                    	}                    	
                    	filter(iq);
                    } else if (parser.getName().equals("presence")) {
                    	final Presence presence = XmppObjectParser.parsePresence(parser);
                    	log(presence);
                    	for (final StanzaListener listener: stanzaListeners) {
            	    		executorService.execute(new Runnable() {								
            					@Override
            					public void run() {
                            		try {
                            			listener.onPresence(presence);
        	                		} catch (Exception e) {
        	                			e.printStackTrace();
                            			handleError(new Error(Condition.undefined_condition, Type.cancel, String.format("Error on client listener: %s - %s",e.getClass(),e.getMessage())));  
        	                		}                   		
            					}
            				});                    		
                    	}
                    	filter(presence);
                    }
                    // We found an opening stream. Record information about it, then notify
                    // the connectionID lock so that the packet reader startup can finish.
                    else if (parser.getName().equals("stream")) {
                        // Ensure the correct jabber:client namespace is being used.
                        if ("jabber:client".equals(parser.getNamespace(null))) {
                            // Get the connection id.
                            for (int i=0; i<parser.getAttributeCount(); i++) {
                                if (parser.getAttributeName(i).equals("id")) {
                                    // Save the connectionID
                                	connectionId = parser.getAttributeValue(i);
                                	log("Received new connection stream with id: " + connectionId);
                                    if (!"1.0".equals(parser.getAttributeValue("", "version"))) {
                                        // Notify that a stream has been opened if the
                                        // server is not XMPP 1.0 compliant otherwise make the
                                        // notification after TLS has been negotiated or if TLS
                                        // is not supported
                                    	connectionEstablished();
                                    }
                                }
                                else if (parser.getAttributeName(i).equals("from")) {

                                }
                            }
                        }
                    }
                    else if (parser.getName().equals("error")) {
                    	Error error = XmppObjectParser.parseError(parser);
                    	log(error);
                    	filter(error);
                    	handleError(error);
                    }
                    else if (parser.getName().equals("features")) {
                    	log("Received features");
                    	parseFeatures(parser);
                    }
                    else if (parser.getName().equals("proceed")) {

                    }
                    else if (parser.getName().equals("failure")) {

                    }
                    else if (parser.getName().equals("challenge")) {
                    	final Challenge challenge = new Challenge().setText(parser.nextText());
                    	for (final AuthenticationListener listener: authListeners) {
	        	    		executorService.execute(new Runnable() {
	        	    			@Override
	        	    			public void run() {
	                	    		listener.authChallenge(challenge);
	        	    			}
	        	    		});
                    	}
                    }
                    else if (parser.getName().equals("success")) {
                    	final Success success = new Success().setText(parser.nextText());
                    	log(success);
                    	for (final AuthenticationListener listener: authListeners) {
            	    		executorService.execute(new Runnable() {
            	    			@Override
            	    			public void run() {
                    	    		listener.authSuccessful(success);
            	    			}
            	    		});                    		
                    	}
                    	
                    	filter(success);

                    	// We now need to bind a resource for the connection
                        // Open a new stream and wait for the response
                    	for (final XmppConnectionListener listener: listeners) {
            	    		executorService.execute(new Runnable() {								
								@Override
								public void run() {
		            	    		listener.connectionReset(connectionId);									
								}
							});
                    		
                    	}

                        // Reset the state of the parser since a new stream element is going
                        // to be sent by the server
                    	resetParser(reader);                    	
                    	
                    }
                    else if (parser.getName().equals("compressed")) {

                    }
                }
                else if (eventType == XmlPullParser.END_TAG) {
                    if (parser.getName().equals("stream")) {
                        // Disconnect the connection
            	    	for (final XmppConnectionListener listener: listeners) {
            	    		executorService.execute(new Runnable() {								
								@Override
								public void run() {
		            	    		listener.connectionFinished(connectionId);									
								}
							});
            	    	}
                    }
                }
                if (parser == null) {
                	log("Parser is null. Exiting.");
                	done = true;
                }
                eventType = parser.next();
            } while (!done && eventType != XmlPullParser.END_DOCUMENT);
        } catch (SocketException se) {
        	if (!done) {
            	se.printStackTrace();
                handleError(new Error(Condition.gone, Type.cancel, se.getMessage()));        		
        	}
        } catch (Exception e) {        	
        	e.printStackTrace();    
        	handleError(new Error(Condition.undefined_condition, Type.cancel, e.getMessage()));
        }
    }


    private void filter(final AbstractXmppObject object) {

		executorService.execute(new Runnable() {								
			@Override
			public void run() {
		    	for (XmppObjectFilter filter: filters) {		    		
		    		try {
		    			filter.filter(object);
					} catch (Exception e) {
						e.printStackTrace();
		    			handleError(new Error(Condition.undefined_condition, Type.cancel, String.format("Error on client filter: %s - %s",e.getClass(),e.getMessage())));  
					}    		
		    	}                  		
			}
		}); 
	}

	private void parseFeatures(XmlPullParser parser) throws Exception {
    	
        boolean startTLSReceived = false;
        boolean startTLSRequired = false;
        boolean done = false;
        while (!done) {
            int eventType = parser.next();

            if (eventType == XmlPullParser.START_TAG) {
                if (parser.getName().equals("starttls")) {
                    startTLSReceived = true;
                }
                else if (parser.getName().equals("mechanisms")) {
                	log("Received mechanisms");
                    // The server is reporting available SASL mechanisms. Store this information
                    // which will be used later while logging (i.e. authenticating) into
                    // the server
                	final Collection<String> mechanisms = XmppObjectParser.parseMechanisms(parser);
        	    	for (final AuthenticationListener listener: authListeners) {
        	    		executorService.execute(new Runnable() {
        	    			@Override
        	    			public void run() {
                	    		listener.authSettingsReceived(mechanisms);
        	    			}
        	    		});
        	    	}
                }
                else if (parser.getName().equals("bind")) {
                	log("Received bind");                	
        	    	for (final AuthenticationListener listener: authListeners) {
                    	log("Sending bind task to executors");                	
        	    		executorService.execute(new Runnable() {
        	    			@Override
        	    			public void run() {
        	                	log("Calling authentication listener");                	
                	    		listener.authBindingRequired();
        	    			}
        	    		});        	    	
        	    	}
                }
                else if (parser.getName().equals("session")) {
                	log("Received session");
        	    	for (final AuthenticationListener listener: authListeners) {
        	    		executorService.execute(new Runnable() {
        	    			@Override
        	    			public void run() {
                	    		listener.authSessionsSupported();
        	    			}
        	    		}); 
        	    	}
                }
                else if (parser.getName().equals("compression")) {
                    // The server supports stream compression

                }
                else if (parser.getName().equals("register")) {

                }
            }
            else if (eventType == XmlPullParser.END_TAG) {
                if (parser.getName().equals("starttls")) {
                    // Confirm the server that we want to use TLS

                }
                else if (parser.getName().equals("required") && startTLSReceived) {
                    startTLSRequired = true;
                }
                else if (parser.getName().equals("features")) {
                    done = true;
                }
            }
        }
        
        //TODO: Lots of stuff to handle here. Code based in Packet reader from Smack
        
        // Release the lock after TLS has been negotiated or we are not insterested in TLS
        if (!startTLSReceived || (startTLSReceived && !startTLSRequired)) {
        	connectionEstablished();
        }
    }
    
    private void connectionEstablished() {
    	
    	if (connectionId != null) {
	    	for (final XmppConnectionListener listener: listeners) {
	    		executorService.execute(new Runnable() {								
					@Override
					public void run() {
        	    		listener.connectionEstablished(connectionId);									
					}
				});
	    	}
    	}
    }
    
    private void connectionFinished() {
    	
    	if (connectionId != null) {
	    	for (final XmppConnectionListener listener: listeners) {
	    		executorService.execute(new Runnable() {								
					@Override
					public void run() {
        	    		listener.connectionFinished(connectionId);									
					}
				});	    		
	    	}
    	}
    }
    
    void handleError(Error e) {
    	
    	for (StanzaListener listener: stanzaListeners) {
    		try {
    			listener.onError(e);
    		} catch (Exception ex) {
    			ex.printStackTrace();
    		}    			
    	}
    }

	public void setDone(boolean done) {
		
		this.done = done;
		connectionFinished();
	}
	
	public void reset() {
		
		resetParser(reader);
		cleanListeners();
	}

	public void shutdown() {
		
		reader = null;
		parser = null;
		connectionId = null;
		cleanListeners();
	}
	
	private void cleanListeners() {
		
		listeners.clear();
		stanzaListeners.clear();
		authListeners.clear();
		filters.clear();
	};
    
    public String getConnectionId() {
    	
    	return connectionId;
    }
    
    private void log(XmppObject object) {
    	
    	log(object.toString());
    }
    
    private void log(String value) {
    	
    	System.out.println(String.format("[IN ] [%s] [%s]", DateFormatUtils.format(new Date(),"hh:mm:ss.SSS"),value));
    }
}
