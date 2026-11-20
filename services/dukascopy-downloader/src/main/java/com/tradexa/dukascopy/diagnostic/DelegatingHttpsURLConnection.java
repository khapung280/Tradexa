package com.tradexa.dukascopy.diagnostic;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.ProtocolException;
import java.net.URL;
import java.security.Permission;
import java.security.Principal;
import java.security.cert.Certificate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocketFactory;

/**
 * Pass-through HttpsURLConnection. SSL settings and the response body stay
 * on the JDK connection created by the platform HTTPS handler.
 */
final class DelegatingHttpsURLConnection extends HttpsURLConnection {
    private final AttemptRecorder recorder;

    DelegatingHttpsURLConnection(
            HttpsURLConnection delegate,
            HttpObservation observation) {
        super(delegate.getURL());
        this.recorder = new AttemptRecorder(delegate, observation);
    }

    public String getCipherSuite() {
        return ((HttpsURLConnection) recorder.delegate()).getCipherSuite();
    }

    public Certificate[] getLocalCertificates() {
        return ((HttpsURLConnection) recorder.delegate()).getLocalCertificates();
    }

    public Certificate[] getServerCertificates() throws SSLPeerUnverifiedException {
        return ((HttpsURLConnection) recorder.delegate()).getServerCertificates();
    }

    public Principal getPeerPrincipal() throws SSLPeerUnverifiedException {
        return ((HttpsURLConnection) recorder.delegate()).getPeerPrincipal();
    }

    public Principal getLocalPrincipal() {
        return ((HttpsURLConnection) recorder.delegate()).getLocalPrincipal();
    }

    public void setHostnameVerifier(HostnameVerifier verifier) {
        ((HttpsURLConnection) recorder.delegate()).setHostnameVerifier(verifier);
    }

    public HostnameVerifier getHostnameVerifier() {
        return ((HttpsURLConnection) recorder.delegate()).getHostnameVerifier();
    }

    public void setSSLSocketFactory(SSLSocketFactory factory) {
        ((HttpsURLConnection) recorder.delegate()).setSSLSocketFactory(factory);
    }

    public SSLSocketFactory getSSLSocketFactory() {
        return ((HttpsURLConnection) recorder.delegate()).getSSLSocketFactory();
    }

    public Optional<javax.net.ssl.SSLSession> getSSLSession() {
        return ((HttpsURLConnection) recorder.delegate()).getSSLSession();
    }

    public void connect() throws IOException {
        recorder.delegate().connect();
    }

    public void disconnect() {
        recorder.delegate().disconnect();
    }

    public boolean usingProxy() {
        return recorder.delegate().usingProxy();
    }

    public void setConnectTimeout(int timeout) {
        recorder.delegate().setConnectTimeout(timeout);
    }

    public int getConnectTimeout() {
        return recorder.delegate().getConnectTimeout();
    }

    public void setReadTimeout(int timeout) {
        recorder.delegate().setReadTimeout(timeout);
    }

    public int getReadTimeout() {
        return recorder.delegate().getReadTimeout();
    }

    public URL getURL() {
        return recorder.delegate().getURL();
    }

    public int getContentLength() {
        return recorder.delegate().getContentLength();
    }

    public long getContentLengthLong() {
        return recorder.delegate().getContentLengthLong();
    }

    public String getContentType() {
        return recorder.delegate().getContentType();
    }

    public String getContentEncoding() {
        return recorder.delegate().getContentEncoding();
    }

    public long getExpiration() {
        return recorder.delegate().getExpiration();
    }

    public long getDate() {
        return recorder.delegate().getDate();
    }

    public long getLastModified() {
        return recorder.delegate().getLastModified();
    }

    public String getHeaderField(String name) {
        return recorder.delegate().getHeaderField(name);
    }

    public Map<String, List<String>> getHeaderFields() {
        return recorder.delegate().getHeaderFields();
    }

    public int getHeaderFieldInt(String name, int defaultValue) {
        return recorder.delegate().getHeaderFieldInt(name, defaultValue);
    }

    public long getHeaderFieldLong(String name, long defaultValue) {
        return recorder.delegate().getHeaderFieldLong(name, defaultValue);
    }

    public long getHeaderFieldDate(String name, long defaultValue) {
        return recorder.delegate().getHeaderFieldDate(name, defaultValue);
    }

    public String getHeaderFieldKey(int index) {
        return recorder.delegate().getHeaderFieldKey(index);
    }

    public String getHeaderField(int index) {
        return recorder.delegate().getHeaderField(index);
    }

    public Object getContent() throws IOException {
        return recorder.delegate().getContent();
    }

    public Object getContent(Class<?>[] classes) throws IOException {
        return recorder.delegate().getContent(classes);
    }

    public Permission getPermission() throws IOException {
        return recorder.delegate().getPermission();
    }

    public InputStream getInputStream() throws IOException {
        return recorder.input();
    }

    public OutputStream getOutputStream() throws IOException {
        return recorder.delegate().getOutputStream();
    }

    public String toString() {
        return recorder.delegate().toString();
    }

    public void setDoInput(boolean doInput) {
        recorder.delegate().setDoInput(doInput);
    }

    public boolean getDoInput() {
        return recorder.delegate().getDoInput();
    }

    public void setDoOutput(boolean doOutput) {
        recorder.delegate().setDoOutput(doOutput);
    }

    public boolean getDoOutput() {
        return recorder.delegate().getDoOutput();
    }

    public void setAllowUserInteraction(boolean allow) {
        recorder.delegate().setAllowUserInteraction(allow);
    }

    public boolean getAllowUserInteraction() {
        return recorder.delegate().getAllowUserInteraction();
    }

    public void setUseCaches(boolean useCaches) {
        recorder.delegate().setUseCaches(useCaches);
    }

    public boolean getUseCaches() {
        return recorder.delegate().getUseCaches();
    }

    public void setIfModifiedSince(long ifModifiedSince) {
        recorder.delegate().setIfModifiedSince(ifModifiedSince);
    }

    public long getIfModifiedSince() {
        return recorder.delegate().getIfModifiedSince();
    }

    public boolean getDefaultUseCaches() {
        return recorder.delegate().getDefaultUseCaches();
    }

    public void setDefaultUseCaches(boolean defaultUseCaches) {
        recorder.delegate().setDefaultUseCaches(defaultUseCaches);
    }

    public void setRequestProperty(String key, String value) {
        recorder.delegate().setRequestProperty(key, value);
    }

    public void addRequestProperty(String key, String value) {
        recorder.delegate().addRequestProperty(key, value);
    }

    public String getRequestProperty(String key) {
        return recorder.delegate().getRequestProperty(key);
    }

    public Map<String, List<String>> getRequestProperties() {
        return recorder.delegate().getRequestProperties();
    }

    public void setFixedLengthStreamingMode(int contentLength) {
        recorder.delegate().setFixedLengthStreamingMode(contentLength);
    }

    public void setFixedLengthStreamingMode(long contentLength) {
        recorder.delegate().setFixedLengthStreamingMode(contentLength);
    }

    public void setChunkedStreamingMode(int chunkLength) {
        recorder.delegate().setChunkedStreamingMode(chunkLength);
    }

    public void setInstanceFollowRedirects(boolean followRedirects) {
        recorder.delegate().setInstanceFollowRedirects(followRedirects);
    }

    public boolean getInstanceFollowRedirects() {
        return recorder.delegate().getInstanceFollowRedirects();
    }

    public void setRequestMethod(String method) throws ProtocolException {
        recorder.delegate().setRequestMethod(method);
    }

    public String getRequestMethod() {
        return recorder.delegate().getRequestMethod();
    }

    public int getResponseCode() throws IOException {
        return recorder.responseCode();
    }

    public String getResponseMessage() throws IOException {
        return recorder.delegate().getResponseMessage();
    }

    public InputStream getErrorStream() {
        return recorder.errorStream();
    }

    public void setAuthenticator(Authenticator authenticator) {
        recorder.delegate().setAuthenticator(authenticator);
    }
}
