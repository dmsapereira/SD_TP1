package sd1920.trab1.server.soap.resources;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.logging.Logger;

import javax.jws.WebService;

import sd1920.trab1.api.Message;
import sd1920.trab1.api.soap.MessagesException;
import sd1920.trab1.api.soap.MessageServiceSoap;

@WebService(serviceName=MessageServiceSoap.NAME, 
targetNamespace=MessageServiceSoap.NAMESPACE, 
endpointInterface=MessageServiceSoap.INTERFACE)
public class MessageServiceImpl implements MessageServiceSoap {

	private Random randomNumberGenerator;

	private final Map<Long,Message> allMessages; 
	private final Map<String,Set<Long>> userInboxs;

	private static Logger Log = Logger.getLogger(MessageServiceImpl.class.getName());

	public MessageServiceImpl() {
		this.randomNumberGenerator = new Random(System.currentTimeMillis());
		this.allMessages = new HashMap<Long, Message>();
		this.userInboxs = new HashMap<String, Set<Long>>();
	}
	
	@Override
	public long postMessage(Message msg) throws MessagesException {
		Log.info("Received request to register a new message (Sender: " + msg.getSender() + "; Subject: "+msg.getSubject()+")");

		//Check if message is valid, if not return HTTP CONFLICT (409)
		if(msg.getSender() == null || msg.getDestination() == null || msg.getDestination().size() == 0) {
			Log.info("Message was rejected due to lack of recepients.");
			throw new MessagesException( "Message does not exists." );
		}

		long newID = 0;
		
		synchronized (this) {

			//Generate a new id for the message, that is not in use yet
			newID = Math.abs(randomNumberGenerator.nextLong());
			while(allMessages.containsKey(newID)) {
				newID = Math.abs(randomNumberGenerator.nextLong());
			}

			//Add the message to the global list of messages
			allMessages.put(newID, msg);
		}

		Log.info("Created new message with id: " + newID);
		MessageUtills.printMessage(allMessages.get(newID));

		synchronized (this) {
			//Add the message (identifier) to the inbox of each recipient
			for(String recipient: msg.getDestination()) {
				if(!userInboxs.containsKey(recipient)) {
					userInboxs.put(recipient, new HashSet<Long>());
				}
				userInboxs.get(recipient).add(newID);
			}
		}

		//Return the id of the registered message to the client (in the body of a HTTP Response with 200)
		Log.info("Recorded message with identifier: " + newID);
		return newID;
	}

	@Override
	public Message getMessage(long mid) throws MessagesException {
		Log.info("Received request for message with id: " + mid +".");
		Message m = null;
		
		synchronized (this) {
			m = allMessages.get(mid);
		}
		
		if(m == null) {  //check if message exists	
			Log.info("Requested message does not exists.");
			throw new MessagesException("Message does not exists"); //if not send HTTP 404 back to client
		}

		Log.info("Returning requested message to user.");
		return m; //Return message to the client with code HTTP 200
	}

	@Override
	public byte[] getMessageBody(long mid) throws MessagesException {
		Log.info("Received request for body of message with id: " + mid +".");
		byte[] contents = null;
		synchronized (this) {
			Message m = allMessages.get(mid);
			if(m != null)
				contents = m.getContents();
		}
		
		
		if(contents != null) { //implicitaly checks if message exists
			Log.info("Requested message does not exists.");
			throw new MessagesException("Message does not exists"); //if not send HTTP 404 back to client
		}

		Log.info("Returning requested message body to user.");
		return contents; //Return message contents to the client with code HTTP 200
	}

	@Override
	public List<Message> getMessages(String user) {
		Log.info("Received request for messages with optional user parameter set to: '" + user + "'");
		List<Message> messages = new ArrayList<Message>();
		if(user == null) {
			Log.info("Collecting all messages in server");
			synchronized (this) {
				messages.addAll(allMessages.values());
			}
			
		} else {
			Log.info("Collecting all messages in server for user " + user);
			synchronized (this) {
				Set<Long> mids = userInboxs.getOrDefault(user, Collections.emptySet());
				for(Long l: mids) { 
					Log.info("Adding messaeg with id: " + l + ".");
					messages.add(allMessages.get(l));
				}
			}
			
		}
		Log.info("Returning message list to user with " + messages.size() + " messages.");
		return messages;
	}

	@Override
	public void deleteMessage(long mid) {
		throw new Error("Not Implemented...");
	}

	@Override
	public void removeFromUserInbox(String user, long mid) {
		throw new Error("Not Implemented...");

	}

}
