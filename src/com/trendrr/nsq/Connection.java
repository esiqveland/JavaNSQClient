package com.trendrr.nsq;

/**
 * 
 */

import java.util.Date;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;

import com.trendrr.nsq.exceptions.DisconnectedException;
import com.trendrr.nsq.frames.ErrorFrame;
import com.trendrr.nsq.frames.MessageFrame;
import com.trendrr.nsq.frames.NSQFrame;
import com.trendrr.nsq.frames.ResponseFrame;
import com.trendrr.oss.exceptions.TrendrrDisconnectedException;




/**
 * @author Dustin Norlander
 * @created Jan 14, 2013
 * 
 */
public class Connection {

	protected static Log log = LogFactory.getLog(Connection.class);
	
	Channel channel;
	int heartbeats = 0;
	Date lastHeartbeat = new Date();
	
	MessageCallback callback = null;
	int totalMessages = 0;
	int messagesPerBatch = 200;
	
	AbstractNSQClient client = null;
	
	String host = null;
	int port;
	
	LinkedBlockingQueue<NSQCommand> requests = new LinkedBlockingQueue<NSQCommand>(1);
	LinkedBlockingQueue<NSQFrame> responses = new LinkedBlockingQueue<NSQFrame>(1);
	
	
	public Connection(String host, int port, Channel channel, AbstractNSQClient client) {
		this.channel = channel;
		this.channel.setAttachment(this);
		this.client = client;
		this.host = host;
		this.port = port;
	}
	
	public boolean isRequestInProgress() {
		return this.requests.size() > 0;
	}
	
	public String getHost() {
		return host;
	}

	public int getPort() {
		return port;
	}

	public int getMessagesPerBatch() {
		return messagesPerBatch;
	}


	public void setMessagesPerBatch(int messagesPerBatch) {
		this.messagesPerBatch = messagesPerBatch;
	}


	
	
	
	public void incoming(NSQFrame frame) {
		System.out.println("INCOMING: "+ frame);
		if (frame instanceof ResponseFrame) {
			if ("_heartbeat_".equals(((ResponseFrame) frame).getMessage())) {
				this.heartbeat();
				return;
			} else {
				this.responses.add(frame);
				return;
			}
		}
		if (frame instanceof ErrorFrame) {
			this.responses.add(frame);
			return;
		}
		if (frame instanceof MessageFrame) {
			this.totalMessages++;
			if (totalMessages % messagesPerBatch > (messagesPerBatch/2)) {
				//request some more!
				this.command(NSQCommand.instance("RDY " + this.messagesPerBatch));
			}
			
			
			NSQMessage message = new NSQMessage();
			message.setAttempts(((MessageFrame) frame).getAttempts());
			message.setConnection(this);
			message.setId(((MessageFrame) frame).getMessageId());
			message.setMessage(((MessageFrame) frame).getMessageBody());
			message.setTimestamp(new Date(((MessageFrame) frame).getTimestamp()));
			if (this.callback == null) {
				log.warn("NO CAllback, dropping message: " + message);
			} else {
				this.callback.message(message);
			}
			return;
		}
		log.warn("Unknown frame type: " + frame);
	}
	
	
	void heartbeat() {
		System.out.println("HEARTBEAT!");
		this.heartbeats++;
		this.lastHeartbeat = new Date();
		//send NOP here.
		this.command(NSQCommand.instance("NOP"));
	}
	
	/**
	 * called when this connection is disconnected socket level
	 * this is used internally, generally close() should be used instead.
	 */
	public void _disconnected() {
		//clean up anything that needs cleaning up.
		this.client._disconnected(this);
	} 
	
	public int getHeartbeats() {
		return heartbeats;
	}

	/**
	 * Do not use this, only here until server implements producer heartbeats.
	 */
	public synchronized void _setLastHeartbeat() {
		this.lastHeartbeat = new Date();
	}
	
	public synchronized Date getLastHeartbeat() {
		return lastHeartbeat;
	}

	public int getTotalMessages() {
		return totalMessages;
	}

	public MessageCallback getCallback() {
		return callback;
	}


	public void setCallback(MessageCallback callback) {
		this.callback = callback;
	}


	public void close() {
		try {
			channel.close().await(10000);
		} catch (Exception x) {
			log.error("Caught", x);
		}
		this._disconnected();
	}
	
	/**
	 * issues a command and waits for the result
	 * 
	 * @param command
	 * @return
	 * @throws Exception
	 */
	public NSQFrame commandAndWait(NSQCommand command) throws DisconnectedException{	
		
		try {
			try {
				if (!this.requests.offer(command, 5, TimeUnit.SECONDS)) {
					//throw timeout, and disconnect?
					throw new DisconnectedException("command: " + command + " timedout, disconnecting..", null);
				}
				
				ChannelFuture fut = this.command(command);
				
				if (!fut.await(5, TimeUnit.SECONDS)) {
					//throw timeout, and disconnect?
					throw new DisconnectedException("command: " + command + " timedout, disconnecting..", null);
				}
				
				NSQFrame frame = this.responses.poll(5, TimeUnit.SECONDS);
				if (frame == null) {
					throw new DisconnectedException("command: " + command + " timedout, disconnecting..", null);
				}
				
				this.requests.poll(); //clear the request object
				return frame;
				
			} catch (DisconnectedException x) {
				throw x;
			} catch (Exception x) {
				throw new DisconnectedException("command: " + command + " timedout, disconnecting..", x);
			}
		} catch (DisconnectedException x) {
			//now disconnect this 
			this.close();
			throw x;
		}
	}
	
	/**
	 * issues a command.  doesnt wait on response, the future is only for delivery.
	 * 
	 * @param command
	 * @return
	 */
	public ChannelFuture command(NSQCommand command) {
		return this.channel.write(command);
	}
}