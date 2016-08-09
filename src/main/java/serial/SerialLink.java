package serial;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.handler.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.io.serial.CommPortConfigException;
import com.serotonin.io.serial.CommPortProxy;
import com.serotonin.io.serial.SerialUtils;


public class SerialLink {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(SerialLink.class);
	
	static List<String> charsets = new ArrayList<String>(Charset.availableCharsets().keySet());
	static String defaultCharset = Charset.defaultCharset().name();
	static {
		charsets.add("None");
	}
	
	private Node node;
	Set<SerialConn> conns = new HashSet<SerialConn>();
	
	private SerialLink(Node node) {
		this.node = node;
	}
	
	public static void start(Node node) {
		SerialLink sl = new SerialLink(node);
		sl.init();
	}
	
	private void init() {
		restoreLastSession();
		
		makeAddConnAction();
		makePortScanAction();
	}
	
	private void restoreLastSession() {
		node.clearChildren();
	}
	
	/* Creates the action that refreshes the serial ports available through drop-downs
	   in other actions. */
	private void makePortScanAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			public void handle(ActionResult event) {
				doPortScan();
			}
		});
		node.createChild("scan for serial ports").setAction(act).build().setSerializable(false);
	}
	
	/* Re-creates the 'add connection' action and the edit actions, so they accurately
	   reflect which serial ports are currently available. */
	private void doPortScan() {
		makeAddConnAction();
		
		for (SerialConn sc: conns) {
			sc.makeEditAction();
		}
	}
	
	/* Returns a set of names of serial ports currently available. */
	public Set<String> getCOMPorts() {
		Set<String> ports = new HashSet<String>();
		try {
			for (CommPortProxy p: SerialUtils.getCommPorts()) {
				ports.add(p.getId());
			}
		} catch (CommPortConfigException e) {
			LOGGER.debug("" ,e);
		}
		return ports;
	}
	
	/* Creates the action that adds a new connection to a serial port. */
	private void makeAddConnAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			public void handle(ActionResult event) {
				handleAddConn(event);
			}
		});
		act.addParameter(new Parameter("Name", ValueType.STRING));
		
		Set<String> portids = getCOMPorts();
		if (portids.size() > 0) {
			act.addParameter(new Parameter("Serial Port", ValueType.makeEnum(portids)));
			act.addParameter(new Parameter("Serial Port (manual entry)", ValueType.STRING));
		} else {
			act.addParameter(new Parameter("Serial Port", ValueType.STRING));
		}
		
		act.addParameter(new Parameter("Baud Rate", ValueType.NUMBER, new Value(9600)));
		act.addParameter(new Parameter("Data Bits", ValueType.NUMBER, new Value(8)));
		act.addParameter(new Parameter("Stop Bits", ValueType.NUMBER, new Value(1)));
		act.addParameter(new Parameter("Parity", ValueType.NUMBER, new Value(0)));
		act.addParameter(new Parameter("Start Code", ValueType.STRING, new Value("0x05")));
		act.addParameter(new Parameter("End Code", ValueType.STRING, new Value("0x0D")));
		act.addParameter(new Parameter("Charset", ValueType.makeEnum(charsets), new Value(defaultCharset)));
		
		Node anode = node.getChild("add connection");
		if (anode == null) node.createChild("add connection").setAction(act).build().setSerializable(false);
		else anode.setAction(act);
	}
	
	/* Creates a node for a new serial connection, and initializes an instance 
	   of the SerialConn class, which handles this connection. */
	private void handleAddConn(ActionResult event) {
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
		
		Node cnode = node.createChild(name).setValueType(ValueType.STRING).build();
		cnode.setAttribute("Serial Port", new Value(com));
		cnode.setAttribute("Baud Rate", new Value(baud));
		cnode.setAttribute("Data Bits", new Value(dbits));
		cnode.setAttribute("Stop Bits", new Value(sbits));
		cnode.setAttribute("Parity", new Value(parity));
		cnode.setAttribute("Start Code", new Value(start));
		cnode.setAttribute("End Code", new Value(end));
		cnode.setAttribute("Charset", new Value(charset));
		
		SerialConn sc = new SerialConn(this, cnode);
		sc.init();
	}

}
