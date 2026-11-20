package com.tradexa.dukascopy.diagnostic;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;
import javax.net.ssl.HttpsURLConnection;

/**
 * Installs handlers only for http and https. Every other protocol returns
 * null so java.net.URL keeps the platform handler.
 */
final class ObservingURLStreamHandlerFactory implements URLStreamHandlerFactory {
    private final HttpObservation observation;

    ObservingURLStreamHandlerFactory(HttpObservation observation) {
        this.observation = observation;
    }

    static void install(HttpObservation observation) {
        URL.setURLStreamHandlerFactory(
            new ObservingURLStreamHandlerFactory(observation)
        );
    }

    public URLStreamHandler createURLStreamHandler(String protocol) {
        if ("http".equalsIgnoreCase(protocol)) {
            return new ObservingHttpHandler(observation);
        }
        if ("https".equalsIgnoreCase(protocol)) {
            return new ObservingHttpsHandler(observation);
        }
        return null;
    }
}

final class ObservingHttpHandler extends sun.net.www.protocol.http.Handler {
    private final HttpObservation observation;

    ObservingHttpHandler(HttpObservation observation) {
        this.observation = observation;
    }

    protected URLConnection openConnection(URL url) throws IOException {
        return wrap(super.openConnection(url));
    }

    protected URLConnection openConnection(URL url, Proxy proxy) throws IOException {
        return wrap(super.openConnection(url, proxy));
    }

    private URLConnection wrap(URLConnection real) {
        return new DelegatingHttpURLConnection(
            (HttpURLConnection) real,
            observation
        );
    }
}

final class ObservingHttpsHandler extends sun.net.www.protocol.https.Handler {
    private final HttpObservation observation;

    ObservingHttpsHandler(HttpObservation observation) {
        this.observation = observation;
    }

    protected URLConnection openConnection(URL url) throws IOException {
        return wrap(super.openConnection(url));
    }

    protected URLConnection openConnection(URL url, Proxy proxy) throws IOException {
        return wrap(super.openConnection(url, proxy));
    }

    private URLConnection wrap(URLConnection real) {
        return new DelegatingHttpsURLConnection(
            (HttpsURLConnection) real,
            observation
        );
    }
}
