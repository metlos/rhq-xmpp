package org.rhq.enterprise.server.plugins.xmpp.receiver;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedAction;
import java.security.PrivilegedExceptionAction;
import java.util.Collections;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Map;
import java.util.Set;

import javax.script.ScriptEngine;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jivesoftware.smack.Chat;
import org.jivesoftware.smack.ChatManagerListener;
import org.jivesoftware.smack.MessageListener;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.packet.Message;

import org.rhq.bindings.ScriptEngineFactory;
import org.rhq.bindings.StandardBindings;
import org.rhq.bindings.StandardScriptPermissions;
import org.rhq.bindings.util.PackageFinder;
import org.rhq.core.domain.auth.Subject;
import org.rhq.core.util.exception.ThrowableUtil;
import org.rhq.enterprise.client.LocalClient;
import org.rhq.enterprise.server.util.LookupUtil;

public class XMPPReceiver implements MessageListener, ChatManagerListener {

	private static final Log LOG = LogFactory.getLog(XMPPReceiver.class);
	
	private static class MyOutputStream extends ByteArrayOutputStream {
	
	    public String toSubString(int fromByte) {
	        if (fromByte < count) {
	            return new String(buf, fromByte, count - fromByte);
	        } else {
	            return "";
	        }
	    }
	}
	
	private static class Engine {
	    ScriptEngine scriptEngine;
	    StandardBindings rhqBindings;
	    PrintWriter stdOut;
	    MyOutputStream rawOut;
	    long lastAccessTimeStamp;
	}
	
	//we want the synchronized access to the hash... therefore Hashtable
	private final Map<Chat, Engine> clis = new Hashtable<Chat, Engine>();
	
        public XMPPReceiver() {
            Thread cliCleaner = new Thread(new Runnable() {
                @Override
                public void run() {
                    Set<Chat> expiredChats = new HashSet<Chat>();
                    while(true) {
                        if (Thread.currentThread().isInterrupted()) {
                            return;
                        }
                        
                        long now = System.currentTimeMillis();
                        expiredChats.clear();
                        for(Map.Entry<Chat, Engine> entry : clis.entrySet()) {
                            long lastAccessPlus30Mins = entry.getValue().lastAccessTimeStamp + 30 * 60 * 1000;
                            long lastAccessPlus25Mins = lastAccessPlus30Mins - 5 * 60 * 1000;
                            Chat chat = entry.getKey();
                            
                            if (lastAccessPlus25Mins < now) {
                                if (lastAccessPlus30Mins < now) {
                                    expiredChats.add(chat);
                                } else {
                                    try {
                                        entry.getKey().sendMessage("Your CLI session is going to expire in 5 minutes.");
                                    } catch (Exception e) {
                                        LOG.warn("Failed to send the timeout warning to chat " + chat, e);
                                    }
                                }
                            }
                        }
                        
                        for(Chat chat : expiredChats) {
                            Engine eng = clis.get(chat);
                            synchronized(eng) {
                                long lastAccessPlus30Mins = eng.lastAccessTimeStamp + 30 * 60 * 1000;
                                if (lastAccessPlus30Mins < now) {
                                    clis.remove(chat);
                                    eng.stdOut.close();
                                }
                            }
                        }
                        
                        try {
                            Thread.sleep(10 * 60 * 1000);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            }, "XMPP Receiver CLI session cleaner thread");
            cliCleaner.setDaemon(true);            
            cliCleaner.start();
        }
        
	@Override
	public void processMessage(Chat chat, Message message) {
                String script = message.getBody();
                if (script == null) {
                    return;
                }
                
	        try {
	            Engine engine = accessEngine(chat);	            
		    if (engine == null) {
		        throw new IllegalStateException("Session expired.");
		    }
		    ScriptEngine scriptEngine = engine.scriptEngine;
		    
		    int outputLengthSoFar = engine.rawOut.size();
		    MyOutputStream rawOut = engine.rawOut;
		    
                    Object response = scriptEngine.eval(script);
                    if (response != null) {
                        engine.rhqBindings.getPretty().getValue().print(response);
                    }
		    		    
		    engine.stdOut.flush();
		    
		    sendResponse(chat, rawOut.toSubString(outputLengthSoFar));
		} catch (Exception e) {
			LOG.error("Error While processing message: " + message.getBody(), e);
			sendResponse(chat, "RHQ internal error:\n" + ThrowableUtil.getAllMessages(e));
		}
	}

	@Override
	public void chatCreated(Chat chat, boolean arg1) {
	    chat.addMessageListener(this);
	    try {
	        clis.put(chat, createNewScriptEngine());	        
	    } catch (Exception e) {
	        LOG.error("Failed to create a script engine for chat " + chat, e);
	    }
    	}

	private void sendResponse(Chat chat, String response) {
		try {
			chat.sendMessage(response);
		} catch (XMPPException e) {
			LOG.error("Error While sending response to: " + chat.getParticipant(), e);
		}
	}
	
	private Engine createNewScriptEngine() throws Exception {
	    MyOutputStream rawOut = new MyOutputStream();
	    PrintWriter stdOut = new PrintWriter(rawOut);
	    
	    //the default local client can only be "preauthenticated" with the 
	    //provided subject. We need to actually enable logins through the 
	    //local interface, too, so we need to reimplement the login/logout
	    //methods to actually do anything.
	    final LocalClient rhqClient = new LocalClient(null) {
                private Subject subject;
                
                @Override
                public Subject login(final String user, final String password) throws Exception {
                    return AccessController.doPrivileged(new PrivilegedExceptionAction<Subject>() {
                        @Override
                        public Subject run() throws Exception {
                            subject = LookupUtil.getSubjectManager().login(user, password);
                            return subject;
                        }
                    });
                }
                
                @Override
                public void logout() {
                    AccessController.doPrivileged(new PrivilegedAction<Void>() {
                        @Override
                        public Void run() {
                            LookupUtil.getSubjectManager().logout(subject);
                            return null;
                        }
                    });
                }
                
                @Override
                public Subject getSubject() {
                    return subject;
                }
            };
            
	    StandardBindings rhqBindings =  new StandardBindings(stdOut, rhqClient);
	    
	    //add a new object into the bindings that the user can use to login and 
	    //log out.
	    rhqBindings.put("rhq", new Object() {
	        @SuppressWarnings("unused")
                public Subject login(String username, String password) throws Exception {
	            return rhqClient.login(username, password);
	        }
	        
	        @SuppressWarnings("unused")
                public void logout() {
	            rhqClient.logout();
	        }
	    });
	    
            ScriptEngine scriptEngine = ScriptEngineFactory.getSecuredScriptEngine("JavaScript", new PackageFinder(Collections.<File>emptyList()), rhqBindings, new StandardScriptPermissions());            
            scriptEngine.getContext().setWriter(stdOut);
            scriptEngine.getContext().setErrorWriter(stdOut);
            
            Engine ret = new Engine();
            ret.scriptEngine = scriptEngine;
            ret.rhqBindings = rhqBindings;
            ret.rawOut = rawOut;
            ret.stdOut = stdOut;
            ret.lastAccessTimeStamp = System.currentTimeMillis();
            return ret;            
	}
	
	private Engine accessEngine(Chat chat) throws Exception {
            Engine engine = clis.get(chat);
            if (engine != null) {
                synchronized(engine) {
                    if (clis.containsKey(chat)) {
                        engine.lastAccessTimeStamp = System.currentTimeMillis();
                        return engine;
                    } else {
                        return null;
                    }
                }
            } else {
                engine = createNewScriptEngine();
                clis.put(chat, engine);
                return engine;
            }
	}
}
