// Copyright 2016 Twitter. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.twitter.heron.packing.binpacking;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import com.twitter.heron.api.generated.TopologyAPI;
import com.twitter.heron.packing.Container;
import com.twitter.heron.packing.PackingUtils;
import com.twitter.heron.packing.RamRequirement;
import com.twitter.heron.spi.common.Config;
import com.twitter.heron.spi.common.Constants;
import com.twitter.heron.spi.common.Context;
import com.twitter.heron.spi.packing.IPacking;
import com.twitter.heron.spi.packing.IRepacking;
import com.twitter.heron.spi.packing.InstanceId;
import com.twitter.heron.spi.packing.PackingPlan;
import com.twitter.heron.spi.packing.Resource;
import com.twitter.heron.spi.utils.TopologyUtils;

import static com.twitter.heron.api.Config.TOPOLOGY_CONTAINER_MAX_CPU_HINT;
import static com.twitter.heron.api.Config.TOPOLOGY_CONTAINER_MAX_DISK_HINT;
import static com.twitter.heron.api.Config.TOPOLOGY_CONTAINER_MAX_RAM_HINT;
import static com.twitter.heron.api.Config.TOPOLOGY_CONTAINER_PADDING_PERCENTAGE;

/**
 * FirstFitDecreasing packing algorithm
 * <p>
 * This IPacking implementation generates a PackingPlan based on the
 * First Fit Decreasing heuristic for the binpacking problem. The algorithm attempts to minimize
 * the amount of resources used.
 * <p>
 * Following semantics are guaranteed:
 * 1. Supports heterogeneous containers. The number of containers used is determined
 * by the algorithm and not by the config file.
 * The user provides a hint for the maximum container size and a padding percentage.
 * The padding percentage whose values range from [0, 100], determines the additional per container
 * resources allocated for system-related processes (e.g., the stream manager).
 * The algorithm might produce containers whose size is slightly higher than
 * the maximum container size provided by the user depending on the per-container instance allocation
 * and the padding configuration.
 * <p>
 * 2. The user provides a hint for the maximum CPU, RAM and Disk that can be used by each container through
 * the com.twitter.heron.api.Config.TOPOLOGY_CONTAINER_MAX_CPU_HINT,
 * com.twitter.heron.api.Config.TOPOLOGY_CONTAINER_MAX_RAM_HINT,
 * com.twitter.heron.api.Config.TOPOLOGY_CONTAINER_MAX_DISK_HINT parameters.
 * If the parameters are not specified then a default value is used for the maximum container
 * size.
 * <p>
 * 3. The user provides a percentage of each container size that will be added to the resources
 * allocated by the container through the com.twitter.heron.api.Config.TOPOLOGY_CONTAINER_PADDING_PERCENTAGE
 * If the parameter is not specified then a default value of 10 is used (10% of the container size)
 * <p>
 * 4. The ram required for one instance is calculated as:
 * value in com.twitter.heron.api.Config.TOPOLOGY_COMPONENT_RAMMAP if exists, otherwise,
 * the default ram value for one instance.
 * <p>
 * 5. The cpu required for one instance is calculated as the default cpu value for one instance.
 * <p>
 * 6. The disk required for one instance is calculated as the default disk value for one instance.
 * <p>
 * 7. The ram required for a container is calculated as:
 * (ram for instances in container) + (paddingPercentage * ram for instances in container)
 * <p>
 * 8. The cpu required for a container is calculated as:
 * (cpu for instances in container) + (paddingPercentage * cpu for instances in container)
 * <p>
 * 9. The disk required for a container is calculated as:
 * (disk for instances in container) + ((paddingPercentage * disk for instances in container)
 * <p>
 * 10. The size of resources required by the whole topology is equal to
 * ((# containers used by FFD)* size of each container
 * + size of a container that includes one instance with default resource requirements).
 * We add some resources to consider the Heron internal container (i.e. the one containing Scheduler
 * and TMaster).
 * <p>
 * 11. The pack() return null if PackingPlan fails to pass the safe check, for instance,
 * the size of ram for an instance is less than the minimal required value or .
 */
public class FirstFitDecreasingPacking implements IPacking, IRepacking {

  private static final long MIN_RAM_PER_INSTANCE = 192L * Constants.MB;
  private static final int DEFAULT_CONTAINER_PADDING_PERCENTAGE = 10;
  private static final int DEFAULT_NUMBER_INSTANCES_PER_CONTAINER = 4;

  private static final Logger LOG = Logger.getLogger(FirstFitDecreasingPacking.class.getName());
  private TopologyAPI.Topology topology;

  private Resource defaultInstanceResources;
  private Resource maxContainerResources;

  private int paddingPercentage;

  @Override
  public void initialize(Config config, TopologyAPI.Topology inputTopology) {
    this.topology = inputTopology;
    setPackingConfigs(config);
  }

  /**
   * Instatiate the packing algorithm parameters related to this topology.
   */
  private void setPackingConfigs(Config config) {
    List<TopologyAPI.Config.KeyValue> topologyConfig = topology.getTopologyConfig().getKvsList();

    this.defaultInstanceResources = new Resource(
        Context.instanceCpu(config),
        Context.instanceRam(config),
        Context.instanceDisk(config));

    this.maxContainerResources = new Resource(
        TopologyUtils.getConfigWithDefault(topologyConfig, TOPOLOGY_CONTAINER_MAX_CPU_HINT,
            this.defaultInstanceResources.getCpu() * DEFAULT_NUMBER_INSTANCES_PER_CONTAINER),
        TopologyUtils.getConfigWithDefault(topologyConfig, TOPOLOGY_CONTAINER_MAX_RAM_HINT,
            this.defaultInstanceResources.getRam() * DEFAULT_NUMBER_INSTANCES_PER_CONTAINER),
        TopologyUtils.getConfigWithDefault(topologyConfig, TOPOLOGY_CONTAINER_MAX_DISK_HINT,
            this.defaultInstanceResources.getDisk() * DEFAULT_NUMBER_INSTANCES_PER_CONTAINER));

    this.paddingPercentage = TopologyUtils.getConfigWithDefault(topologyConfig,
        TOPOLOGY_CONTAINER_PADDING_PERCENTAGE, DEFAULT_CONTAINER_PADDING_PERCENTAGE);
  }

  /**
   * Get a packing plan using First Fit Decreasing
   *
   * @return packing plan
   */
  @Override
  public PackingPlan pack() {
    // Get the instances using FFD allocation
    Map<Integer, List<InstanceId>> ffdAllocation = getFFDAllocation();
    // Construct the PackingPlan
    Map<String, Long> ramMap = TopologyUtils.getComponentRamMapConfig(topology);

    Set<PackingPlan.ContainerPlan> containerPlans = PackingUtils.buildContainerPlans(
        ffdAllocation, ramMap, this.defaultInstanceResources, this.paddingPercentage);
    return new PackingPlan(topology.getId(), containerPlans);
  }

  /**
   * Get a new packing plan given an existing packing plan and component-level changes.
   * The current implementation assign the new instances into newly created containers and
   * does not affect the contents of existing containers.
   *
   * @return new packing plan
   */
  public PackingPlan repack(PackingPlan currentPackingPlan, Map<String, Integer> componentChanges) {
    // Get the instances using FFD allocation
    Map<Integer, List<InstanceId>> ffdAllocation =
        getFFDAllocation(currentPackingPlan, componentChanges);

    // Construct the PackingPlan
    Map<String, Long> ramMap = TopologyUtils.getComponentRamMapConfig(topology);

    Set<PackingPlan.ContainerPlan> containerPlans = PackingUtils.buildContainerPlans(
        ffdAllocation, ramMap, defaultInstanceResources, paddingPercentage);

    //merge the two plans
    Set<PackingPlan.ContainerPlan> totalContainerPlans = new HashSet<>();
    totalContainerPlans.addAll(containerPlans);
    totalContainerPlans.addAll(currentPackingPlan.getContainers());

    return new PackingPlan(topology.getId(), totalContainerPlans);
  }

  @Override
  public void close() {

  }

  /**
   * Sort the components in decreasing order based on their RAM requirements
   *
   * @return The sorted list of components and their RAM requirements
   */
  private ArrayList<RamRequirement> getSortedRAMInstances(Map<String, Integer> parallelismMap) {
    ArrayList<RamRequirement> ramRequirements = new ArrayList<>();
    Map<String, Long> ramMap = TopologyUtils.getComponentRamMapConfig(topology);

    for (String component : parallelismMap.keySet()) {
      if (ramMap.containsKey(component)) {
        if (!PackingUtils.isValidInstance(
            this.defaultInstanceResources.cloneWithRam(ramMap.get(component)),
            MIN_RAM_PER_INSTANCE, maxContainerResources)) {
          throw new RuntimeException("The topology configuration does not have "
              + "valid resource requirements. Please make sure that the instance resource "
              + "requirements do not exceed the maximum per-container resources.");
        } else {
          ramRequirements.add(new RamRequirement(component, ramMap.get(component)));
        }
      } else {
        if (!PackingUtils.isValidInstance(this.defaultInstanceResources,
            MIN_RAM_PER_INSTANCE, maxContainerResources)) {
          throw new RuntimeException("The topology configuration does not have "
              + "valid resource requirements. Please make sure that the instance resource "
              + "requirements do not exceed the maximum per-container resources.");
        } else {
          ramRequirements.add(
              new RamRequirement(component, this.defaultInstanceResources.getRam()));
        }
      }
    }
    Collections.sort(ramRequirements, Collections.reverseOrder());

    return ramRequirements;
  }

  /**
   * Get the instances' allocation based on the First Fit Decreasing algorithm
   *
   * @return Map &lt; containerId, list of InstanceId belonging to this container &gt;
   */
  private Map<Integer, List<InstanceId>> getFFDAllocation() {
    Map<String, Integer> parallelismMap = TopologyUtils.getComponentParallelism(topology);
    return assignInstancesToContainers(parallelismMap, 1, 1);
  }

  /**
   * Get the instances' allocation based on the First Fit Decreasing algorithm
   *
   * @return Map &lt; containerId, list of InstanceId belonging to this container &gt;
   */
  private Map<Integer, List<InstanceId>> getFFDAllocation(PackingPlan currentPackingPlan,
                                                          Map<String, Integer> componentChanges) {
    int numContainers = currentPackingPlan.getContainers().size();
    int maxInstanceIndex = 0;
    for (PackingPlan.ContainerPlan containerPlan : currentPackingPlan.getContainers()) {
      for (PackingPlan.InstancePlan instancePlan : containerPlan.getInstances()) {
        maxInstanceIndex = Math.max(maxInstanceIndex, instancePlan.getTaskId());
      }
    }
    return assignInstancesToContainers(componentChanges, numContainers + 1, maxInstanceIndex + 1);
  }

  /**
   * Place a set of instances into container using the FFD heuristic. Adds new containers and
   * instances starting at {@code firstContainerIndex) and {@code firstTaskIndex}, respectively.
   *
   * @return true if a placement was found, false otherwise
   */
  private Map<Integer, List<InstanceId>> assignInstancesToContainers(
      Map<String, Integer> parallelismMap, int firstContainerIndex, int firstTaskIndex) {
    Map<Integer, List<InstanceId>> allocation = new HashMap<>();
    ArrayList<Container> containers = new ArrayList<>();
    ArrayList<RamRequirement> ramRequirements = getSortedRAMInstances(parallelismMap);
    int globalTaskIndex = firstTaskIndex;
    for (RamRequirement ramRequirement : ramRequirements) {
      String component = ramRequirement.getComponentName();
      int numInstance = parallelismMap.get(component);
      for (int j = 0; j < numInstance; j++) {
        Resource instanceResource =
            this.defaultInstanceResources.cloneWithRam(ramRequirement.getRamRequirement());
        // placeFFDInstance returns containerIds that are 1-based so we have to offset by 1
        int containerId = placeFFDInstance(containers, instanceResource) + firstContainerIndex - 1;
        List<InstanceId> instances = allocation.get(containerId);
        if (instances == null) {
          instances = new ArrayList<>();
        }
        instances.add(new InstanceId(component, globalTaskIndex++, j));
        allocation.put(containerId, instances);
      }
    }
    return allocation;
  }

  /**
   * Assign a particular instance to an existing container or to a new container
   *
   * @return the container Id that incorporated the instance
   */
  private int placeFFDInstance(ArrayList<Container> containers, Resource instanceResource) {
    boolean placed = false;
    int containerId = 0;
    for (int i = 0; i < containers.size() && !placed; i++) {
      if (containers.get(i).add(instanceResource)) {
        placed = true;
        containerId = i + 1;
      }
    }
    if (!placed) {
      containerId = allocateNewContainer(containers);
      containers.get(containerId - 1).add(instanceResource);
    }
    return containerId;
  }

  /**
   * Allocate a new container taking into account the maximum resource requirements specified
   * in the config
   *
   * @return the number of containers
   */
  private int allocateNewContainer(ArrayList<Container> containers) {
    containers.add(new Container(maxContainerResources));
    return containers.size();
  }
}
