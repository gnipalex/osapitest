package org.github.hnyp.osapitest.jclouds;

import static com.google.common.collect.Sets.newHashSet;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toMap;

import com.google.common.collect.Sets;
import com.google.inject.Module;
import org.github.hnyp.osapitest.common.Credentials;
import org.jclouds.ContextBuilder;
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.jclouds.openstack.keystone.config.KeystoneProperties;
import org.jclouds.openstack.neutron.v2.NeutronApi;
import org.jclouds.openstack.neutron.v2.domain.Network;
import org.jclouds.openstack.neutron.v2.domain.Port;
import org.jclouds.openstack.nova.v2_0.NovaApi;
import org.jclouds.openstack.nova.v2_0.domain.Flavor;
import org.jclouds.openstack.nova.v2_0.domain.Server;
import org.jclouds.openstack.nova.v2_0.domain.Server.Status;
import org.jclouds.openstack.nova.v2_0.domain.regionscoped.AvailabilityZoneDetails;
import org.jclouds.openstack.nova.v2_0.domain.regionscoped.AvailabilityZoneDetails.HostService;
import org.jclouds.openstack.nova.v2_0.domain.regionscoped.HypervisorDetails;
import org.jclouds.openstack.v2_0.domain.Resource;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class Main {

    public static final Set<String> AZ_NAMES = newHashSet("nova");
    public static final Set<Status> ACTIVE_VM_STATUSES = newHashSet(Status.ACTIVE, Status.BUILD);

    public static final int INPUT_RAM_MB = 1024;
    public static final int INPUT_DISK_GB = 10;
    public static final int INPUT_CPUS = 2;
    public static final int INPUT_VPORTS_COUNT = 5;

    public static void main(String[] args) throws IOException {
        System.out.println("Nova API");

        NovaApi novaApi = initApi("openstack-nova", NovaApi.class);

        Set<String> configuredRegions = novaApi.getConfiguredRegions();
        String region = configuredRegions.iterator().next();
        System.out.println("Configured regions " + configuredRegions);

        // 1) Get list of AZ https://developer.openstack.org/api-ref/compute/?expanded=#get-detailed-availability-zone-information
        // 1.1) select AZ based on "name" and supported sriov feature.
        List<AvailabilityZoneDetails> activeZones = novaApi.getAvailabilityZoneApi(region).get().listInDetail()
                .filter(az -> AZ_NAMES.contains(az.getName()))
                .filter(az -> az.getState().isAvailable())
                .toList();

        // attributes:
        // available - ok
        // hosts info - ok

        System.out.println("Availability zones in state 'available'");
        activeZones.forEach(it -> System.out.println("  az # " + it));

        // 1.2) create list of "hosts" which is available
        Set<String> activeComputeHosts = activeZones.stream()
                .map(az -> az.getHosts()
                        .entrySet().stream()
                        .filter(e -> ofNullable(e.getValue().get("nova-compute"))
                                .map(HostService::isActive)
                                .orElse(false)
                        )
                        .map(Map.Entry::getKey)
                        .collect(Collectors.toSet())
                )
                .flatMap(Set::stream)
                .collect(Collectors.toSet());

        System.out.println("Active compute hosts for availability zones (having 'nova-compute' host service)");
        activeComputeHosts.forEach(h -> System.out.println("  compute host # " + h));

        // 2.1) Get information basic about available resources in computes(hypervisors).
        // https://developer.openstack.org/api-ref/compute/?expanded=#list-hypervisors-details
        //
        // no query params thus retrieve all compute hypervisors
        Map<String, HypervisorDetails> hypervisiorsDetailsPerHostName = novaApi.getHypervisorApi(region).get()
                .listInDetail().toList().stream()
                .filter(hv -> activeComputeHosts.contains(hv.getName()))
                .collect(toMap(HypervisorDetails::getName, Function.identity()));

        System.out.println("Hypervisors on active compute hosts");
        hypervisiorsDetailsPerHostName.values().forEach(it -> System.out.println("  hyperv # " + it));
        // attributes:
        // free_ram_mb - ok
        // diskAvailableLeast - ok

        // 2.2) Get Disk usage from CEPH
        // (TODO)

        // ELSE if FARM does not use CEPH then disk space is retrieved from compute hypervisor
        Map<String, ComputeResource> computeHostResources = hypervisiorsDetailsPerHostName.values().stream()
                .collect(toMap(HypervisorDetails::getName, it -> {
                    ComputeResource resources = new ComputeResource();
                    resources.freeDiskSpace = it.getDiskAvailableLeast();
                    resources.freeRam = it.getFreeRamMb();
                    return resources;
                }));

        // 3) First filtration: based on <TODO, get info from CEPH, about disk, step #2.2>
        //  and "free_ram_mb" values from step #2.1.
        //  Create list of computes, which is suitable by this values. (based on Flavor required size)
        Map<String, ComputeResource> filteredComputeHostResources = computeHostResources.entrySet().stream()
                .filter(e -> {
                    ComputeResource res = e.getValue();
                    return res.freeDiskSpace >= INPUT_DISK_GB
                            && res.freeRam >= INPUT_RAM_MB;
                }).collect(toMap(Entry::getKey, Entry::getValue));

        // 4.1) Get list of VMs (filtration, based on compute is available only for this API call, but filter in Nova applies only one compute,
        // so all VMs could be received, instead of sending API for each node. + other APIs dosn't support filtration by compute node):
        Map<String, List<Server>> activeServersPerComputeHost = novaApi.getServerApi(region).listInDetail().concat()
                .toList().stream()
                .filter(vm -> ACTIVE_VM_STATUSES.contains(vm.getStatus()))
                // filter VMs only for computes which have enough RAM/HDD (filtered in step 3.)
                .filter(vm -> filteredComputeHostResources
                        .containsKey(vm.getExtendedAttributes().get().getHostName()))
                .collect(Collectors.groupingBy(vm -> vm.getExtendedAttributes().get().getHostName()));
        // attributes:
        // flavor - ok
        // id - ok
        // OS-EXT-SRV-ATTR:host - ok

        System.out.println("Active VMs");
        activeServersPerComputeHost.entrySet()
                .forEach(e -> System.out.println(" compute " + e.getKey() + " # " + e.getValue()));

        // 4.2.1) Get list of flavors
        // https://developer.openstack.org/api-ref/compute/?expanded=list-flavors-with-details-detail#list-flavors-with-details
        List<Flavor> allFlavors = novaApi.getFlavorApi(region).listInDetail().concat().toList();
        System.out.println("All flavors");
        allFlavors.forEach(f -> System.out.println("  # " + f));

        // attributes:
        // id - ok
        // vcpus - ok

        Map<String, Integer> flavorToVCPUs = allFlavors.stream()
                .collect(toMap(Resource::getId, Flavor::getVcpus));

        // 4.2.2) Calculate used "vcpus" per compute.
        // It could be done via following values: VM ↔ OS-EXT-SRV-ATTR:host, VM.flavor ↔ Flavor.id, Flavor.vcpus.
        activeServersPerComputeHost.entrySet().stream()
                .collect(toMap(Entry::getKey,
                        e -> e.getValue().stream()
                                .map(vm -> flavorToVCPUs.get(vm.getFlavor().getId()))
                                .filter(Objects::nonNull)
                                .mapToInt(Integer::valueOf)
                                .sum()
                ))
                .forEach((host, vcpus) -> filteredComputeHostResources.computeIfPresent(host, (k, v) -> {
                    v.usedvCPUs = vcpus;
                    return v;
                }));

        System.out.println("Compute host resouces filled with vCPUs info");
        filteredComputeHostResources.forEach((k, v) -> System.out.println(" compute " + k + " # " + v));

        novaApi.close();

        System.out.println("\nNeutron API\n");

        NeutronApi neutronApi = initApi("openstack-neutron", NeutronApi.class);

        // 4.3.1) Get list of vports
        // https://developer.openstack.org/api-ref/network/v2/index.html?expanded=list-ports-detail#list-ports

        List<Port> allPorts = neutronApi.getPortApi(region).list().concat().toList();
        //  System.out.println("All ports for region " + region);
        //  allPorts.forEach(p -> System.out.println("  # " + p));

        // attributes:
        // id - ok
        // device_id - ok
        // network_id - ok

        // 4.3.2) Find relations between "compute nodes" and "ports".
        // It could be done via following values: VM ↔ OS-EXT-SRV-ATTR:host, VM.id ↔ Vport.device_id

        Map<String, List<Port>> portsByComputeHost = activeServersPerComputeHost.entrySet().stream()
                .collect(toMap(Entry::getKey,
                        e -> e.getValue().stream()
                                .map(vm -> allPorts.stream().filter(p -> Objects.equals(p.getDeviceId(), vm.getId()))
                                        .findFirst().orElse(null))
                                .filter(Objects::nonNull)
                                .collect(Collectors.toList())
                        )
                );

        System.out.println("Ports per compute host (by active vnfs only)");
        portsByComputeHost.forEach((host, ports) -> System.out.println("compute " + host + " # " + ports));

        // 4.4.1) Get list of networks
        // https://developer.openstack.org/api-ref/network/v2/index.html?expanded=list-networks-detail#list-networks

        List<Network> allNetworks = neutronApi.getNetworkApi(region).list().concat().toList();

        System.out.println("All networks in region " + region);
        allNetworks.forEach(n -> System.out.println("  # " + n));

        // 4.4.2) Find relations between cpus of "VMs" and "CPU-zone".
        // It could be done via following values: VM ↔ OS-EXT-SRV-ATTR:host, VM.id ↔ Vport.device_id, Network.id ↔ Vport.network_id
        // It gives information about "How many CPUs could be allocated on 'CPU-numa-node'?".

        // todo

        neutronApi.close();
    }

    static <T extends Closeable> T initApi(String provider, Class<T> apiType) {
        final Properties overrides = new Properties();
        overrides.put(KeystoneProperties.TENANT_NAME, Credentials.TENANT);
        overrides.put("jclouds.wire.log.sensitive", Boolean.TRUE);

        Set<Module> modules = Sets.newHashSet(new SLF4JLoggingModule());

        return ContextBuilder.newBuilder(provider)
                .endpoint(Credentials.KEYSTONE_AUTH_URL)
                .credentials(Credentials.USERNAME, Credentials.PASS)
                .overrides(overrides)
                .modules(modules)
                .buildApi(apiType);
    }

    static class ComputeResource {

        Integer freeDiskSpace;
        Integer freeRam;
        Integer usedvCPUs;

        @Override
        public String toString() {
            return "ComputeResource{" +
                    "freeDiskSpace=" + freeDiskSpace +
                    ", freeRam=" + freeRam +
                    ", usedvCPUs=" + usedvCPUs +
                    '}';
        }
    }

}
