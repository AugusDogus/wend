package dev.augie.wend;

import java.net.InetAddress;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AgentServerTest {
    private static final String GATEWAY = "100.109.37.39", UNIT = "100.112.208.29";

    private static boolean allowed(String from, String to, String peers) throws Exception {
        return AgentServer.allowed(InetAddress.getByName(from), InetAddress.getByName(to), peers);
    }

    @Test public void theUnitItselfMayAlways() throws Exception {
        assertTrue(allowed("127.0.0.1", "127.0.0.1", null));
    }

    @Test public void aListedTailnetPeerMayOverTheTailnet() throws Exception {
        assertTrue(allowed(GATEWAY, UNIT, GATEWAY));
        assertTrue("Listed among others", allowed(GATEWAY, UNIT, "100.90.202.82, " + GATEWAY));
    }

    @Test public void noOneElseMay() throws Exception {
        assertFalse("No peers listed", allowed(GATEWAY, UNIT, null));
        assertFalse("No peers listed", allowed(GATEWAY, UNIT, ""));
        assertFalse("Another tailnet machine", allowed("100.96.192.84", UNIT, GATEWAY));
        assertFalse("The car's own network", allowed("192.168.43.20", "192.168.43.1", GATEWAY));
        assertFalse("A listed address, but not over the tailnet", allowed(GATEWAY, "192.168.43.1", GATEWAY));
    }
}
