package dev.octoshrimpy.quik.feature.phone;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SipUriTest {
    private final SipConfigStore.Profile profile = new SipConfigStore.Profile(
        true, "pbx.example.net", 5061, "108", "not-used", "*", SipConfigStore.Transport.TLS);

    @Test public void extractsPlainSipUser() {
        assertEquals("108", SipUri.user("sip:108@pbx.example.net"));
    }

    @Test public void extractsDisplayNameSipUser() {
        assertEquals("61400111222", SipUri.user("\"Caller\" <sip:61400111222@pbx.example.net>;tag=abc"));
    }

    @Test public void buildsDestinationFromExtension() {
        assertEquals("sip:109@pbx.example.net:5061;transport=tls", SipUri.destination("109", profile));
    }

    @Test public void preservesFullSipUri() {
        assertEquals("sip:109@other.example.net", SipUri.destination("sip:109@other.example.net", profile));
    }

    @Test public void usesConfiguredTlsProxyForEveryRequest() {
        assertEquals("sip:pbx.example.net:5061;transport=tls;lr", SipUri.proxy(profile));
    }

    @Test public void supportsNonstandardTcpPort() {
        SipConfigStore.Profile tcp = new SipConfigStore.Profile(true, "pbx.example.net", 5091,
            "extension", "not-used", "*", SipConfigStore.Transport.TCP);
        assertEquals("sip:109@pbx.example.net:5091;transport=tcp", SipUri.destination("109", tcp));
    }

    @Test public void supportsAddressWithoutScheme() {
        assertEquals("sip:109@other.example.net", SipUri.destination("109@other.example.net", profile));
    }

    @Test public void extractsSecureSipUser() {
        assertEquals("109", SipUri.user("Name <sips:109@pbx.example.net>;tag=x"));
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsHeaderInjection() {
        SipUri.destination("109\r\nRoute: evil", profile);
    }

    @Test public void supportsIpv6Server() {
        SipConfigStore.Profile ipv6 = new SipConfigStore.Profile(true, "2001:db8::1", 5061,
            "extension", "not-used", "*", SipConfigStore.Transport.TLS);
        assertEquals("sip:109@[2001:db8::1]:5061;transport=tls", SipUri.destination("109", ipv6));
    }
}
