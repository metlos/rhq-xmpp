package org.rhq.enterprise.server.plugins.alertXmpp;

import org.jivesoftware.smack.Chat;
import org.jivesoftware.smack.MessageListener;
import org.jivesoftware.smack.packet.Message;

public class ChatListener implements MessageListener {

	@Override
	public void processMessage(Chat chat, Message message) {
		 //TODO: process the message
	}

}
