/**
 * $RCSfile$
 * $Revision: $
 * $Date: $
 *
 * Copyright 2003-2007 Jive Software.
 *
 * All rights reserved. Licensed under the Apache License, Version 2.0 (the "License");
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

package com.rayo.client.auth;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.PasswordCallback;

import com.rayo.client.XmppConnection;
import com.rayo.client.XmppException;
import com.rayo.client.filter.XmppObjectFilter;
import com.rayo.client.filter.XmppObjectIdFilter;
import com.rayo.client.xmpp.stanza.Authentication;
import com.rayo.client.xmpp.stanza.IQ;

/**
 * Implementation of JEP-0078: Non-SASL Authentication. Follow the following
 * <a href=http://www.jabber.org/jeps/jep-0078.html>link</a> to obtain more
 * information about the JEP.
 *
 * @author Gaston Dombiak
 */
class NonSASLAuthentication implements UserAuthentication {

    private XmppConnection connection;

    public NonSASLAuthentication(XmppConnection connection) {
    	
        super();
        this.connection = connection;
    }

    public String authenticate(String username, String resource, CallbackHandler cbh, int timeout) throws XmppException {
    	
        //Use the callback handler to determine the password, and continue on.
        PasswordCallback pcb = new PasswordCallback("Password: ",false);
        try {
            cbh.handle(new Callback[]{pcb});
            return authenticate(username, String.valueOf(pcb.getPassword()),resource, timeout);
        } catch (Exception e) {
            throw new XmppException("Unable to determine password.",e);
        }   
    }

    public String authenticate(String username, String password, String resource, int timeout) throws XmppException {
    	
        // If we send an authentication packet in "get" mode with just the username,
        // the server will return the list of authentication protocols it supports.
        Authentication discoveryAuth = new Authentication();
        discoveryAuth.setType(IQ.Type.get);
        discoveryAuth.setUsername(username);

        XmppObjectFilter filter = new XmppObjectIdFilter(discoveryAuth.getId());
        connection.addFilter(filter);
        connection.send(discoveryAuth);
        
        // Wait up to a certain number of seconds for a response from the server.
        IQ response = (IQ)filter.poll(5000);
        if (response == null) {
            throw new XmppException("No response from the server.");
        }
        // If the server replied with an error, throw an exception.
        else if (response.getType() == IQ.Type.error) {
            throw new XmppException(response.getError());
        }
        // Otherwise, no error so continue processing.
        Authentication authTypes = (Authentication) response;
        filter.stop();

        // Now, create the authentication packet we'll send to the server.
        Authentication auth = new Authentication();
        auth.setUsername(username);

        // Figure out if we should use digest or plain text authentication.
        if (authTypes.getDigest() != null) {
            auth.setDigest(connection.getConnectionId(), password);
        }
        else if (authTypes.getPassword() != null) {
            auth.setPassword(password);
        }
        else {
            throw new XmppException("Server does not support compatible authentication mechanism.");
        }

        auth.setResource(resource);

        filter = new XmppObjectIdFilter(auth.getId());
        connection.addFilter(filter);
        connection.send(auth);
        // Wait up to a certain number of seconds for a response from the server.
        response = (IQ) filter.poll(5000);
        if (response == null) {
            throw new XmppException("Authentication failed.");
        }
        else if (response.getType() == IQ.Type.error) {
            throw new XmppException(response.getError());
        }
        filter.stop();

        return response.getTo();
    }

    public String authenticateAnonymously() throws XmppException {
    	
        // Create the authentication packet we'll send to the server.
        Authentication auth = new Authentication();

        XmppObjectFilter filter = new XmppObjectIdFilter(auth.getId());
        connection.addFilter(filter);
        connection.send(auth);
        // Wait up to a certain number of seconds for a response from the server.
        IQ response = (IQ) filter.poll(5000);
        if (response == null) {
            throw new XmppException("Anonymous login failed.");
        }
        else if (response.getType() == IQ.Type.error) {
            throw new XmppException(response.getError());
        }

        filter.stop();

        if (response.getTo() != null) {
            return response.getTo();
        }
        else {
            return connection.getServiceName() + "/" + ((Authentication) response).getResource();
        }
    }
    
    @Override
    public void authenticated() {}
    
    @Override
    public void bindingRequired() {}
    
    @Override
    public void sessionsSupported() {}
}
