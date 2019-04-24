package org.github.hnyp.osapitest.osjavasdk;

import static com.woorea.openstack.keystone.utils.KeystoneUtils.findEndpointURL;
import static java.util.stream.Collectors.toList;

import com.woorea.openstack.base.client.OpenStackSimpleTokenProvider;
import com.woorea.openstack.keystone.Keystone;
import com.woorea.openstack.keystone.model.Access;
import com.woorea.openstack.keystone.model.authentication.UsernamePassword;
import com.woorea.openstack.nova.Nova;
import com.woorea.openstack.nova.model.Flavor;
import com.woorea.openstack.nova.model.Hosts.Host;
import com.woorea.openstack.nova.model.Hypervisor;
import com.woorea.openstack.nova.model.Server;
import com.woorea.openstack.quantum.Quantum;
import com.woorea.openstack.quantum.model.Network;
import com.woorea.openstack.quantum.model.Port;
import org.github.hnyp.osapitest.common.Credentials;

import java.util.List;

public class Main {

    public static void main(String[] args) {

        Keystone keystone = new Keystone(Credentials.KEYSTONE_AUTH_URL);
        Access access = keystone.tokens()
                .authenticate(new UsernamePassword(Credentials.USERNAME, Credentials.PASS))
                .withTenantName(Credentials.TENANT)
                .execute();

        System.out.println(access);

        //use the token in the following requests
        keystone.token(access.getToken().getId());

        Nova novaClient = new Nova(findEndpointURL(access.getServiceCatalog(), "compute", null, "public"));
        novaClient.token(access.getToken().getId());

        // 1.1) select AZ based on "name" and supported sriov feature.
        //
        // todo there's no api to get AVAILABILITY ZONES atm
        // https://github.com/woorea/openstack-java-sdk/issues/148
        // https://github.com/woorea/openstack-java-sdk/pull/149/commits/bbb9da81e26c0221254e0d8f9ecea2a265f07e30

        // 1.2) create list of "hosts" which is available

        List<Host> computeHosts = novaClient.hosts().list().execute()
                .getList().stream()
                .filter(h -> "compute".equals(h.getService()))
                .collect(toList());
        System.out.println("Nova compute hosts");
        System.out.println(computeHosts);

        // attributes:
        // zone - ok
        // hostName - ok
        // service -?
        // availability -?

        // 2.1) Get information basic about available resources in computes(hypervisors).
        // https://developer.openstack.org/api-ref/compute/?expanded=#list-hypervisors-details

        List<Hypervisor> allHypervisors = novaClient.hypervisors().list().execute()
                .getList();

        // attributes:
        // todo Hypervisor dto contains no info except 'hypervisor_hostname' and 'id'


        // 4.1) Get list of VMs (filtration, based on compute is available only for this API call,
        // but filter in Nova applies only one compute, so all VMs could be received,
        // instead of sending API for each node. + other APIs dosn't support filtration by compute node):

        List<Server> allServers = novaClient.servers().list(true).execute().getList();
        // attributes:
        // id - ok
        // flavor - ok
        // OS-EXT-SRV-ATTR:host - ok


        // 4.2.1) Get list of flavors
        // https://developer.openstack.org/api-ref/compute/?expanded=list-flavors-with-details-detail#list-flavors-with-details

        List<Flavor> allFlavors = novaClient.flavors().list(true).execute().getList();
        // attributes:
        // id - ok
        // vcpus - ok



        Quantum neutronClient = new Quantum(findEndpointURL(access.getServiceCatalog(), "network", null, "public"));
        neutronClient.setTokenProvider(new OpenStackSimpleTokenProvider(access.getToken().getId()));

        // 4.3.1) Get list of vports
        // https://developer.openstack.org/api-ref/network/v2/index.html?expanded=list-ports-detail#list-ports
        List<Port> allPorts = neutronClient.ports().list().execute().getList();
        // dto attributes:
        // id - ok
        // device_id - ok
        // network_id - ok

        // todo api does not work


        // 4.4.1) Get list of networks
        // https://developer.openstack.org/api-ref/network/v2/index.html?expanded=list-networks-detail#list-networks

        List<Network> allNetworks = neutronClient.networks().list().execute().getList();
        // attributes:
        // id -ok
        // provider:physical_network - ok

        // todo api does not work

        // todo - seems that api v2 is not supported
        // GET https://stg-nfv.sirius.pn.telstra.com:9696/ports
        // >>
        // 404 Not Found
        // Unknown API version specified
    }

}
