package serial;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.ArrayUtils;
import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.Objects;
import org.dsa.iot.dslink.util.handler.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.io.serial.SerialParameters;
import com.serotonin.io.serial.SerialPortException;
import com.serotonin.io.serial.SerialPortProxy;
import com.serotonin.io.serial.SerialUtils;

public class SerialConn {
	private static final Logger LOGGER = LoggerFactory.getLogger(SerialConn.class);
	
	private Node node;
	private SerialLink link;
	
	// Status node. Communicates whether the port is open or closed.
	private Node statnode = null;
	
	// This connection's serial port
	private SerialPortProxy serialPort = null;
	
	// When my node is subscribed to, this refers to the thread that listens on
	// my serial port (if it's open). When not subscribed, this is null. 
	private ScheduledFuture<?> future;
	
	// The message currently being read from the port. 
	private List<Byte> message = null;
	
	// The bytes that signify the start and end of a message.
	private int startCode;
	private int endCode;
	
	SerialConn(SerialLink link, Node node) {
		this.link = link;
		this.node = node;
		this.link.conns.add(this);
		setupNode();
	}
	
	void restoreLastSession() {
		node.clearChildren();
		init();
	}
	
	void init() {
		if (statnode == null) {
			statnode = node.createChild("Status").setValueType(ValueType.STRING).setValue(new Value("Initializing")).build();
		} else {
			statnode.setValue(new Value("Initializing"));
		}
		
		startCode = parseCode(node.getAttribute("Start Code").getString());
		endCode = parseCode(node.getAttribute("End Code").getString());
		
		makeEditAction();
		makeRemoveAction();
		
		connect();
	}
	
	/* Parses a string representing a byte. */
	private int parseCode(String s) {
		if (s.startsWith("0x")) {
			try {
				return Integer.parseInt(s.substring(2), 16);
			} catch (Exception e) {
			}
		} else {
			try {
				return Integer.parseInt(s, 10);
			} catch (Exception e) {
			}
		}
		String charset = node.getAttribute("Charset").getString();
		if ("None".equals(charset)) {
			LOGGER.error("Failed to parse start or end code");
			return 0;
		}
		try {
			return Byte.toUnsignedInt(s.getBytes(charset)[0]);
		} catch (Exception e) {
			LOGGER.error("Failed to parse start or end code");
		}
		return 0;
	}
	
	/* Open the serial port and set up actions which should be available while the
	   port is open. */
	private void connect() {
		if (serialPort != null) return;
		
		SerialParameters serialParams = new SerialParameters();

        serialParams.setCommPortId(node.getAttribute("Serial Port").getString());
        serialParams.setBaudRate(node.getAttribute("Baud Rate").getNumber().intValue());
        serialParams.setDataBits(node.getAttribute("Data Bits").getNumber().intValue());
        serialParams.setStopBits(node.getAttribute("Stop Bits").getNumber().intValue());
        serialParams.setParity(node.getAttribute("Parity").getNumber().intValue());
        
        try {
			serialPort = SerialUtils.openSerialPort(serialParams);
		} catch (SerialPortException e) {
			LOGGER.debug("", e);
			serialPort = null;
		}
        
        if (serialPort != null) {
        	statnode.setValue(new Value("Connected"));
        	node.removeChild("connect");
        	makeDisconnectAction();
        	makeSendAction();
        } else {
        	statnode.setValue(new Value("Failed to Connect"));
        	node.removeChild("disconnect");
        	node.removeChild("send message");
        	makeConnectAction();
        }
	}
	
	/* Close the serial port and set up actions which should be available while the port
	   is closed. Also discard any bytes that were read since the last complete message. */
	private void disconnect() {
		message = null;
		if (serialPort == null) return;
		try {
			SerialUtils.close(serialPort);
		} catch (SerialPortException e) {
			LOGGER.debug("", e);		
		}
		serialPort = null;
		
		statnode.setValue(new Value("Disconnected"));
    	node.removeChild("disconnect");
    	node.removeChild("send message");
    	makeConnectAction();
		
	}
	
	/* Setup the node so we start listening for serial data when the node is subscribed to,
	   and stop when it's unsubscribed from. */
	private void setupNode() {
		node.getListener().setOnSubscribeHandler(new Handler<Node>() {
			public void handle(Node event) {
				subscribe();
			}
		});
		node.getListener().setOnUnsubscribeHandler(new Handler<Node>() {
			public void handle(Node event) {
				unsubscribe();
			}
		});
	}
	
	/* Read and handle all available bytes from the serial port. Once no bytes are
	   available, wait half a second and check for more. (If serial port is closed,
	   just wait until it is open) */
	private void subscribe() {
		if (future != null) return;
		ScheduledThreadPoolExecutor stpe = Objects.getDaemonThreadPool();
		future = stpe.scheduleWithFixedDelay(new Runnable() {
			public void run() {
				readWhileAvailable();
			}
		}, 0, 500, TimeUnit.MILLISECONDS);
	}
	
	/* Stop reading (or trying to read) bytes from the serial port. Discard any bytes
	   that were read since the last complete message. */
	private void unsubscribe() {
		message = null;
		if (future == null) return;
		future.cancel(false);
		future = null;
	}
	
	/* Read and handle all available bytes from the serial port. */
	private void readWhileAvailable() {
		if (serialPort == null) return;
		try {
			while (serialPort.getInputStream().available() > 0) {
				int b = serialPort.getInputStream().read();
				if (message == null) {
					// 'message' is null, so we're between messages right now
					if (b == startCode) {
						// start a new message
						message = new ArrayList<Byte>();
					}
				} else {
					// 'message' not null, so we're in the process of reading a message
					if (b == endCode) {
						// The message is complete, so update the node accordingly
						// and set 'message' back to null
						finishRead();
						message = null;
					} else {
						// Append the byte to 'message'
						message.add((byte) b);
					}
				}
			}
		} catch (IOException e) {
			LOGGER.debug("", e);
		}
	}
	
	/* Parse the finished message to a string and set the node's value to it. */
	private void finishRead() {
		String charset = node.getAttribute("Charset").getString();
		String value;
		if ("None".equals(charset)) {
			// No charset, so display the message as a hex string.
			value = bytesToHexString(message);
		} else {
			try {
				// Decode the message according to our charset.
				byte[] bytes = ArrayUtils.toPrimitive(message.toArray(new Byte[message.size()]));
				value = new String(bytes, charset);
			} catch (UnsupportedEncodingException e) {
				// Decoding failed, so display the message as a hex string.
				LOGGER.debug("" ,e);
				value = bytesToHexString(message);
			}
		}
		node.setValue(new Value(value));
	}
	
	/* Constructs a hex string from a list of bytes. */
	private static String bytesToHexString(List<Byte> byteList) {
		StringBuffer result = new StringBuffer();
		for (byte b: byteList) {
			String asString = Integer.toHexString(Byte.toUnsignedInt(b));
			result.append(asString);
			result.append(" ");
		}
		return result.toString();
	}
	
	/* Create the action that allows editing the connection's parameters. */
	void makeEditAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			public void handle(ActionResult event) {
				handleEdit(event);
			}
		});
		act.addParameter(new Parameter("Name", ValueType.STRING, new Value(node.getName())));
		
		Set<String> portids = link.getCOMPorts();
		portids.add(node.getAttribute("Serial Port").getString());
		act.addParameter(new Parameter("Serial Port", ValueType.makeEnum(portids), node.getAttribute("Serial Port")));
		act.addParameter(new Parameter("Serial Port (manual entry)", ValueType.STRING));
		
		act.addParameter(new Parameter("Baud Rate", ValueType.NUMBER, node.getAttribute("Baud Rate")));
		act.addParameter(new Parameter("Data Bits", ValueType.NUMBER, node.getAttribute("Data Bits")));
		act.addParameter(new Parameter("Stop Bits", ValueType.NUMBER, node.getAttribute("Stop Bits")));
		act.addParameter(new Parameter("Parity", ValueType.NUMBER, node.getAttribute("Parity")));
		act.addParameter(new Parameter("Start Code", ValueType.STRING, node.getAttribute("Start Code")));
		act.addParameter(new Parameter("End Code", ValueType.STRING, node.getAttribute("End Code")));
		act.addParameter(new Parameter("Charset", ValueType.makeEnum(SerialLink.charsets), node.getAttribute("Charset")));
		
		Node anode = node.getChild("edit");
		if (anode == null) node.createChild("edit").setAction(act).build().setSerializable(false);
		else anode.setAction(act);
	}
	
	/* Handle an invocation of the edit action, restarting the connection to the
	   serial port with the new parameters. */
	private void handleEdit(ActionResult event) {
		String name = event.getParameter("Name", ValueType.STRING).getString();
		String com;
		Value customPort = event.getParameter("Serial Port (manual entry)");
		if (customPort != null && customPort.getString() != null && customPort.getString().trim().length() > 0) {
			com = customPort.getString();
		} else {
			com = event.getParameter("Serial Port").getString();
		}
		int baud = event.getParameter("Baud Rate", ValueType.NUMBER).getNumber().intValue();
		int dbits = event.getParameter("Data Bits", ValueType.NUMBER).getNumber().intValue();
		int sbits = event.getParameter("Stop Bits", ValueType.NUMBER).getNumber().intValue();
		int parity = event.getParameter("Parity", ValueType.NUMBER).getNumber().intValue();
		String start = event.getParameter("Start Code", ValueType.STRING).getString();
		String end = event.getParameter("End Code", ValueType.STRING).getString();
		String charset = event.getParameter("Charset").getString();
		
		if (!node.getName().equals(name)) {
			Node cnode = node.getParent().createChild(name).setValueType(ValueType.STRING).setValue(node.getValue()).build();
			cnode.setAttribute("Serial Port", new Value(com));
			cnode.setAttribute("Baud Rate", new Value(baud));
			cnode.setAttribute("Data Bits", new Value(dbits));
			cnode.setAttribute("Stop Bits", new Value(sbits));
			cnode.setAttribute("Parity", new Value(parity));
			cnode.setAttribute("Start Code", new Value(start));
			cnode.setAttribute("End Code", new Value(end));
			cnode.setAttribute("Charset", new Value(charset));
			SerialConn sc = new SerialConn(link, cnode);
			remove();
			sc.init();
		} else {
			node.setAttribute("Serial Port", new Value(com));
			node.setAttribute("Baud Rate", new Value(baud));
			node.setAttribute("Data Bits", new Value(dbits));
			node.setAttribute("Stop Bits", new Value(sbits));
			node.setAttribute("Parity", new Value(parity));
			node.setAttribute("Start Code", new Value(start));
			node.setAttribute("End Code", new Value(end));
			node.setAttribute("Charset", new Value(charset));
			
			disconnect();
			init();
		}
	}
	
	/* Make the action that closes this connection and removes my node. */
	private void makeRemoveAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			public void handle(ActionResult event) {
				remove();
			}
		});
		Node anode = node.getChild("remove");
		if (anode == null) node.createChild("remove").setAction(act).build().setSerializable(false);
		else anode.setAction(act);
	}
	
	/* Close my serial port and delete my node. */
	private void remove() {
		disconnect();
		node.clearChildren();
		node.getParent().removeChild(node);
	}
	
	/* Make the action that opens the serial port. */
	private void makeConnectAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			public void handle(ActionResult event) {
				connect();
			}
		});
		Node anode = node.getChild("connect");
		if (anode == null) node.createChild("connect").setAction(act).build().setSerializable(false);
		else anode.setAction(act);
	}
	
	/* Make the action that closes the serial port. */
	private void makeDisconnectAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			public void handle(ActionResult event) {
				disconnect();
			}
		});
		Node anode = node.getChild("disconnect");
		if (anode == null) node.createChild("disconnect").setAction(act).build().setSerializable(false);
		else anode.setAction(act);
	}
	
	/* Make the action that sends a message to the serial port. */
	private void makeSendAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			public void handle(ActionResult event) {
				handleSend(event);
			}
		});
		act.addParameter(new Parameter("Message", ValueType.STRING));
		Node anode = node.getChild("send message");
		if (anode == null) node.createChild("send message").setAction(act).build().setSerializable(false);
		else anode.setAction(act);
	}
	
	/* Sends a message to the serial port. */
	private void handleSend(ActionResult event) {
		String msgStr = event.getParameter("Message", ValueType.STRING).getString();
		String charset = node.getAttribute("Charset").getString();
		byte[] bytes;
		if ("None".equals(charset)) {
			// Parse the message as a hex string.
			String[] byteStrings = msgStr.split("\\s+");
			bytes = new byte[byteStrings.length + 2];
			for (int i=0; i<byteStrings.length; i++) {
				try {
					byte b = Byte.parseByte(byteStrings[i], 16);
					bytes[i+1] = b;
				} catch (Exception e) {
					LOGGER.error("No charset, and message not a string of bytes in hex notation");
					return;
				}
			}
		} else {
			// Encode the message according to our charset.
			try {
				byte[] msgBytes = msgStr.getBytes(charset);
				bytes = new byte[msgBytes.length + 2];
				System.arraycopy(msgBytes, 0, bytes, 1, msgBytes.length);
			} catch (UnsupportedEncodingException e) {
				LOGGER.debug("" ,e);
				return;
			}
		}
		bytes[0] = (byte) startCode;
		bytes[bytes.length - 1] = (byte) endCode;
		try {
			serialPort.getOutputStream().write(bytes);
			serialPort.getOutputStream().flush();
		} catch (IOException e) {
			LOGGER.debug("" , e);
		}
	}
	
	

}
