package se.ostberg.wiremock.extension;

import com.github.tomakehurst.wiremock.extension.Extension;

/**
 * Assembles all WireMock extensions as a single combined instance.
 */
public class ExtensionBundle {

    private final Extension[] extensions;

    private ExtensionBundle() {
        extensions = new Extension[]{ new WireMockExtensions() };
    }

    public static ExtensionBundle create() {
        return new ExtensionBundle();
    }

    /** All extensions ready to pass to WireMockConfiguration.extensions(). */
    public Extension[] getExtensions() {
        return extensions;
    }
}
