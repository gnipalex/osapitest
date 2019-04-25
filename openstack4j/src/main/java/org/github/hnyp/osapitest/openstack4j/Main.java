package org.github.hnyp.osapitest.openstack4j;

import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.RequestLine;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.BufferedHttpEntity;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.github.hnyp.osapitest.common.Credentials;
import org.openstack4j.api.OSClient.OSClientV2;
import org.openstack4j.connectors.httpclient.HttpClientFactory;
import org.openstack4j.model.compute.Flavor;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.ext.AvailabilityZone;
import org.openstack4j.model.compute.ext.Hypervisor;
import org.openstack4j.model.network.Network;
import org.openstack4j.model.network.Port;
import org.openstack4j.openstack.OSFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

public class Main {

    public static void main(String[] args) {

//        OSFactory.enableHttpLoggingFilter(true);
//        SLF4JBridgeHandler.removeHandlersForRootLogger();
//        SLF4JBridgeHandler.install();

        HttpClientFactory.registerInterceptor((httpClientBuilder, requestConfig, config) -> {
            RequestResponseInterceptor interceptor = new RequestResponseInterceptor();
            httpClientBuilder.addInterceptorLast((HttpRequestInterceptor) interceptor);
            httpClientBuilder.addInterceptorLast((HttpResponseInterceptor) interceptor);
        });

        OSClientV2 os = OSFactory.builderV2()
                .endpoint(Credentials.KEYSTONE_AUTH_URL)
                .credentials(Credentials.USERNAME, Credentials.PASS)
                .tenantName(Credentials.TENANT)
                .authenticate();

        // 1) Get list of AZ
        // https://developer.openstack.org/api-ref/compute/?expanded=#get-detailed-availability-zone-information
        List<? extends AvailabilityZone> availabilityZones = os.compute().zones().list(true);
//        System.out.println("Availability zones: \n" + availabilityZones + "\n");
        // attributes:
        // zoneName - org.openstack4j.model.compute.ext.AvailabilityZone.getZoneName
        // zoneState.available - org.openstack4j.model.compute.ext.AvailabilityZone.ZoneState.getAvailable
        // hosts

        // 2.1) Get information basic about available resources in computes(hypervisors).
        // https://developer.openstack.org/api-ref/compute/?expanded=#list-hypervisors-details
        List<? extends Hypervisor> hypervisors = os.compute().hypervisors().list();
//        System.out.println("Hypervisors: \n" + hypervisors + "\n");
        // attributes:
        // free_ram_mb - org.openstack4j.model.compute.ext.Hypervisor.getFreeRam
        // leastDiskAvail - org.openstack4j.model.compute.ext.Hypervisor.getLeastDiskAvailable

        // 3) First filtration: based on <TODO, get info from CEPH, about disk, step #2.2> adn "free_ram_mb" values from step #2.1.
        //  Create list of computes, which is suitable by this values. (based on Flavor required size)

        // 4.1) Get list of VMs (filtration, based on compute is available only for this API call,
        // but filter in Nova applies only one compute, so all VMs could be received, instead of sending API for each node.
        // + other APIs dosn't support filtration by compute node):
        // https://developer.openstack.org/api-ref/compute/?expanded=#list-servers-detailed
        List<? extends Server> allServers = os.compute().servers().list(true);
//        System.out.println("VMs: \n" + allServers + "\n");
        // attributes:
        // "flavor" - org.openstack4j.model.compute.Server.getFlavorId
        //          - org.openstack4j.model.compute.Server.getFlavor - flavor details
        // "id" - org.openstack4j.model.compute.Server.getId
        // "OS-EXT-SRV-ATTR:host" - org.openstack4j.openstack.compute.domain.NovaServer.getHost

        // 4.2.1) Get list of flavors
        // https://developer.openstack.org/api-ref/compute/?expanded=list-flavors-with-details-detail#list-flavors-with-details
        List<? extends Flavor> allFlavors = os.compute().flavors().list(true);
//        System.out.println("Flavors: \n" + allFlavors + "\n");
        // attributes:
        // "id" - org.openstack4j.model.compute.Flavor.getId
        // "vcpus" - org.openstack4j.model.compute.Flavor.getVcpus

        // 4.3.1) Get list of vports
        // https://developer.openstack.org/api-ref/network/v2/index.html?expanded=list-ports-detail#list-ports
        List<? extends Port> allVports = os.networking().port().list();
//        System.out.println("vPorts: \n" + allVports + "\n");
        // attributes:
        //  "id" - org.openstack4j.model.common.IdEntity.getId
        //  "device_id" - org.openstack4j.model.network.Port.getDeviceId
        //  "network_id" - org.openstack4j.model.network.Port.getNetworkId

        // 4.4.1) Get list of networks
        // https://developer.openstack.org/api-ref/network/v2/index.html?expanded=list-networks-detail#list-networks
        List<? extends Network> allNetworks = os.networking().network().list();
//        System.out.println("Networks: \n" + allNetworks + "\n");
        // attributes:
        // "id" - org.openstack4j.model.common.IdEntity.getId
        // "provider:physical_network" - org.openstack4j.model.network.Network.getProviderPhyNet
    }

    static class RequestResponseInterceptor implements HttpRequestInterceptor, HttpResponseInterceptor {

        private static final Logger LOG = LoggerFactory.getLogger(RequestResponseInterceptor.class);

        @Override
        public void process(HttpRequest httpRequest, HttpContext httpContext) throws HttpException, IOException {
            String formattedRequestLine = formatRequestLine(httpRequest, httpContext);

            String bodyContent = "<empty>";
            if (httpRequest instanceof HttpEntityEnclosingRequest) {
                bodyContent = EntityUtils.toString(((HttpEntityEnclosingRequest) httpRequest).getEntity());
            }

            LOG.info("Outgoing request {}, with headers {}, with body {}", formattedRequestLine,
                    httpRequest.getAllHeaders(), bodyContent);
        }

        @Override
        public void process(HttpResponse httpResponse, HttpContext httpContext) throws HttpException, IOException {

            HttpClientContext context = (HttpClientContext) httpContext;
            HttpRequest request = ((HttpClientContext) httpContext).getRequest();

            String formattedRequestLine = formatRequestLine(request, httpContext);

            String bodyContent = "<empty>";
            if (httpResponse.getEntity() != null) {
                if (!httpResponse.getEntity().isRepeatable()) {
                    httpResponse.setEntity(new BufferedHttpEntity(httpResponse.getEntity()));
                }
                bodyContent = EntityUtils.toString(httpResponse.getEntity());
            }

            LOG.info("Incoming response {} from {}, with body {}", httpResponse.getStatusLine(), formattedRequestLine,
                    bodyContent);
        }

        private static String formatRequestLine(HttpRequest request, HttpContext context) {
            String targetHost = ((HttpClientContext) context).getTargetHost().toString();
            RequestLine requestLine = request.getRequestLine();
            return String.format("%s %s%s", requestLine.getMethod(),
                    targetHost, requestLine.getUri());
        }

    }


}
