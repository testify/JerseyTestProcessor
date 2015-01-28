/*
 * Copyright 2015 Codice Foundation
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package org.codice.testify.processors.Jersey;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import org.codice.testify.objects.TestifyLogger;
import org.codice.testify.objects.Request;
import org.codice.testify.objects.Response;
import org.codice.testify.processors.TestProcessor;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

import javax.net.ssl.*;
import javax.ws.rs.core.MediaType;
import java.io.InputStream;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Scanner;

/**
 * The JerseyTestProcessor class is a Testify TestProcessor service to create a REST message from a string and retrieve a response
 */
public class JerseyTestProcessor implements BundleActivator, TestProcessor {

    @Override
    public Response executeTest(Request request) {

        TestifyLogger.debug("Running JerseyTestProcessor", this.getClass().getSimpleName());

        //Split operationBlock from testBlock
        String operationBlock = request.getTestBlock().substring(request.getTestBlock().indexOf("<operation>") + 11, request.getTestBlock().indexOf("</operation>")).trim();
        TestifyLogger.debug("REST Operation: " + operationBlock, this.getClass().getSimpleName());

        //If there is a <body> element in the test block separate it out
        String testBlock = null;
        if (request.getTestBlock().contains("<body>")) {
            testBlock = request.getTestBlock().substring(request.getTestBlock().indexOf("<body>") + 6, request.getTestBlock().indexOf("</body>")).trim();
            TestifyLogger.debug("REST Body: " + testBlock, this.getClass().getSimpleName());
        }

        // Trust all certificates
        try {
            doTrustToCertificates();
        } catch (NoSuchAlgorithmException e) {
            TestifyLogger.error("NoSuchAlgorithmException: " + e.getMessage(), this.getClass().getSimpleName());
            return new Response(null);
        } catch (KeyManagementException e) {
            TestifyLogger.error("KeyManagementException: " + e.getMessage(), this.getClass().getSimpleName());
            return new Response(null);
        }

        //Create a rest client pointing to the given endpoint
        Client client = Client.create();

        if (request.getEndpoint().contains("${")) {
            TestifyLogger.error("Endpoint: " + request.getEndpoint() + " contains a property that has not been expanded", this.getClass().getSimpleName());

            return new Response(null);
        }
        WebResource.Builder webResource = client.resource(request.getEndpoint()).getRequestBuilder();

        //If there is a <header> element in the test block, separate it out, split it into individual headers, and add them to the request
        if (request.getTestBlock().contains("<header>")) {
            String headerBlock = request.getTestBlock().substring(request.getTestBlock().indexOf("<header>") + 8, request.getTestBlock().indexOf("</header>")).trim();
            TestifyLogger.debug("REST Header: " + headerBlock, this.getClass().getSimpleName());

            //Split the header block into header lines
            String[] headers = headerBlock.split( System.lineSeparator() );

            //Add each header to the request
            for (String headerLine : headers) {
                if (headerLine.contains(":")) {
                    int headerBlockIndex = headerLine.indexOf(":");
                    webResource = webResource.header(headerLine.substring(0, headerBlockIndex), headerLine.substring(headerBlockIndex + 2, headerLine.length()));
                } else {
                    TestifyLogger.error("Headers must be provided in the following format -> Header_Type: Header_Value", this.getClass().getSimpleName());
                }
            }
        }

        //If there is a <media> element in the test block separate it out and add it to the request
        if (request.getTestBlock().contains("<media>")) {
            String mediaType = request.getTestBlock().substring(request.getTestBlock().indexOf("<media>") + 7, request.getTestBlock().indexOf("</media>")).trim();
            TestifyLogger.debug("Media Type: " + mediaType, this.getClass().getSimpleName());

            //Check the media type
            switch (mediaType.toLowerCase()) {
                case "application/json":
                    webResource.type(MediaType.APPLICATION_JSON_TYPE);
                    break;
                case "application/xml":
                    webResource.type(MediaType.APPLICATION_XML_TYPE);
                    break;
                case "application/octet-stream":
                    webResource.type(MediaType.APPLICATION_OCTET_STREAM_TYPE);
                    break;
                case "text/xml":
                    webResource.type(MediaType.TEXT_XML_TYPE);
                    break;
                case "multipart/form-data":
                    webResource.type(MediaType.MULTIPART_FORM_DATA_TYPE);
                    break;
            }
        }

        //Run the given operation and save data as a clientResponse object
        ClientResponse clientResponse = null;
        switch (operationBlock.toUpperCase()) {
            case "DELETE":
                clientResponse = webResource.delete(ClientResponse.class);
                break;
            case "GET":
                clientResponse = webResource.get(ClientResponse.class);
                break;
            case "HEAD":
                clientResponse = webResource.head();
                break;
            case "POST":
                clientResponse = webResource.post(ClientResponse.class, testBlock);
                break;
            case "PUT":
                clientResponse = webResource.put(ClientResponse.class, testBlock);
                break;
            default:
                TestifyLogger.error("Operation: " + operationBlock.toUpperCase() + " is not a REST operation", this.getClass().getSimpleName());
        }

        //If clientResponse is not null, return it. Otherwise return a null Response object.
        if (clientResponse != null) {
            Response restResponse;

            //Add response string if one exists, otherwise set to null
            InputStream inputStream = clientResponse.getEntityInputStream();
            if (inputStream != null) {
                Scanner scanner = new Scanner(inputStream);
                String responseString = "";
                while (scanner.hasNext()) {
                    responseString = responseString + scanner.nextLine();
                }
                scanner.close();
                restResponse = new Response(responseString);
            } else {
                restResponse = new Response(null);
            }

            //Add response code
            restResponse.setResponseCode(clientResponse.getStatus());

            //If there are returned headers, set them in response object
            if (clientResponse.getHeaders() != null) {
                restResponse.setResponseHeaders(clientResponse.getHeaders().toString());
            }

            return restResponse;

        } else {
            return new Response(null);
        }
    }

    // Purpose: Accept all certificates
    // Reference: https://gist.github.com/sandeepkunkunuru/7030828
    //
    public static void doTrustToCertificates() throws NoSuchAlgorithmException, KeyManagementException {
        //Security.addProvider(new Provider()):
        TrustManager[] trustAllCerts = new TrustManager[] { new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
                return;
            }

            @Override
            public void checkServerTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
                return;
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return null;
            }
        } };

        // Set HttpsURLConnection settings
        SSLContext sslContext = SSLContext.getInstance("SSL");
        sslContext.init(null, trustAllCerts, new SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
        HostnameVerifier hostnameVerifier = new HostnameVerifier() {
            @Override
            public boolean verify(String s, SSLSession sslSession) {
                return s.equalsIgnoreCase(sslSession.getPeerHost());
            }
        };
        HttpsURLConnection.setDefaultHostnameVerifier(hostnameVerifier);
    }

    @Override
    public void start(BundleContext bundleContext) throws Exception {

        //Register the JerseyTestProcessor service
        bundleContext.registerService(TestProcessor.class.getName(), new JerseyTestProcessor(), null);

    }

    @Override
    public void stop(BundleContext bundleContext) throws Exception {

    }
}