package com.rayo.client.xmpp.stanza.sasl;

import org.dom4j.Element;

import com.rayo.client.xmpp.Namespaces;
import com.rayo.client.xmpp.stanza.AbstractXmppObject;

public class Abort extends AbstractXmppObject {

	public Abort() {
		
		super(Namespaces.SASL);
	}
	
	public Abort(Element element) {
		
		this();
		setElement(element);
	}
	
	@Override
	public String getStanzaName() {

		return "abort";
	}
}
