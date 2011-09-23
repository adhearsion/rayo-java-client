package com.voxeo.rayo.client.test.util;

import com.rayo.client.XmppConnectionAdapter;
import com.rayo.client.xmpp.stanza.XmppObject;

public class MockConnectionListener extends XmppConnectionAdapter {

	int errorsCount = 0;
	int establishedCount = 0;
	int finishedCount = 0;
	int resettedCount = 0;
	int sent = 0;
	
	@Override
	public void connectionError(String connectionId, Exception e) {

		errorsCount++;
	}
	
	@Override
	public void connectionEstablished(String connectionId) {

		establishedCount++;
	}
	
	@Override
	public void connectionFinished(String connectionId) {

		finishedCount++;
	}
	
	@Override
	public void connectionReset(String connectionId) {

		resettedCount++;
	}
	
	@Override
	public void messageSent(XmppObject message) {

		sent++;
	}

	public int getErrorsCount() {
		return errorsCount;
	}

	public void setErrorsCount(int errorsCount) {
		this.errorsCount = errorsCount;
	}

	public int getEstablishedCount() {
		return establishedCount;
	}

	public void setEstablishedCount(int establishedCount) {
		this.establishedCount = establishedCount;
	}

	public int getFinishedCount() {
		return finishedCount;
	}

	public void setFinishedCount(int finishedCount) {
		this.finishedCount = finishedCount;
	}

	public int getResettedCount() {
		return resettedCount;
	}

	public void setResettedCount(int resettedCount) {
		this.resettedCount = resettedCount;
	}

	public int getSent() {
		return sent;
	}

	public void setSent(int sent) {
		this.sent = sent;
	}
}
