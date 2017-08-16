# dslink-java-serial
A slightly modified version of the original generic DSLink for serial communication.
This version will read the serial port buffer until either the end code is found or the end of the buffer is reached. This is useful for serial devices that do not have a specific end code, but simply respond with a string of data.

## Usage
Add a connection by specifying a serial port, baud rate, etc. Available serial ports are listed in the dropdown in the "add connection" action. You can rescan the system for available ports using the "scan for serial ports" action.

The "Start Code" and "End Code" parameters specify how the connection will identify the beginning and end of a message. Each should be a single byte in hexadecimal or decimal notation (e.g. '0xE7' or '231').

The "Charset" specifies how the connection will parse incoming messages and encode outgoing messages. Setting the charset to 'None' will cause the connection to interpret messages as raw byte data in hex notation, with spaces between bytes (e.g. 48 65 6c 6c 6f 2c 20 57 6f 72 6c 64 21).

The connection listens on its serial port as long as its node is subscribed to, and the node's value is always set to the last message recieved. Messages can be sent to the serial port using the "send message" action, which allows you to choose start and end codes different from those used for incoming messages.

## Modifying/Extending the DSLink

This DSLink is partially intended as a template for DSLinks that use serial communication. 

If you are writing a DSLink for a specific protocol, you will probably want to remove the "Start Code" and "End Code" parameters, and possibly add other parameters specific to the protocol. Do so by editing SerialLink.makeAddConnAction(), SerialLink.handleAddConn(), SerialConn.makeEditAction(), and SerialConn.handleEdit(). You will probably need to tweak SerialConn.init() as well.

You will need to modify SerialConn.readWhileAvailable() to customize how the DSLink parses incoming data. As long as the node is subscibed to, readWhileAvailable() gets called repeatedly, with a half-second delay between the end of one call and the start of the next (See SerialConn.subscribe()). So the code inside the while loop of this method executes for every byte that's read from the port.

Edit SerialConn.handleSend() to change how outgoing messages are encoded. You may also want to edit SerialConn.makeSendAction() to change what parameters the "send message" action has.

For an example of a protocol-specific DSLink based on this one, see https://github.com/IOT-DSA/dslink-java-dmx-device 
