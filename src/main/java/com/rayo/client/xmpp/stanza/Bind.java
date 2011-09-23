package com.rayo.client.xmpp.stanza;

import com.rayo.client.xmpp.Namespaces;


public class Bind extends AbstractXmppObject {

	public static final String NAME = "bind";
	
	public Bind() {
		
		super(Namespaces.BIND);
	}
	
	public String getResource() {
		
		return value("resource");
	}
	
	public Bind setResource(String resource) {
		
		set("resource", resource);
		return this;
	}

	public String getJID() {
		
		return value("jid");
	}
	
	public Bind setJID(String jid) {
		
		set("jid", jid);
		return this;
	}
	
	@Override
	public String getStanzaName() {

		return NAME;
	}
	
	@Override
	public Bind copy() {

		Bind bind = new Bind();
		bind.copy(this);
		return bind;
	}
}
