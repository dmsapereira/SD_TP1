package sd1920.trab1.server.soap.resources;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.logging.Logger;

import javax.jws.WebService;

import sd1920.trab1.api.Message;
import sd1920.trab1.api.User;
import sd1920.trab1.api.soap.MessageServiceSoap;
import sd1920.trab1.api.soap.MessagesException;
import sd1920.trab1.server.serverUtils.ServerMessageUtils;
import sd1920.trab1.server.soap.SOAPMailServer;

@WebService(serviceName=MessageServiceSoap.NAME, 
	targetNamespace=MessageServiceSoap.NAMESPACE, 
	endpointInterface=MessageServiceSoap.INTERFACE)
public class MessageResourceSoap extends ServerMessageUtils implements MessageServiceSoap {
	
	public MessageResourceSoap() throws UnknownHostException {
		super();

		this.randomNumberGenerator = new Random(System.currentTimeMillis());
		
		Log = Logger.getLogger(MessageResourceSoap.class.getName());
		
		this.domain = InetAddress.getLocalHost().getHostName();

		this.serverUri = String.format(DOMAIN_FORMAT_SOAP, InetAddress.getLocalHost().getHostAddress(), SOAPMailServer.PORT);
	}
	
	@Override
	public long postMessage(String pwd, Message msg) throws MessagesException{
		User user;
		String sender = msg.getSender();
		Set<String> recipientDomains = new HashSet<>();

		Log.info("postMessage: Received request to register a new message (Sender: " + sender + "; Subject: "+msg.getSubject()+")");
		
		if(sender == null || msg.getDestination() == null || msg.getDestination().size() == 0) {
			Log.info("postMessage: Message was rejected due to lack of recepients.");
			throw new MessagesException("postMessage: Message was rejected due to lack of recepients");
		}

		String senderName = getSenderCanonicalName(sender);

		user = getUserSoap(senderName, pwd);

		if(user == null){
			Log.info("postMessage: user error");
			throw new MessagesException("postMessage: Message was rejected due to unexisting user.");
		}

		long newID = Math.abs(randomNumberGenerator.nextLong());

		synchronized(this.allMessages){
			while(allMessages.containsKey(newID))
				newID = Math.abs(randomNumberGenerator.nextLong());
			
			msg.setSender(String.format(SENDER_FORMAT, user.getDisplayName(), user.getName(), user.getDomain()));
			msg.setId(newID);
			allMessages.put(newID, msg);
							
		}
		Log.info("postMessage: Created new message with id: " + newID);

		for(String recipient: msg.getDestination()){
			String[] tokens = recipient.split("@");
			if(tokens[1].equals(this.domain))
				saveMessage(senderName, recipient, false, msg);
			else
				recipientDomains.add(tokens[1]);
		}
		
		this.forwardMessage(recipientDomains,msg,false);
		

		Log.info("postMessage: Recorded message with identifier: " + msg.getId());
		return msg.getId();	
	}

	@Override
	public Message getMessage(String user, String pwd, long mid) throws MessagesException {
	
		
		User u = this.getUserSoap(user, pwd);
		
		if(u == null){
			Log.info("getMessage: user error");
			throw new MessagesException("getMessage: User does not exist or password is incorrect");
		}

		Log.info("getMessage: Received request for message with id: " + mid +".");
		synchronized(this.allMessages){
			synchronized(this.userInboxs){
				System.out.println(this.allMessages.containsKey(mid));
				System.out.println(this.userInboxs.get(user).contains(mid));
				if(!this.allMessages.containsKey(mid) || !this.userInboxs.get(user).contains(mid)) { //check if message exists
					Log.info("getMessage: Requested message does not exists.");
					throw new MessagesException("getMessage: Requested message does not exists."); //if not send HTTP 404 back to client
				}
			}
		}
		
		return allMessages.get(mid); //Return message to the client with code HTTP 200
	
	}

	@Override
	public List<Long> getMessages(String user, String pwd) throws MessagesException{

		Log.info("getMessages: Received request for messages with optional user parameter set to: '" + user + "'");
		User u = this.getUserSoap(user, pwd);

		if(u == null){
			Log.info("getMessages: User with name " + user + " does not exist in the domain.");
			throw new MessagesException("getMessages: User with name " + user + " does not exist in the domain.");
		}

		Set<Long> mids = new HashSet<Long>();
		
		synchronized(this.userInboxs){
			mids = userInboxs.getOrDefault(user, Collections.emptySet());
		}

		Log.info("getMessages: Returning message list to user with " + mids.size() + " messages.");
		return new ArrayList<>(mids);
	}

	@Override
	public void deleteMessage(String user, String pwd, long mid) throws MessagesException{		
		User sender = null;
		
		Message msg;

		Log.info("deleteMesage: Received request to delete a message with the id: " + String.valueOf(mid));
		
		synchronized(this.allMessages){
			msg = this.allMessages.get(mid);
		}

		sender  = this.getUserSoap(user, pwd);
		
		if(sender == null){
			Log.info("delete message: User not found or wrong password");
			throw new MessagesException("Delete message: User not found or wrong password");
		}
		
		synchronized(this.userInboxs){
			this.userInboxs.get(user).remove(mid);
		}

		String userName = getSenderCanonicalName(user);

		if(msg == null || !getSenderCanonicalName(msg.getSender()).equals(userName))
			return;
		

		Set<String> recipientDomains = new HashSet<>();

		for(String u : msg.getDestination()){
			String[] tokens = u.split("@");
			if(tokens[1].equals(this.domain)){
				synchronized(this.userInboxs){
					userInboxs.get(tokens[0]).remove(mid);					
					Log.info("deleteMessage: Removing message for user " + u);
				}
			}else
				recipientDomains.add(tokens[1]);
		}
		forwardDelete(recipientDomains, String.valueOf(mid),false);
	}

	@Override
	public void removeFromUserInbox(String user, String pwd, long mid) throws MessagesException{

		Log.info("removeFromUserInbox: Received request to delete message " + mid + " from the inbox of " + user);
		

		User u = this.getUserSoap(user, pwd);

		if(u == null){
			Log.info("removeFromUserInbox; User with name " + user + " does not exist in the domain.");
			throw new MessagesException("User with name " + user + " does not exist in the domain.");
		}
		
		synchronized(this.allMessages){
			synchronized(this.userInboxs){
				if(!this.allMessages.containsKey(mid) || !this.userInboxs.get(user).contains(mid)){
					Log.info("removeFromUserInbox: Message not found");
					throw new MessagesException("Message not found");
				}
			}
		}

		synchronized(this.userInboxs){
			this.userInboxs.get(user).remove(mid);
		}
	}

	@Override
	public void createInbox(String user) throws MessagesException{
		synchronized(this.userInboxs){
			this.userInboxs.put(user, new HashSet<>());
		}
	}

	@Override
	public List<String> postForwardedMessage(Message msg){
		List<String> failedDeliveries = new LinkedList<>();

		Log.info("postForwardedMessage: Received forwarded message from " + msg.getSender() + ". ID: " + msg.getId());

		for(String recipient: msg.getDestination()){
			String[] tokens = recipient.split("@");
			if(tokens[1].equals(this.domain) && !this.saveMessage(msg.getSender(), tokens[0], true, msg))
				failedDeliveries.add(recipient);
		}

		return failedDeliveries;
	}

	@Override
	public void deleteForwardedMessage(long mid) throws MessagesException{
		Set<String> recipients = null;

		Log.info("deleteForwardedMessage: Received request to delete message " + mid);
		synchronized(this.allMessages){
			if(!this.allMessages.containsKey(mid)){
				Log.info("deleteForwardedMessage: Message not found");
				return;
			}

			recipients = this.allMessages.get(mid).getDestination();
			this.allMessages.remove(mid);
		}

		for(String s : recipients){
			String[] tokens = s.split("@");
			if(tokens[1].equals(this.domain)){
				synchronized(this.userInboxs){
					userInboxs.get(tokens[0]).remove(mid);
				}
			}
		}
	}
}