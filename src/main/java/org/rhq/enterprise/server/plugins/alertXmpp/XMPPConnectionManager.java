package org.rhq.enterprise.server.plugins.alertXmpp;

import org.jivesoftware.smack.Chat;
import org.jivesoftware.smack.ChatManager;
import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.MessageListener;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;

public class XMPPConnectionManager {

	private static XMPPConnectionManager instance;

	private XMPPConnection xmppConnection;

	public static XMPPConnectionManager getInstance() {
		if (instance == null) {	
			instance = new XMPPConnectionManager();
		}
		return instance;
	}

	public void connect(String server, String port, String username, String password, String serviceName) throws XMPPException {
		disconnect();
		ConnectionConfiguration connectionConfiguration = new ConnectionConfiguration(server, Integer.parseInt(port), serviceName);
		xmppConnection = new XMPPConnection(connectionConfiguration);
		xmppConnection.connect();
		xmppConnection.login(username, password, PluginConstants.LOGIN_RESOURCE);
	}

	public void disconnect() throws XMPPException {
		if (xmppConnection != null && xmppConnection.isConnected()) {
			xmppConnection.disconnect();
		}
	}

	public void sendChat(String message, String to) throws XMPPException {
		checkConnection();
		ChatManager chatManager = xmppConnection.getChatManager();
		MessageListener chatListener = new ChatListener();
		Chat chat = chatManager.createChat(to, chatListener);
		chat.sendMessage(message);
	}

	private void checkConnection() {

	}


}
