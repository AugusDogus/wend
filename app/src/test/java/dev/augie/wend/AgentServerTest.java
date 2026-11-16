package dev.augie.wend;

import java.net.InetAddress;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AgentServerTest {
    private static final String GATEWAY = "100.109.37.39", UNIT = "100.112.208.29";

    private static boolean allowed(String from, String to) throws Exception {
        return AgentServer.allowed(InetAddress.getByName(from), InetAddress.getByName(to));
    }

    @Test public void theUnitItselfMay() throws Exception {
        assertTrue(allowed("127.0.0.1", "127.0.0.1"));
    }

    @Test public void anyTailnetMachineMayOverTheTailnet() throws Exception {
        assertTrue(allowed(GATEWAY, UNIT));
        assertTrue(allowed("100.96.192.84", UNIT));
    }

    @Test public void nothingElseMay() throws Exception {
        assertFalse("The car's own network", allowed("192.168.43.20", "192.168.43.1"));
        assertFalse("A tailnet address, but not over the tailnet", allowed(GATEWAY, "192.168.43.1"));
        assertFalse("To the tailnet address, from elsewhere", allowed("192.168.43.20", UNIT));
    }
}
