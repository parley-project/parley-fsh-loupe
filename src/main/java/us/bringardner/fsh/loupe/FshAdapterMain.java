/**
 * <PRE>
 *
 * Copyright Tony Bringardner 1998, 2026 <A href="http://bringardner.com/tony">Tony Bringardner</A>
 *
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       <A href="http://www.apache.org/licenses/LICENSE-2.0">http://www.apache.org/licenses/LICENSE-2.0</A>
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  </PRE>
 *
 *
 *	@author Tony Bringardner
 *
 *
 */
package us.bringardner.fsh.loupe;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;

import us.bringardner.parley.loupe.AdapterHandler;
import us.bringardner.parley.loupe.LoupeConnection;
import us.bringardner.parley.loupe.LoupeEndpoint;
import us.bringardner.parley.loupe.protocol.Loupe;
import us.bringardner.parley.loupe.protocol.PeerInfo;

/**
 * The Loupe adapter for fsh. With no arguments it speaks Loupe on its standard input and
 * output, so {@code DapBridgeMain --exec java -cp ... us.bringardner.fsh.loupe.FshAdapterMain}
 * is a DAP debugger for fsh scripts; {@code --listen port} accepts connections one at a time
 * on the loopback address instead.
 */
public final class FshAdapterMain {

	private FshAdapterMain() {
	}

	/** A handler for a connection; every connection gets its own backend (one script each). */
	public static AdapterHandler handler(LoupeEndpoint endpoint) {
		return new AdapterHandler(endpoint, new FshBackend())
				.setAdapter(new PeerInfo().setName("fsh-loupe").setVersion("1"))
				.setFeatures(List.of(Loupe.FEATURE_SNAPSHOT))
				.setLaunchSchema(FshBackend.launchSchema());
	}

	public static void main(String[] args) throws IOException {
		if( args.length == 2 && args[0].equals("--listen")) {
			try (ServerSocket server = new ServerSocket(Integer.parseInt(args[1]), 1, InetAddress.getLoopbackAddress())) {
				for(;;) {
					Socket s = server.accept();
					LoupeConnection c = LoupeConnection.serve(FshAdapterMain::handler, s.getInputStream(), s.getOutputStream());
					c.closed().join();
					s.close();
				}
			}
		}
		if( args.length != 0 ) {
			System.err.println("usage: FshAdapterMain [--listen port]");
			System.exit(2);
		}
		// Standard output carries the protocol: nothing else may write to it
		java.io.PrintStream protocolOut = System.out;
		System.setOut(System.err);
		LoupeConnection c = LoupeConnection.serve(FshAdapterMain::handler, System.in, protocolOut);
		c.closed().join();
	}
}
