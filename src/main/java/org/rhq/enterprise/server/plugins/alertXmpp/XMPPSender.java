package org.rhq.enterprise.server.plugins.alertXmpp;

import org.jivesoftware.smack.XMPPException;
import org.rhq.core.domain.alert.Alert;
import org.rhq.core.domain.alert.notification.SenderResult;
import org.rhq.enterprise.server.alert.AlertManagerLocal;
import org.rhq.enterprise.server.plugin.pc.alert.AlertSender;
import org.rhq.enterprise.server.util.LookupUtil;


@SuppressWarnings("rawtypes")
public class XMPPSender extends AlertSender {

	@Override
	public SenderResult send(Alert alert) {
		try {
			String messageTo = getMessageTo();
			String messageBody = createMessageBody(alert);
			XMPPConnectionManager connectionManager = XMPPConnectionManager.getInstance();
			connectionManager.sendChat(messageBody, messageTo);
		} catch (XMPPException e) {
			e.printStackTrace();
			return SenderResult.getSimpleFailure(e.getMessage());
		}
		return SenderResult.getSimpleSuccess("ok");

	}

	private String getMessageTo() {
		return alertParameters.getSimpleValue("messageTo", null);
	}

	private String createMessageBody(Alert alert) {
		AlertManagerLocal alertManager = LookupUtil.getAlertManager();
		StringBuilder stringBuilder = new StringBuilder();
		stringBuilder.append("Alert: ");
		stringBuilder.append(alert.getAlertDefinition().getName());
		stringBuilder.append("\nResource: ");
		stringBuilder.append(alert.getAlertDefinition().getResource().getName());
		stringBuilder.append("\nCondition: ");
		stringBuilder.append(alertManager.prettyPrintAlertConditions(alert, false));
		stringBuilder.append("\nURL: ");
		stringBuilder.append(alertManager.prettyPrintAlertURL(alert));
		return stringBuilder.toString();
	}

}